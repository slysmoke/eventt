package org.eventt.features.orders

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private const val TOLERANCE = 0.0001

class OrdersTablesTest {
    private fun sellOrder(
        price: Double,
        volumeRemaining: Int = 10,
        relistFeesPaid: Double = 0.0,
    ) = CharacterOrder(
        orderId = 1L,
        typeId = 34,
        typeName = "Tritanium",
        locationId = 60003760L,
        regionId = 10000002,
        stationName = "Jita IV - Moon 4",
        price = price,
        volumeTotal = 10,
        volumeRemaining = volumeRemaining,
        isBuyOrder = false,
        duration = 90,
        issued = "2024-01-01T00:00:00Z",
        state = "active",
        relistFeesPaid = relistFeesPaid,
    )

    // Matching the competing price means relisting: EVE charges a broker's fee for that just like
    // any other price change, so "Best Margin" has to net it out or it overstates what relisting
    // to the competitive price actually nets (see OrdersTables.kt computeSellMetrics).
    @Test
    fun `bestMarginPct nets out the relist fee for moving to the competing price`() {
        val order = sellOrder(price = 6.00, volumeRemaining = 10_000)
        val taxConfig = CostBasisService.TaxConfig(salesTaxPct = 0.0, brokerFeePct = 1.88)
        val comparison = MarketComparison(bestSell = 7.00, bestBuy = null)

        val metrics =
            computeSellMetrics(
                order = order,
                inventory = emptyMap(),
                historyCostBasis = mapOf(34 to 5.00),
                taxConfig = taxConfig,
                comparisons = mapOf((34 to 60003760L) to comparison),
                competition = emptyMap(),
                relistDiscountPct = OrderFeeService.relistDiscountPct(4),
            )

        // Relist fee for 6.00 -> 7.00 on this order (EVE University worked example): 530.16 ISK.
        // Revenue at 7.00 net of tax/broker fee (sellMultiplier = 1 - 1.88% = 0.9812): 10000*7*0.9812 = 68684
        // Profit = 68684 - 10000*5.00 - 530.16 = 18153.84; cost basis total = 50000 -> margin 36.31%
        metrics.bestMarginPct.shouldNotBeNull() should (36.3077 plusOrMinus TOLERANCE)
    }

    // Same scenario, but the order already sunk 300 ISK into past relists -- "Best Margin" is
    // supposed to be the fully net figure, so that sunk cost comes off too, not just the fee for
    // the next move.
    @Test
    fun `bestMarginPct also nets out relist fees already paid on the order`() {
        val order = sellOrder(price = 6.00, volumeRemaining = 10_000, relistFeesPaid = 300.0)
        val taxConfig = CostBasisService.TaxConfig(salesTaxPct = 0.0, brokerFeePct = 1.88)
        val comparison = MarketComparison(bestSell = 7.00, bestBuy = null)

        val metrics =
            computeSellMetrics(
                order = order,
                inventory = emptyMap(),
                historyCostBasis = mapOf(34 to 5.00),
                taxConfig = taxConfig,
                comparisons = mapOf((34 to 60003760L) to comparison),
                competition = emptyMap(),
                relistDiscountPct = OrderFeeService.relistDiscountPct(4),
            )

        // Same as above but profit is additionally reduced by the 300 already paid:
        // 18153.84 - 300 = 17853.84; margin = 17853.84 / 50000 * 100 = 35.71%
        metrics.bestMarginPct.shouldNotBeNull() should (35.7077 plusOrMinus TOLERANCE)
    }

