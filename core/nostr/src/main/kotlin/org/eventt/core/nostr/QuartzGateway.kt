package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip13Pow.miner.PoWMiner
import com.vitorpamplona.quartz.nip17Dm.NIP17Factory
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NSec
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent

/**
 * The only file in this module that calls Quartz's API directly — everything else in core:nostr
 * goes through this object. Confining the "we verified this against the real library in the
 * Phase 0 spike, not just assumed it" surface to one file means a future Quartz upgrade that
 * changes a signature only ripples through here, not through every caller.
 */
object QuartzGateway {
    fun generateKeyPair(): KeyPair = KeyPair()

    /** Accepts either a bech32 `nsec1...` or a raw 64-char hex private key. Null on anything else. */
    fun importPrivateKey(nsecOrHex: String): KeyPair? =
        runCatching {
            val trimmed = nsecOrHex.trim()
            val privKeyHex =
                if (trimmed.startsWith("nsec1")) {
                    Nip19Parser
                        .parseAll(trimmed)
                        .filterIsInstance<NSec>()
                        .first()
                        .hex
                } else {
                    trimmed
                }
            KeyPair(privKey = privKeyHex.hexToByteArray())
        }.getOrNull()

    /** Null for a read-only keypair (no private key held). */
    fun encodeAsNsec(keyPair: KeyPair): String? = keyPair.privKey?.toNsec()

    fun privKeyHex(keyPair: KeyPair): String? = keyPair.privKey?.toHexKey()

    fun pubKeyHex(keyPair: KeyPair): String = keyPair.pubKey.toHexKey()

    fun signerFor(keyPair: KeyPair): NostrSignerSync = NostrSignerSync(keyPair)

    /**
     * [powDifficulty] > 0 mines a NIP-13 nonce tag (leading zero bits of the resulting event id)
     * before signing — makes the published event costlier to reject-and-retry-spam, and keeps us
     * publishable on relays that enforce a minimum-PoW acceptance policy. 0 (the default) skips
     * mining entirely; only order events opt into it, so DMs/receipts aren't slowed down.
     */
    fun signEvent(
        signer: NostrSignerSync,
        createdAt: Long,
        kind: Int,
        tags: Array<Array<String>>,
        content: String,
        powDifficulty: Int = 0,
    ): Event {
        val minedTags =
            if (powDifficulty > 0) {
                PoWMiner.run(EventTemplate<Event>(createdAt, kind, tags, content), signer.pubKey, powDifficulty).tags
            } else {
                tags
            }
        return signer.sign(createdAt, kind, minedTags, content)
    }

    /** Inverse of [Event.toJson] — null on malformed input. Used to replay the persistent outbox. */
    fun eventFromJson(json: String): Event? = Event.fromJsonOrNull(json)

    /** The suspend-capable signer NIP17Factory needs — wraps the same keypair as [signerFor]. */
    fun asyncSignerFor(keyPair: KeyPair): NostrSigner = NostrSignerInternal(keyPair)

    /**
     * Builds a NIP-17 encrypted DM and gift-wraps it — returns the wrap events ready to publish
     * (one per recipient, per NIP-59; there's no separate "send" step, publishing the wraps *is*
     * sending). [content] is our own JSON reservation payload, opaque to Quartz.
     */
    suspend fun buildEncryptedDm(
        signer: NostrSigner,
        recipientPubkeyHex: String,
        content: String,
        createdAt: Long,
    ): List<Event> {
        val template = ChatMessageEvent.build(content, listOf(PTag(recipientPubkeyHex, null)), createdAt) {}
        return NIP17Factory().createMessageNIP17(template, signer).wraps
    }

    /** True when [event] is a NIP-59 gift wrap (kind 1059) — the shape everything in our DM subscription receives. */
    fun isGiftWrap(event: Event): Boolean = event is GiftWrapEvent

    /** The gift wrap's own (unencrypted) recipient tag — used to route without decrypting anything. */
    fun giftWrapRecipient(event: Event): String? = (event as? GiftWrapEvent)?.recipientPubKey()

    /**
     * Decrypts+unseals a gift wrap addressed to [signer]'s identity. NIP-59 is two layers:
     * unwrapping the gift wrap (kind 1059) only yields the "seal" (kind 13), whose own [content]
     * is still NIP-44 ciphertext — it must be unsealed in turn to reach the plaintext rumor.
     * Null on any failure at either layer (not ours, or malformed).
     */
    suspend fun unwrapGiftWrap(
        event: Event,
        signer: NostrSigner,
    ): Event? {
        val seal = (event as? GiftWrapEvent)?.unwrapOrNull(signer) as? SealedRumorEvent ?: return null
        return seal.unsealOrNull(signer)
    }
}
