package org.eventt.features.market

import io.kotest.matchers.shouldBe
import org.eventt.core.model.MarketHistoryModel
import org.junit.jupiter.api.Test
import java.time.LocalDate

private fun historyRow(
    daysAgo: Long,
    volume: Long,
) = MarketHistoryModel(
    typeId = 1,
    regionId = 1,
    date = LocalDate.now().minusDays(daysAgo).toString(),
    average = 1.0,
    volume = volume,
    orderCount = 1,
    highest = 1.0,
    lowest = 1.0,
)

class MedianDailyVolumeTest {
    @Test
    fun `stale scattered trades outside the window are treated as zero, not as the window's data`() {
        // 30 real trading-day rows, but the most recent one is 200 days ago — an item that hasn't
        // traded in the actual 30-day window at all. Row count alone (30 == windowDays) used to
        // read as "no gaps," letting these old trades set the median instead of zero.
        val history = (200..229).map { historyRow(daysAgo = it.toLong(), volume = 5L) }

        medianDailyVolume(history, windowDays = 30) shouldBe 0L
    }

    @Test
    fun `a handful of sales this week among mostly-quiet days medians to zero`() {
        // 10 units sold on one day this week; the rest of the 30-day window has no trades at all
        // (and so no rows, per ESI's omit-empty-days behavior).
        val history = listOf(historyRow(daysAgo = 3, volume = 10L))

        medianDailyVolume(history, windowDays = 30) shouldBe 0L
    }

    @Test
    fun `busy item with a trade most days medians close to typical volume`() {
        val history = (0..27).map { historyRow(daysAgo = it.toLong(), volume = 20L) }

        medianDailyVolume(history, windowDays = 30) shouldBe 20L
    }

    @Test
    fun `a single freak high-volume day doesn't drag the median up`() {
        val quietDays = (1..25).map { historyRow(daysAgo = it.toLong(), volume = 2L) }
        val freakDay = historyRow(daysAgo = 0, volume = 100_000L)

        medianDailyVolume(quietDays + freakDay, windowDays = 30) shouldBe 2L
    }
}
