package org.eventt.ui.common

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
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

/**
 * Shows a number field's leading digit run in groups of three ("3500000000" -> "3 500 000 000")
 * while the field's text stays plain digits, so parsing and saved settings don't change.
 */
object DigitGroupTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val digits = raw.takeWhile { it.isDigit() }.length
        val out = StringBuilder()
        // toOut[i] = position in the shown text of raw offset i; toRaw is its inverse.
        val toOut = IntArray(raw.length + 1)
        val toRaw = ArrayList<Int>(raw.length + raw.length / 3 + 1)
        raw.forEachIndexed { i, c ->
            if (i in 1 until digits && (digits - i) % 3 == 0) {
                toRaw += i
                out.append(' ')
            }
            toOut[i] = out.length
            toRaw += i
            out.append(c)
        }
        toOut[raw.length] = out.length
        toRaw += raw.length
        return TransformedText(
            AnnotatedString(out.toString()),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = toOut[offset]

                override fun transformedToOriginal(offset: Int) = toRaw[offset]
            },
        )
    }
}
