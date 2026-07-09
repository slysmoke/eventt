package org.eventt.core.marketlogs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

sealed class MarketLogEvent {
    data class MyOrdersImported(
        val charId: Int,
        val rows: List<MarketLogOrderRow>,
        val sourceFile: String,
    ) : MarketLogEvent()

    // No corp id is present in the export itself — only the exporting character's own id per row.
    data class CorpOrdersImported(
        val rows: List<MarketLogOrderRow>,
        val sourceFile: String,
    ) : MarketLogEvent()

    data class OrderBookImported(
        val typeId: Int,
        val regionId: Int,
        val buyRows: List<MarketLogBookRow>,
        val sellRows: List<MarketLogBookRow>,
        val sourceFile: String,
    ) : MarketLogEvent()
}

/**
 * Polls the configured Marketlogs directory for new EVE export files, parses recognized ones,
 * emits an event, then deletes the file. No java.nio.file.WatchService — inotify is known to be
 * unreliable over Proton/compatdata's FUSE-like mounts, and this folder is normally empty, so a
 * cheap poll loop (mirroring the overlay's own existing clipboard-poll idiom) is simpler and more
 * robust than chasing OS-level file-event delivery through a Wine prefix.
 */
object MarketLogWatcher {
    private const val DEFAULT_POLL_INTERVAL_MS = 3_000L

    private val _events = MutableSharedFlow<MarketLogEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<MarketLogEvent> = _events.asSharedFlow()

    private var scope: CoroutineScope? = null

    // filename -> (size, mtime) seen on the previous poll. A file is only processed once its
    // stamp is unchanged across two consecutive polls, so a file mid-write by the game client
    // (written in one shot, but the OS may still be flushing) is never read half-finished.
    private val seen = mutableMapOf<String, Pair<Long, Long>>()

    fun start(intervalMillis: Long = DEFAULT_POLL_INTERVAL_MS) {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            while (isActive) {
                runCatching { pollOnce() }.onFailure { println("[MarketLogs] poll failed: ${it.message}") }
                delay(intervalMillis)
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        seen.clear()
    }

    internal fun pollOnce(dir: File? = MarketLogPaths.resolveDirectory()) {
        if (dir == null || !dir.isDirectory) return
        val txtFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt", ignoreCase = true) } ?: return
        val currentNames = txtFiles.map { it.name }.toSet()
        seen.keys.retainAll(currentNames) // forget files that vanished between polls

        for (file in txtFiles) {
            val stamp = file.length() to file.lastModified()
            val previous = seen[file.name]
            seen[file.name] = stamp
            if (previous != stamp) continue // not stable yet — wait for the next poll
            processFile(file)
            seen.remove(file.name)
        }
    }

    private fun processFile(file: File) {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return
        if (lines.isEmpty()) return

        var handled = true
        when (MarketLogParser.detectKind(lines[0])) {
            MarketLogFileKind.MY_ORDERS -> {
                val rows = MarketLogParser.parseOrderLog(lines)
                val charId = rows.firstOrNull()?.charId
                if (charId != null) _events.tryEmit(MarketLogEvent.MyOrdersImported(charId, rows, file.name))
            }
            MarketLogFileKind.CORP_ORDERS -> {
                val rows = MarketLogParser.parseOrderLog(lines)
                _events.tryEmit(MarketLogEvent.CorpOrdersImported(rows, file.name))
            }
            MarketLogFileKind.ORDER_BOOK -> {
                val rows = MarketLogParser.parseOrderBook(lines)
                val typeId = rows.firstOrNull()?.typeId
                val regionId = rows.firstOrNull()?.regionId
                if (typeId != null && regionId != null) {
                    _events.tryEmit(
                        MarketLogEvent.OrderBookImported(
                            typeId = typeId,
                            regionId = regionId,
                            buyRows = rows.filter { it.isBuyOrder },
                            sellRows = rows.filter { !it.isBuyOrder },
                            sourceFile = file.name,
                        ),
                    )
                }
            }
            // Unrelated .txt someone dropped in the folder — leave it alone entirely.
            MarketLogFileKind.UNKNOWN -> handled = false
        }
        if (handled) {
            runCatching { file.delete() }.onFailure { println("[MarketLogs] could not delete ${file.name}: ${it.message}") }
        }
    }
}
