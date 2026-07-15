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
    fun `pnlForOrder pro-rates profit across the sells that cover the filled quantity`() {
        val profit =
            CostBasisService
                .pnlForOrder(sampleResult(), TYPE_ID, issuedDate = "2024-01-01", filledQty = 8)
                .shouldNotBeNull()

        // full 50.0 from the first sell (5 of 5) + 3/10 of the second sell's 100.0
        profit should (80.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `pnlForOrder ignores sells before the order was issued`() {
        val profit =
            CostBasisService
                .pnlForOrder(sampleResult(), TYPE_ID, issuedDate = "2024-01-02", filledQty = 10)
                .shouldNotBeNull()

        profit should (100.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `pnlForOrder returns null when nothing matches after the date filter`() {
        val profit = CostBasisService.pnlForOrder(sampleResult(), TYPE_ID, issuedDate = "2024-01-03", filledQty = 5)

        profit.shouldBeNull()
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

        val profit = CostBasisService.pnlForOrder(result, TYPE_ID, issuedDate = utcIssuedDate, filledQty = 5).shouldNotBeNull()

        profit should (50.0 plusOrMinus TOLERANCE)
    }
}
