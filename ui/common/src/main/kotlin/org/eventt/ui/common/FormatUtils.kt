package org.eventt.ui.common

import java.util.Locale

/**
 * Formats a price for UI display using abbreviated units (B/M/K) similar to MarketBrowserScreen.
 */
fun formatPriceAbbr(price: Double): String =
    when {
        price >= 1_000_000_000 -> String.format(Locale.US, "%.2fB", price / 1_000_000_000)
        price >= 1_000_000 -> String.format(Locale.US, "%.2fM", price / 1_000_000)
        price >= 1_000 -> String.format(Locale.US, "%,.2f", price)
        else -> String.format(Locale.US, "%.4f", price)
    }

/**
 * Formats a price with two decimal places and comma grouping, used in PricingScreen.
 */
fun formatPriceSimple(value: Double): String = String.format(Locale.US, "%,.2f", value)

/**
 * Formats a volume (in m³) for UI display, using thresholds similar to MarketAnalysisScreen.
 */
fun formatVolume(m3: Double): String =
    when {
        m3 >= 1_000_000_000 -> String.format(Locale.US, "%.2fB", m3 / 1_000_000_000)
        m3 >= 1_000_000 -> String.format(Locale.US, "%.2fM", m3 / 1_000_000)
        m3 >= 1_000 -> String.format(Locale.US, "%.1fk", m3 / 1_000)
        else -> String.format(Locale.US, "%.2f", m3)
    }

/**
 * Formats a volume given as a long integer, using abbreviated units (B/M/K).
 */
fun formatVolume(vol: Long): String =
    when {
        vol >= 1_000_000_000L -> String.format(Locale.US, "%.1fB", vol / 1_000_000_000.0)
        vol >= 1_000_000L -> String.format(Locale.US, "%.1fM", vol / 1_000_000.0)
        vol >= 1_000L -> String.format(Locale.US, "%.1fK", vol / 1_000.0)
        else -> vol.toString()
    }

/**
 * Formats a volume for the SplitterScreen (in cubic meters) with one decimal place.
 */
fun formatVolumeM3(v: Double): String = "%.1f m³".format(v)
