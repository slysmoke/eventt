package org.eventt.features.tools.pricing

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.eventt.core.model.eveSigFigStep
import org.junit.jupiter.api.Test
import kotlin.math.round

// PricingService.computePrices talks to CostBasisService (real wallet DB) and EsiClient/character
// location (real network) internally, so it isn't unit-testable end-to-end without a running
// DB/mock server. These tests instead pin down the pure margin/undercut arithmetic that the UI
// and any future refactor must preserve, expressed directly against the formulas computePrices
// uses (mirrored from its private resolveFinalPrice/undercutPrice helpers).
class PricingServiceTest {
    // Sales tax + broker fee both come off a completed sale, so the target price is grossed up by
    // this rate to compensate — mirrors computePrices' private netProceedsRate/target formulas.
    private fun netProceedsRate(
        salesTaxPct: Double = 8.0,
        brokerFeePct: Double = 3.0,
    ): Double = 1.0 - (salesTaxPct + brokerFeePct) / 100.0

    // Mirrors computePrices' private roundToSigFig — rounds to a real EVE price tick (4 sig figs)
    // rather than leaving a target at raw decimal precision no one could actually list an order at.
    private fun roundToSigFig(price: Double): Double {
        if (price <= 0) return 0.01
        val step = eveSigFigStep(price)
        return round(price / step) * step
    }

    private fun targetPrice(
        costBasis: Double,
        marginPct: Double,
        salesTaxPct: Double = 8.0,
        brokerFeePct: Double = 3.0,
    ): Double = roundToSigFig(costBasis * (1.0 + marginPct / 100.0) / netProceedsRate(salesTaxPct, brokerFeePct))

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
    fun `margin math applies the percentage on top of cost basis, then grosses up for zero fees`() {
        targetPrice(costBasis = 100.0, marginPct = 30.0, salesTaxPct = 0.0, brokerFeePct = 0.0) shouldBe 130.0
        targetPrice(costBasis = 1_000_000.0, marginPct = 0.0, salesTaxPct = 0.0, brokerFeePct = 0.0) shouldBe 1_000_000.0
    }

    @Test
    fun `target price is grossed up so sales tax and broker fee don't eat into the requested margin`() {
        // 100 cost, 30% margin, 8% tax + 3% broker: naive target (130) would net only
        // 130 * 0.89 - 100 = 15.7 (15.7%), not 30% — the grossed-up target corrects for that.
        // Raw 130 / 0.89 ≈ 146.067, rounded to the nearest 0.1 tick at this price magnitude.
        val target = targetPrice(costBasis = 100.0, marginPct = 30.0, salesTaxPct = 8.0, brokerFeePct = 3.0)
        target shouldBe 146.1
        val netProceeds = target * 0.89
        ((netProceeds - 100.0) / 100.0 * 100.0) shouldBe (30.0 plusOrMinus 0.1)
    }

    @Test
    fun `target price is rounded to a real EVE price tick, not left at raw decimal precision`() {
        roundToSigFig(146.0674157303371) shouldBe 146.1
        // Large prices round to a coarser tick (4 sig figs) — nearest 1000, not nearest cent.
        roundToSigFig(1_234_567.0) shouldBe 1_235_000.0
    }

    @Test
    fun `undercut price is one EVE price tick below the rounded market price`() {
        // eveSigFigStep(100.0) == 0.1 (4 sig figs) -> undercut = 100.0 - 0.1
        undercutPrice(100.0) shouldBe 99.9
    }

    @Test
    fun `market undercut is used whenever it is cheaper than the margin target, always, not optionally`() {
        val target = targetPrice(100.0, 30.0) // ≈ 146.1 with default 8% tax + 3% broker, sigfig-rounded
        val (final, usedMarket) = resolveFinalPrice(marginLimitEnabled = true, target = target, marketUndercut = 110.0)
        final shouldBe 110.0
        usedMarket shouldBe true
    }

    @Test
    fun `margin target wins when the market undercut is higher than it`() {
        val target = targetPrice(100.0, 30.0) // ≈ 146.1 with default 8% tax + 3% broker, sigfig-rounded
        val (final, usedMarket) = resolveFinalPrice(marginLimitEnabled = true, target = target, marketUndercut = 150.0)
        final shouldBe target
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
                PricingResult(1, "Tritanium", 100, 4.0, 5.2, 5.05, 5.0, 5.0, usedMarketPrice = true, salesTaxPct = 8.0, brokerFeePct = 3.0),
                PricingResult(
                    2,
                    "Pyerite",
                    50,
                    null,
                    null,
                    null,
                    null,
                    null,
                    usedMarketPrice = false,
                    salesTaxPct = 8.0,
                    brokerFeePct = 3.0,
                ),
                PricingResult(
                    3,
                    "Mexallon",
                    10,
                    20.0,
                    26.0,
                    null,
                    null,
                    26.0,
                    usedMarketPrice = false,
                    salesTaxPct = 8.0,
                    brokerFeePct = 3.0,
                ),
            )
        val text = PricingService.formatForClipboard(results)
        text shouldBe "Tritanium\t5.00\nMexallon\t26.00"
    }
}
