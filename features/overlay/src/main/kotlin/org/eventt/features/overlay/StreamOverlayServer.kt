package org.eventt.features.overlay

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eventt.features.orders.CostBasisService
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors

@Serializable
data class StreamOverlayStats(
    val tradesSession: Int,
    val profitSession: Double,
    val relistsSession: Int,
    val elapsedSeconds: Long,
)

/**
 * Local-only HTTP server for an OBS Browser Source overlay (issue #15) — same
 * `com.sun.net.httpserver.HttpServer` pattern SsoAuthManager already uses for the OAuth callback,
 * just on a different port. Serves the overlay page at `/` and live stats as JSON at
 * `/api/stats`, polled client-side; nothing here needs a push channel.
 */
object StreamOverlayServer {
    const val PORT = 8001

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var server: HttpServer? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        if (server != null) return
        StreamOverlaySession.reset()

        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", PORT), 0)
        srv.createContext("/") { exchange ->
            sendResponse(exchange, "text/html; charset=utf-8", StreamOverlayPage.HTML)
        }
        srv.createContext("/api/stats") { exchange ->
            sendResponse(exchange, "application/json; charset=utf-8", json.encodeToString(currentStats()))
        }
        srv.setExecutor(Executors.newSingleThreadExecutor())
        srv.start()

        server = srv
        _isRunning.value = true
    }

    fun stop() {
        server?.stop(0)
        server = null
        _isRunning.value = false
    }

    fun resetSession() = StreamOverlaySession.reset()

    private fun currentStats(): StreamOverlayStats {
        val since = StreamOverlaySession.startedAtKey
        val sellsThisSession = CostBasisService.compute().realizedSells.filter { it.date >= since }
        return StreamOverlayStats(
            tradesSession = sellsThisSession.size,
            profitSession = sellsThisSession.sumOf { it.profit },
            relistsSession = StreamOverlaySession.relistsSinceStart(),
            elapsedSeconds = Duration.between(StreamOverlaySession.startedAt, java.time.LocalDateTime.now()).seconds,
        )
    }

    private fun sendResponse(
        exchange: com.sun.net.httpserver.HttpExchange,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.close()
    }
}
