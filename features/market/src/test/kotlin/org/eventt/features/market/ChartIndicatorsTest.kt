package org.eventt.features.market

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.eventt.core.model.MarketHistoryModel
import org.junit.jupiter.api.Test

class ChartIndicatorsTest {
    @Test
    fun `sma is null until the window fills, then the rolling mean`() {
        sma(listOf(1.0, 2.0, 3.0, 4.0), 2) shouldBe listOf(null, 1.5, 2.5, 3.5)
    }

    @Test
    fun `bollinger collapses onto the mean for a flat series`() {
        val b = bollinger(List(20) { 5.0 }, period = 20).last()!!
        b.upper shouldBe 5.0
        b.lower shouldBe 5.0
    }

    @Test
    fun `vwap weights each day by its volume`() {
        vwap(listOf(10.0, 20.0), listOf(3.0, 1.0), period = 2) shouldBe listOf(null, 12.5)
        vwap(listOf(10.0, 20.0), listOf(0.0, 0.0), period = 2).last() shouldBe 15.0
    }

    @Test
    fun `z score measures distance from vwap in standard deviations`() {
        val prices = listOf(10.0, 10.0, 10.0, 14.0)
        val v = vwap(prices, List(4) { 1.0 }, period = 4) // 11.0, sd around it = sqrt(3)
        vwapZScore(prices, v, period = 4).last()!! shouldBe (3.0 / kotlin.math.sqrt(3.0) plusOrMinus 1e-9)
        vwapZScore(List(4) { 5.0 }, vwap(List(4) { 5.0 }, List(4) { 1.0 }, 4), 4).last() shouldBe null
    }

    @Test
    fun `ema smooths toward new values and keeps null gaps`() {
        ema(listOf(null, 10.0, 20.0, null, 4.0), period = 3) shouldBe listOf(null, 10.0, 15.0, null, 4.0)
    }

    @Test
    fun `latestVwapZ reads newest-first history and is high after a jump, null when too short`() {
        // Newest first: a jump on the latest day after a gently oscillating month.
        val history =
            (0 until 40).map { d ->
                val avg = if (d == 0) 150.0 else 100.0 + d % 2
                MarketHistoryModel(1, 1, "day -$d", avg, 10, 1, avg, avg)
            }
        (latestVwapZ(history)!! > 1.0) shouldBe true
        latestVwapZ(history.take(10)) shouldBe null
    }

    @Test
    fun `vwapBandPrice is where the raw z-score of today's window equals z`() {
        // Newest first, alternating 90/110 with uneven volume.
        val history =
            (0 until 40).map { d ->
                (
                    if (d % 2 ==
                        0
                    ) {
                        90.0
                    } else {
                        110.0
                    }
                ).let { MarketHistoryModel(1, 1, "d$d", it, 10L + d % 3, 1, it, it) }
            }
        val p = vwapBandPrice(history, 1.0)!!
        val rows = history.take(Z_FILTER_PERIOD).reversed()
        val prices = rows.map { it.average }
        val v = vwap(prices, rows.map { it.volume.toDouble() }, Z_FILTER_PERIOD)
        val sd = vwapSd(prices, v, Z_FILTER_PERIOD).last()!!
        p shouldBe (v.last()!! + sd plusOrMinus 1e-9)
        vwapBandPrice(history.take(10), 1.0) shouldBe null
        vwapBandPrice(List(30) { MarketHistoryModel(1, 1, "d", 5.0, 1, 1, 5.0, 5.0) }, 1.0) shouldBe null
    }
}
