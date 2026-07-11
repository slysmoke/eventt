package org.eventt.core.model

import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

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
