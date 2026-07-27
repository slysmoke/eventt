package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LeaderboardServiceTest {
    private val signer = QuartzGateway.signerFor(KeyPair())

    @Test
    fun `entry event round-trips through build and parse`() {
        val event =
            LeaderboardService.buildEntryEvent(
                signer,
                pnl7d = 1.5,
                pnl30d = 12.0,
                pnl365d = 100.0,
                traderChar = "Some Trader",
                traderCharId = 42,
            )

        val parsed = LeaderboardService.parseEntryEvent(event)

        parsed.shouldNotBeNull()
        parsed.pubkey shouldBe event.pubKey
        parsed.traderChar shouldBe "Some Trader"
        parsed.traderCharId shouldBe 42
        parsed.pnl7d shouldBe 1.5
        parsed.pnl30d shouldBe 12.0
        parsed.pnl365d shouldBe 100.0
    }

    @Test
    fun `an older replayed entry does not overwrite a newer one`() {
        val newer = LeaderboardService.buildEntryEvent(signer, 1.0, 2.0, 3.0, "Trader", 1, createdAt = 1_700_000_600L)
        val older = LeaderboardService.buildEntryEvent(signer, 99.0, 99.0, 99.0, "Trader", 1, createdAt = 1_700_000_000L)

        LeaderboardService.onEvent(newer)
        LeaderboardService.onEvent(older)

        LeaderboardService.entries.value[newer.pubKey]?.pnl7d shouldBe 1.0
    }

    @Test
    fun `a tombstone drops the entry from the aggregate`() {
        val event = LeaderboardService.buildEntryEvent(signer, 1.0, 2.0, 3.0, "Trader", 1)
        LeaderboardService.onEvent(event)
        LeaderboardService.entries.value[event.pubKey].shouldNotBeNull()

        LeaderboardService.onTombstone(event.pubKey)

        LeaderboardService.entries.value[event.pubKey].shouldBeNull()
    }

    @Test
    fun `an event under a different d tag is ignored`() {
        val foreign =
            QuartzGateway.signEvent(
                signer,
                1_700_000_000L,
                LEADERBOARD_KIND,
                arrayOf(arrayOf("d", "not-ours")),
                "",
            )

        LeaderboardService.parseEntryEvent(foreign).shouldBeNull()
    }
}
