package org.eventt.core.everef

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.eventt.core.database.MarketDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.http.EveHttpClient
import org.eventt.core.model.MarketHistoryModel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

object EveRefService {
    // internal var (not private const) so tests can point it at a local MockWebServer.
    internal var baseUrl = "https://data.everef.net/market-history"
    private const val MIN_FILE_SIZE = 300_000L
    private const val DATA_DELAY_DAYS = 3L
    private const val PARALLEL_DOWNLOADS = 6

    data class SyncState(
        val isRunning: Boolean = false,
        val progress: Float = 0f,
        val status: String = "",
        val error: String? = null,
        val filesDownloaded: Int = 0,
        val totalFiles: Int = 0,
    )

    data class FileEntry(
        val name: String,
        val url: String,
        val size: Long,
        val date: String,
    )

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    fun getSelectedSource(): String = StaticDataDao.getSetting("market.history.source") ?: "esi"

    fun setSelectedSource(source: String) = StaticDataDao.setSetting("market.history.source", source)

    // 1 year was removed as a choice (see SettingsScreen) — clamp anything outside the
    // remaining 1/3/6 month options down to 6, so a value saved before that change (or any
    // other stale/invalid setting) doesn't leave the UI with no chip selected.
    fun getHistoryPeriodMonths(): Int {
        val stored = (StaticDataDao.getSetting("market.history.period_months") ?: "6").toIntOrNull() ?: 6
        return if (stored in setOf(1, 3, 6)) stored else 6
    }

    fun setHistoryPeriodMonths(months: Int) = StaticDataDao.setSetting("market.history.period_months", months.toString())

    fun getLastSyncMillis(): Long? = StaticDataDao.getSetting("everef.last_sync")?.toLongOrNull()

    suspend fun sync() =
        withContext(Dispatchers.IO) {
            if (_state.value.isRunning) return@withContext
            _state.value = SyncState(isRunning = true, progress = 0.01f, status = "Starting sync…")

            try {
                val periodMonths = getHistoryPeriodMonths()
                val today = LocalDate.now()
                val cutoffDate = today.minusMonths(periodMonths.toLong())
                val endDate = today.minusDays(DATA_DELAY_DAYS)

                setState(0.02f, "Cleaning old data outside period…")
                EveRefDao.deleteBeforeDate(cutoffDate.toString())
                MarketDao.deleteEveRefBeforeDate(cutoffDate.toString())

                setState(0.05f, "Fetching file index…")
                val filesToDownload = findMissingFiles(cutoffDate, endDate)

                if (filesToDownload.isEmpty()) {
                    StaticDataDao.setSetting("everef.last_sync", System.currentTimeMillis().toString())
                    _state.value =
                        SyncState(
                            isRunning = false,
                            progress = 1f,
                            status = "Already up to date",
                        )
                    return@withContext
                }

                val total = filesToDownload.size
                _state.value =
                    _state.value.copy(
                        progress = 0.07f,
                        status = "Downloading $total file(s) ($PARALLEL_DOWNLOADS parallel)…",
                        totalFiles = total,
                    )

                val downloaded = AtomicInteger(0)
                val failed = AtomicInteger(0)
                val semaphore = Semaphore(PARALLEL_DOWNLOADS)

                coroutineScope {
                    filesToDownload
                        .map { file ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    try {
                                        downloadAndParse(file)
                                        val done = downloaded.incrementAndGet()
                                        val progress = 0.07f + (done.toFloat() / total) * 0.93f
                                        _state.value =
                                            _state.value.copy(
                                                progress = progress,
                                                status = "$done/$total downloaded…",
                                                filesDownloaded = done,
                                            )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        failed.incrementAndGet()
                                        println("[EveRef] Failed ${file.date}: ${e.message}")
                                    }
                                }
                            }
                        }.awaitAll()
                }

