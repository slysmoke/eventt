package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip40Expiration.expiration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.eventt.core.database.StaticDataDao

// NIP-38 user status. The d tag scopes our presence away from "general" statuses other Nostr
// clients publish under the same kind — an Amethyst status with no expiration must not make a
// trader look permanently online here, and our heartbeats shouldn't clobber their status either.
const val PRESENCE_KIND = 30315

/** Per-trading-identity presence — drives the online badge on orders and requests. */
const val PRESENCE_D_TAG = "eventt-p2p"

/**
 * Per-installation presence, published by a dedicated anonymous keypair that signs nothing else —
 * counting these counts *people running the app*, not characters, without linking anyone's
 * trading identities together.
 */
const val PRESENCE_APP_D_TAG = "eventt-app"

const val PRESENCE_HEARTBEAT_SECONDS = 300L

private const val APP_INSTANCE_KEY_SETTING = "nostr_app_instance_privkey"

data class Presence(
    val lastSeen: Long,
    val expiration: Long,
) {
    fun isOnline(nowSec: Long = System.currentTimeMillis() / 1000): Boolean = expiration > nowSec
}

/**
 * Online/offline presence over NIP-38, in two independent planes: every local trading identity
 * heartbeats under [PRESENCE_D_TAG] while the app runs (so counterparties see who's really at the
 * keyboard), and one anonymous per-install key heartbeats under [PRESENCE_APP_D_TAG] (so the
 * header can show how many people are running the app). A peer is "online" while their latest
 * status hasn't expired; after that its created_at doubles as "last seen". Addressable kind =
 * relays keep exactly one status per pubkey; going offline is just the heartbeat stopping.
 */
object PresenceService {
    private val _presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    private val _appPresence = MutableStateFlow<Map<String, Presence>>(emptyMap())

    /** Latest known trader presence per pubkey (session-lifetime; repopulated from relays on start). */
    val presence: StateFlow<Map<String, Presence>> = _presence.asStateFlow()

    /** Latest known app-instance presence per anonymous install pubkey — count the unexpired ones. */
    val appPresence: StateFlow<Map<String, Presence>> = _appPresence.asStateFlow()

    fun buildHeartbeat(
        signer: NostrSignerSync,
        createdAt: Long = System.currentTimeMillis() / 1000,
        dTag: String = PRESENCE_D_TAG,
    ): Event =
        QuartzGateway.signEvent(
            signer,
            createdAt,
            PRESENCE_KIND,
            TagArrayBuilder<Event>()
                .add(DTag(dTag).toTagArray())
                // Twice the cadence: one lost heartbeat doesn't flicker us offline, two do.
                .expiration(createdAt + 2 * PRESENCE_HEARTBEAT_SECONDS)
                .build(),
            "online",
        )

    /**
     * The install-counter keypair: generated once, stored *unencrypted* in app settings — unlike
     * trading keys it protects nothing (it only ever signs empty heartbeats), so it doesn't go
     * through [org.eventt.core.database.NostrKeyCrypto] or the identity table.
     */
    suspend fun appInstanceKeyPair(): KeyPair =
        withContext(Dispatchers.IO) {
            StaticDataDao.getSetting(APP_INSTANCE_KEY_SETTING)?.let { QuartzGateway.importPrivateKey(it) }
                ?: QuartzGateway.generateKeyPair().also {
                    StaticDataDao.setSetting(APP_INSTANCE_KEY_SETTING, QuartzGateway.privKeyHex(it)!!)
                }
        }

    /** Called by [NostrRelayManager]'s presence subscription; ignores anything but our scoped statuses. */
    fun onPresenceEvent(event: Event) {
        if (event.kind != PRESENCE_KIND) return
        val target =
            when (runCatching { event.dTag() }.getOrNull()) {
                PRESENCE_D_TAG -> _presence
                PRESENCE_APP_D_TAG -> _appPresence
                else -> return
            }
        val expiresAt = event.expiration() ?: return
        target.update { current ->
            val previous = current[event.pubKey]
            // Relays replay in arbitrary order — an older status must not overwrite a newer one.
            if (previous != null && previous.lastSeen >= event.createdAt) {
                current
            } else {
                current + (event.pubKey to Presence(event.createdAt, expiresAt))
            }
        }
    }
}