    // Buying out same-station competitors cheaper than my own price and reselling against this
    // order -- two profitable layers combine: their volumes sum and their prices blend into one
    // average, both cheaper than my price so the blend stays cheaper too.
    @Test
    fun `computeBuyoutPlan combines multiple profitable layers into one blended average`() {
        val plan =
            computeBuyoutPlan(
                competingSellOrders = listOf(4.00 to 100L, 5.00 to 50L),
                maxBuyPrice = 6.00,
                resalePrice = 6.00,
                maxVolume = 1_000L,
            )

        // volume = 150; cost = 400 + 250 = 650; avg = 650/150 = 4.3333; profit = 6*150 - 650 = 250
        plan.shouldNotBeNull()
        plan.volume shouldBe 150L
        plan.avgCost should (4.3333 plusOrMinus TOLERANCE)
        plan.totalProfit should (250.0 plusOrMinus TOLERANCE)
    }

    // A layer priced at or above maxBuyPrice can't help -- and since the book is walked
    // cheapest-first, nothing after it could either, so the walk stops there.
    @Test
    fun `computeBuyoutPlan stops at the first layer priced at or above maxBuyPrice`() {
        val plan =
            computeBuyoutPlan(
                competingSellOrders = listOf(4.00 to 100L, 6.00 to 500L, 4.50 to 50L),
                maxBuyPrice = 6.00,
                resalePrice = 6.00,
                maxVolume = 1_000L,
            )

        // Only the two layers priced below 6.00 count, regardless of list order (sorted first).
        plan.shouldNotBeNull()
        plan.volume shouldBe 150L
    }

    // maxVolume is this order's own remaining volume -- no point buying more than it can absorb,
    // even if the competing book has more profitable stock available.
    @Test
    fun `computeBuyoutPlan caps volume and splits the final layer to fit`() {
        val plan =
            computeBuyoutPlan(
                competingSellOrders = listOf(4.00 to 100L, 5.00 to 100L),
                maxBuyPrice = 6.00,
                resalePrice = 6.00,
                maxVolume = 120L,
            )

        // Takes all 100 of the first layer, then only 20 of the second.
        plan.shouldNotBeNull()
        plan.volume shouldBe 120L
        plan.avgCost should ((400.0 + 100.0) / 120L plusOrMinus TOLERANCE)
    }

    // The Jita gate in practice: a competitor priced below my own order (5.00 < 6.00, technically
    // "beaten") but above Jita's current price (4.20) doesn't qualify -- maxBuyPrice is
    // min(myPrice, jitaPrice), so only the layer that's also cheaper than Jita gets bought, and
    // profit is still measured against my own resale price, not Jita's.
    @Test
    fun `maxBuyPrice below resalePrice excludes layers cheaper than me but not cheaper than Jita`() {
        val plan =
            computeBuyoutPlan(
                competingSellOrders = listOf(3.50 to 100L, 5.00 to 200L),
                maxBuyPrice = minOf(6.00, 4.20),
                resalePrice = 6.00,
                maxVolume = 1_000L,
            )

        // Only the 3.50 layer qualifies (< 4.20); the 5.00 layer is excluded even though it's
        // still < my own 6.00 price. Profit is still measured against 6.00, not 4.20.
        plan.shouldNotBeNull()
        plan.volume shouldBe 100L
        plan.totalProfit should (250.0 plusOrMinus TOLERANCE) // 6.00*100 - 350 = 250
    }

    @Test
    fun `computeBuyoutPlan is null when nothing is cheaper than maxBuyPrice or maxVolume is zero`() {
        computeBuyoutPlan(
            competingSellOrders = listOf(7.00 to 100L),
            maxBuyPrice = 6.00,
            resalePrice = 6.00,
            maxVolume = 1_000L,
        ).shouldBeNull()
        computeBuyoutPlan(
            competingSellOrders = listOf(4.00 to 100L),
            maxBuyPrice = 6.00,
            resalePrice = 6.00,
            maxVolume = 0L,
        ).shouldBeNull()
        computeBuyoutPlan(
            competingSellOrders = emptyList(),
            maxBuyPrice = 6.00,
            resalePrice = 6.00,
            maxVolume = 1_000L,
        ).shouldBeNull()
    }
}
