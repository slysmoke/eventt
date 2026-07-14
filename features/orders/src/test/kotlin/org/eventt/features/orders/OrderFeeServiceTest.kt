package org.eventt.features.orders

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private const val TOLERANCE = 0.0001

class OrderFeeServiceTest {
    @Test
    fun `relistDiscountPct is 50 base plus 6 points per Advanced Broker Relations level, clamped to level 0-5`() {
        OrderFeeService.relistDiscountPct(0) shouldBe 50.0
        OrderFeeService.relistDiscountPct(4) shouldBe 74.0
        OrderFeeService.relistDiscountPct(5) shouldBe 80.0
        OrderFeeService.relistDiscountPct(-1) shouldBe 50.0
        OrderFeeService.relistDiscountPct(99) shouldBe 80.0
    }

    // Worked examples from EVE University's Trading page, verified to match exactly -- these are
    // the ground truth for the formula shape and the 6%/level relist discount.
    @Test
    fun `computeModificationFee matches EVE University's price-increase example`() {
        // 13,000x tritanium, 10,000 remaining (order value 60,000 -> 70,000 at 6.00 -> 7.00 ISK),
        // broker's fee 1.88%, Advanced Broker Relations IV (RD = 50+6*4 = 74%). Expected: 530.16 ISK.
        val fee =
            OrderFeeService.computeModificationFee(
                oldPrice = 6.00,
                newPrice = 7.00,
                volumeRemaining = 10_000,
                brokerFeePct = 1.88,
                relistDiscountPct = OrderFeeService.relistDiscountPct(4),
            )

        fee should (530.16 plusOrMinus TOLERANCE)
    }

    @Test
    fun `computeModificationFee matches EVE University's price-decrease example`() {
        // Same order, price cut 6.00 -> 5.00 ISK (order value 60,000 -> 50,000). Expected: 244.40 ISK.
        val fee =
            OrderFeeService.computeModificationFee(
                oldPrice = 6.00,
                newPrice = 5.00,
                volumeRemaining = 10_000,
                brokerFeePct = 1.88,
                relistDiscountPct = OrderFeeService.relistDiscountPct(4),
            )

        fee should (244.40 plusOrMinus TOLERANCE)
    }

