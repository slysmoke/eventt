package org.eventt.features.orders

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.eventt.core.database.OrderHistoryDao
import org.eventt.core.database.WalletDao
import org.eventt.core.model.utcToLocalDateTime
import org.eventt.features.orders.CostBasisService.FifoResult
import org.eventt.features.orders.CostBasisService.InventoryItem
import org.eventt.features.orders.CostBasisService.RealizedSellTx
import org.eventt.features.orders.CostBasisService.TaxConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val TYPE_ID = 34
private const val TOLERANCE = 0.0001

class CostBasisServiceTest {
    @BeforeEach
    fun setUp() {
        mockkObject(WalletDao)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(WalletDao)
    }

    private fun stubTransactions(vararg tx: WalletDao.RawTxRecord) {
        every { WalletDao.getAllTransactions(any()) } returns tx.toList()
    }

    private fun buy(
        date: String,
        qty: Int,
        price: Double,
        typeId: Int = TYPE_ID,
    ) = WalletDao.RawTxRecord(date, typeId, "Tritanium", qty, price, isBuy = true)

    private fun sell(
        date: String,
        qty: Int,
        price: Double,
        typeId: Int = TYPE_ID,
    ) = WalletDao.RawTxRecord(date, typeId, "Tritanium", qty, price, isBuy = false)

    private fun p2pBuy(
        date: String,
        qty: Int,
        price: Double,
        typeId: Int = TYPE_ID,
    ) = WalletDao.RawTxRecord(date, typeId, "Tritanium", qty, price, isBuy = true, isP2p = true)

    private fun p2pSell(
        date: String,
        qty: Int,
        price: Double,
        typeId: Int = TYPE_ID,
    ) = WalletDao.RawTxRecord(date, typeId, "Tritanium", qty, price, isBuy = false, isP2p = true)

    @Test
    fun `holding cycle restarts when a position is rebought after being fully sold`() {
        stubTransactions(
            buy("2026-07-17", 5, 241.0),
            sell("2026-07-18", 5, 276.0),
            buy("2026-09-08", 3, 186.0),
            sell("2026-09-10", 1, 200.0),
            buy("2026-09-14", 2, 177.0),
        )

        val item = CostBasisService.compute(characterId = 1).inventory.getValue(TYPE_ID)

        item.remainingQty shouldBe 4
        // The September rebuy starts a new cycle; a partial sell inside it doesn't.
        item.cycleStartDate shouldBe "2026-09-08"
    }

    @Test
    fun `heldDays is the quantity-weighted age of the lots a sale consumed`() {
        stubTransactions(
            buy("2026-01-01T10:00", 10, 100.0),
            buy("2026-01-05T10:00", 10, 100.0),
            sell("2026-01-11T09:00", 15, 300.0),
        )

        // 10 units held 10 days, 5 units held 6 days.
        CostBasisService
            .compute(characterId = 1)
            .realizedSells
            .single()
            .heldDays should (130.0 / 15 plusOrMinus TOLERANCE)
    }

    @Test
    fun `stockCostByDay tracks open lot cost at the end of each day`() {
        stubTransactions(
            buy("2026-01-01T10:00", 10, 100.0),
            buy("2026-01-01T12:00", 10, 200.0),
            sell("2026-01-03T09:00", 15, 300.0),
        )

        val byDay = CostBasisService.compute(characterId = 1).stockCostByDay

        byDay.keys.toList() shouldBe listOf("2026-01-01", "2026-01-03")
        byDay.getValue("2026-01-01") should (3000.0 * 1.03 plusOrMinus TOLERANCE)
        // 15 sold: all 10 of the first lot and 5 of the second -- 5 × 200 left.
        byDay.getValue("2026-01-03") should (1000.0 * 1.03 plusOrMinus TOLERANCE)
    }

