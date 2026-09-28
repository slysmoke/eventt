package org.eventt.features.market

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.eventt.core.http.EveHttpClient
import org.eventt.core.model.AppLog
import org.eventt.core.model.QueuedRequest
import org.eventt.core.model.RequestSource
import org.eventt.core.model.RequestStatus
import org.eventt.core.queue.RequestQueueManager
import java.io.BufferedReader
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields

/**
 * Keeps [A4eHistoryStore] filled with ~a year of Adam4EVE MarketOrdersTrades data.
 *
 * The server keeps daily files for only ~2 weeks, but weekly files (the same per-day rows, 7 days
 * per file) back to 2022, published after each ISO week ends. So: completed weeks come from
 * weekly files, the current week's tail from daily files, and a weekly file replaces the days a
 * daily one wrote. File names are discovered from the directory listing rather than built from
 * the calendar — at year boundaries the weekly naming is irregular (weekly_2025-1 sits in /2026/).
 * HTTPS, so the server gzips (~4.7 MB per week on the wire instead of 22 MB).
 */
object A4eHistorySync {
    internal var baseUrl = "https://static.adam4eve.eu/MarketOrdersTrades"
    private const val PARALLEL_DOWNLOADS = 4
    private const val RETENTION_MONTHS = 12L
    private const val RESYNC_INTERVAL_MS = 6L * 60 * 60 * 1000

