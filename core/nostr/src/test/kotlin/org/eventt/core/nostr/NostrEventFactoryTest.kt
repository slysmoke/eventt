package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NostrEventFactoryTest {
    private val signer = QuartzGateway.signerFor(KeyPair())

    private fun draft(
        side: OrderSide = OrderSide.SELL,
        minLotUnit: MinLotUnit = MinLotUnit.UNITS,
    ) = OrderDraft(
        side = side,
        typeId = 44992,
        regionId = 10000002,
        price = 480.5,
        qtyTotal = 10_000L,
        minLot = 500L,
        minLotUnit = minLotUnit,
        traderChar = "Some Character",
    )

    @Test
    fun `buildOrderEvent round-trips through parseOrderEvent with all fields intact`() {
        val orderUuid = "order-abc"
        val createdAt = 1_700_000_000L
        val event = NostrEventFactory.buildOrderEvent(signer, draft(), orderUuid = orderUuid, createdAt = createdAt)

        val parsed = NostrEventFactory.parseOrderEvent(event)

        requireNotNull(parsed)
        parsed.orderUuid shouldBe orderUuid
        parsed.createdAt shouldBe createdAt
        parsed.side shouldBe OrderSide.SELL
        parsed.typeId shouldBe 44992
        parsed.regionId shouldBe 10000002
        parsed.price shouldBe 480.5
        parsed.qtyTotal shouldBe 10_000L
        parsed.qtyRemaining shouldBe 10_000L
        parsed.minLot shouldBe 500L
        parsed.minLotUnit shouldBe MinLotUnit.UNITS
        parsed.traderChar shouldBe "Some Character"
        parsed.expiration shouldBe createdAt + 14L * 24 * 3600
    }

    @Test
    fun `buildDeletionEvent produces a kind 5 addressing every revision of the order`() {
        val original = NostrEventFactory.buildOrderEvent(signer, draft(), orderUuid = "order-abc", createdAt = 1_700_000_000L)
        val parsed = requireNotNull(NostrEventFactory.parseOrderEvent(original))

        val deletion = NostrEventFactory.buildDeletionEvent(signer, parsed)

        deletion.kind shouldBe 5
        deletion.tags.first { it[0] == "a" }[1] shouldBe "$ORDER_KIND:${parsed.pubkey}:order-abc"
        deletion.tags.first { it[0] == "k" }[1] shouldBe ORDER_KIND.toString()
    }

    @Test
    fun `republishOrder keeps the same order_uuid and expiration unless renew is requested`() {
        val original = NostrEventFactory.buildOrderEvent(signer, draft(), orderUuid = "order-abc", createdAt = 1_700_000_000L)
        val previous = requireNotNull(NostrEventFactory.parseOrderEvent(original))

        val republished = NostrEventFactory.republishOrder(signer, previous, newQtyRemaining = 4_000L)
        val parsed = requireNotNull(NostrEventFactory.parseOrderEvent(republished))

        parsed.orderUuid shouldBe previous.orderUuid
        parsed.qtyRemaining shouldBe 4_000L
        parsed.expiration shouldBe previous.expiration
    }

    @Test
    fun `republishOrder can change price and qty_total together, independent of qty_remaining`() {
        val original = NostrEventFactory.buildOrderEvent(signer, draft(), orderUuid = "order-abc", createdAt = 1_700_000_000L)
        val previous = requireNotNull(NostrEventFactory.parseOrderEvent(original))

        val edited =
            NostrEventFactory.republishOrder(signer, previous, newPrice = 512.0, newQtyTotal = 6_000L, newQtyRemaining = 4_500L)
        val parsed = requireNotNull(NostrEventFactory.parseOrderEvent(edited))

        parsed.orderUuid shouldBe previous.orderUuid
        parsed.price shouldBe 512.0
        parsed.qtyTotal shouldBe 6_000L
        parsed.qtyRemaining shouldBe 4_500L
        parsed.expiration shouldBe previous.expiration
    }

    @Test
    fun `republishOrder with renew pushes expiration two weeks past the new created_at`() {
        val original = NostrEventFactory.buildOrderEvent(signer, draft(), orderUuid = "order-abc", createdAt = 1_700_000_000L)
        val previous = requireNotNull(NostrEventFactory.parseOrderEvent(original))

        val renewed = NostrEventFactory.republishOrder(signer, previous, renew = true)
        val parsed = requireNotNull(NostrEventFactory.parseOrderEvent(renewed))

        (parsed.expiration > previous.expiration) shouldBe true
        parsed.expiration shouldBe parsed.createdAt + 14L * 24 * 3600
    }

    @Test
    fun `p2pTransactionId is always negative and deterministic per tradeId and role`() {
        val buyerId = p2pTransactionId("trade-abc", "buyer")
        val sellerId = p2pTransactionId("trade-abc", "seller")

        (buyerId < 0) shouldBe true
        (sellerId < 0) shouldBe true
        buyerId shouldBe p2pTransactionId("trade-abc", "buyer")
        (buyerId == sellerId) shouldBe false
        (buyerId == p2pTransactionId("trade-xyz", "buyer")) shouldBe false
    }

    @Test
    fun `parseOrderEvent returns null for an event of a different kind`() {
        val wrongKindEvent = QuartzGateway.signEvent(signer, 1_700_000_000L, 1, emptyArray(), "not an order")

        NostrEventFactory.parseOrderEvent(wrongKindEvent).shouldBeNull()
    }

    @Test
    fun `parseOrderEvent returns null when a required tag is missing rather than throwing`() {
        val tagsMissingPrice =
            QuartzGateway.signEvent(
                signer,
                1_700_000_000L,
                ORDER_KIND,
                arrayOf(
                    arrayOf("d", "order-abc"),
                    arrayOf("t", "eventt-p2pmarket"),
                    arrayOf("t", "side:sell"),
                    arrayOf("t", "type:44992"),
                    arrayOf("t", "region:10000002"),
                ),
                "",
            )

        NostrEventFactory.parseOrderEvent(tagsMissingPrice).shouldBeNull()
    }
}
