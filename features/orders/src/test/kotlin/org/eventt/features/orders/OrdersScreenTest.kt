package org.eventt.features.orders

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.eventt.core.database.OrderHistoryDao
import org.eventt.features.orders.CostBasisService.FifoResult
import org.eventt.features.orders.CostBasisService.InventoryItem
import org.eventt.features.orders.CostBasisService.RealizedSellTx
import org.eventt.features.orders.CostBasisService.TaxConfig
import org.junit.jupiter.api.Test

private const val TOLERANCE = 0.0001
private const val TYPE_ID = 34

class OrdersScreenTest {
    private val tax = TaxConfig()

    @Test
    fun `computeMarginPct is net of buy broker fee and sell tax plus fee`() {
        // buy cost = 100 * 1.03 = 103.0; sell revenue = 150 * 0.89 = 133.5
        val margin = computeMarginPct(buyPrice = 100.0, sellPrice = 150.0, taxConfig = tax).shouldNotBeNull()

        margin should ((133.5 - 103.0) / 103.0 * 100 plusOrMinus TOLERANCE)
    }

    @Test
    fun `computeMarginPct is null when either price is missing`() {
        computeMarginPct(null, 150.0, tax).shouldBeNull()
        computeMarginPct(100.0, null, tax).shouldBeNull()
    }

    @Test
    fun `computeMarginPct is null for a non-positive buy price`() {
        computeMarginPct(buyPrice = 0.0, sellPrice = 150.0, taxConfig = tax).shouldBeNull()
    }

    @Test
    fun `computeBestMarginPct reads bestBuy as acquisition price and bestSell as resale price`() {
        val comparison = MarketComparison(bestSell = 150.0, bestBuy = 100.0)

        val margin = computeBestMarginPct(comparison, tax).shouldNotBeNull()

        margin should ((133.5 - 103.0) / 103.0 * 100 plusOrMinus TOLERANCE)
    }

    @Test
    fun `computeBestMarginPct is null when there's no comparison or a one-sided market`() {
        computeBestMarginPct(null, tax).shouldBeNull()
        computeBestMarginPct(MarketComparison(bestSell = null, bestBuy = 100.0), tax).shouldBeNull()
        computeBestMarginPct(MarketComparison(bestSell = 150.0, bestBuy = null), tax).shouldBeNull()
    }

    private fun sellOrder(
        price: Double = 150.0,
        volumeTotal: Int = 10,
        volumeRemaining: Int = 0,
        issued: String = "2024-01-05",
        isBuyOrder: Boolean = false,
        state: String = "expired",
    ) = OrderHistoryDao.OrderHistoryRecord(
        orderId = 1L,
        typeId = TYPE_ID,
        typeName = "Tritanium",
        locationId = 60003760L,
        stationName = "Jita IV - Moon 4",
        price = price,
        volumeTotal = volumeTotal,
        volumeRemaining = volumeRemaining,
        isBuyOrder = isBuyOrder,
        duration = 90,
        issued = issued,
        range = "region",
        minVolume = 1,
        state = state,
        characterId = 1,
    )

