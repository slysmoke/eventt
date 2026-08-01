package org.eventt.features.overlay

import org.eventt.core.database.ActiveOrderDao
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// "Session" for the OBS overlay: a start timestamp plus a relist/fill baseline, reset whenever the
// overlay server (re)starts or the user hits "reset" in Settings. Trades/profit are computed by
// filtering dated records against startedAtKey (see StreamOverlayServer), but relistCount and
// volume_remaining are running totals on the *active* order with no per-event timestamp (see
// ActiveOrderDao) — so relists and provisional fills this session can only be a baseline diff.
object StreamOverlaySession {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    var startedAt: LocalDateTime = LocalDateTime.now()
        private set
    private var relistBaseline: Int = currentRelistTotal()

    // orderId -> units filled (volume_total - volume_remaining) as of session start, for every
    // sell order active at that moment. volume_remaining only ever decreases while an order stays
    // active, so diffing against this baseline is a same-poll-cycle "was this sold?" signal —
    // available immediately, unlike wallet transactions (ESI cache ~1h+, see WalletSyncService).
    private var fillBaseline: Map<Long, Int> = currentFilledMap()

    // Matches the format WalletDao/CostBasisService dates are stored in (utcToLocalDateTime), so
    // it can be compared lexicographically against RealizedSellTx.date / transaction dates.
    val startedAtKey: String get() = startedAt.format(FORMAT)

    fun reset() {
        startedAt = LocalDateTime.now()
        relistBaseline = currentRelistTotal()
        fillBaseline = currentFilledMap()
    }

    // ponytail: counts only orders still active; an order's relists made this session are lost
    // from this total once it closes (OrderHistoryDao has no relistCount column). Fine for a live
    // stream stat — add a dated relist-event log if exact historical accuracy ever matters.
    fun relistsSinceStart(): Int = (currentRelistTotal() - relistBaseline).coerceAtLeast(0)

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

    private fun currentRelistTotal(): Int = ActiveOrderDao.getAll().sumOf { it.relistCount }

    private fun currentFilledMap(): Map<Long, Int> =
        ActiveOrderDao
            .getAll()
            .filter { !it.isBuyOrder }
            .associate { it.orderId to (it.volumeTotal - it.volumeRemaining) }
}
