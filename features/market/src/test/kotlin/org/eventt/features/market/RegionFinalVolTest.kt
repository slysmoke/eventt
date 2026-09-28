package org.eventt.features.market

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private fun opp(
    dailyVolume: Long = 100,
    dailyVolumeSrc: Long = 100,
    profitableVolume: Long = 50,
    adam4EveBuyLegVol: Long? = null,
    adam4EveSellLegVol: Long? = null,
) = RegionOpportunity(
    typeId = 34,
    typeName = "Tritanium",
    buyRegionName = "The Forge",
    sellRegionName = "Domain",
    buyPrice = 4.0,
    sellPrice = 5.0,
    grossProfit = 1.0,
    netProfit = 1.0,
    marginPct = 20.0,
    roiPct = 25.0,
    itemVolumeM3 = 0.01,
    shippingCostPerUnit = 0.0,
    dailyVolume = dailyVolume,
    dailyVolumeSrc = dailyVolumeSrc,
    profitableVolume = profitableVolume,
    adam4EveBuyLegVol = adam4EveBuyLegVol,
    adam4EveSellLegVol = adam4EveSellLegVol,
)

class RegionFinalVolTest {
    // BUY_TO_SELL: both legs are our own placed orders -- take the min of the ESI-based estimate
    // and whichever real Adam4EVE leg data is present.
    @Test
    fun `BUY_TO_SELL is capped by the tighter of the two real legs`() {
        val withoutAdam4Eve = opp(dailyVolume = 100)
        regionFinalVol(withoutAdam4Eve, InterRegionTradeType.BUY_TO_SELL, volCapEnabled = false, volCapPct = 100.0) shouldBe 100L

        val buyLegBinds = opp(dailyVolume = 100, adam4EveBuyLegVol = 3, adam4EveSellLegVol = 50)
        regionFinalVol(buyLegBinds, InterRegionTradeType.BUY_TO_SELL, volCapEnabled = false, volCapPct = 100.0) shouldBe 3L

        val sellLegBinds = opp(dailyVolume = 100, adam4EveBuyLegVol = 50, adam4EveSellLegVol = 2)
        regionFinalVol(sellLegBinds, InterRegionTradeType.BUY_TO_SELL, volCapEnabled = false, volCapPct = 100.0) shouldBe 2L
    }

    @Test
    fun `SAFE_BUY_TO_SELL follows the same rule as BUY_TO_SELL`() {
        val o = opp(dailyVolume = 100, adam4EveBuyLegVol = 4, adam4EveSellLegVol = 50)
        regionFinalVol(o, InterRegionTradeType.SAFE_BUY_TO_SELL, volCapEnabled = false, volCapPct = 100.0) shouldBe 4L
    }

    // SELL_TO_SELL: buy leg is already walked from the real source order book (profitableVolume);
    // only the sell leg (our own placed sell order at the destination) gets the extra Adam4EVE cap.
    @Test
    fun `SELL_TO_SELL is capped by the walked buy leg AND the real sell-side flow`() {
        val noData = opp(profitableVolume = 50, dailyVolume = 100)
        regionFinalVol(noData, InterRegionTradeType.SELL_TO_SELL, volCapEnabled = false, volCapPct = 100.0) shouldBe 50L

        val thinSellSide = opp(profitableVolume = 50, dailyVolume = 100, adam4EveSellLegVol = 2)
        regionFinalVol(thinSellSide, InterRegionTradeType.SELL_TO_SELL, volCapEnabled = false, volCapPct = 100.0) shouldBe 2L

        // A generous real sell-side flow shouldn't raise the estimate above what was actually walked.
        val healthySellSide = opp(profitableVolume = 50, dailyVolume = 100, adam4EveSellLegVol = 9999)
        regionFinalVol(healthySellSide, InterRegionTradeType.SELL_TO_SELL, volCapEnabled = false, volCapPct = 100.0) shouldBe 50L
    }

    // BUY_TO_BUY: the mirror of SELL_TO_SELL -- sell leg is already walked from the real
    // destination buy book; the buy leg (our own placed buy order at the source) gets the cap.
    @Test
    fun `BUY_TO_BUY is capped by the walked sell leg AND the real buy-side flow`() {
        val thinBuySide = opp(profitableVolume = 50, adam4EveBuyLegVol = 1)
        regionFinalVol(thinBuySide, InterRegionTradeType.BUY_TO_BUY, volCapEnabled = false, volCapPct = 100.0) shouldBe 1L
    }

    // SELL_TO_BUY: both legs are real existing orders already walked together (walkCrossedBook) --
    // no placed-order leg exists, so Adam4EVE data (even if present) never applies here.
    @Test
    fun `SELL_TO_BUY ignores Adam4EVE fields entirely`() {
        val o = opp(profitableVolume = 50, adam4EveBuyLegVol = 1, adam4EveSellLegVol = 1)
        regionFinalVol(o, InterRegionTradeType.SELL_TO_BUY, volCapEnabled = false, volCapPct = 100.0) shouldBe 50L
    }
}
