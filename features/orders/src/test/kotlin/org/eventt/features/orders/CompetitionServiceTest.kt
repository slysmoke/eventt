package org.eventt.features.orders

import io.kotest.matchers.shouldBe
import org.eventt.core.database.MarketTopSnapshot
import org.junit.jupiter.api.Test

class CompetitionServiceTest {
    private val tickMillis = 5L * 60 * 1000

    // Snapshots every 5 minutes starting at epoch; mineFlags[i] = was I on top at tick i.
    private fun snapshots(vararg mineFlags: Boolean): List<MarketTopSnapshot> =
        mineFlags.mapIndexed { i, mine ->
            MarketTopSnapshot(
                ts = i * tickMillis,
                bestPrice = 100.0,
                bestOrderId = if (mine) 1L else 100L + (i % 3), // three distinct rivals rotate
                bestIsMine = mine,
            )
        }

    @Test
    fun `too few ticks means still collecting`() {
        CompetitionService.compute(snapshots(true, false, true)).level shouldBe CompetitionService.Level.COLLECTING
    }

    @Test
    fun `unchallenged order book is calm`() {
        val stats = CompetitionService.compute(snapshots(*BooleanArray(20) { true }))
        stats.level shouldBe CompetitionService.Level.CALM
        stats.timeOnTopPct shouldBe 1.0
        stats.competitors shouldBe 0
    }

    @Test
    fun `mostly on top is calm even with occasional rivals`() {
        // 16 of 20 ticks on top = 80% ≥ the 60% calm threshold.
        val flags = BooleanArray(20) { it % 5 != 4 }
        CompetitionService.compute(snapshots(*flags)).level shouldBe CompetitionService.Level.CALM
    }

    @Test
    fun `fast beats concentrated in a play session are contested, not a bot`() {
        // Alternating mine/rival — every beat is instant, but 24 ticks span only 2 UTC hours,
        // far below the round-the-clock coverage a bot verdict requires.
        val flags = BooleanArray(24) { it % 2 == 0 }
        val stats = CompetitionService.compute(snapshots(*flags))
        stats.level shouldBe CompetitionService.Level.CONTESTED
        stats.fastBeatShare shouldBe 1.0
    }

    @Test
    fun `instant beats around the clock are a bot war`() {
        // Alternating mine/rival across 3 days: every beat happens by the next tick and beats
        // land in every hour of the day.
        val flags = BooleanArray(3 * 24 * 12) { it % 2 == 0 }
        val stats = CompetitionService.compute(snapshots(*flags))
        stats.level shouldBe CompetitionService.Level.BOT_WAR
        stats.beatHourCoverage shouldBe 24
        stats.medianBeatTicks shouldBe 1.0
    }

    @Test
    fun `distinct rival order ids are counted`() {
        val flags = BooleanArray(24) { it % 2 == 0 }
        // Rival ids rotate across 100..102 in the fixture.
        CompetitionService.compute(snapshots(*flags)).competitors shouldBe 3
    }
}
