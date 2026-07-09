package org.eventt.features.tools.pricing

import io.kotest.matchers.shouldBe
import org.eventt.features.orders.eveSigFigStep
import org.junit.jupiter.api.Test
import kotlin.math.round

// PricingService.computePrices talks to CostBasisService (real wallet DB) and EsiClient/character
// location (real network) internally, so it isn't unit-testable end-to-end without a running
// DB/mock server. These tests instead pin down the pure margin/undercut arithmetic that the UI
// and any future refactor must preserve, expressed directly against the formulas computePrices
// uses (mirrored from its private resolveFinalPrice/undercutPrice helpers).
class PricingServiceTest {
    private fun targetPrice(
        costBasis: Double,
        marginPct: Double,
    ): Double = costBasis * (1.0 + marginPct / 100.0)

    private fun undercutPrice(bestSell: Double): Double {
        val step = eveSigFigStep(bestSell)
        return round(bestSell / step) * step - step
    }

    private fun resolveFinalPrice(
        marginLimitEnabled: Boolean,
        target: Double?,
        marketUndercut: Double?,
    ): Pair<Double?, Boolean> =
        when {
            !marginLimitEnabled -> marketUndercut to true
            target != null && marketUndercut != null ->
                if (marketUndercut < target) marketUndercut to true else target to false
            target != null -> target to false
            marketUndercut != null -> marketUndercut to true
            else -> null to false
        }

    @Test
    fun `margin math applies the percentage on top of cost basis`() {
        targetPrice(costBasis = 100.0, marginPct = 30.0) shouldBe 130.0
        targetPrice(costBasis = 1_000_000.0, marginPct = 0.0) shouldBe 1_000_000.0
    }

    @Test
    fun `undercut price is one EVE price tick below the rounded market price`() {
        // eveSigFigStep(100.0) == 0.1 (4 sig figs) -> undercut = 100.0 - 0.1
        undercutPrice(100.0) shouldBe 99.9
    }

    @Test
    fun `market undercut is used whenever it is cheaper than the margin target, always, not optionally`() {
        val target = targetPrice(100.0, 30.0) // 130
        val (final, usedMarket) = resolveFinalPrice(marginLimitEnabled = true, target = target, marketUndercut = 110.0)
        final shouldBe 110.0
        usedMarket shouldBe true
    }

    @Test
    fun `margin target wins when the market undercut is higher than it`() {
        val target = targetPrice(100.0, 30.0) // 130
        val (final, usedMarket) = resolveFinalPrice(marginLimitEnabled = true, target = target, marketUndercut = 150.0)
        final shouldBe 130.0
        usedMarket shouldBe false
    }

    @Test
    fun `disabling the margin limit ignores cost basis entirely and always returns the market undercut`() {
        val target = targetPrice(50.0, 20.0) // 60 — should be irrelevant when margin limit is off
        val (final, usedMarket) = resolveFinalPrice(marginLimitEnabled = false, target = target, marketUndercut = 1000.0)
        final shouldBe 1000.0
        usedMarket shouldBe true
    }

    @Test
    fun `missing cost basis with margin enabled falls back to the market undercut if available`() {
        val (final, usedMarket) = resolveFinalPrice(marginLimitEnabled = true, target = null, marketUndercut = 50.0)
        final shouldBe 50.0
        usedMarket shouldBe true
    }

    @Test
    fun `nothing resolvable when both target and market undercut are unavailable`() {
        val (final, usedMarket) = resolveFinalPrice(marginLimitEnabled = true, target = null, marketUndercut = null)
        final shouldBe null
        usedMarket shouldBe false
    }

    @Test
    fun `formatForClipboard skips items with no resolvable price and is tab-separated`() {
        val results =
            listOf(
                PricingResult(1, "Tritanium", 100, 4.0, 5.2, 5.05, 5.0, 5.0, usedMarketPrice = true),
                PricingResult(2, "Pyerite", 50, null, null, null, null, null, usedMarketPrice = false),
                PricingResult(3, "Mexallon", 10, 20.0, 26.0, null, null, 26.0, usedMarketPrice = false),
            )
        val text = PricingService.formatForClipboard(results)
        text shouldBe "Tritanium\t5.00\nMexallon\t26.00"
    }
}
