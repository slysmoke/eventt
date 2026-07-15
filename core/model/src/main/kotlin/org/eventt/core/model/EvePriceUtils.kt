package org.eventt.core.model

import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

// PLEX doesn't trade in ordinary regions — CCP settles it in one global virtual market region
// shared across all of New Eden (not in the SDE; announced by CCP as ID 19000001). Station or
// system scoping is meaningless for it.
const val PLEX_TYPE_ID = 44992
const val PLEX_MARKET_REGION_ID = 19000001

/**
 * Calculates EVE's price tick step size based on 4 significant figures,
 * with a minimum step size (precision) of 0.01 ISK.
 */
fun eveSigFigStep(price: Double): Double {
    if (price <= 0) return 0.01
    val magnitude = floor(log10(price))
    return maxOf(0.01, 10.0.pow(magnitude - 3))
}

/**
 * Format a price for EVE's order dialogs using 4-sigfig precision.
 * Uses the correct number of decimal places for the price magnitude.
 */
fun formatEveSigFigPrice(price: Double): String {
    if (price <= 0) return "0.01"
    val step = eveSigFigStep(price)
    val decimals = maxOf(0, -floor(log10(step)).toInt())
    return String.format(Locale.US, "%.${decimals}f", price)
}

/**
 * One tick below [price] on EVE's 4-sigfig grid — the price to paste to undercut a sell order.
 * Snaps to the grid first so float noise doesn't skip a tick.
 */
fun eveUndercutPrice(price: Double): Double {
    val step = eveSigFigStep(price)
    return (round(price / step) * step - step).coerceAtLeast(0.01)
}

/** One tick above [price] on EVE's 4-sigfig grid — the price to paste to outbid a buy order. */
fun eveOutbidPrice(price: Double): Double {
    val step = eveSigFigStep(price)
    return round(price / step) * step + step
}