    @Test
    fun `computeModificationFee matches EVE University's structure example`() {
        // 1,000x item A, 1k -> 10k ISK each (order value 1,000,000 -> 10,000,000), 1% broker's
        // fee, Advanced Broker Relations 0 (RD = 50%). Expected: 140,000 ISK.
        val fee =
            OrderFeeService.computeModificationFee(
                oldPrice = 1_000.0,
                newPrice = 10_000.0,
                volumeRemaining = 1_000,
                brokerFeePct = 1.0,
                relistDiscountPct = OrderFeeService.relistDiscountPct(0),
            )

        fee should (140_000.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `computeModificationFee floors small fees at the 100 ISK minimum charge`() {
        // At volume 1, this is just per-unit price: br*(1200-1000) = 6; relist = 0.5*0.03*1200 = 18; total 24, floored to 100.
        val fee =
            OrderFeeService.computeModificationFee(
                oldPrice = 1000.0,
                newPrice = 1200.0,
                volumeRemaining = 1,
                brokerFeePct = 3.0,
                relistDiscountPct = 50.0,
            )

        fee shouldBe 100.0
    }

    @Test
    fun `computeModificationFee adds the price-increase component on top of the relist portion`() {
        // br*(10000-1000) = 270; relist = 0.5*0.03*10000 = 150; total 420, above the minimum.
        val fee =
            OrderFeeService.computeModificationFee(
                oldPrice = 1000.0,
                newPrice = 10000.0,
                volumeRemaining = 1,
                brokerFeePct = 3.0,
                relistDiscountPct = 50.0,
            )

        fee should (420.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `computeModificationFee for a price decrease only charges the relist portion, floored at 100`() {
        // price dropped, so max(0, br*delta) = 0; relist = 0.5*0.03*800 = 12, floored to 100.
        val fee =
            OrderFeeService.computeModificationFee(
                oldPrice = 1000.0,
                newPrice = 800.0,
                volumeRemaining = 1,
                brokerFeePct = 3.0,
                relistDiscountPct = 50.0,
            )

        fee shouldBe 100.0
    }

    @Test
    fun `computeModificationFee scales with the order's remaining volume, not just per-unit price`() {
        // Same per-unit prices as the floored test above, but 100 units on the order: fee is
        // charged on total order value (price*volume), so it's no longer floored at 100.
        // oldValue=100000, newValue=120000; br*(20000)=600; relist=0.5*0.03*120000=1800; total 2400.
        val fee =
            OrderFeeService.computeModificationFee(
                oldPrice = 1000.0,
                newPrice = 1200.0,
                volumeRemaining = 100,
                brokerFeePct = 3.0,
                relistDiscountPct = 50.0,
            )

        fee should (2400.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `estimateUpdatesRemaining returns null without a cost basis`() {
        OrderFeeService
            .estimateUpdatesRemaining(
                volumeRemaining = 10,
                price = 1000.0,
                netSellPricePerUnit = 920.0,
                costBasis = null,
                brokerFeePct = 3.0,
                relistCount = 0,
                relistFeesPaid = 0.0,
                relistDiscountPct = 50.0,
            ).shouldBeNull()
    }

    @Test
    fun `estimateUpdatesRemaining with no relist history uses the volume-scaled relist floor`() {
        // margin = 10*(920-500) = 4200
        // relist floor = (1-0.5)*0.03*1000*10 = 150 (above the 100 ISK minimum, so it isn't floored)
        val result =
            OrderFeeService.estimateUpdatesRemaining(
                volumeRemaining = 10,
                price = 1000.0,
                netSellPricePerUnit = 920.0,
                costBasis = 500.0,
                brokerFeePct = 3.0,
                relistCount = 0,
                relistFeesPaid = 0.0,
                relistDiscountPct = 50.0,
            )

        result shouldBe 28 // floor(4200 / 150)
    }

    @Test
    fun `estimateUpdatesRemaining with relist history uses the average past fee, net of fees already paid`() {
        // margin = 4200, fees paid so far = 90 -> remaining margin 4110; avg fee per update = 90/2 = 45
        val result =
            OrderFeeService.estimateUpdatesRemaining(
                volumeRemaining = 10,
                price = 1000.0,
                netSellPricePerUnit = 920.0,
                costBasis = 500.0,
                brokerFeePct = 3.0,
                relistCount = 2,
                relistFeesPaid = 90.0,
                relistDiscountPct = 50.0,
            )

        result shouldBe 91 // floor(4110 / 45)
    }

    @Test
    fun `estimateUpdatesRemaining never goes negative once margin is already exhausted`() {
        val result =
            OrderFeeService.estimateUpdatesRemaining(
                volumeRemaining = 10,
                price = 1000.0,
                netSellPricePerUnit = 920.0,
                costBasis = 1000.0, // cost exceeds net sell price -- already underwater
                brokerFeePct = 3.0,
                relistCount = 0,
                relistFeesPaid = 0.0,
                relistDiscountPct = 50.0,
            )

        result shouldBe 0
    }

    @Test
    fun `estimateUpdatesRemaining with zero volume remaining is 0, not a division-by-zero crash`() {
        // volumeRemaining=0 makes the "no history" floor formula's V=price*volumeRemaining term
        // zero too -- the 100 ISK minimum charge keeps the denominator safely non-zero.
        val noHistory =
            OrderFeeService.estimateUpdatesRemaining(
                volumeRemaining = 0,
                price = 1000.0,
                netSellPricePerUnit = 920.0,
                costBasis = 500.0,
                brokerFeePct = 3.0,
                relistCount = 0,
                relistFeesPaid = 0.0,
                relistDiscountPct = 50.0,
            )
        noHistory shouldBe 0

        // With relist history, remainingMargin is negative (0 margin minus fees already sunk),
        // and the average-past-fee denominator stays positive -- still floors to 0, not negative.
        val withHistory =
            OrderFeeService.estimateUpdatesRemaining(
                volumeRemaining = 0,
                price = 1000.0,
                netSellPricePerUnit = 920.0,
                costBasis = 500.0,
                brokerFeePct = 3.0,
                relistCount = 2,
                relistFeesPaid = 200.0,
                relistDiscountPct = 50.0,
            )
        withHistory shouldBe 0
    }
}
