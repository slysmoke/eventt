package org.eventt.features.orders

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StandingOrdersTest {
    @Test
    fun `a buy order far enough under the best bid is standing, a near one is just outbid`() {
        StandingOrders.isStanding(ownPrice = 90.0, bestBuy = 100.0, threshold = 5.0) shouldBe true
        StandingOrders.isStanding(ownPrice = 99.0, bestBuy = 100.0, threshold = 5.0) shouldBe false
        StandingOrders.isStanding(ownPrice = 50.0, bestBuy = 100.0, threshold = 0.0) shouldBe false
        StandingOrders.isStanding(ownPrice = 50.0, bestBuy = null, threshold = 5.0) shouldBe false
    }

    @Test
    fun `distance is how far behind the best competing price, negative when on top`() {
        distancePct(102.0, isBuy = false, MarketComparison(bestSell = 100.0, bestBuy = null))!! should (2.0 plusOrMinus 1e-9)
        distancePct(98.0, isBuy = false, MarketComparison(bestSell = 100.0, bestBuy = null))!! should (-2.0 plusOrMinus 1e-9)
        distancePct(90.0, isBuy = true, MarketComparison(bestSell = null, bestBuy = 100.0))!! should (10.0 plusOrMinus 1e-9)
        distancePct(90.0, isBuy = true, MarketComparison(bestSell = 100.0, bestBuy = null)).shouldBeNull()
    }

    @Test
    fun `a one-tick undercut keeps two decimals instead of reading +0_0 percent`() {
        formatDistance(0.047, signed = true) shouldBe "+0.05%"
        formatDistance(2.43, signed = true) shouldBe "+2.4%"
        formatDistance(12.0, signed = false) shouldBe "12.0%"
    }

    @Test
    fun `an order flagged long-term by hand is standing whatever its price`() {
        StandingOrders.isManual(42L) shouldBe false
        StandingOrders.mark(listOf(42L), true)
        try {
            StandingOrders.isManual(42L) shouldBe true
            StandingOrders.isManual(43L) shouldBe false
        } finally {
            StandingOrders.mark(listOf(42L), false)
        }
        StandingOrders.isManual(42L) shouldBe false
    }

    @Test
    fun `shift-click takes every shown row between the last clicked one and this one`() {
        val shown = listOf(5L, 3L, 9L, 7L)
        longTermRange(shown, anchor = 9L, clicked = 5L, shift = true) shouldBe listOf(5L, 3L, 9L)
        longTermRange(shown, anchor = 3L, clicked = 7L, shift = true) shouldBe listOf(3L, 9L, 7L)
        longTermRange(shown, anchor = 3L, clicked = 7L, shift = false) shouldBe listOf(7L)
        // The anchor row is filtered out or was never set: just the clicked row.
        longTermRange(shown, anchor = 1L, clicked = 7L, shift = true) shouldBe listOf(7L)
        longTermRange(shown, anchor = null, clicked = 7L, shift = true) shouldBe listOf(7L)
    }
}
