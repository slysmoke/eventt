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
        val stats =
            LeaderboardStats(
                pnl7d = 1.5,
                pnl30d = 12.0,
                pnl365d = 100.0,
                roi7dPct = 0.5,
                roi30dPct = 2.0,
                roi365dPct = 9.0,
                winRate30dPct = 61.0,
                margin30dPct = 7.5,
                profitFactor30d = 3.2,
                sells30d = 155,
                turnover30d = 4.2e10,
                daily30d = listOf(0.0, -5.0, 1e9),
                netWorth = 9.2e11,
            )
        val event = LeaderboardService.buildEntryEvent(signer, stats, traderChar = "Some Trader", traderCharId = 42)

        val parsed = LeaderboardService.parseEntryEvent(event)

        parsed.shouldNotBeNull()
        parsed.pubkey shouldBe event.pubKey
        parsed.traderChar shouldBe "Some Trader"
        parsed.traderCharId shouldBe 42
        parsed.stats shouldBe stats
    }

    @Test
    fun `an entry from an older client without the optional tags still parses`() {
        val old =
            QuartzGateway.signEvent(
                signer,
                1_700_000_000L,
                LEADERBOARD_KIND,
                arrayOf(
                    arrayOf("d", LEADERBOARD_D_TAG),
                    arrayOf("pnl_7d", "1.0"),
                    arrayOf("pnl_30d", "2.0"),
                    arrayOf("pnl_365d", "3.0"),
                    arrayOf("trader_char", "Old"),
                    arrayOf("roi_30d", "not-a-number"),
                ),
                "",
            )

        val stats = LeaderboardService.parseEntryEvent(old).shouldNotBeNull().stats

        stats.pnl30d shouldBe 2.0
        stats.roi30dPct.shouldBeNull()
        stats.daily30d shouldBe emptyList()
        stats.netWorth.shouldBeNull()
    }

    @Test
    fun `an older replayed entry does not overwrite a newer one`() {
        val newer = LeaderboardService.buildEntryEvent(signer, LeaderboardStats(1.0, 2.0, 3.0), "Trader", 1, createdAt = 1_700_000_600L)
        val older = LeaderboardService.buildEntryEvent(signer, LeaderboardStats(99.0, 99.0, 99.0), "Trader", 1, createdAt = 1_700_000_000L)

        LeaderboardService.onEvent(newer)
        LeaderboardService.onEvent(older)

        LeaderboardService.entries.value[newer.pubKey]
            ?.stats
            ?.pnl7d shouldBe 1.0
    }

    @Test
    fun `a tombstone drops the entry from the aggregate`() {
        val event = LeaderboardService.buildEntryEvent(signer, LeaderboardStats(1.0, 2.0, 3.0), "Trader", 1)
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