    @Test
    fun `historyPnl uses the FIFO-matched sell when one covers the order's fill date`() {
        val fifoResult =
            FifoResult(
                inventory = emptyMap(),
                realizedSells = listOf(RealizedSellTx("2024-01-05", TYPE_ID, 10, 150.0, 100.0, 300.0, 1.0)),
                taxConfig = tax,
            )

        val (profit, margin) = historyPnl(sellOrder(), fifoResult)

        // profit and margin both come from the matched sell itself, not order.price:
        // cost = costBasis(100) * qty(10) = 1000; margin = profit(300) / cost(1000) * 100
        profit.shouldNotBeNull() should (300.0 plusOrMinus TOLERANCE)
        margin.shouldNotBeNull() should (30.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `historyPnl falls back to avgCostBasisForType when no FIFO sell matches`() {
        val fifoResult =
            FifoResult(
                inventory = mapOf(TYPE_ID to InventoryItem(TYPE_ID, "Tritanium", 5, 100.0, 500.0)),
                realizedSells = emptyList(),
                taxConfig = tax,
            )

        val (profit, margin) = historyPnl(sellOrder(), fifoResult)

        // netSellPrice = 133.5; cb = 100.0 (from inventory); profit = (133.5-100.0)*10
        profit.shouldNotBeNull() should ((133.5 - 100.0) * 10 plusOrMinus TOLERANCE)
        margin.shouldNotBeNull() should ((133.5 - 100.0) / 100.0 * 100 plusOrMinus TOLERANCE)
    }

    @Test
    fun `historyPnl is null-null when there's no cost basis data at all for the type`() {
        val fifoResult = FifoResult(inventory = emptyMap(), realizedSells = emptyList(), taxConfig = tax)

        val (profit, margin) = historyPnl(sellOrder(), fifoResult)

        profit.shouldBeNull()
        margin.shouldBeNull()
    }

    @Test
    fun `historyPnl is null-null for a buy order regardless of FIFO data`() {
        val fifoResult =
            FifoResult(
                inventory = mapOf(TYPE_ID to InventoryItem(TYPE_ID, "Tritanium", 5, 100.0, 500.0)),
                realizedSells = emptyList(),
                taxConfig = tax,
            )

        val (profit, margin) = historyPnl(sellOrder(isBuyOrder = true), fifoResult)

        profit.shouldBeNull()
        margin.shouldBeNull()
    }

    @Test
    fun `historyPnl is null-null when there's no FIFO result yet`() {
        val (profit, margin) = historyPnl(sellOrder(), fifoResult = null)

        profit.shouldBeNull()
        margin.shouldBeNull()
    }

    @Test
    fun `historyPnl is null-null when nothing was actually filled`() {
        val fifoResult = FifoResult(inventory = emptyMap(), realizedSells = emptyList(), taxConfig = tax)

        val (profit, margin) = historyPnl(sellOrder(volumeTotal = 10, volumeRemaining = 10), fifoResult)

        profit.shouldBeNull()
        margin.shouldBeNull()
    }

    // ESI's order-history `state` never says "fulfilled" — only "expired" or "cancelled", even
    // for an order that sold out completely. effectiveOrderState derives the real outcome from
    // volume_remain vs volume_total instead of trusting that field alone.
    @Test
    fun `effectiveOrderState is fulfilled when nothing remains, regardless of the raw ESI state`() {
        effectiveOrderState(sellOrder(volumeTotal = 10, volumeRemaining = 0, state = "expired")) shouldBe "fulfilled"
        effectiveOrderState(sellOrder(volumeTotal = 10, volumeRemaining = 0, state = "cancelled")) shouldBe "fulfilled"
    }

    @Test
    fun `effectiveOrderState is partially_filled when some but not all volume remains`() {
        effectiveOrderState(sellOrder(volumeTotal = 10, volumeRemaining = 4, state = "expired")) shouldBe "partially_filled"
        effectiveOrderState(sellOrder(volumeTotal = 10, volumeRemaining = 4, state = "cancelled")) shouldBe "partially_filled"
    }

    @Test
    fun `effectiveOrderState falls back to the raw ESI state when nothing was filled at all`() {
        effectiveOrderState(sellOrder(volumeTotal = 10, volumeRemaining = 10, state = "expired")) shouldBe "expired"
        effectiveOrderState(sellOrder(volumeTotal = 10, volumeRemaining = 10, state = "cancelled")) shouldBe "cancelled"
    }

    private fun activeOrder(
        price: Double,
        volumeRemaining: Int = 5,
    ) = CharacterOrder(
        orderId = 1L,
        typeId = TYPE_ID,
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
    )

    private fun priorRecord(
        price: Double,
        relistCount: Int,
        relistFeesPaid: Double,
        priceUpdatedAt: Long,
    ) = org.eventt.core.database.ActiveOrderDao.ActiveOrderRecord(
        orderId = 1L,
        typeId = TYPE_ID,
        typeName = "Tritanium",
        locationId = 60003760L,
        regionId = 10000002,
        stationName = "Jita IV - Moon 4",
        price = price,
        volumeTotal = 10,
        volumeRemaining = 7,
        isBuyOrder = false,
        duration = 90,
        issued = "2024-01-01T00:00:00Z",
        state = "active",
        issuedByCharId = null,
        characterId = 1,
        corporationId = null,
        relistCount = relistCount,
        relistFeesPaid = relistFeesPaid,
        priceUpdatedAt = priceUpdatedAt,
    )

    @Test
    fun `mergeRelistStats keeps the already-applied newer price when the fetch is older than the last relist`() {
        // A relist to 110 was applied as of t=1000; this fetch's snapshot is from t=900 and still
        // carries the pre-relist price 100 — accepting it would revert the row and make the fresher
        // public order book re-detect (and re-charge) the same relist.
        val prior = mapOf(1L to priorRecord(price = 110.0, relistCount = 1, relistFeesPaid = 50.0, priceUpdatedAt = 1_000L))

        val merged = mergeRelistStats(prior, listOf(activeOrder(price = 100.0)), fetchAsOf = 900L).single()

        merged.price shouldBe 110.0
        merged.relistCount shouldBe 1
        merged.relistFeesPaid shouldBe 50.0
        merged.priceUpdatedAt shouldBe 1_000L
        merged.volumeRemaining shouldBe 5 // fresh volume is taken even when the price is kept
    }

    @Test
    fun `mergeRelistStats keeps the newer price when the fetch age is unknown`() {
        val prior = mapOf(1L to priorRecord(price = 110.0, relistCount = 1, relistFeesPaid = 50.0, priceUpdatedAt = 1_000L))

        mergeRelistStats(prior, listOf(activeOrder(price = 100.0)), fetchAsOf = null).single().price shouldBe 110.0
    }

    @Test
    fun `mergeRelistStats accepts the fetched price when the fetch is genuinely newer`() {
        val prior = mapOf(1L to priorRecord(price = 110.0, relistCount = 1, relistFeesPaid = 50.0, priceUpdatedAt = 1_000L))

        val merged = mergeRelistStats(prior, listOf(activeOrder(price = 120.0)), fetchAsOf = 2_000L).single()

        merged.price shouldBe 120.0
        merged.relistCount shouldBe 1 // stats still only carried, never bumped here
    }

    @Test
    fun `mergeRelistStats accepts the fetched price when no relist was ever applied`() {
        val prior = mapOf(1L to priorRecord(price = 100.0, relistCount = 0, relistFeesPaid = 0.0, priceUpdatedAt = 0L))

        mergeRelistStats(prior, listOf(activeOrder(price = 105.0)), fetchAsOf = null).single().price shouldBe 105.0
    }

    @Test
    fun `mergeRelistStats leaves a brand-new order untouched`() {
        val merged = mergeRelistStats(emptyMap(), listOf(activeOrder(price = 100.0)), fetchAsOf = null).single()

        merged.relistCount shouldBe 0
        merged.price shouldBe 100.0
    }
}
