package org.eventt.features.market

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

private fun candidate(
    typeId: Int,
    currentPrice: Double,
    vsAvgPct: Double,
    dailyVolume: Long,
    position: MaterialPosition? = null,
) = MaterialCandidate(
    typeId = typeId,
    typeName = "Type $typeId",
    currentPrice = currentPrice,
    avgPrice = currentPrice / (1.0 + vsAvgPct / 100.0),
    highPrice = currentPrice * 1.5,
    lowPrice = currentPrice * 0.5,
    vsAvgPct = vsAvgPct,
    drawdownFromHighPct = -10.0,
    trendPct = 0.0,
    volatilityPct = 5.0,
    dailyVolume = dailyVolume,
    spikeDetected = false,
    position = position,
    backtest = null,
)

class AllocateBudgetTest {
    @Test
    fun `maxItems keeps the biggest real opportunity, not just the deepest discount percentage`() {
        // Thin item: deep 40% discount but only 2 units/day at 5 ISK -- trivial total opportunity.
        val thinDeepDiscount = candidate(typeId = 1, currentPrice = 5.0, vsAvgPct = -40.0, dailyVolume = 2)
        // Liquid material: a shallower 8% discount, but trades billions of ISK/day -- the real
        // dip-buying opportunity here dwarfs the thin item's, even though its % looks smaller.
        val liquidMaterial = candidate(typeId = 2, currentPrice = 5000.0, vsAvgPct = -8.0, dailyVolume = 2_000_000)

        val result =
            allocateBudget(
                candidates = listOf(thinDeepDiscount, liquidMaterial),
                totalBudget = 1_000_000_000.0,
                maxItems = 1,
                maxPerItemPct = 100.0,
                liquidityDays = 30.0,
                ladderLevels = 3,
                ladderStepPct = 5.0,
            )

        result.size shouldBe 1
        result.first().candidate.typeId shouldBe 2
        result.first().candidate.typeId shouldNotBe thinDeepDiscount.typeId
    }

    @Test
    fun `ladder anchors to real cost basis when already holding and underwater, not the live price`() {
        // Bought in at 10, price has since fallen to 8 (currently -20% vs its own average of 10).
        val held = MaterialPosition(qtyHeld = 100, avgBuyPrice = 10.0)
        val c = candidate(typeId = 1, currentPrice = 8.0, vsAvgPct = -20.0, dailyVolume = 1000, position = held)

        val result = allocateBudget(listOf(c), totalBudget = 1_000_000.0, maxItems = 1, 100.0, 30.0, ladderLevels = 3, ladderStepPct = 5.0)

        val ladder = result.first().ladder
        // Level 1 must be priced off the 10 ISK cost basis, not the 8 ISK live price -- otherwise
        // the ladder resets to a fresh "8 and down" on every re-scan instead of measuring further
        // downside from where the position was actually opened.
        ladder.first().triggerPrice shouldBe 10.0
        // That level sits above the live price, so it's already effectively crossed.
        ladder.first().alreadyTriggered shouldBe true
    }

    @Test
    fun `ladder anchors to the live price when not holding a position`() {
        val c = candidate(typeId = 1, currentPrice = 8.0, vsAvgPct = -20.0, dailyVolume = 1000, position = null)

        val result = allocateBudget(listOf(c), totalBudget = 1_000_000.0, maxItems = 1, 100.0, 30.0, ladderLevels = 3, ladderStepPct = 5.0)

        val ladder = result.first().ladder
        ladder.first().triggerPrice shouldBe 8.0
        ladder.first().alreadyTriggered shouldBe true
    }

    @Test
    fun `a position already at its allocation waits instead of recommending more buys`() {
        // Bought 100 @ 10 = 1000 ISK held; its budget share is capped at 1000 too, so nothing's left to buy.
        val held = MaterialPosition(qtyHeld = 100, avgBuyPrice = 10.0)
        val c = candidate(typeId = 1, currentPrice = 8.0, vsAvgPct = -20.0, dailyVolume = 1000, position = held)

        val r = allocateBudget(listOf(c), totalBudget = 1000.0, maxItems = 1, 100.0, 30.0, ladderLevels = 3, ladderStepPct = 5.0).single()

        r.toBuyIsk shouldBe 0.0
        r.ladder.size shouldBe 0
        r.action shouldBe MaterialAction.WAIT
    }

    @Test
    fun `held position flips to SELL once the lowest ask clears cost plus take-profit after fees`() {
        val held = MaterialPosition(qtyHeld = 100, avgBuyPrice = 10.0)
        // Still 5% under its average, so it also has a budget share -- SELL must win and hide it.
        val c = candidate(typeId = 1, currentPrice = 11.0, vsAvgPct = -5.0, dailyVolume = 1000, position = held).copy(bestAsk = 11.2)

        val r = allocateBudget(listOf(c), totalBudget = 1_000_000.0, maxItems = 1, 100.0, 30.0, 3, 5.0, sellFeePct = 4.0).single()

        // 10 * 1.05 / 0.96 = 10.9375
        r.sellTarget!!.targetPrice shouldBe (10.0 * 1.05 / 0.96)
        r.action shouldBe MaterialAction.SELL
        r.toBuyIsk shouldBe 0.0
        // (11.2 * 0.96 - 10) * 100
        r.sellTarget!!.profitNow!! shouldBe ((11.2 * 0.96 - 10.0) * 100 plusOrMinus 1e-6)
    }

    @Test
    fun `sell target is only generated when actually holding a position`() {
        val held = MaterialPosition(qtyHeld = 100, avgBuyPrice = 10.0)
        val holding = candidate(typeId = 1, currentPrice = 8.0, vsAvgPct = -20.0, dailyVolume = 1000, position = held)
        val notHolding = candidate(typeId = 2, currentPrice = 8.0, vsAvgPct = -20.0, dailyVolume = 1000, position = null)

        val result =
            allocateBudget(
                listOf(holding, notHolding),
                totalBudget = 1_000_000.0,
                maxItems = 2,
                100.0,
                30.0,
                ladderLevels = 3,
                ladderStepPct = 5.0,
            )

        result.first { it.candidate.typeId == 1 }.sellTarget!!.qty shouldBe 100
        result.first { it.candidate.typeId == 2 }.sellTarget shouldBe null
    }
}
