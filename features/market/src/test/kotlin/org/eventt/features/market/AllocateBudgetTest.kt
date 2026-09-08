package org.eventt.features.market

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

private fun candidate(
    typeId: Int,
    currentPrice: Double,
    vsAvgPct: Double,
    dailyVolume: Long,
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
}