    data class State(
        val running: Boolean = false,
        val filesDone: Int = 0,
        val filesTotal: Int = 0,
        // Bumped after every ingested file so open charts can re-query.
        val revision: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var storeInstance: A4eHistoryStore? = null

    internal val store: A4eHistoryStore
        get() = storeInstance ?: synchronized(this) { storeInstance ?: A4eHistoryStore().also { storeInstance = it } }

    private var scope: CoroutineScope? = null

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            while (true) {
                runCatching { sync() }.onFailure {
                    if (it is CancellationException) {
                        throw it
                    } else {
                        AppLog.warn(
                            "Adam4EVE",
                            "sync failed: ${it.message}",
                        )
                    }
                }
                delay(RESYNC_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        storeInstance?.close()
        storeInstance = null
    }

    internal data class RemoteFile(
        val dir: Int,
        val name: String,
        val weekly: Boolean,
        // Inclusive day range the file is expected to cover (weekly: its ISO week).
        val from: LocalDate,
        val to: LocalDate,
    ) {
        val key get() = "$dir/$name"
    }

    private val weeklyRe = Regex("""marketOrderTrades_weekly_(\d{4})-(\d{1,2})\.csv""")
    private val dailyRe = Regex("""marketOrderTrades_daily_(\d{4}-\d{2}-\d{2})\.csv""")

    internal fun parseListing(
        dir: Int,
        html: String,
    ): List<RemoteFile> {
        val names = Regex("""href="([^"]+\.csv)"""").findAll(html).map { it.groupValues[1] }.toSet()
        return names.mapNotNull { name ->
            weeklyRe.matchEntire(name)?.let { m ->
                // ISO week number within the *directory's* year — see the class comment on naming.
                val monday =
                    LocalDate
                        .of(dir, 1, 4)
                        .with(WeekFields.ISO.weekOfWeekBasedYear(), m.groupValues[2].toLong())
                        .with(DayOfWeek.MONDAY)
                return@mapNotNull RemoteFile(dir, name, weekly = true, from = monday, to = monday.plusDays(6))
            }
            dailyRe.matchEntire(name)?.let { m ->
                val d = LocalDate.parse(m.groupValues[1])
                RemoteFile(dir, name, weekly = false, from = d, to = d)
            }
        }
    }

    /** What still needs downloading, newest first so the chart fills from the right. */
    internal fun plan(
        remote: List<RemoteFile>,
        cutoff: LocalDate,
        isFileDone: (String) -> Boolean,
        loggedDays: Map<Int, String>,
    ): List<RemoteFile> {
        val inRange = remote.filter { it.to >= cutoff }
        val weekly = inRange.filter { it.weekly }
        val weeklyCovered = weekly.flatMap { w -> generateSequence(w.from) { it.plusDays(1) }.takeWhile { it <= w.to }.toList() }.toSet()
        val weeklyTodo = weekly.filter { !isFileDone(it.key) }
        // Daily only for days no weekly file covers yet, and not already stored.
        val dailyTodo = inRange.filter { !it.weekly && it.from !in weeklyCovered && it.from.toEpochDay().toInt() !in loggedDays }
        return (weeklyTodo + dailyTodo).sortedByDescending { it.to }
    }

    internal suspend fun sync() {
        val today = LocalDate.now()
        val cutoff = today.minusMonths(RETENTION_MONTHS).withDayOfMonth(1)
        val remote = (cutoff.year..today.year).flatMap { year -> parseListing(year, httpGet("$baseUrl/$year/")) }
        val todo = plan(remote, cutoff, store::isFileDone, store.loggedDays())
        store.prune(cutoff.year * 100 + cutoff.monthValue)
        if (todo.isEmpty()) return

        _state.value = _state.value.copy(running = true, filesDone = 0, filesTotal = todo.size)
        val sem = Semaphore(PARALLEL_DOWNLOADS)
        try {
            coroutineScope {
                todo
                    .map { f ->
                        async(Dispatchers.IO) {
                            sem.withPermit {
                                runCatching { fetchAndIngest(f, cutoff) }
                                    .onFailure {
                                        if (it is CancellationException) {
                                            throw it
                                        } else {
                                            AppLog.warn(
                                                "Adam4EVE",
                                                "${f.key}: ${it.message}",
                                            )
                                        }
                                    }
                                val s = _state.value
                                _state.value = s.copy(filesDone = s.filesDone + 1, revision = s.revision + 1)
                            }
                        }
                    }.awaitAll()
            }
        } finally {
            _state.value = _state.value.copy(running = false)
        }
    }

    private fun fetchAndIngest(
        f: RemoteFile,
        cutoff: LocalDate,
    ) {
        val url = "$baseUrl/${f.dir}/${f.name}"
        val q =
            QueuedRequest(endpoint = url, description = "Adam4EVE ${f.name}", source = RequestSource.SERVER, status = RequestStatus.QUEUED)
        RequestQueueManager.enqueue(q)
        RequestQueueManager.markInProgress(q.id)
        try {
            val rows =
                EveHttpClient.getClient().newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw java.io.IOException("HTTP ${resp.code}")
                    }
                    parseRows(resp.body.charStream().buffered())
                }
            RequestQueueManager.updateProgress(q.id, 0.7f)
            store.ingest(f.key, rows, if (f.weekly) "weekly" else "daily", cutoff.toEpochDay().toInt())
            RequestQueueManager.completeRequest(q.id)
        } catch (e: Exception) {
            RequestQueueManager.completeRequest(q.id, error = e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun httpGet(url: String): String =
        EveHttpClient.getClient().newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} for $url")
            resp.body.string()
        }

    // location_id;region_id;type_id;is_buy_order;has_gone;scanDate;amount;high;low;avg;orderNum;iskValue
    // has_gone = 1 rows (disappeared orders counted as filled) would double-count the same fills
    // as the has_gone = 0 row for that key, so only 0 is kept.
    internal fun parseRows(reader: BufferedReader): List<Pair<Int, A4eRow>> {
        val out = ArrayList<Pair<Int, A4eRow>>(300_000)
        reader.useLines { lines ->
            for (line in lines) {
                val c = line.split(';')
                if (c.size < 12 || c[4] != "0") continue
                val type = c[2].toIntOrNull() ?: continue
                val day = runCatching { LocalDate.parse(c[5]).toEpochDay().toInt() }.getOrNull() ?: continue
                out +=
                    type to
                    A4eRow(
                        day = day,
                        locationId = c[0].toLongOrNull() ?: continue,
                        regionId = c[1].toIntOrNull() ?: continue,
                        isBuy = c[3] == "1",
                        amount = c[6].toDoubleOrNull()?.toLong() ?: continue,
                        lowCents = cents(c[8]) ?: continue,
                        highCents = cents(c[7]) ?: continue,
                        iskCents = cents(c[11]) ?: continue,
                        orders = c[10].toIntOrNull() ?: 0,
                    )
            }
        }
        return out
    }

    // Two-decimal values up to ~1e13 ISK round-trip exactly through a double at this scale.
    private fun cents(s: String): Long? = s.toDoubleOrNull()?.let { Math.round(it * 100) }
}
