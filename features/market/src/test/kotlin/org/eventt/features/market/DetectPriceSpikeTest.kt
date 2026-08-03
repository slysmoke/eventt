package org.eventt.features.market

import io.kotest.matchers.shouldBe
import org.eventt.core.model.MarketHistoryModel
import org.junit.jupiter.api.Test
import java.time.LocalDate

private fun historyRow(
    daysAgo: Long,
    average: Double,
) = MarketHistoryModel(
    typeId = 1,
    regionId = 1,
    date = LocalDate.now().minusDays(daysAgo).toString(),
    average = average,
    volume = 1,
    orderCount = 1,
    highest = average,
    lowest = average,
)

class DetectPriceSpikeTest {
    @Test
    fun `a spike on the most recent day is detected even though it hasn't reverted yet`() {
        val stable = (1..20).map { historyRow(daysAgo = it.toLong(), average = 1_000_000.0) }
        val spike = historyRow(daysAgo = 0, average = 3_000_000.0)

        detectPriceSpike(stable + spike) shouldBe true
    }

    @Test
    fun `a spike that has since come back down to baseline is also detected`() {
        val before = (10..20).map { historyRow(daysAgo = it.toLong(), average = 1_000_000.0) }
        val spike = historyRow(daysAgo = 5, average = 3_000_000.0)
        val after = (1..4).map { historyRow(daysAgo = it.toLong(), average = 1_050_000.0) }

        detectPriceSpike(before + spike + after) shouldBe true
    }

    @Test
    fun `steady gradual drift with no spike is not flagged`() {
        val history = (0..20).map { historyRow(daysAgo = it.toLong(), average = 1_000_000.0 + it * 10_000.0) }

        detectPriceSpike(history) shouldBe false
    }

    @Test
    fun `too little history to judge a baseline is not flagged`() {
        val history = listOf(historyRow(daysAgo = 1, average = 1_000_000.0), historyRow(daysAgo = 0, average = 3_000_000.0))

        detectPriceSpike(history) shouldBe false
    }
}
