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
    fun `rsi is 100 for a strictly rising series and 0 for a falling one`() {
        rsi((1..20).map { it.toDouble() }).last() shouldBe 100.0
        rsi((20 downTo 1).map { it.toDouble() }).last()!! shouldBe (0.0 plusOrMinus 1e-9)
        rsi((1..14).map { it.toDouble() }).all { it == null } shouldBe true
    }

    @Test
    fun `rsi matches Wilder smoothing on a known alternating series`() {
        // Changes alternate +1/-1: after seeding, avg gain == avg loss, so RSI sits at 50.
        val values = (0..30).map { if (it % 2 == 0) 10.0 else 11.0 }
        rsi(values, 14).last()!! shouldBe (50.0 plusOrMinus 5.0)
    }
}
