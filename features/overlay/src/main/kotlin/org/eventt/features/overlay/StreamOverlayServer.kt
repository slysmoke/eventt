package org.eventt.features.overlay

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eventt.core.database.ActiveOrderDao
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.StaticDataDao
import org.eventt.features.orders.CostBasisService
import org.eventt.features.orders.MarketWatchService
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors

private const val AUTOSTART_SETTING = "stream_overlay.autostart"

@Serializable
data class StreamOverlayStats(
    val tradesSession: Int,
    val profitSession: Double,
    val relistsSession: Int,
    val elapsedSeconds: Long,
    // Live snapshot of the current order book (every local character/corp, not session-scoped —
    // these describe standing orders right now, not what changed since the session started).
    val sellOrdersCount: Int,
    val buyOrdersCount: Int,
    val iskInOrders: Double,
    val expectedProfit: Double,
    val relistFeesPaid: Double,
    val beatenCount: Int,
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

    fun isAutostartEnabled(): Boolean = StaticDataDao.getSetting(AUTOSTART_SETTING) == "true"

    fun setAutostartEnabled(enabled: Boolean) = StaticDataDao.setSetting(AUTOSTART_SETTING, enabled.toString())

    /** Called once from Main.kt at launch — a no-op unless the user opted in via Settings. */
    fun startIfAutostartEnabled() {
        if (isAutostartEnabled()) start()
    }

    // Mutable accumulator across every local character/corp — see currentStats(). A plain data
    // class instead of local `var`s so accumulate() can build one and the caller folds it in.
    private data class Totals(
        val trades: Int = 0,
        val profit: Double = 0.0,
        val sellOrders: Int = 0,
        val buyOrders: Int = 0,
        val iskInOrders: Double = 0.0,
        val expectedProfit: Double = 0.0,
        val relistFees: Double = 0.0,
    ) {
        operator fun plus(o: Totals) =
            Totals(
                trades + o.trades,
                profit + o.profit,
                sellOrders + o.sellOrders,
                buyOrders + o.buyOrders,
                iskInOrders + o.iskInOrders,
                expectedProfit + o.expectedProfit,
                relistFees + o.relistFees,
            )
    }

    // Each character's/corp's own FIFO, computed and summed separately — never one merged FIFO
    // across everyone. CostBasisService.compute(characterId=null, corporationId=null) runs FIFO
    // over every character's transactions pooled into one chronological queue, so a cheap lot one
    // character bought can end up "consumed" by a completely unrelated character's sell — cost
    // basis (and so expected profit) silently drifts from what that character's own Orders screen
    // shows. Summing independent per-entity results avoids that entirely.
    private fun accumulate(
        since: String,
        characterId: Int?,
        corporationId: Int?,
        // Whose tax/broker-fee settings apply — the character itself in personal mode, or (same
        // stand-in ActiveOrdersSyncService/WalletSyncService use for ESI auth) the first known
        // member in corp mode. Using CostBasisService's plain defaults here — same bug already
        // fixed in LeaderboardPublisher — silently mismatched real orders by billions of ISK at
        // real trading volumes.
        actingCharId: Int,
    ): Totals {
        val taxConfig =
            CostBasisService.TaxConfig(
                salesTaxPct = StaticDataDao.getCharSalesTax(actingCharId),
                brokerFeePct = StaticDataDao.getCharBrokersFee(actingCharId),
            )
        val fifo = CostBasisService.compute(characterId = characterId, corporationId = corporationId, taxConfig = taxConfig)
        val sellsThisSession = fifo.realizedSells.filter { it.date >= since }

        // Same "active" filter OrdersSummaryBar/MarketWatchService apply — active_orders can hold
        // rows in a non-"active" ESI state (expired/cancelled) briefly before they roll into
        // history; counting those inflated buy/sell counts and ISK totals.
        val activeOrders = ActiveOrderDao.getAll(characterId = characterId, corporationId = corporationId).filter { it.state == "active" }
        val sellOrders = activeOrders.filter { !it.isBuyOrder }

        return Totals(
            trades = sellsThisSession.size,
            profit = sellsThisSession.sumOf { it.profit },
            sellOrders = sellOrders.size,
            buyOrders = activeOrders.size - sellOrders.size,
            iskInOrders = activeOrders.sumOf { it.price * it.volumeRemaining },
            // Potential profit if every active sell order fully fills at its listed price, against
            // this type's current inventory cost basis — same lookup OrdersSummaryBar uses
            // (inventory only, no realized-sells fallback), and buy orders aren't inventory yet so
            // they don't contribute.
            expectedProfit =
                sellOrders.sumOf { order ->
                    val costBasis = fifo.inventory[order.typeId]?.avgCostBasis ?: return@sumOf 0.0
                    (order.price * fifo.taxConfig.sellMultiplier - costBasis) * order.volumeRemaining
                },
            relistFees = activeOrders.sumOf { it.relistFeesPaid },
        )
    }

    private fun currentStats(): StreamOverlayStats {
        val since = StreamOverlaySession.startedAtKey
        val characters = CharacterDao.getAll()

        var totals = Totals()
        characters.forEach { totals += accumulate(since, characterId = it.id, corporationId = null, actingCharId = it.id) }
        characters
            .mapNotNull { it.corporationId }
            .distinct()
            .forEach { corpId ->
                val actingId = characters.first { it.corporationId == corpId }.id
                totals += accumulate(since, characterId = null, corporationId = corpId, actingCharId = actingId)
            }

        return StreamOverlayStats(
            tradesSession = totals.trades,
            profitSession = totals.profit,
            relistsSession = StreamOverlaySession.relistsSinceStart(),
            elapsedSeconds = Duration.between(StreamOverlaySession.startedAt, java.time.LocalDateTime.now()).seconds,
            sellOrdersCount = totals.sellOrders,
            buyOrdersCount = totals.buyOrders,
            iskInOrders = totals.iskInOrders,
            expectedProfit = totals.expectedProfit,
            relistFeesPaid = totals.relistFees,
            // Already computed globally (every local character/corp) by MarketWatchService's own
            // background sweep — same number that drives the Orders tab's nav badge, just read
            // here instead of recomputed.
            beatenCount = MarketWatchService.beatenCount.value,
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
