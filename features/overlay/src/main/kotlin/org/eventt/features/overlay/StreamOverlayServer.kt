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
import org.eventt.core.database.CorporationDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.model.stringBlocking
import org.eventt.features.orders.CostBasisService
import org.eventt.features.orders.MarketWatchService
import org.eventt.overlay.generated.resources.*
import java.net.InetSocketAddress
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

private const val AUTOSTART_SETTING = "stream_overlay.autostart"

@Serializable
data class StreamOverlayStats(
    val tradesSession: Int,
    val profitSession: Double,
    val relistsSession: Int,
    val elapsedSeconds: Long,
    // Chart x-axis length: the session so far, or the whole N-day window.
    val spanSeconds: Long,
    // Shown in the header instead of the clock for N-day periods.
    val periodLabel: String?,
    val pnlLabel: String,
    val rateSeconds: Long,
    val rateLabel: String,
    // Live snapshot of the current order book (every local character/corp, not session-scoped —
    // these describe standing orders right now, not what changed since the session started).
    val sellOrdersCount: Int,
    val buyOrdersCount: Int,
    val iskInOrders: Double,
    val expectedProfit: Double,
    val relistFeesPaid: Double,
    val beatenCount: Int,
    // Confirmed (wallet-transaction) sells this session.
    val revenueSession: Double,
    val winningTrades: Int,
    val recentSales: List<OverlaySale>,
    val topItems: List<OverlayItem>,
    val profitSeries: List<OverlayPoint>,
)

@Serializable
data class OverlaySale(
    val typeId: Int,
    val name: String,
    val qty: Int,
    val price: Double,
    val profit: Double,
    // Local HH:mm.
    val at: String,
)

@Serializable
data class OverlayItem(
    val typeId: Int,
    val name: String,
    val qty: Int,
    val profit: Double,
)

@Serializable
data class OverlayPoint(
    val t: Long,
    val v: Double,
)

// Same layout as the dates CostBasisService stores, so plain string comparison orders them.
private val KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

private val PNL_LABELS =
    mapOf(
        OverlayPeriod.SESSION to Res.string.so_pnl,
        OverlayPeriod.DAY to Res.string.so_pnl_24h,
        OverlayPeriod.WEEK to Res.string.so_pnl_7d,
        OverlayPeriod.MONTH to Res.string.so_pnl_30d,
    )
private val LAST_LABELS =
    mapOf(
        OverlayPeriod.DAY to Res.string.so_last_24h,
        OverlayPeriod.WEEK to Res.string.so_last_7d,
        OverlayPeriod.MONTH to Res.string.so_last_30d,
    )

