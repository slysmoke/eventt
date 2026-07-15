package org.eventt.features.orders

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.eveSigFigStep
import org.eventt.core.model.formatEveSigFigPrice
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.math.round

data class PendingOrder(
    val charId: Int,
    val orderId: Long,
    val typeId: Int,
    val typeName: String,
    val isBuyOrder: Boolean,
    val regionId: Int,
    val ownPrice: Double,
    val bestCompetingPrice: Double?,
    val isBeaten: Boolean,
) {
    // Price to paste into the EVE modify-order dialog to beat the competition by one sigfig step.
    val priceToSet: Double get() =
        when {
            isBeaten && bestCompetingPrice != null -> {
                val step = eveSigFigStep(bestCompetingPrice)
                val rounded = round(bestCompetingPrice / step) * step
                if (isBuyOrder) rounded + step else rounded - step
            }
            else -> ownPrice
        }
}

/**
 * Global queue of active orders used by the keyboard hotkey.
 * Updated by OrdersScreen whenever orders or market comparisons change.
 * Beaten orders are sorted first so the most urgent ones get cycled first.
 */
object PendingOrdersQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var queue: List<PendingOrder> = emptyList()

    // The order the hotkey acted on last. The cycle position derives from it by orderId, so a
    // queue update (auto price refresh, resort) keeps the user's place instead of resetting it.
    @Volatile private var lastOrderId: Long? = null

    /** When on, the hotkey cycles only orders currently beaten by a competitor. Toggled from the UI. */
    @Volatile var onlyBeaten: Boolean = false

    // One-shot override set by selecting a row in OrdersScreen: the next hotkey press acts on
    // this order (then the cycle continues from there) instead of wherever the cycle left off.
    @Volatile private var nextOrderId: Long? = null

    /** Make the next hotkey press start from [orderId] — set when the user selects an order row. */
    fun startFrom(orderId: Long) {
        nextOrderId = orderId
    }

    // Set by the app shell at startup; invoked with (title, message) when orders newly become
    // beaten. Lives here because features:orders has no tray icon or windowing of its own.
    @Volatile var notifier: ((title: String, message: String) -> Unit)? = null

    /** The orderId most recently opened via the hotkey. Observed by OrdersScreen to highlight the row. */
    private val _currentOrderId = MutableStateFlow<Long?>(null)
    val currentOrderId: StateFlow<Long?> = _currentOrderId

    private fun active(): List<PendingOrder> = if (onlyBeaten) queue.filter { it.isBeaten } else queue

    // Index of the order the next hotkey press will act on: the one after the last-processed
    // order, by id. If that order left the queue (no longer beaten, filter change), indexOfFirst
    // returns -1 and the cycle restarts at the most urgent entry.
    private fun nextIndex(q: List<PendingOrder>): Int {
        nextOrderId?.let { id ->
            val i = q.indexOfFirst { it.orderId == id }
            if (i >= 0) return i
        }
        return (q.indexOfFirst { it.orderId == lastOrderId } + 1) % q.size
    }

    val size: Int get() = active().size

    /** Current 1-based position in the cycle (the next order the hotkey acts on), for display. */
    val currentPosition: Int get() {
        val q = active()
        return if (q.isEmpty()) 0 else nextIndex(q) + 1
    }

    /** Replace the queue. Beaten orders sort first, then alphabetically by name. */
    fun update(orders: List<PendingOrder>) {
        queue =
            orders.sortedWith(
                compareByDescending<PendingOrder> { it.isBeaten }.thenBy { it.typeName },
            )
    }

    fun clear() {
        queue = emptyList()
        lastOrderId = null
        nextOrderId = null
        _currentOrderId.value = null
    }

    /**
     * Process the next order in the cycle:
     *   1. Open its market window in the running EVE client
     *   2. Copy the overbid/undercut price (or own price if no competition) to clipboard
     */
    fun processNext() {
        val q = active()
        if (q.isEmpty()) return
        val order = q[nextIndex(q)]
        nextOrderId = null
        lastOrderId = order.orderId
        _currentOrderId.value = order.orderId
        scope.launch {
            runCatching { EsiClient.openMarketWindow(order.charId, order.typeId) }
            val text = formatEveSigFigPrice(order.priceToSet)
            val sel = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
        }
    }
}
