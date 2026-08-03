package org.eventt.features.market

import io.kotest.matchers.shouldBe
import org.eventt.core.model.MarketHistoryModel
import org.junit.jupiter.api.Test
import java.time.LocalDate

private fun historyRow(
    daysAgo: Long,
    average: Double,
    volume: Long = 1,
) = MarketHistoryModel(
    typeId = 1,
    regionId = 1,
    date = LocalDate.now().minusDays(daysAgo).toString(),
    average = average,
    volume = volume,
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

    @Test
    fun `a volume-only spike is detected even when price barely moves`() {
        val stable = (1..20).map { historyRow(daysAgo = it.toLong(), average = 1_000_000.0, volume = 50) }
        val volumeSpike = historyRow(daysAgo = 0, average = 1_050_000.0, volume = 2_000)

        detectPriceSpike(stable + volumeSpike) shouldBe true
    }

    @Test
    fun `regression - a real item whose price alone stays under the 1_5x threshold is still caught by its volume spike`() {
        // The Forge, 30 real days for a ship whose price only moved ~1.4x on its spike day (below
        // the 1.5x price threshold) but whose volume that day (2307) was ~46x the ~50/day norm.
        val history =
            listOf(
                historyRow(0, 16_260_000.0, 2307),
                historyRow(1, 11_316_250.0, 32),
                historyRow(2, 11_253_225.81, 31),
                historyRow(3, 11_288_400.0, 25),
                historyRow(4, 11_409_655.17, 29),
                historyRow(5, 11_393_529.41, 51),
                historyRow(6, 11_393_720.93, 43),
                historyRow(7, 11_180_000.0, 124),
                historyRow(8, 11_483_235.29, 34),
                historyRow(9, 11_512_195.12, 41),
                historyRow(10, 11_590_000.0, 252),
                historyRow(11, 11_519_193.55, 62),
                historyRow(12, 11_484_375.0, 48),
                historyRow(13, 11_447_910.45, 67),
                historyRow(14, 11_631_011.24, 89),
                historyRow(15, 11_746_200.0, 50),
                historyRow(16, 11_624_814.81, 27),
                historyRow(17, 11_711_578.95, 57),
                historyRow(18, 11_901_724.14, 29),
                historyRow(19, 11_703_333.33, 45),
                historyRow(20, 11_794_375.0, 64),
                historyRow(21, 11_977_000.0, 40),
                historyRow(22, 11_985_208.33, 96),
                historyRow(23, 12_107_017.54, 57),
                historyRow(24, 11_778_378.38, 37),
                historyRow(25, 11_994_838.71, 31),
                historyRow(26, 11_350_000.0, 206),
                historyRow(27, 12_230_000.0, 103),
                historyRow(28, 12_031_486.49, 74),
                historyRow(29, 12_026_052.63, 38),
            )

        detectPriceSpike(history) shouldBe true
    }
}
