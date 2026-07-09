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

// EVE price rounding: 4 significant figures, minimum 0.01 ISK precision. Duplicated from
// StationTradingQueue.kt rather than shared, since these are (otherwise) the only two call sites
// in this module. internal (not private) so MarketAnalysisScreen.kt can reuse this exact copy for
// its Safe Buy→Sell price-floor logic instead of adding a third duplicate.
internal fun eveSigFigStep(price: Double): Double {
    if (price <= 0) return 0.01
    val magnitude = floor(log10(price))
    return maxOf(0.01, 10.0.pow(magnitude - 3))
}

private fun formatEveSigFigPrice(price: Double): String {
    if (price <= 0) return "0.01"
    val step = eveSigFigStep(price)
    val decimals = maxOf(0, -floor(log10(step)).toInt())
    return String.format(Locale.US, "%.${decimals}f", price)
}

data class PendingRegionItem(
    val charId: Int,
    val typeId: Int,
    val typeName: String,
    val price: Double,
    // true when `price` is a competing buy order you need to outbid (BUY_TO_* trade types — you
    // place your own buy order at the source region and wait for it to fill); false when it's an
    // existing sell order you just click "buy" on directly (SELL_TO_* trade types — the price is
    // already fixed by the seller, so there's nothing to outbid).
    val isCompetitiveBid: Boolean,
    val volume: Long,
) {
    val priceToSet: Double get() =
        if (isCompetitiveBid) {
            val step = eveSigFigStep(price)
            round(price / step) * step + step
        } else {
            price
        }
}

private enum class RegionPhase { PRICE, VOLUME }

/**
 * Global queue of inter-region opportunities used by the keyboard hotkey — mirrors
 * StationTradingQueue's two-phase price/volume cycle, but only ever drives the *acquisition*
 * (buy-region) leg: open the item's market window in the EVE client and copy the price to
 * bid/pay, then on the second press the suggested quantity to buy, then advance. The sell leg
 * happens later at a different station once the goods have actually been hauled there, so it
 * isn't part of this immediate cycle.
 */
object InterRegionQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cursor = AtomicInteger(0)

    @Volatile private var queue: List<PendingRegionItem> = emptyList()

    @Volatile private var phase: RegionPhase = RegionPhase.PRICE

    /** Whether the second hotkey press copies the suggested volume. Toggled from the UI. */
    @Volatile var copyVolume: Boolean = true

    /** The typeId most recently acted on. Observed by InterRegionTab to highlight the row. */
    private val _currentTypeId = MutableStateFlow<Int?>(null)
    val currentTypeId: StateFlow<Int?> = _currentTypeId

    val size: Int get() = queue.size

    /** Current 1-based position in the cycle, for display. */
    val currentPosition: Int get() {
        val q = queue
        return if (q.isEmpty()) 0 else (cursor.get() % q.size) + 1
    }

    fun update(items: List<PendingRegionItem>) {
        queue = items
        cursor.set(0)
        phase = RegionPhase.PRICE
    }

    fun clear() {
        queue = emptyList()
        cursor.set(0)
        phase = RegionPhase.PRICE
        _currentTypeId.value = null
    }

    fun processNext() {
        val q = queue
        if (q.isEmpty()) return
        val item = q[cursor.get() % q.size]
        _currentTypeId.value = item.typeId

        if (phase == RegionPhase.PRICE) {
            scope.launch {
                runCatching { EsiClient.openMarketWindow(item.charId, item.typeId) }
                val sel = StringSelection(formatEveSigFigPrice(item.priceToSet))
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            }
            if (copyVolume) {
                phase = RegionPhase.VOLUME
            } else {
                cursor.incrementAndGet()
            }
        } else {
            scope.launch {
                val sel = StringSelection(item.volume.coerceAtLeast(1).toString())
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            }
            cursor.incrementAndGet()
            phase = RegionPhase.PRICE
        }
    }
}
