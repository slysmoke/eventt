package org.eventt.features.market

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.formatEveSigFigPrice
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicInteger

// One rung of one selected item's buy ladder, already snapped to EVE's 4-sigfig price grid --
// see MaterialsInvestmentTab, which builds this list from the selected rows' AllocatedMaterial.ladder.
data class PendingMaterialItem(
    val charId: Int?,
    val typeId: Int,
    val typeName: String,
    val price: Double,
    val qty: Long,
)

/**
 * Global queue of Materials Investment buy-ladder rungs used by the keyboard hotkey -- the same
 * PRICE/VOLUME two-phase cycle as StationTradingQueue, but built from the ladder rungs of whatever
 * rows are checked in the table rather than a single per-item price. Since this tab is about
 * placing standing buy orders at strategic support levels (not undercutting the current best bid
 * to win the top spot), the price pasted is the rung's own target price, not price+1 tick.
 */
object MaterialsInvestmentQueue {
    private enum class Phase { PRICE, VOLUME }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cursor = AtomicInteger(0)

    @Volatile private var queue: List<PendingMaterialItem> = emptyList()

    @Volatile private var phase: Phase = Phase.PRICE

    /** Whether the second hotkey press copies the suggested quantity. Toggled from the UI. */
    @Volatile var copyVolume: Boolean = true

    /** The typeId most recently acted on. Observed by MaterialsInvestmentTab to highlight the row. */
    private val _currentTypeId = MutableStateFlow<Int?>(null)
    val currentTypeId: StateFlow<Int?> = _currentTypeId

    val size: Int get() = queue.size

    /** Current 1-based position in the cycle, for display. */
    val currentPosition: Int get() {
        val q = queue
        return if (q.isEmpty()) 0 else (cursor.get() % q.size) + 1
    }

    fun update(items: List<PendingMaterialItem>) {
        queue = items
        cursor.set(0)
        phase = Phase.PRICE
        if (items.isEmpty()) _currentTypeId.value = null
    }

    fun clear() {
        queue = emptyList()
        cursor.set(0)
        phase = Phase.PRICE
        _currentTypeId.value = null
    }

    fun processNext() {
        val q = queue
        if (q.isEmpty()) return
        val item = q[cursor.get() % q.size]
        _currentTypeId.value = item.typeId

        if (phase == Phase.PRICE) {
            scope.launch {
                item.charId?.let { runCatching { EsiClient.openMarketWindow(it, item.typeId) } }
                val sel = StringSelection(formatEveSigFigPrice(item.price))
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            }
            if (copyVolume) {
                phase = Phase.VOLUME
            } else {
                cursor.incrementAndGet()
            }
        } else {
            scope.launch {
                val sel = StringSelection(item.qty.coerceAtLeast(1).toString())
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            }
            cursor.incrementAndGet()
            phase = Phase.PRICE
        }
    }
}