private const val RECENT_SALES = 6
private const val TOP_ITEMS = 3

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
            sendResponse(exchange, "text/html; charset=utf-8", StreamOverlayPage.localizedHtml())
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
        val sells: List<CostBasisService.RealizedSellTx> = emptyList(),
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
                sells + o.sells,
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
        // Exclusive end of a finished fixed window; null = up to now.
        until: String?,
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
        // FIFO runs over the whole history, so a sale in the window of stock bought before it
        // still carries that stock's real (older) cost.
        val sellsThisSession = fifo.realizedSells.filter { it.date >= since && (until == null || it.date < until) }

        // Same "active" filter OrdersSummaryBar/MarketWatchService apply — active_orders can hold
        // rows in a non-"active" ESI state (expired/cancelled) briefly before they roll into
        // history; counting those inflated buy/sell counts and ISK totals.
        val activeOrders = ActiveOrderDao.getAll(characterId = characterId, corporationId = corporationId).filter { it.state == "active" }
        val sellOrders = activeOrders.filter { !it.isBuyOrder }

        // Live profit estimate from the order book (volume_remaining dropping since session start,
        // same cost-basis lookup as expectedProfit below) blended with the tx-confirmed FIFO profit
        // above, per type. max() rather than +: once a wallet-transactions sync (ESI cache ~1h+)
        // confirms a type's real sells, that number is truth and must replace the estimate, not
        // stack with it — the estimate only fills the gap before that sync lands.
        // ponytail: fills since the overlay started; a rolling window shorter than the app's uptime
        // could count an hour-old fill that is already outside it until the wallet sync confirms.
        val provisionalProfitByType =
            if (until != null) {
                emptyMap()
            } else {
                sellOrders
                    .groupBy { it.typeId }
                    .mapValues { (_, orders) ->
                        orders.sumOf { order ->
                            val costBasis = fifo.inventory[order.typeId]?.avgCostBasis ?: return@sumOf 0.0
                            val filled = StreamOverlaySession.provisionalFilledQty(order.orderId, order.volumeTotal - order.volumeRemaining)
                            (order.price * fifo.taxConfig.sellMultiplier - costBasis) * filled
                        }
                    }
            }
        val confirmedProfitByType = sellsThisSession.groupBy { it.typeId }.mapValues { (_, sells) -> sells.sumOf { it.profit } }
        val blendedProfit =
            (provisionalProfitByType.keys + confirmedProfitByType.keys)
                .sumOf { typeId -> maxOf(provisionalProfitByType[typeId] ?: 0.0, confirmedProfitByType[typeId] ?: 0.0) }

        return Totals(
            trades = sellsThisSession.size,
            profit = blendedProfit,
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
            sells = sellsThisSession,
        )
    }

    private fun currentStats(): StreamOverlayStats {
        val w = StreamOverlayConfig.window()
        val since = w.since.format(KEY)
        val until = w.until?.format(KEY)
        val characters = CharacterDao.getAll()

        var totals = Totals()
        characters.forEach { totals += accumulate(since, until, characterId = it.id, corporationId = null, actingCharId = it.id) }
        CorporationDao.actingPairsForTracked(characters).forEach { (corpId, actingId) ->
            totals += accumulate(since, until, characterId = null, corporationId = corpId, actingCharId = actingId)
        }

        val elapsed = w.elapsedSeconds
        val multiDay = (w.period.days ?: 0) > 1
        val names =
            totals.sells
                .map { it.typeId }
                .distinct()
                .associateWith { StaticDataDao.getTypeName(it) ?: "#$it" }
        return StreamOverlayStats(
            tradesSession = totals.trades,
            profitSession = totals.profit,
            // Dated relist log, so any period counts them (logged from Nov 2026 on; older relists were never dated).
            relistsSession = ActiveOrderDao.relistsBetween(w.since.toMillis(), w.until?.toMillis() ?: Long.MAX_VALUE).first,
            elapsedSeconds = elapsed,
            spanSeconds = w.spanSeconds,
            periodLabel = periodLabel(w),
            pnlLabel = stringBlocking(PNL_LABELS.getValue(w.period)),
            // Long periods read better per day than per hour.
            rateSeconds = if (multiDay) 86_400 else 3_600,
            rateLabel = stringBlocking(if (multiDay) Res.string.so_per_day else Res.string.so_per_hour),
            sellOrdersCount = totals.sellOrders,
            buyOrdersCount = totals.buyOrders,
            iskInOrders = totals.iskInOrders,
            expectedProfit = totals.expectedProfit,
            relistFeesPaid = totals.relistFees,
            // Already computed globally (every local character/corp) by MarketWatchService's own
            // background sweep — same number that drives the Orders tab's nav badge, just read
            // here instead of recomputed.
            beatenCount = MarketWatchService.beatenCount.value,
            revenueSession = totals.sells.sumOf { it.sellPrice * it.qty },
            winningTrades = totals.sells.count { it.profit > 0 },
            recentSales =
                totals.sells.sortedByDescending { it.date }.take(RECENT_SALES).map {
                    OverlaySale(it.typeId, names.getValue(it.typeId), it.qty, it.sellPrice, it.profit, saleTime(it.date, multiDay))
                },
            topItems =
                totals.sells
                    .groupBy { it.typeId }
                    .map { (typeId, sells) ->
                        OverlayItem(typeId, names.getValue(typeId), sells.sumOf { it.qty }, sells.sumOf { it.profit })
                    }.sortedByDescending { it.profit }
                    .take(TOP_ITEMS),
            profitSeries =
                if (w.period == OverlayPeriod.SESSION) {
                    StreamOverlaySession.recordProfit(elapsed, totals.profit)
                } else {
                    periodSeries(totals.sells, w, totals.profit)
                },
        )
    }

    private fun LocalDateTime.toMillis() = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // "21:42", or "03.11 21:42" when the period spans days.
    private fun saleTime(
        date: String,
        withDay: Boolean,
    ) = (if (withDay) date.substring(8, 10) + "." + date.substring(5, 7) + " " else "") + date.drop(11).take(5)

    // Header text in place of the session clock: "Last 7 days" or "Day 3 of 30"; null = the clock.
    private fun periodLabel(w: OverlayWindow): String? {
        val days = w.period.days ?: return null
        if (!w.fixed) return stringBlocking(LAST_LABELS.getValue(w.period))
        val day = (w.elapsedSeconds / 86_400 + 1).coerceAtMost(days)
        return stringBlocking(Res.string.so_day_of, day.toString(), days.toString())
    }

    /** Cumulative confirmed profit through the window, ending on [total] (which may include estimates). */
    internal fun periodSeries(
        sells: List<CostBasisService.RealizedSellTx>,
        w: OverlayWindow,
        total: Double,
    ): List<OverlayPoint> {
        var sum = 0.0
        val points =
            listOf(OverlayPoint(0, 0.0)) +
                sells.sortedBy { it.date }.map {
                    sum += it.profit
                    val at = LocalDateTime.parse(it.date, KEY)
                    OverlayPoint(Duration.between(w.since, at).seconds.coerceIn(0, w.spanSeconds), sum)
                }
        return if (points.last().v == total) points else points + OverlayPoint(w.elapsedSeconds, total)
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
