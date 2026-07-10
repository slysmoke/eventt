package org.eventt.core.database

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class NostrReceiptDaoTest {
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
        DatabaseManager.transaction { createStatement().use { it.execute("DELETE FROM nostr_receipts") } }
    }

    private fun receipt(
        eventId: String,
        tradeId: String,
        authorPubkey: String,
        counterpartyPubkey: String,
        role: String,
    ) = NostrReceiptDao.insertIfAbsent(
        eventId = eventId,
        tradeId = tradeId,
        orderCoordinate = "30735:seller-pubkey:order-1",
        authorPubkey = authorPubkey,
        counterpartyPubkey = counterpartyPubkey,
        role = role,
        qty = 1000L,
        createdAt = System.currentTimeMillis() / 1000,
        rawEventJson = "{}",
    )

    @Test
    fun `a lone receipt from one side is not mutual`() {
        receipt("event-buyer", "trade-1", "buyer-pk", "seller-pk", "buyer")

        NostrReceiptDao.hasMutualReceipt("trade-1").shouldBeFalse()
        NostrReceiptDao.countConfirmedTrades("buyer-pk") shouldBe 0
    }

    @Test
    fun `receipts from both sides make the trade mutual and count for both pubkeys`() {
        receipt("event-buyer", "trade-1", "buyer-pk", "seller-pk", "buyer")
        receipt("event-seller", "trade-1", "seller-pk", "buyer-pk", "seller")

        NostrReceiptDao.hasMutualReceipt("trade-1").shouldBeTrue()
        NostrReceiptDao.countConfirmedTrades("buyer-pk") shouldBe 1
        NostrReceiptDao.countConfirmedTrades("seller-pk") shouldBe 1
    }

    @Test
    fun `two receipts from the same author never become mutual`() {
        receipt("event-1", "trade-1", "buyer-pk", "seller-pk", "buyer")
        receipt("event-2", "trade-1", "buyer-pk", "seller-pk", "buyer")

        NostrReceiptDao.hasMutualReceipt("trade-1").shouldBeFalse()
    }

    @Test
    fun `insertIfAbsent is idempotent for a re-delivered event id`() {
        receipt("event-dup", "trade-1", "buyer-pk", "seller-pk", "buyer")
        receipt("event-dup", "trade-1", "buyer-pk", "seller-pk", "buyer")

        NostrReceiptDao.listForTrade("trade-1") shouldHaveSize 1
    }

    @Test
    fun `countConfirmedTrades only counts trades with this pubkey, not unrelated ones`() {
        receipt("event-a1", "trade-1", "buyer-pk", "seller-pk", "buyer")
        receipt("event-a2", "trade-1", "seller-pk", "buyer-pk", "seller")
        receipt("event-b1", "trade-2", "other-buyer-pk", "other-seller-pk", "buyer")
        receipt("event-b2", "trade-2", "other-seller-pk", "other-buyer-pk", "seller")

        NostrReceiptDao.countConfirmedTrades("buyer-pk") shouldBe 1
    }
}
