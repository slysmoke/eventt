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
    fun `ctrl and shift clicks pick rows for a bulk action, a plain click drops the pick`() {
        val pick = RowPick().apply { visible = listOf(5L, 3L, 9L, 7L) }

        pick.click(3L, ctrl = false, shift = false) shouldBe false
        pick.ids shouldBe emptySet()
        // Shift takes the range from the last clicked row, in the shown order.
        pick.click(7L, ctrl = false, shift = true) shouldBe true
        pick.ids shouldBe setOf(3L, 9L, 7L)
        pick.click(9L, ctrl = true, shift = false) shouldBe true
        pick.ids shouldBe setOf(3L, 7L)

        // The flag on a picked row goes to the whole pick, on another row to that row alone.
        pick.targets(7L).toSet() shouldBe setOf(3L, 7L)
        pick.targets(5L) shouldBe listOf(5L)
        // Rows filtered out of view since being picked are left alone.
        pick.visible = listOf(7L, 5L)
        pick.targets(7L).toSet() shouldBe setOf(7L)

        pick.click(5L, ctrl = false, shift = false)
        pick.ids shouldBe emptySet()

        // Dragging from one shown row to another picks everything between, in either direction.
        pick.visible = listOf(5L, 3L, 9L, 7L)
        pick.drag(from = 2, to = 0)
        pick.ids shouldBe setOf(5L, 3L, 9L)
        pick.drag(from = 2, to = 3)
        pick.ids shouldBe setOf(9L, 7L)
        pick.drag(from = -1, to = 3)
        pick.ids shouldBe setOf(9L, 7L)
    }
}