    @Test
    fun `a full sell of a single buy lot computes cost basis net of tax and broker fee`() {
        stubTransactions(
            buy("2024-01-01", 10, 100.0),
            sell("2024-01-02", 10, 150.0),
        )

        val result = CostBasisService.compute(characterId = 1)

        result.inventory shouldBe emptyMap()
        val tx = result.realizedSells.single()
        tx.qty shouldBe 10
        tx.costBasis should (100.0 * 1.03 plusOrMinus TOLERANCE) // buy price + 3% broker fee
        tx.profit should (10 * (150.0 * 0.89 - 103.0) plusOrMinus TOLERANCE) // net of 8% tax + 3% fee
    }

    @Test
    fun `a P2P buy and sell apply no tax or broker fee, unlike a market transaction`() {
        stubTransactions(
            p2pBuy("2024-01-01", 10, 100.0),
            p2pSell("2024-01-02", 10, 150.0),
        )

        val result = CostBasisService.compute(characterId = 1)

        val tx = result.realizedSells.single()
        tx.costBasis should (100.0 plusOrMinus TOLERANCE) // no 3% broker fee added
        tx.profit should (10 * (150.0 - 100.0) plusOrMinus TOLERANCE) // no 8% tax / 3% fee subtracted
    }

    @Test
    fun `tax and fee are decided per side of the trade, not by how the lot was originally acquired`() {
        // Bought P2P (no fee) then sold on the market (real tax+fee applies to that sell).
        stubTransactions(
            p2pBuy("2024-01-01", 10, 100.0),
            sell("2024-01-02", 10, 150.0),
        )
        val p2pBuyThenMarketSell = CostBasisService.compute(characterId = 1).realizedSells.single()
        p2pBuyThenMarketSell.costBasis should (100.0 plusOrMinus TOLERANCE) // no broker fee on the P2P buy
        p2pBuyThenMarketSell.profit should (10 * (150.0 * 0.89 - 100.0) plusOrMinus TOLERANCE) // tax+fee on the market sell

        // Bought on the market (real broker fee applies) then sold P2P (no fee on that sell).
        stubTransactions(
            buy("2024-02-01", 10, 100.0),
            p2pSell("2024-02-02", 10, 150.0),
        )
        val marketBuyThenP2pSell = CostBasisService.compute(characterId = 1).realizedSells.single()
        marketBuyThenP2pSell.costBasis should (103.0 plusOrMinus TOLERANCE) // 3% broker fee on the market buy
        marketBuyThenP2pSell.profit should (10 * (150.0 - 103.0) plusOrMinus TOLERANCE) // no tax/fee on the P2P sell
    }

    @Test
    fun `a sell spanning two buy lots consumes them oldest first`() {
        stubTransactions(
            buy("2024-01-01", 5, 100.0),
            buy("2024-01-02", 5, 200.0),
            sell("2024-01-03", 8, 300.0),
        )

        val result = CostBasisService.compute(characterId = 1)

        val tx = result.realizedSells.single()
        tx.qty shouldBe 8
        // (5 units @ 103.0) + (3 units @ 206.0), averaged over 8 units
        tx.costBasis should ((5 * 103.0 + 3 * 206.0) / 8 plusOrMinus TOLERANCE)

        val remaining = result.inventory.getValue(TYPE_ID)
        remaining.remainingQty shouldBe 2
        remaining.avgCostBasis should (206.0 plusOrMinus TOLERANCE)
        // The first lot was fully consumed, so the oldest remaining lot is the second buy.
        remaining.oldestLotDate shouldBe "2024-01-02"
    }

    @Test
    fun `selling more than is held only realizes the quantity actually covered by lots`() {
        stubTransactions(
            buy("2024-01-01", 3, 100.0),
            sell("2024-01-02", 5, 150.0),
        )

        val result = CostBasisService.compute(characterId = 1)

        result.realizedSells.single().qty shouldBe 3
        result.inventory shouldBe emptyMap()
    }

    @Test
    fun `selling with no prior buy lots realizes nothing`() {
        stubTransactions(sell("2024-01-01", 5, 150.0))

        val result = CostBasisService.compute(characterId = 1)

        result.realizedSells.shouldBeEmpty()
        result.inventory shouldBe emptyMap()
    }

