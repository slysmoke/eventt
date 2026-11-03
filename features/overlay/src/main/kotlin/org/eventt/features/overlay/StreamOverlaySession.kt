package org.eventt.features.overlay

import org.eventt.core.database.ActiveOrderDao
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// "Session" for the OBS overlay: a start timestamp plus a fill baseline, reset whenever the
// overlay server (re)starts or the user hits "reset" in Settings. Trades/profit are computed by
// filtering dated records against startedAtKey (see StreamOverlayServer), but volume_remaining is a
// running total on the *active* order with no per-event timestamp (see ActiveOrderDao) — so
// provisional fills this session can only be a baseline diff.
private const val MAX_POINTS = 600

// Chart resolution, seconds.
const val CHART_TICK = 60L

object StreamOverlaySession {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    var startedAt: LocalDateTime = LocalDateTime.now()
        private set

    // orderId -> units filled (volume_total - volume_remaining) as of session start, for every
    // sell order active at that moment. volume_remaining only ever decreases while an order stays
    // active, so diffing against this baseline is a same-poll-cycle "was this sold?" signal —
    // available immediately, unlike wallet transactions (ESI cache ~1h+, see WalletSyncService).
    private var fillBaseline: Map<Long, Int> = currentFilledMap()

    // Session P&L over time (seconds since start -> profit), one point per change: the overlay's chart.
    private val profitSeries = mutableListOf(OverlayPoint(0, 0.0))

    // Matches the format WalletDao/CostBasisService dates are stored in (utcToLocalDateTime), so
    // it can be compared lexicographically against RealizedSellTx.date / transaction dates.
    val startedAtKey: String get() = startedAt.format(FORMAT)

    fun reset() {
        startedAt = LocalDateTime.now()
        fillBaseline = currentFilledMap()
        synchronized(profitSeries) {
            profitSeries.clear()
            profitSeries += OverlayPoint(0, 0.0)
        }
    }

    /** Appends [profit] if it moved and returns the whole curve. */
    fun recordProfit(
        elapsedSeconds: Long,
        profit: Double,
    ): List<OverlayPoint> =
        synchronized(profitSeries) {
            // One-minute chart resolution: a change within the last point's minute replaces it.
            val t = elapsedSeconds / CHART_TICK * CHART_TICK
            val last = profitSeries.last()
            when {
                last.v == profit -> Unit
                last.t == t && profitSeries.size > 1 -> profitSeries[profitSeries.lastIndex] = OverlayPoint(t, profit)
                else -> profitSeries += OverlayPoint(t, profit)
            }
            // A long stream halves its resolution instead of growing without bound (first point kept).
            if (profitSeries.size > MAX_POINTS) {
                val thinned = profitSeries.filterIndexed { i, _ -> i == 0 || i % 2 == 1 || i == profitSeries.lastIndex }
                profitSeries.clear()
                profitSeries += thinned
            }
            profitSeries.toList()
        }

    // Units of [orderId] sold since session start, going only off its own volume_remaining —
    // a fresh estimate available every MarketWatchService sweep instead of waiting on the next
    // wallet-transactions sync. Not in the baseline (order placed mid-session) defaults to 0
    // filled at that point, so its whole current fill counts.
    // ponytail: an order fully filling and dropping out of active_orders in one sweep is
    // indistinguishable from a cancel/expiry here, so that fill is lost from this estimate —
    // StreamOverlayServer's tx-confirmed profit (which does catch it, just later) is taken via
    // max(), not summed, so this never double-counts once transactions confirm it.
    fun provisionalFilledQty(
        orderId: Long,
        currentFilled: Int,
    ): Int = (currentFilled - (fillBaseline[orderId] ?: 0)).coerceAtLeast(0)

    private fun currentFilledMap(): Map<Long, Int> =
        ActiveOrderDao
            .getAll()
            .filter { !it.isBuyOrder }
            .associate { it.orderId to (it.volumeTotal - it.volumeRemaining) }
}
