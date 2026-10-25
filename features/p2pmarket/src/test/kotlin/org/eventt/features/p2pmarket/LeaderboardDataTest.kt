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
    fun `ranks by pnl regardless of staleness, and change counts places gained`() {
        val entries = listOf(entry("a", 5.0), entry("b", 9.0, ageSec = 2 * STALE_SECONDS), entry("c", 7.0))

        val r = rank(entries, LbWindow.MONTH, previous = mapOf("a" to 1, "b" to 2), mine = setOf("a"), nowSec = now)

        r.map { it.entry.pubkey } shouldBe listOf("b", "c", "a")
        r.map { it.change } shouldBe listOf(1, null, -1)
        r.map { it.stale } shouldBe listOf(true, false, false)
        r.single { it.mine }.entry.pubkey shouldBe "a"
    }

    @Test
    fun `traders dropping off the board don't move anyone up`() {
        // Yesterday: a, gone, b, gone2, c. Today the two gone ones fell out of the window.
        val entries = listOf(entry("a", 9.0), entry("b", 5.0), entry("c", 1.0))
        val previous = mapOf("a" to 1, "gone" to 2, "b" to 3, "gone2" to 4, "c" to 5)

        rank(entries, LbWindow.MONTH, previous, emptySet(), now).map { it.change } shouldBe listOf(0, 0, 0)
    }

    @Test
    fun `entries last published before the window began are left out of it`() {
        val entries = listOf(entry("a", 5.0), entry("gone", 9.0, ageSec = 15 * 86400L))

        rank(entries, LbWindow.WEEK, emptyMap(), emptySet(), now).map { it.entry.pubkey } shouldBe listOf("a")
        rank(entries, LbWindow.MONTH, emptyMap(), emptySet(), now).map { it.entry.pubkey } shouldBe listOf("gone", "a")
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
