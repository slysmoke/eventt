package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class GiveawayServiceTest {
    private val signer = QuartzGateway.signerFor(KeyPair())
    private val people = (1..10).map { GiveawayParticipant("pk%02d".format(it), "Trader $it") }
    private val drawn =
        Giveaway(
            id = "g-1",
            status = GiveawayStatus.DRAWN,
            plexPerWinner = 500,
            winnerCount = 3,
            activeDays = 7,
            drawAt = 1_800_000_000,
            note = "Autumn giveaway",
            participants = people,
            btcHeight = 900_000,
            btcHash = "00000000000000000001abc",
            winners = people.take(3),
        )

    @Test
    fun `an organiser event round-trips, anyone else's or a tampered one is dropped`() {
        val event = GiveawayService.buildEvent(signer, drawn, createdAt = 1_800_000_100)

        GiveawayService.parseEvent(event, admin = signer.pubKey) shouldBe drawn.copy(updatedAt = 1_800_000_100)
        GiveawayService.parseEvent(event).shouldBeNull()
        val forged = Event(event.id, event.pubKey, event.createdAt, event.kind, event.tags, "Every PLEX to me", event.sig)
        GiveawayService.parseEvent(forged, admin = signer.pubKey).shouldBeNull()
        val dryRun = QuartzGateway.signEvent(signer, 1_800_000_100, GIVEAWAY_KIND, event.tags + arrayOf("test", "1"), "")
        GiveawayService.parseEvent(dryRun, admin = signer.pubKey).shouldBeNull()
    }

    @Test
    fun `the draw depends only on the block hash, the id and the participant set`() {
        val a = GiveawayService.drawWinners(people, "hash-a", "g-1", 3)

        a.size shouldBe 3
        a.distinct().size shouldBe 3
        GiveawayService.drawWinners(people.shuffled(), "hash-a", "g-1", 3) shouldBe a
        GiveawayService.drawWinners(people, "hash-b", "g-1", 3) shouldNotBe a
        GiveawayService.drawWinners(people.take(2), "hash-a", "g-1", 3).size shouldBe 2
    }
}
