package org.eventt.features.market

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.eventt.core.esi.EsiClient
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

// EVE price rounding: 4 significant figures, minimum 0.01 ISK precision. Mirrors the identical
// helper in features/orders/PendingOrdersQueue.kt — duplicated rather than shared across modules
// since features:market doesn't depend on features:orders.
private fun eveSigFigStep(price: Double): Double {
    if (price <= 0) return 0.01
    val magnitude = floor(log10(price))
    return maxOf(0.01, 10.0.pow(magnitude - 3))
}

private fun formatEveSigFigPrice(price: Double): String {
    if (price <= 0) return "0.01"
    val step     = eveSigFigStep(price)
    val decimals = maxOf(0, -floor(log10(step)).toInt())
    return String.format(Locale.US, "%.${decimals}f", price)
}

data class PendingStationItem(
    val charId: Int,
    val typeId: Int,
    val typeName: String,
    val bestBuy: Double,  // current best competing buy order — we step one tick above it to lead
    val volume: Long,     // suggested buy quantity (after any volume modifier applied by the caller)
) {
    // Price to paste into the EVE buy-order dialog to become the new top buyer.
    val priceToSet: Double get() {
        val step = eveSigFigStep(bestBuy)
        return round(bestBuy / step) * step + step
    }
}

/**
 * Global queue of station-trading opportunities used by the keyboard hotkey.
 * Updated by MarketAnalysisScreen whenever results, selection, or the volume modifier change.
 *
 * Unlike PendingOrdersQueue (single phase), each item here is cycled through two phases:
 *   1. PRICE — open the item's market window in the EVE client and copy the buy price to paste.
 *   2. VOLUME — copy the suggested quantity to buy, then advance to the next item.
 * When `copyVolume` is turned off, phase 2 is skipped entirely (every press is price + advance).
 */
object StationTradingQueue {

    private enum class Phase { PRICE, VOLUME }

    private val scope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cursor = AtomicInteger(0)

    @Volatile private var queue: List<PendingStationItem> = emptyList()
    @Volatile private var phase: Phase = Phase.PRICE

    /** Whether the second hotkey press copies the suggested volume. Toggled from the UI. */
    @Volatile var copyVolume: Boolean = true

    /** The typeId most recently acted on. Observed by MarketAnalysisScreen to highlight the row. */
    private val _currentTypeId = MutableStateFlow<Int?>(null)
    val currentTypeId: StateFlow<Int?> = _currentTypeId

    val size: Int get() = queue.size

    /** Current 1-based position in the cycle, for display. */
    val currentPosition: Int get() {
        val q = queue
        return if (q.isEmpty()) 0 else (cursor.get() % q.size) + 1
    }

    fun update(items: List<PendingStationItem>) {
        queue = items
        cursor.set(0)
        phase = Phase.PRICE
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
                runCatching { EsiClient.openMarketWindow(item.charId, item.typeId) }
                val sel = StringSelection(formatEveSigFigPrice(item.priceToSet))
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            }
            if (copyVolume) {
                phase = Phase.VOLUME
            } else {
                cursor.incrementAndGet()
            }
        } else {
            scope.launch {
                val sel = StringSelection(item.volume.coerceAtLeast(1).toString())
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            }
            cursor.incrementAndGet()
            phase = Phase.PRICE
        }
    }
}