    @Test
    fun `totalRealizedPnl sums profit across all realized sells`() {
        stubTransactions(
            buy("2024-01-01", 10, 100.0),
            sell("2024-01-02", 4, 150.0),
            sell("2024-01-03", 6, 150.0),
        )

        val result = CostBasisService.compute(characterId = 1)

        result.realizedSells.size shouldBe 2
        // 4 units + 6 units, both sold from the same 103.0 cost-basis lot at net 133.5 each
        result.totalRealizedPnl should (305.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `avgCostBasisForType prefers current inventory over historical sells`() {
        val result =
            FifoResult(
                inventory = mapOf(TYPE_ID to InventoryItem(TYPE_ID, "Tritanium", 5, 42.0, 210.0)),
                realizedSells =
                    listOf(
                        RealizedSellTx("2024-01-01", TYPE_ID, 10, 150.0, 999.0, 1.0, 1.0),
                    ),
                taxConfig = TaxConfig(),
            )

        result.avgCostBasisForType(TYPE_ID) shouldBe 42.0
    }

    @Test
    fun `avgCostBasisForType falls back to a qty-weighted average of historical sells`() {
        val result =
            FifoResult(
                inventory = emptyMap(),
                realizedSells =
                    listOf(
                        RealizedSellTx("2024-01-01", TYPE_ID, 5, 150.0, 100.0, 250.0, 1.0),
                        RealizedSellTx("2024-01-02", TYPE_ID, 15, 150.0, 200.0, 250.0, 1.0),
                    ),
                taxConfig = TaxConfig(),
            )

        // (5 * 100.0 + 15 * 200.0) / 20 = 175.0
        result.avgCostBasisForType(TYPE_ID).shouldNotBeNull() should (175.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `avgCostBasisForType is null for a type with no inventory or sell history`() {
        val result = FifoResult(inventory = emptyMap(), realizedSells = emptyList(), taxConfig = TaxConfig())

        result.avgCostBasisForType(TYPE_ID).shouldBeNull()
    }

    private fun sampleResult() =
        FifoResult(
            inventory = emptyMap(),
            realizedSells =
                listOf(
                    RealizedSellTx("2024-01-01", TYPE_ID, 5, 150.0, 100.0, 50.0, 1.0),
                    RealizedSellTx("2024-01-02", TYPE_ID, 10, 150.0, 100.0, 100.0, 1.0),
                ),
            taxConfig = TaxConfig(),
        )

    @Test
    fun `pnlForOrder pro-rates profit and cost-weighted margin across the sells that cover the filled quantity`() {
        val (profit, margin) =
            CostBasisService
                .pnlForOrder(sampleResult(), TYPE_ID, issuedDate = "2024-01-01", filledQty = 8)
                .shouldNotBeNull()

        // full 50.0 from the first sell (5 of 5) + 3/10 of the second sell's 100.0
        profit should (80.0 plusOrMinus TOLERANCE)
        // cost matched: 5*100.0 + 3*100.0 = 800.0 -> 80.0 / 800.0 * 100
        margin should (10.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `pnlForOrder ignores sells before the order was issued`() {
        val (profit, _) =
            CostBasisService
                .pnlForOrder(sampleResult(), TYPE_ID, issuedDate = "2024-01-02", filledQty = 10)
                .shouldNotBeNull()

        profit should (100.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `pnlForOrder walks back past issued for a repriced order's earlier fills`() {
        // ESI moved `issued` to the last modify (01-03); the fills happened before it.
        val (profit, _) =
            CostBasisService
                .pnlForOrder(sampleResult(), TYPE_ID, issuedDate = "2024-01-03", filledQty = 12)
                .shouldNotBeNull()

        // newest first: all 100.0 of 01-02, then 2/5 of 01-01's 50.0
        profit should (120.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `pnlForOrder returns null for an unknown type`() {
        val profit = CostBasisService.pnlForOrder(sampleResult(), typeId = 999, issuedDate = "2024-01-01", filledQty = 5)

        profit.shouldBeNull()
    }

    @Test
    fun `pnlForOrder returns null for a non-positive filled quantity`() {
        val profit = CostBasisService.pnlForOrder(sampleResult(), TYPE_ID, issuedDate = "2024-01-01", filledQty = 0)

        profit.shouldBeNull()
    }

    // ActiveOrderDao/OrderHistoryDao store `issued` as the raw UTC timestamp ESI sent (its offset
    // is needed elsewhere for exact expiry math), while WalletDao converts sell dates to local time
    // at ingestion -- so pnlForOrder must convert issuedDate the same way before comparing, or the
    // two sides drift apart by the local UTC offset.
    @Test
    fun `pnlForOrder converts a raw UTC issuedDate to local time before comparing against WalletDao's local sell dates`() {
        val utcIssuedDate = "2024-01-01T00:00:00Z"
        val localIssuedDate = utcIssuedDate.utcToLocalDateTime()
        val result =
            FifoResult(
                inventory = emptyMap(),
                realizedSells = listOf(RealizedSellTx(localIssuedDate, TYPE_ID, 5, 150.0, 100.0, 50.0, 1.0)),
                taxConfig = TaxConfig(),
            )

        val (profit, _) = CostBasisService.pnlForOrder(result, TYPE_ID, issuedDate = utcIssuedDate, filledQty = 5).shouldNotBeNull()

        profit should (50.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `merge sums per-ledger results without pooling their lots`() {
        fun part(
            qty: Int,
            cost: Double,
            stock: Map<String, Double>,
        ) = FifoResult(
            inventory = mapOf(TYPE_ID to InventoryItem(TYPE_ID, "Tritanium", qty, cost / qty, cost, oldestLotDate = "2026-01-0$qty")),
            realizedSells = emptyList(),
            taxConfig = TaxConfig(),
            stockCostByDay = stock,
        )

        val merged =
            CostBasisService.merge(
                listOf(
                    part(1, 100.0, mapOf("2026-01-01" to 100.0)),
                    part(3, 900.0, mapOf("2026-01-02" to 900.0)),
                ),
                TaxConfig(),
            )

        val item = merged.inventory.getValue(TYPE_ID)
        item.remainingQty shouldBe 4
        item.avgCostBasis should (250.0 plusOrMinus TOLERANCE)
        item.oldestLotDate shouldBe "2026-01-01"
        // Day 2: the first ledger carries its day-1 value forward.
        merged.stockCostByDay shouldBe mapOf("2026-01-01" to 100.0, "2026-01-02" to 1000.0)
    }

    @Test
    fun `instant trades matched to a duration-0 order pay no broker fee`() {
        stubTransactions(
            buy("2026-01-01T10:00:00", 10, 100.0),
            sell("2026-01-02T10:00:00", 10, 200.0),
        )

        fun instantOrder(
            localDate: String,
            isBuy: Boolean,
        ) = OrderHistoryDao.OrderHistoryRecord(
            orderId = localDate.hashCode().toLong(),
            typeId = TYPE_ID,
            typeName = "Tritanium",
            locationId = 0,
            stationName = "",
            price = 0.0,
            volumeTotal = 10,
            volumeRemaining = 0,
            isBuyOrder = isBuy,
            duration = 0,
            // ESI's UTC `issued`, a second off the local-time fill.
            issued =
                java.time.LocalDateTime
                    .parse(localDate)
                    .plusSeconds(1)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toString(),
            range = "station",
            minVolume = 1,
            state = "expired",
            characterId = 1,
        )
        mockkObject(OrderHistoryDao)
        try {
            every { OrderHistoryDao.getAll(any(), any(), any(), any()) } returns
                listOf(instantOrder("2026-01-01T10:00:00", isBuy = true), instantOrder("2026-01-02T10:00:00", isBuy = false))

            val sell = CostBasisService.compute(characterId = 1).realizedSells.single()

            sell.costBasis should (100.0 plusOrMinus TOLERANCE)
            sell.profit should (10 * (200.0 * 0.92 - 100.0) plusOrMinus TOLERANCE)
        } finally {
            unmockkObject(OrderHistoryDao)
        }
    }
}
