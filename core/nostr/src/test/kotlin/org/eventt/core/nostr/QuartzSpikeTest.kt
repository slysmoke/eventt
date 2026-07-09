package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01Crypto
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip17Dm.NIP17Factory
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NSec
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import com.vitorpamplona.quartz.nip40Expiration.expiration
import com.vitorpamplona.quartz.nip40Expiration.isExpired
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Phase 0 spike (see plan: hashed-prancing-rabbit.md) — validates the real Quartz API surface
 * that this module's future NostrIdentityService/NostrEventFactory/ReservationService will be
 * built on, against the actual quartz-jvm dependency rather than assumed signatures. ORDER_KIND
 * below is a placeholder — finalize against the live NIP registry
 * (github.com/nostr-protocol/nips) before any production kind assignment.
 */
class QuartzSpikeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `generates a new random keypair with a valid 32-byte pubkey`() {
        val kp = KeyPair()
        requireNotNull(kp.privKey).size shouldBe 32
        kp.pubKey.size shouldBe 32
    }

    @Test
    fun `round-trips a private key through nsec encoding and back`() {
        val original = KeyPair()
        val nsec = requireNotNull(original.privKey).toNsec()
        nsec.startsWith("nsec1").shouldBeTrue()

        val parsed = Nip19Parser.parseAll(nsec).filterIsInstance<NSec>().single()
        val imported = KeyPair(privKey = parsed.hex.hexToByteArray())

        imported.pubKey.toHexKey() shouldBe original.pubKey.toHexKey()
    }

    @Test
    fun `builds and signs a custom addressable order event matching our schema, and it verifies`() {
        val seller = KeyPair()
        val signer = NostrSignerSync(seller)
        val orderId = "order-${System.nanoTime()}"
        val nowSec = System.currentTimeMillis() / 1000
        val expirationSec = nowSec + 14 * 24 * 3600

        val tags =
            TagArrayBuilder<Event>()
                .add(DTag(orderId).toTagArray())
                .add(arrayOf("t", "eve-otc"))
                .add(arrayOf("t", "side:sell"))
                .add(arrayOf("t", "type:44992"))
                .add(arrayOf("t", "region:10000002"))
                .expiration(expirationSec)
                .add(arrayOf("price", "480.5"))
                .add(arrayOf("qty_total", "10000"))
                .add(arrayOf("qty_remaining", "10000"))
                .add(arrayOf("min_lot", "500"))
                .add(arrayOf("min_lot_unit", "units"))
                .add(arrayOf("trader_char", "Some Character"))
                .build()

        val order: Event = signer.sign(nowSec, ORDER_KIND, tags, "")

        order.kind shouldBe ORDER_KIND
        order.dTag() shouldBe orderId
        order.expiration() shouldBe expirationSec
        order.isExpired().shouldBeFalse()
        Nip01Crypto
            .verify(order.sig.hexToByteArray(), order.id.hexToByteArray(), order.pubKey.hexToByteArray())
            .shouldBeTrue()
    }

    @Test
    fun `NIP-17 DM round-trips the reservation payload through gift-wrap encryption`() =
        runBlocking {
            val buyer = KeyPair()
            val seller = KeyPair()
            val buyerSigner = NostrSignerInternal(buyer)
            val sellerSigner = NostrSignerInternal(seller)

            val payload = ReservationRequest(orderId = "order-abc", tradeId = "trade-xyz", qty = 1000)
            val content = json.encodeToString(payload)

            val template =
                ChatMessageEvent.build(
                    content,
                    listOf(PTag(seller.pubKey.toHexKey(), null)),
                    System.currentTimeMillis() / 1000,
                ) {}

            val result = NIP17Factory().createMessageNIP17(template, buyerSigner)
            result.wraps.shouldNotBeEmpty()
            // Ciphertext must not leak the plaintext payload anywhere in the wrapped event.
            result.wraps.forEach { wrap -> wrap.toJson().contains("trade-xyz").shouldBeFalse() }

            val unwrapped = result.wraps.firstNotNullOf { it.unwrapOrNull(sellerSigner) }
            unwrapped.content shouldBe content
        }

    private companion object {
        const val ORDER_KIND = 30735
    }
}

@Serializable
private data class ReservationRequest(
    val orderId: String,
    val tradeId: String,
    val qty: Int,
)
