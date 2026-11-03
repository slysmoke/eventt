package org.eventt.features.market

import org.eventt.core.model.MarketHistoryModel
import kotlin.math.sqrt

// Pure indicator math for the item chart. Inputs are one value per *trading* day (ESI history
// omits no-trade days), oldest first; outputs are index-aligned, null until enough data exists.

internal fun sma(
    values: List<Double>,
    period: Int,
): List<Double?> {
    if (period <= 0) return values.map { null }
    var sum = 0.0
    return values.indices.map { i ->
        sum += values[i]
        if (i >= period) sum -= values[i - period]
        if (i >= period - 1) sum / period else null
    }
}

internal data class Band(
    val upper: Double,
    val lower: Double,
)

// Bollinger bands: SMA(period) ± k population standard deviations over the same window.
internal fun bollinger(
    values: List<Double>,
    period: Int = 20,
    k: Double = 2.0,
): List<Band?> {
    val mid = sma(values, period)
    return values.indices.map { i ->
        val m = mid[i] ?: return@map null
        val window = values.subList(i - period + 1, i + 1)
        val sd = sqrt(window.sumOf { (it - m) * (it - m) } / period)
        Band(m + k * sd, m - k * sd)
    }
}

// Rolling volume-weighted average price over the last `period` trading days. A window with no
// volume at all falls back to the plain mean.
internal fun vwap(
    values: List<Double>,
    volumes: List<Double>,
    period: Int = 20,
): List<Double?> =
    values.indices.map { i ->
        if (i < period - 1) return@map null
        val from = i - period + 1
        val vol = (from..i).sumOf { volumes[it] }
        if (vol > 0) (from..i).sumOf { values[it] * volumes[it] } / vol else (from..i).sumOf { values[it] } / period
    }

// Population deviation of the window's prices around that window's VWAP. Null while warming up.
internal fun vwapSd(
    values: List<Double>,
    vwap: List<Double?>,
    period: Int = 20,
): List<Double?> =
    values.indices.map { i ->
        val v = vwap[i] ?: return@map null
        sqrt((i - period + 1..i).sumOf { (values[it] - v) * (values[it] - v) } / period)
    }

// How many standard deviations price sits from its VWAP: (price - vwap) / sd (see vwapSd).
// Null while warming up or flat.
internal fun vwapZScore(
    values: List<Double>,
    vwap: List<Double?>,
    period: Int = 20,
): List<Double?> {
    val sd = vwapSd(values, vwap, period)
    return values.indices.map { i ->
        val v = vwap[i] ?: return@map null
        sd[i]?.takeIf { it > 0 }?.let { (values[i] - v) / it }
    }
}

// Exponential smoothing of an already-computed series: alpha = 2 / (period + 1). Restarts after a
// null, so the warm-up gap of the input stays a gap.
internal fun ema(
    values: List<Double?>,
    period: Int,
): List<Double?> {
    val alpha = 2.0 / (period + 1)
    var prev: Double? = null
    return values.map { v ->
        prev = v?.let { x -> prev?.let { it + alpha * (x - it) } ?: x }
        prev
    }
}

// EMA period applied on top of the VWAP lines and their z-scores, to calm day-to-day jitter.
internal const val VWAP_SMOOTH = 5

// VWAP period behind the Analysis max-Z filter (the item chart's first VWAP line), and how much
// history it fetches for it.
internal const val Z_FILTER_PERIOD = 28
internal const val Z_HISTORY_DAYS = 60

// Latest smoothed z-score of daily averages vs their VWAP -- the right edge of the item chart's Z
// pane. [history] is newest first, as fetchHistory returns it. Null without enough history.
internal fun latestVwapZ(
    history: List<MarketHistoryModel>,
    period: Int = Z_FILTER_PERIOD,
): Double? {
    val rows = history.reversed()
    val prices = rows.map { it.average }
    val v = vwap(prices, rows.map { it.volume.toDouble() }, period)
    return ema(vwapZScore(prices, v, period), VWAP_SMOOTH).lastOrNull()
}
