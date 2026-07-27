package org.eventt.features.overlay

import org.eventt.core.database.ActiveOrderDao
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// "Session" for the OBS overlay: a start timestamp plus a relist baseline, reset whenever the
// overlay server (re)starts or the user hits "reset" in Settings. Trades/profit are computed by
// filtering dated records against startedAtKey (see StreamOverlayServer), but relistCount is a
// running total per *active* order with no per-event timestamp (see ActiveOrderDao) — so relists
// this session can only be a baseline diff.
object StreamOverlaySession {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    var startedAt: LocalDateTime = LocalDateTime.now()
        private set
    private var relistBaseline: Int = currentRelistTotal()

    // Matches the format WalletDao/CostBasisService dates are stored in (utcToLocalDateTime), so
    // it can be compared lexicographically against RealizedSellTx.date / transaction dates.
    val startedAtKey: String get() = startedAt.format(FORMAT)

    fun reset() {
        startedAt = LocalDateTime.now()
        relistBaseline = currentRelistTotal()
    }

    // ponytail: counts only orders still active; an order's relists made this session are lost
    // from this total once it closes (OrderHistoryDao has no relistCount column). Fine for a live
    // stream stat — add a dated relist-event log if exact historical accuracy ever matters.
    fun relistsSinceStart(): Int = (currentRelistTotal() - relistBaseline).coerceAtLeast(0)

    private fun currentRelistTotal(): Int = ActiveOrderDao.getAll().sumOf { it.relistCount }
}