                val doneCount = downloaded.get()
                val failCount = failed.get()
                StaticDataDao.setSetting("everef.last_sync", System.currentTimeMillis().toString())
                _state.value =
                    SyncState(
                        isRunning = false,
                        progress = 1f,
                        status =
                            if (failCount == 0) {
                                "Sync complete — $doneCount files downloaded"
                            } else {
                                "Sync complete — $doneCount downloaded, $failCount failed"
                            },
                        filesDownloaded = doneCount,
                        totalFiles = total,
                    )
            } catch (e: CancellationException) {
                _state.value = SyncState(isRunning = false, status = "Sync cancelled")
                throw e
            } catch (e: Exception) {
                _state.value =
                    SyncState(
                        isRunning = false,
                        error = e.message,
                        status = "Sync failed: ${e.message}",
                    )
            }
        }

    // ─── Private ──────────────────────────────────────────────────────────

    private fun setState(
        progress: Float,
        status: String,
    ) {
        _state.value = _state.value.copy(progress = progress, status = status)
    }

    private fun findMissingFiles(
        cutoff: LocalDate,
        end: LocalDate,
    ): List<FileEntry> {
        val alreadyDownloaded = EveRefDao.getDownloadedDates().toSet()
        val result = mutableListOf<FileEntry>()

        for (year in cutoff.year..end.year) {
            for (entry in fetchYearIndex(year)) {
                if (entry.size < MIN_FILE_SIZE) continue
                val fileDate = parseFileDate(entry.name) ?: continue
                if (fileDate.isBefore(cutoff) || fileDate.isAfter(end)) continue
                if (fileDate.toString() in alreadyDownloaded) continue
                result.add(entry.copy(date = fileDate.toString()))
            }
        }
        return result.sortedBy { it.date }
    }

    internal fun parseFileDate(filename: String): LocalDate? {
        val match = Regex("""market-history-(\d{4}-\d{2}-\d{2})\.csv\.bz2""").find(filename) ?: return null
        return runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull()
    }

    internal fun fetchYearIndex(year: Int): List<FileEntry> {
        return try {
            val request = Request.Builder().url("$baseUrl/$year/index.json").build()
            val body =
                EveHttpClient.getClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return emptyList()
                    response.body?.string() ?: return emptyList()
                }
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(body)
            // Response is { "path": "...", "files": [ {...}, ... ] }
            val array = root.jsonObject["files"]?.jsonArray ?: return emptyList()
            array.mapNotNull { elem ->
                val obj = elem.jsonObject
                FileEntry(
                    name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    url = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    date = "",
                )
            }
        } catch (e: Exception) {
            println("[EveRef] Failed to fetch $year index: ${e.message}")
            emptyList()
        }
    }

    internal fun downloadAndParse(file: FileEntry) {
        val request = Request.Builder().url(file.url).build()
        val rows =
            EveHttpClient.getClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body ?: throw Exception("Empty response")

                // BufferedInputStream avoids byte-at-a-time reads into BZip2 decompressor
                BZip2CompressorInputStream(body.byteStream().buffered(65536)).use { bzStream ->
                    BufferedReader(InputStreamReader(bzStream)).use { reader ->
                        val headerLine = reader.readLine() ?: throw Exception("Empty CSV for ${file.date}")
                        val header = headerLine.split(",").map { it.trim().lowercase() }

                        fun col(vararg names: String) = names.firstNotNullOfOrNull { header.indexOf(it).takeIf { i -> i >= 0 } } ?: -1

                        val typeIdIdx = col("typeid", "type_id")
                        val regionIdIdx = col("regionid", "region_id")
                        val dateIdx = col("date")
                        val highestIdx = col("highest")
                        val averageIdx = col("average")
                        val lowestIdx = col("lowest")
                        val volumeIdx = col("volume")
                        val orderCountIdx = col("ordercount", "order_count")

                        if (typeIdIdx < 0 || regionIdIdx < 0 || dateIdx < 0) {
                            throw Exception("Unrecognized CSV header for ${file.date}: $headerLine")
                        }

                        // Collect all rows first; one DB transaction per file is much faster
                        // than one transaction per row (avoids repeated lock contention)
                        val result = mutableListOf<MarketHistoryModel>()
                        var line = reader.readLine()
                        while (line != null) {
                            parseLine(line, typeIdIdx, regionIdIdx, dateIdx, highestIdx, averageIdx, lowestIdx, volumeIdx, orderCountIdx)
                                ?.let { result.add(it) }
                            line = reader.readLine()
                        }
                        result
                    }
                }
            }
        if (rows.isNotEmpty()) MarketDao.insertHistoryBatch(rows, "everef")
        EveRefDao.markDownloaded(file.date, file.name, file.size)
    }

    internal fun parseLine(
        line: String,
        typeIdIdx: Int,
        regionIdIdx: Int,
        dateIdx: Int,
        highestIdx: Int,
        averageIdx: Int,
        lowestIdx: Int,
        volumeIdx: Int,
        orderCountIdx: Int,
    ): MarketHistoryModel? =
        runCatching {
            val cols = line.split(",")
            if (cols.size <= maxOf(typeIdIdx, regionIdIdx, dateIdx)) return null

            fun dbl(idx: Int) = if (idx in 0 until cols.size) cols[idx].trim().toDoubleOrNull() ?: 0.0 else 0.0

            fun lng(idx: Int) = if (idx in 0 until cols.size) cols[idx].trim().toLongOrNull() ?: 0L else 0L
            MarketHistoryModel(
                typeId = cols[typeIdIdx].trim().toInt(),
                regionId = cols[regionIdIdx].trim().toInt(),
                date = cols[dateIdx].trim(),
                highest = dbl(highestIdx),
                average = dbl(averageIdx),
                lowest = dbl(lowestIdx),
                volume = lng(volumeIdx),
                orderCount = lng(orderCountIdx),
            )
        }.getOrNull()
}
