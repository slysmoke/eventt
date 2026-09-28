package org.eventt.features.market

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

// Wilder's RSI: first average is a plain mean of the first `period` changes, then smoothed.
internal fun rsi(
    values: List<Double>,
    period: Int = 14,
): List<Double?> {
    val out = MutableList<Double?>(values.size) { null }
    if (values.size <= period) return out
    var gain = 0.0
    var loss = 0.0
    for (i in 1..period) {
        val d = values[i] - values[i - 1]
        if (d > 0) gain += d else loss -= d
    }
    gain /= period
    loss /= period

    fun value() = if (loss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + gain / loss)
    out[period] = value()
    for (i in period + 1 until values.size) {
        val d = values[i] - values[i - 1]
        gain = (gain * (period - 1) + maxOf(d, 0.0)) / period
        loss = (loss * (period - 1) + maxOf(-d, 0.0)) / period
        out[i] = value()
    }
    return out
}
