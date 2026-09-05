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

    @Test
    fun `a legitimate 0 ISK trade is still booked, not treated as a missing price`() {
        val gift = reservation("t5", role = "buyer", orderSide = "sell").copy(price = 0.0)

        val booked = ReceiptService.bookCostBasisEntry(gift, TransactionAttribution.Character(1))

        booked shouldBe true
        WalletDao.getAllTransactions(characterId = 1).single().unitPrice shouldBe 0.0
    }

    @Test
    fun `a legacy row with no snapshot falls back to the order's price`() {
        val legacy = reservation("t6", role = "buyer", orderSide = "sell").copy(typeId = 0, price = 0.0, orderSide = "")
        val fallback =
            org.eventt.core.database.NostrOrderModel(
                orderUuid = "order-1",
                pubkey = "seller-pk",
                eventId = "event-1",
                createdAt = 0,
                side = "sell",
                typeId = 34,
                regionId = 0,
                price = 250.0,
                qtyTotal = 10,
                qtyRemaining = 10,
                minLot = 1,
                minLotUnit = "unit",
                traderChar = "Seller Char",
                traderCharId = 2,
                expiration = 0,
                rawEventJson = "{}",
                isMine = true,
            )

        val booked = ReceiptService.bookCostBasisEntry(legacy, TransactionAttribution.Character(1), fallbackOrder = fallback)

        booked shouldBe true
        WalletDao.getAllTransactions(characterId = 1).single().unitPrice shouldBe 250.0
    }
}
