package org.eventt.ui.common

import java.util.Locale
import kotlin.math.abs

private val UNITS = listOf(1e12 to "T", 1e9 to "B", 1e6 to "M", 1e3 to "K")

/**
 * The one K/M/B/T abbreviation every number in the UI goes through. [decimals] is the usual
 * precision; [minSignificant] adds decimals when the leading part is short, so one-tick price
 * differences don't collapse (1,105 vs 1,104 -> "1.105K" / "1.104K", not both "1.10K").
 * Picks the unit from the *rounded* value, so 999,999 reads "1.000M", not "1000.00K".
 */
private fun abbreviate(
    value: Double,
    decimals: Int,
    minSignificant: Int,
    belowThousand: (Double) -> String,
): String {
    val idx = UNITS.indexOfFirst { abs(value) >= it.first }
    if (idx < 0) return belowThousand(value)

    fun render(i: Int): Pair<Double, String> {
        val x = value / UNITS[i].first
        val d = maxOf(decimals, minSignificant - abs(x).toLong().toString().length)
        val text = String.format(Locale.US, "%.${d}f", x)
        return text.toDouble() to text + UNITS[i].second
    }
    val (rounded, text) = render(idx)
    // Rounded up into the next unit (e.g. 999,999 -> "1000.00K") — let the bigger unit render it.
    return if (idx > 0 && abs(rounded) >= 1000) render(idx - 1).second else text
}

/**
 * Formats an ISK amount with abbreviated units (T/B/M/K). Negative values keep their sign.
 */
fun formatIsk(value: Double): String = abbreviate(value, 2, 4) { String.format(Locale.US, "%.2f", it) }

/**
 * Formats a price with abbreviated units (T/B/M/K), keeping enough digits that adjacent price
 * ticks stay distinguishable. Same scale as [formatIsk].
 */
fun formatPriceAbbr(price: Double): String = abbreviate(price, 2, 4) { String.format(Locale.US, "%.2f", it) }

/**
 * Formats a price with two decimal places and comma grouping, used in PricingScreen.
 */
fun formatPriceSimple(value: Double): String = String.format(Locale.US, "%,.2f", value)

/**
 * Formats a volume (in m³) for UI display with abbreviated units (T/B/M/K).
 */
fun formatVolume(m3: Double): String = abbreviate(m3, 1, 0) { String.format(Locale.US, "%.2f", it) }

/**
 * Formats a volume/quantity given as a long integer, using abbreviated units (T/B/M/K).
 */
fun formatVolume(vol: Long): String = abbreviate(vol.toDouble(), 1, 0) { vol.toString() }
