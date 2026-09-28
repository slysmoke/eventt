package org.eventt.features.market

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.eventt.core.model.MarketHistoryModel
import org.junit.jupiter.api.Test
import java.time.LocalDate

private fun row(
    daysAgo: Long,
    price: Double,
) = MarketHistoryModel(
    typeId = 1,
    regionId = 1,
    date = LocalDate.now().minusDays(daysAgo).toString(),
    average = price,
    volume = 100,
    orderCount = 1,
    highest = price,
    lowest = price,
)

class BacktestDipStrategyTest {
    @Test
    fun `not enough history returns null instead of a misleading result`() {
        val history = (0..10L).map { row(it, 10.0) }

        backtestDipStrategy(history, lookbackDays = 90, minDiscountPct = 5.0) shouldBe null
    }

    @Test
    fun `a dip that keeps falling then fully recovers backtests as profitable with a real tracked drawdown`() {
        // 30 flat days at 10 (the trailing-average baseline), then it keeps falling -- 10 days at 7,
        // 10 more at 5 -- buying all the way down, then recovers to 12 by the end. The early lots
        // bought at 7 are genuinely underwater once price reaches 5, before the recovery erases it.
        val flat = (60 downTo 31).map { row(it.toLong(), 10.0) }
        val fallStep1 = (30 downTo 21).map { row(it.toLong(), 7.0) }
        val fallStep2 = (20 downTo 11).map { row(it.toLong(), 5.0) }
        val recovery = (10 downTo 0).map { row(it.toLong(), 12.0) }

        val result = backtestDipStrategy(flat + fallStep1 + fallStep2 + recovery, lookbackDays = 30, minDiscountPct = 5.0)

        result shouldNotBe null
        result!!.buySignals shouldBe (fallStep1.size + fallStep2.size)
        // Bought in on the way down at 7 and 5, ended at 12 -- comfortably profitable overall.
        (result.unrealizedPnlPct > 50.0) shouldBe true
        // But the lots bought at 7 were genuinely underwater once price fell further to 5.
        (result.worstDrawdownPct < -5.0) shouldBe true
    }

    @Test
    fun `round-trip fees come off the backtest result`() {
        // Buy once at 5 (a 50% dip under a flat 10 baseline), mark at 10 on the last day.
        val history = (40 downTo 11).map { row(it.toLong(), 10.0) } + listOf(row(10, 5.0)) + (9 downTo 0).map { row(it.toLong(), 10.0) }
        val noFees = backtestDipStrategy(history, lookbackDays = 30, minDiscountPct = 40.0)!!
        val withFees = backtestDipStrategy(history, 30, 40.0, MaterialFees(8.0, 3.0))!!

        noFees.unrealizedPnlPct shouldBe (100.0 plusOrMinus 1e-9)
        // 1/1.03 units per ISK, marked at 10 * 0.89: 0.89 * 2 / 1.03 - 1
        withFees.unrealizedPnlPct shouldBe ((0.89 * 2 / 1.03 - 1) * 100 plusOrMinus 1e-9)
    }
}
