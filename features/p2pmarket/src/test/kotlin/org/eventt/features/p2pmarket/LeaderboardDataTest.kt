package org.eventt.features.p2pmarket

import io.kotest.matchers.shouldBe
import org.eventt.core.nostr.LeaderboardEntry
import org.eventt.core.nostr.LeaderboardStats
import org.junit.jupiter.api.Test

class LeaderboardDataTest {
    private val now = 1_800_000_000L

    private fun entry(
        key: String,
        pnl30: Double,
        ageSec: Long = 0,
    ) = LeaderboardEntry(key, key, null, LeaderboardStats(0.0, pnl30, 0.0), now - ageSec)

    @Test
    fun `stale entries rank below current ones, and change counts places gained`() {
        val entries = listOf(entry("a", 5.0), entry("b", 9.0, ageSec = 2 * STALE_SECONDS), entry("c", 7.0))

        val r = rank(entries, LbWindow.MONTH, previous = mapOf("a" to 1, "b" to 1), mine = setOf("a"), nowSec = now)

        r.map { it.entry.pubkey } shouldBe listOf("c", "a", "b")
        r.map { it.change } shouldBe listOf(null, -1, -2)
        r.map { it.stale } shouldBe listOf(false, false, true)
        r.single { it.mine }.entry.pubkey shouldBe "a"
    }

    @Test
    fun `summary median and total cover current entries only`() {
        val r =
            rank(
                listOf(entry("a", 1.0), entry("b", 3.0), entry("c", 100.0, ageSec = 2 * STALE_SECONDS)),
                LbWindow.MONTH,
                emptyMap(),
                emptySet(),
                now,
            )

        val s = summary(r, LbWindow.MONTH)

        s.traders shouldBe 3
        s.active shouldBe 2
        s.medianPnl shouldBe 2.0
        s.totalPnl shouldBe 4.0
    }
}
