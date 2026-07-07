package org.eventt.features.market

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private const val TOLERANCE = 0.0001
private val NO_FEE: (Double) -> Double = { 0.0 }
private val NO_FEE_2: (Double, Double) -> Double = { _, _ -> 0.0 }

class OrderBookWalkersTest {
    // ─── walkSourceSellLots (BUY the source's sell orders, ascending) ──────

    @Test
    fun `walkSourceSellLots accumulates lots while margin holds, then stops`() {
        val lots = listOf(50.0 to 10L, 80.0 to 5L, 95.0 to 3L)

        val (volume, profit) =
            walkSourceSellLots(
                lots,
                fixedSellPrice = 100.0,
                shippingPerUnit = 0.0,
                minMarginPct = 10.0,
                feeForBuyPrice = NO_FEE,
            )

        // lot1: margin 100% (50->100), lot2: margin 25% (80->100), lot3: margin ~5.3% (95->100) -> cut
        volume shouldBe 15L
        profit should (600.0 plusOrMinus TOLERANCE) // 10*50 + 5*20
    }

    @Test
    fun `walkSourceSellLots returns nothing when even the best lot misses the margin`() {
        val lots = listOf(98.0 to 10L)

        val (volume, profit) =
            walkSourceSellLots(
                lots,
                fixedSellPrice = 100.0,
                shippingPerUnit = 0.0,
                minMarginPct = 10.0,
                feeForBuyPrice = NO_FEE,
            )

        volume shouldBe 0L
        profit should (0.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `walkSourceSellLots skips zero-volume lots without breaking the walk`() {
        val lots = listOf(50.0 to 0L, 60.0 to 10L)

        val (volume, profit) =
            walkSourceSellLots(
                lots,
                fixedSellPrice = 100.0,
                shippingPerUnit = 0.0,
                minMarginPct = 10.0,
                feeForBuyPrice = NO_FEE,
            )

        volume shouldBe 10L
        profit should (400.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `walkSourceSellLots on an empty book returns nothing`() {
        val (volume, profit) =
            walkSourceSellLots(
                emptyList(),
                fixedSellPrice = 100.0,
                shippingPerUnit = 0.0,
                minMarginPct = 10.0,
                feeForBuyPrice = NO_FEE,
            )

        volume shouldBe 0L
        profit should (0.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `walkSourceSellLots accounts for fees and shipping per unit`() {
        val lots = listOf(50.0 to 10L)

        val (volume, profit) =
            walkSourceSellLots(
                lots,
                fixedSellPrice = 100.0,
                shippingPerUnit = 5.0,
                minMarginPct = 10.0,
                feeForBuyPrice = { 10.0 }, // flat 10 ISK/unit fee
            )

        volume shouldBe 10L
        profit should (350.0 plusOrMinus TOLERANCE) // (100-50-10-5) * 10
    }

    // ─── walkDestBuyLots (SELL into the destination's buy orders, descending) ──

    @Test
    fun `walkDestBuyLots accumulates lots while margin holds, then stops`() {
        val lots = listOf(100.0 to 10L, 70.0 to 5L, 52.0 to 3L)

        val (volume, profit) =
            walkDestBuyLots(
                lots,
                fixedBuyPrice = 50.0,
                shippingPerUnit = 0.0,
                minMarginPct = 10.0,
                feeForSellPrice = NO_FEE,
            )

        // lot1: margin 100%, lot2: margin 40%, lot3: margin 4% -> cut
        volume shouldBe 15L
        profit should (600.0 plusOrMinus TOLERANCE) // 10*50 + 5*20
    }

    @Test
    fun `walkDestBuyLots on an empty book returns nothing`() {
        val (volume, profit) =
            walkDestBuyLots(
                emptyList(),
                fixedBuyPrice = 50.0,
                shippingPerUnit = 0.0,
                minMarginPct = 10.0,
                feeForSellPrice = NO_FEE,
            )

        volume shouldBe 0L
        profit should (0.0 plusOrMinus TOLERANCE)
    }

    // ─── walkCrossedBook (both sides are real order books, SELL_TO_BUY) ────────

    @Test
    fun `walkCrossedBook matches cheapest sell lots against best buy lots until margin fails`() {
        val sellLots = listOf(50.0 to 10L, 60.0 to 10L)
        val buyLots = listOf(100.0 to 5L, 90.0 to 20L)

        val (volume, profit) = walkCrossedBook(sellLots, buyLots, shippingPerUnit = 0.0, minMarginPct = 10.0, feeFor = NO_FEE_2)

        // 5 @ (50->100) + 5 @ (50->90) + 10 @ (60->90) = 250 + 200 + 300
        volume shouldBe 20L
        profit should (750.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `walkCrossedBook stops as soon as the crossing margin drops below the threshold`() {
        val sellLots = listOf(50.0 to 10L, 95.0 to 10L)
        val buyLots = listOf(100.0 to 10L)

        val (volume, profit) = walkCrossedBook(sellLots, buyLots, shippingPerUnit = 0.0, minMarginPct = 10.0, feeFor = NO_FEE_2)

        // first 10 units: 50->100 (margin 100%, fine); remaining 0 buy volume left -> loop ends
        // before ever considering the 95-priced lot
        volume shouldBe 10L
        profit should (500.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `walkCrossedBook returns nothing when either side is empty`() {
        val (volume, profit) =
            walkCrossedBook(emptyList(), listOf(100.0 to 10L), shippingPerUnit = 0.0, minMarginPct = 10.0, feeFor = NO_FEE_2)

        volume shouldBe 0L
        profit should (0.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `walkCrossedBook returns nothing when the best possible crossing already misses margin`() {
        val (volume, profit) =
            walkCrossedBook(
                listOf(98.0 to 10L),
                listOf(100.0 to 10L),
                shippingPerUnit = 0.0,
                minMarginPct = 10.0,
                feeFor = NO_FEE_2,
            )

        volume shouldBe 0L
        profit should (0.0 plusOrMinus TOLERANCE)
    }
}
