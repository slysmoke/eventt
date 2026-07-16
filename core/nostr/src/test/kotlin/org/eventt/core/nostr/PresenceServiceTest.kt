package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PresenceServiceTest {
    private val signer = QuartzGateway.signerFor(KeyPair())

    @Test
    fun `heartbeat is an addressable status scoped to our d tag with a two-cadence expiration`() {
        val createdAt = 1_700_000_000L
        val event = PresenceService.buildHeartbeat(signer, createdAt)

        event.kind shouldBe PRESENCE_KIND
        event.tags.first { it[0] == "d" }[1] shouldBe PRESENCE_D_TAG
        event.tags.first { it[0] == "expiration" }[1] shouldBe (createdAt + 2 * PRESENCE_HEARTBEAT_SECONDS).toString()
    }

    @Test
    fun `app-instance statuses land in their own map, not among traders`() {
        val appSigner = QuartzGateway.signerFor(KeyPair())
        val event = PresenceService.buildHeartbeat(appSigner, 1_700_000_000L, dTag = PRESENCE_APP_D_TAG)

        PresenceService.onPresenceEvent(event)

        PresenceService.appPresence.value.containsKey(event.pubKey) shouldBe true
        PresenceService.presence.value.containsKey(event.pubKey) shouldBe false
    }

    @Test
    fun `an older replayed status does not overwrite a newer one, and freshness decides online`() {
        val newer = PresenceService.buildHeartbeat(signer, 1_700_000_600L)
        val older = PresenceService.buildHeartbeat(signer, 1_700_000_000L)

        PresenceService.onPresenceEvent(newer)
        PresenceService.onPresenceEvent(older)

        val presence = PresenceService.presence.value[newer.pubKey]
        presence.shouldNotBeNull()
        presence.lastSeen shouldBe 1_700_000_600L
        presence.isOnline(nowSec = 1_700_000_700L) shouldBe true
        presence.isOnline(nowSec = 1_700_000_600L + 2 * PRESENCE_HEARTBEAT_SECONDS + 1) shouldBe false
    }
}
