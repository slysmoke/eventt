package org.eventt.features.market

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
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
}
