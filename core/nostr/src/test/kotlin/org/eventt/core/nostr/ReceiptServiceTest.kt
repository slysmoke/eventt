package org.eventt.core.nostr

import io.kotest.matchers.shouldBe
import org.eventt.core.database.DatabaseManager
import org.eventt.core.database.NostrReservationModel
import org.eventt.core.database.TransactionAttribution
import org.eventt.core.database.WalletDao
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class ReceiptServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initInMemoryDb() {
            DatabaseManager.close()
            DatabaseManager.initialize(":memory:")
        }
    }

    @AfterEach
    fun cleanUp() {
        DatabaseManager.transaction { createStatement().use { it.execute("DELETE FROM transactions") } }
    }

    // `role` is a negotiation role (who sent the request vs who owns the order), not the trade's
    // economic direction — those only coincide when the order is a sell order.
    private fun reservation(
        tradeId: String,
        role: String,
        orderSide: String,
    ) = NostrReservationModel(
        tradeId = tradeId,
        orderUuid = "order-1",
        orderPubkey = "seller-pk",
        buyerPubkey = "buyer-pk",
        sellerPubkey = "seller-pk",
        role = role,
        qty = 10,
        price = 100.0,
        typeId = 34,
        orderSide = orderSide,
        note = "",
        buyerChar = "Buyer Char",
        buyerCharacterId = 1,
        status = "completed",
        reservedQty = 10,
        holdUntil = null,
        contactChar = "Seller Char",
        contactCharacterId = 2,
        requestedAt = 0,
        respondedAt = 0,
    )

    private fun isBuyRecorded() = WalletDao.getAllTransactions(characterId = 1).single().isBuy

    @Test
    fun `requester on a sell order is recorded as a buy`() {
        ReceiptService.bookCostBasisEntry(reservation("t1", role = "buyer", orderSide = "sell"), TransactionAttribution.Character(1))

        isBuyRecorded() shouldBe true
    }

    @Test
    fun `order owner of a sell order is recorded as a sell`() {
        ReceiptService.bookCostBasisEntry(reservation("t2", role = "seller", orderSide = "sell"), TransactionAttribution.Character(1))

        isBuyRecorded() shouldBe false
    }

    @Test
    fun `requester supplying a buy order is recorded as a sell, not a buy`() {
        // This is the bug: a requester fulfilling someone else's BUY order is economically
        // selling the item, even though their negotiation `role` is "buyer".
        ReceiptService.bookCostBasisEntry(reservation("t3", role = "buyer", orderSide = "buy"), TransactionAttribution.Character(1))

        isBuyRecorded() shouldBe false
    }

    @Test
    fun `owner of a buy order who receives the item is recorded as a buy, not a sell`() {
        // The reported bug: owning a PLEX buy order and having it filled showed up as a Sell.
        ReceiptService.bookCostBasisEntry(reservation("t4", role = "seller", orderSide = "buy"), TransactionAttribution.Character(1))

        isBuyRecorded() shouldBe true
    }
}
