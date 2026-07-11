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
import java.util.concurrent.atomic.AtomicInteger
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
    private val cursor = AtomicInteger(0)

    @Volatile private var queue: List<PendingOrder> = emptyList()

    /** The orderId most recently opened via the hotkey. Observed by OrdersScreen to highlight the row. */
    private val _currentOrderId = MutableStateFlow<Long?>(null)
    val currentOrderId: StateFlow<Long?> = _currentOrderId

    val size: Int get() = queue.size

    /** Current 1-based position in the cycle, for display. */
    val currentPosition: Int get() {
        val q = queue
        return if (q.isEmpty()) 0 else (cursor.get() % q.size) + 1
    }

    /** Replace the queue. Beaten orders sort first, then alphabetically by name. */
    fun update(orders: List<PendingOrder>) {
        queue =
            orders.sortedWith(
                compareByDescending<PendingOrder> { it.isBeaten }.thenBy { it.typeName },
            )
        cursor.set(0)
    }

    fun clear() {
        queue = emptyList()
        cursor.set(0)
        _currentOrderId.value = null
    }

    /**
     * Process the next order in the cycle:
     *   1. Open its market window in the running EVE client
     *   2. Copy the overbid/undercut price (or own price if no competition) to clipboard
     */
    fun processNext() {
        val q = queue
        if (q.isEmpty()) return
        val order = q[cursor.getAndIncrement() % q.size]
        _currentOrderId.value = order.orderId
        scope.launch {
            runCatching { EsiClient.openMarketWindow(order.charId, order.typeId) }
            val text = formatEveSigFigPrice(order.priceToSet)
            val sel = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
        }
    }
}
