package org.eventt.features.orders

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
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
}
