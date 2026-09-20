package org.eventt.features.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.eventt.core.http.EveHttpClient
import java.time.LocalDate

/**
 * A type's average daily buy/sell activity at one station, over the last week -- from Adam4EVE's
 * MarketOrdersTrades CSV export (static.adam4eve.eu), which diffs order-book snapshots to record
 * actual fills. This is the only source this app has for per-side flow: ESI's market history is
 * region-wide and sums both sides together, so a station where sell orders clear fast but buy
 * orders barely fill (see issue #32) looks identical to one where both sides move at the same
 * rate. Not to be confused with Adam4EVE's *volume* exports (region/station history) -- those are
 * order-book depth ("amount... available for possible trades", per their own column docs), not
 * traded volume.
 *
 * Fields are Double, not Long -- capital-tier items can genuinely trade at "1 unit every several
 * days" (e.g. 1 unit / 5 sampled days = 0.2/day). Averaging that as an integer floors real,
 * verifiable activity (visible in-game as live buy/sell orders) down to a flat 0, which then reads
 * as "no data" -- the exact opposite of what a thin-but-real market looks like.
 */
internal data class StationFlow(
    val buyAmount: Double,
    val sellAmount: Double,
)

internal object Adam4EveFlowService {
    internal var baseUrl = "http://static.adam4eve.eu/MarketOrdersTrades"
    private const val WINDOW_DAYS = 7 // days averaged; the daily export has gaps, e.g. over weekends

    private data class WindowData(
        val forTargetDate: String,
        val rows: Map<Pair<Long, Int>, StationFlow>, // averaged per-day over the days actually fetched
    )

    // One file covers every station/type in New Eden, so the whole week's parse is cached once
    // rather than per-station like the old tracker-API approach needed -- ponytail: process-
    // lifetime only, reset by re-launching the app; fine since the window only shifts once a day.
    @Volatile
    private var cached: WindowData? = null

    // Test-only: the cache is otherwise correct to keep for the process lifetime (see above), but
    // each test that points baseUrl at its own MockWebServer needs a clean slate.
    internal fun resetCacheForTesting() {
        cached = null
    }

    /**
     * Per-side traded volume for each of [typeIds] at [stationId], averaged over the last
     * [WINDOW_DAYS] available days (today excluded -- it's still a partial day) -- a single day's
     * count is noisy for thin items, e.g. a lone whale trade or a quiet day. A type missing from
     * the result means it simply had no tracked activity in the window; callers should treat that
     * the same as "no data", not zero.
     */
    suspend fun fetchStationFlow(
        stationId: Long,
        typeIds: List<Int>,
    ): Map<Int, StationFlow> =
        withContext(Dispatchers.IO) {
            val window = loadWindow() ?: return@withContext emptyMap()
            typeIds.mapNotNull { id -> window.rows[stationId to id]?.let { id to it } }.toMap()
        }

    private fun loadWindow(): WindowData? {
        val targetDate = LocalDate.now().minusDays(1).toString()
        cached?.let { if (it.forTargetDate == targetDate) return it }

        val sums = HashMap<Pair<Long, Int>, DoubleArray>() // [buySum, sellSum]
        var daysFetched = 0
        var date = LocalDate.parse(targetDate)
        repeat(WINDOW_DAYS) {
            val body = runCatching { downloadCsv(date.toString()) }.getOrNull()
            if (body != null) {
                daysFetched++
                parseCsv(body).forEach { (key, flow) ->
                    val acc = sums.getOrPut(key) { DoubleArray(2) }
                    acc[0] += flow.buyAmount
                    acc[1] += flow.sellAmount
                }
            }
            date = date.minusDays(1)
        }
        if (daysFetched == 0) {
            println("[Adam4Eve] no MarketOrdersTrades files found in the last $WINDOW_DAYS days")
            return null
        }
        val rows = sums.mapValues { (_, acc) -> StationFlow(buyAmount = acc[0] / daysFetched, sellAmount = acc[1] / daysFetched) }
        val window = WindowData(targetDate, rows)
        cached = window
        return window
    }

    private fun downloadCsv(date: String): String? {
        val url = "$baseUrl/${date.take(4)}/marketOrderTrades_daily_$date.csv"
        val request = Request.Builder().url(url).build()
        EveHttpClient.getClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.string()
        }
    }

    // location_id;region_id;type_id;is_buy_order;has_gone;scanDate;amount;high;low;avg;orderNum;iskValue
    // The export is already one row per (location, type, side, day) -- accumulation below is just
    // a safety net against a duplicate row, not the normal case.
    internal fun parseCsv(body: String): Map<Pair<Long, Int>, StationFlow> {
        val result = HashMap<Pair<Long, Int>, StationFlow>()
        val lines = body.lineSequence().iterator()
        if (!lines.hasNext()) return result
        lines.next() // header
        while (lines.hasNext()) {
            val cols = lines.next().split(";")
            if (cols.size < 7) continue
            val locationId = cols[0].toLongOrNull() ?: continue
            val typeId = cols[2].toIntOrNull() ?: continue
            val isBuyOrder = cols[3] == "1"
            val amount = cols[6].toDoubleOrNull() ?: continue
            val key = locationId to typeId
            val existing = result[key] ?: StationFlow(buyAmount = 0.0, sellAmount = 0.0)
            result[key] =
                if (isBuyOrder) {
                    existing.copy(buyAmount = existing.buyAmount + amount)
                } else {
                    existing.copy(sellAmount = existing.sellAmount + amount)
                }
        }
        return result
    }
}
