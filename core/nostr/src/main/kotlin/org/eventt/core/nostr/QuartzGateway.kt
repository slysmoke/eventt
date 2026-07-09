package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NSec
import com.vitorpamplona.quartz.nip19Bech32.toNsec

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
                    Nip19Parser.parseAll(trimmed).filterIsInstance<NSec>().first().hex
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

    fun signEvent(
        signer: NostrSignerSync,
        createdAt: Long,
        kind: Int,
        tags: Array<Array<String>>,
        content: String,
    ): Event = signer.sign(createdAt, kind, tags, content)
}
