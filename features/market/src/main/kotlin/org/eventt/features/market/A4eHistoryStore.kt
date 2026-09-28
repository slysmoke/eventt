package org.eventt.features.market

import org.eventt.core.model.AppPaths
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * One Adam4EVE MarketOrdersTrades row: fills seen on one side of one hub's book for one type on
 * one day. Prices and ISK are integer hundredths (ISK has two decimals) — exact, and far more
 * compressible than doubles. The CSV's own `avg` column is not kept: it's unreliable (often below
 * the row's own `low`); iskValue / amount is the real volume-weighted price.
 */
internal data class A4eRow(
    val day: Int, // epoch day
    val locationId: Long,
    val regionId: Int,
    val isBuy: Boolean, // true = a buy order was filled (someone sold into the bid)
    val amount: Long,
    val lowCents: Long,
    val highCents: Long,
    val iskCents: Long,
    val orders: Int,
) {
    val vwap get() = if (amount > 0) iskCents / 100.0 / amount else 0.0
    val low get() = lowCents / 100.0
    val high get() = highCents / 100.0
}

// ─── Block codec ─────────────────────────────────────────────────────────────────────────
//
// A block holds every row of one type for one month. Column-wise, each column zigzag-varint
// encoded (day/location/region/low as deltas from the previous row, high as a delta from the same
// row's low), then raw Deflate — or stored as-is when that doesn't shrink it (most types trade at
// one hub a few days a month, and a tiny block only grows under Deflate). A leading flag byte
// says which. Neighbouring rows share a day/hub and sit at near-identical prices, so the deltas
// are tiny and compress far better than the CSV text.

internal object A4eCodec {
    private const val RAW: Int = 0
    private const val DEFLATED: Int = 1

    fun encode(rows: List<A4eRow>): ByteArray {
        val plain = encodeColumns(rows)
        val packed = ByteArrayOutputStream()
        packed.write(DEFLATED)
        // A caller-supplied Deflater is NOT ended by the stream's close(): its native zlib state
        // (~270 KB at level 9) would otherwise linger until GC finalizes it — thousands of blocks
        // per file ran the process up to many GB of native memory during a sync.
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            DeflaterOutputStream(packed, deflater).use { it.write(plain) }
        } finally {
            deflater.end()
        }
        return if (packed.size() < plain.size + 1) packed.toByteArray() else byteArrayOf(RAW.toByte()) + plain
    }

    private fun encodeColumns(rows: List<A4eRow>): ByteArray {
        val sorted = rows.sortedWith(compareBy<A4eRow>({ it.day }, { it.locationId }, { it.isBuy }))
        val out = ByteArrayOutputStream()
        writeVar(out, sorted.size.toLong())

        fun deltas(sel: (A4eRow) -> Long) {
            var prev = 0L
            sorted.forEach {
                val v = sel(it)
                writeVar(out, zig(v - prev))
                prev = v
            }
        }
        deltas { it.day.toLong() }
        deltas { it.locationId }
        deltas { it.regionId.toLong() }
        sorted.forEach { out.write(if (it.isBuy) 1 else 0) }
        sorted.forEach { writeVar(out, zig(it.amount)) }
        deltas { it.lowCents }
        sorted.forEach { writeVar(out, zig(it.highCents - it.lowCents)) }
        sorted.forEach { writeVar(out, zig(it.iskCents)) }
        sorted.forEach { writeVar(out, zig(it.orders.toLong())) }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): List<A4eRow> {
        val body = ByteArrayInputStream(data, 1, data.size - 1)
        // Same as encode: a caller-supplied Inflater must be ended explicitly.
        val inflater = if (data[0].toInt() == DEFLATED) Inflater(true) else null
        val stream = if (inflater != null) InflaterInputStream(body, inflater) else body
        try {
            return decodeColumns(stream)
        } finally {
            inflater?.end()
        }
    }

    private fun decodeColumns(stream: InputStream): List<A4eRow> {
        stream.use { inp ->
            val n = readVar(inp).toInt()

            fun deltas(): LongArray {
                val a = LongArray(n)
                var prev = 0L
                for (i in 0 until n) {
                    prev += unzig(readVar(inp))
                    a[i] = prev
                }
                return a
            }

            fun plain() = LongArray(n) { unzig(readVar(inp)) }
            val day = deltas()
            val loc = deltas()
            val region = deltas()
            val side = BooleanArray(n) { inp.read() == 1 }
            val amount = plain()
            val low = deltas()
            val highDelta = plain()
            val isk = plain()
            val orders = plain()
            return List(n) { i ->
                A4eRow(
                    day[i].toInt(),
                    loc[i],
                    region[i].toInt(),
                    side[i],
                    amount[i],
                    low[i],
                    low[i] + highDelta[i],
                    isk[i],
                    orders[i].toInt(),
                )
            }
        }
    }

    private fun zig(v: Long) = (v shl 1) xor (v shr 63)

    private fun unzig(v: Long) = (v ushr 1) xor -(v and 1)

    private fun writeVar(
        out: OutputStream,
        value: Long,
    ) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun readVar(inp: InputStream): Long {
        var shift = 0
        var result = 0L
        while (true) {
            val b = inp.read()
            require(b >= 0) { "truncated A4E block" }
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
}

// yyyymm, e.g. 202609 — the block partition key.
internal fun monthKey(epochDay: Int): Int = LocalDate.ofEpochDay(epochDay.toLong()).let { it.year * 100 + it.monthValue }

/**
 * Local Adam4EVE fill history, kept in its own SQLite file (not the main DB) so it can grow to a
 * couple hundred MB — or be deleted outright — without touching real user data; it's a pure cache
 * that A4eHistorySync can always rebuild. Blocks are keyed (month, type): a chart reads ~13 small
 * blobs for a year, a new day rewrites only the current month's blocks, and retention is one
 * contiguous DELETE of the oldest months.
 */
internal class A4eHistoryStore(
    file: File = File(AppPaths.appDataDir, "adam4eve.db"),
) {
    private val conn: Connection =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").apply {
            createStatement().use { st ->
                // auto_vacuum only takes effect on a fresh file, before any table exists.
                st.execute("PRAGMA auto_vacuum = INCREMENTAL")
                st.execute("PRAGMA journal_mode = WAL")
                st.execute("PRAGMA synchronous = NORMAL")
                // id = month * 1e6 + type_id, as the rowid: a plain rowid table stores blobs far
                // more compactly than WITHOUT ROWID (measured ~45% smaller file).
                st.execute("CREATE TABLE IF NOT EXISTS block (id INTEGER PRIMARY KEY, data BLOB NOT NULL)")
                // Which source last wrote each day: a weekly file supersedes daily ones.
                st.execute(
                    "CREATE TABLE IF NOT EXISTS day_log (day INTEGER PRIMARY KEY, source TEXT NOT NULL, fetched_at INTEGER NOT NULL)",
                )
                st.execute("CREATE TABLE IF NOT EXISTS file_log (name TEXT PRIMARY KEY, fetched_at INTEGER NOT NULL)")
            }
        }

    @Synchronized
    fun isFileDone(name: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM file_log WHERE name = ?").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { it.next() }
        }

    @Synchronized
    fun loggedDays(): Map<Int, String> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT day, source FROM day_log").use { rs ->
                buildMap { while (rs.next()) put(rs.getInt(1), rs.getString(2)) }
            }
        }

    /**
     * Writes one downloaded file's rows, replacing whatever was stored for the same (day, type).
     * A daily file never overwrites a day a weekly file already wrote. Rows before [minDay] are
     * dropped (a weekly file can straddle the retention cutoff).
     */
    @Synchronized
    fun ingest(
        fileName: String,
        // (typeId, row) — the type is the block key, so rows themselves don't carry it.
        rows: List<Pair<Int, A4eRow>>,
        source: String,
        minDay: Int,
    ) {
        val weeklyDays = if (source == "daily") loggedDays().filterValues { it == "weekly" }.keys else emptySet()
        val keep = rows.filter { (_, r) -> r.day >= minDay && r.day !in weeklyDays }
        val days = keep.map { it.second.day }.toSet()
        conn.autoCommit = false
        try {
            // ponytail: replace is per (day, type) present in this file — a type that had rows in an
            // earlier daily file but none in the superseding weekly one keeps its daily rows.
            val byBlock = keep.groupBy({ (type, r) -> monthKey(r.day) to type }, { it.second })
            conn.prepareStatement("SELECT data FROM block WHERE id = ?").use { sel ->
                conn.prepareStatement("INSERT OR REPLACE INTO block (id, data) VALUES (?, ?)").use { up ->
                    for ((key, newRows) in byBlock) {
                        val id = blockId(key.first, key.second)
                        sel.setLong(1, id)
                        val existing = sel.executeQuery().use { rs -> if (rs.next()) A4eCodec.decode(rs.getBytes(1)) else emptyList() }
                        val merged = existing.filter { it.day !in days } + newRows
                        up.setLong(1, id)
                        up.setBytes(2, A4eCodec.encode(merged))
                        up.addBatch()
                    }
                    up.executeBatch()
                }
            }
            val now = System.currentTimeMillis()
            conn.prepareStatement("INSERT OR REPLACE INTO day_log (day, source, fetched_at) VALUES (?, ?, ?)").use { ps ->
                days.forEach {
                    ps.setInt(1, it)
                    ps.setString(2, source)
                    ps.setLong(3, now)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.prepareStatement("INSERT OR REPLACE INTO file_log (name, fetched_at) VALUES (?, ?)").use { ps ->
                ps.setString(1, fileName)
                ps.setLong(2, now)
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** Every stored row for [typeId] with fromDay <= day <= toDay. */
    @Synchronized
    fun query(
        typeId: Int,
        fromDay: Int,
        toDay: Int,
    ): List<A4eRow> =
        conn.prepareStatement("SELECT data FROM block WHERE id = ?").use { ps ->
            val first = LocalDate.ofEpochDay(fromDay.toLong()).withDayOfMonth(1)
            val last = LocalDate.ofEpochDay(toDay.toLong())
            buildList {
                var m = first
                while (m <= last) {
                    ps.setLong(1, blockId(m.year * 100 + m.monthValue, typeId))
                    ps.executeQuery().use { rs ->
                        if (rs.next()) addAll(A4eCodec.decode(rs.getBytes(1)).filter { it.day in fromDay..toDay })
                    }
                    m = m.plusMonths(1)
                }
            }
        }

    /** Drops whole months before [minMonth] and hands the freed pages back to the OS. */
    @Synchronized
    fun prune(minMonth: Int) {
        val minDay = LocalDate.of(minMonth / 100, minMonth % 100, 1).toEpochDay()
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM block WHERE id < ${blockId(minMonth, 0)}")
            st.executeUpdate("DELETE FROM day_log WHERE day < $minDay")
            st.execute("PRAGMA incremental_vacuum")
        }
    }

    @Synchronized
    fun close() = conn.close()

    private fun blockId(
        month: Int,
        typeId: Int,
    ) = month * 1_000_000L + typeId
}

// ─── Chart view: one day's fills per side, summed over the requested scope ──────────────────

internal data class A4eSide(
    val amount: Long,
    val low: Double,
    val high: Double,
    val vwap: Double,
)

internal data class A4eDay(
    // Buy orders filled — sellers hitting the bid.
    val bid: A4eSide?,
    // Sell orders filled — buyers lifting the ask.
    val ask: A4eSide?,
) {
    val low get() = listOfNotNull(bid?.low, ask?.low).minOrNull()
    val high get() = listOfNotNull(bid?.high, ask?.high).maxOrNull()
}

/**
 * Per-day, per-side aggregate keyed by ISO date: one station when [stationId] is set, otherwise
 * every tracked hub in [regionId] (Adam4EVE only tracks the big hubs, so "region" here means the
 * sum of those, not the whole region like ESI history).
 */
internal fun aggregateA4e(
    rows: List<A4eRow>,
    stationId: Long?,
    regionId: Int,
): Map<String, A4eDay> {
    val scoped = rows.filter { if (stationId != null) it.locationId == stationId else it.regionId == regionId }

    fun side(list: List<A4eRow>): A4eSide? {
        val amount = list.sumOf { it.amount }
        if (list.isEmpty() || amount <= 0) return null
        return A4eSide(amount, list.minOf { it.low }, list.maxOf { it.high }, list.sumOf { it.iskCents } / 100.0 / amount)
    }
    return scoped
        .groupBy { it.day }
        .map { (day, list) ->
            LocalDate.ofEpochDay(day.toLong()).toString() to
                A4eDay(side(list.filter { it.isBuy }), side(list.filter { !it.isBuy }))
        }.toMap()
}
