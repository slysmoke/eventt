package org.eventt.features.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.eventt.core.database.DatabaseManager
import org.eventt.ui.common.Panel
import org.eventt.ui.theme.DarkColorScheme
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

// Summary-strip totals, plus headless renders to build/analysis-screens/: each tab's filter bar on an
// empty database, and the result tables filled with synthetic rows.
@OptIn(ExperimentalTestApi::class)
class AnalysisScreenTest {
    private val out = File("build/analysis-screens").apply { mkdirs() }

    private fun station(
        id: Int,
        name: String,
        buy: Double,
        sell: Double,
        vol: Long,
    ) = StationOpportunity(
        id,
        name,
        bestSell = sell,
        bestBuy = buy,
        grossProfit = sell - buy,
        netProfit = (sell - buy) * 0.9,
        marginPct = (sell - buy) * 90 / sell,
        roiPct = (sell - buy) * 90 / buy,
        dailyVolume = vol,
        effectiveDailyVol = vol,
        sellOrderCount = 12,
        buyOrderCount = 30,
        estimatedDailyProfit = 0.0,
        priceChange7d = if (id % 2 == 0) 3.4 else -1.2,
    )

    private val stations =
        listOf(
            station(1, "Tritanium", 3.9, 4.6, 2_000_000),
            station(2, "Large Skill Injector", 780e6, 860e6, 40),
            station(3, "Nanite Repair Paste", 9_000.0, 11_500.0, 3_000),
        )

    private val regions =
        listOf(
            RegionOpportunity(
                1,
                "Hornet EC-300",
                "The Forge",
                "Domain",
                9_000.0,
                14_000.0,
                5_000.0,
                3_800.0,
                27.1,
                41.0,
                5.0,
                2_500.0,
                1_000,
                3_000,
                profitableVolume = 400,
                profitableTotalProfit = 1_400_000.0,
                buyVsAvg7dPct = -2.0,
                sellVsAvg7dPct = 14.0,
            ),
            RegionOpportunity(
                2,
                "Mobile Depot",
                "The Forge",
                "Domain",
                600_000.0,
                900_000.0,
                300_000.0,
                210_000.0,
                23.3,
                34.0,
                50.0,
                25_000.0,
                20,
                60,
                profitableVolume = 15,
                profitableTotalProfit = 3_000_000.0,
            ),
        )

    @Test
    fun `totals add up at the suggested quantities`() {
        val s = stationTotals(stations, volCapEnabled = false, volCapPct = 100.0)
        s.count shouldBe 3
        s.dailyProfit shouldBe ((0.7 * 0.9 * 2_000_000) + (80e6 * 0.9 * 40) + (2_500 * 0.9 * 3_000) plusOrMinus 1.0)
        s.best shouldBe "Large Skill Injector"
        s.medianMargin shouldBe (stations[0].marginPct plusOrMinus 1e-9)
        // 15 depots cap the profitable walk; 400 Hornets.
        val r = regionTotals(regions, InterRegionTradeType.SELL_TO_BUY, volCapEnabled = false, volCapPct = 100.0)
        r.dailyProfit shouldBe 4_400_000.0
        r.cargoM3 shouldBe (400 * 5.0 + 15 * 50.0)
        r.capital shouldBe (400 * 11_500.0 + 15 * 625_000.0)
    }

    @Test
    fun `tabs render`() {
        DatabaseManager.close()
        DatabaseManager.initialize(":memory:")
        runDesktopComposeUiTest(1400, 700) {
            setContent { MaterialTheme(colorScheme = DarkColorScheme) { Surface { MarketAnalysisScreen() } } }
            listOf("Station Trading", "Inter-Region", "Long-Term Investment (WIP)").forEachIndexed { i, name ->
                onNodeWithText(name).performClick()
                waitForIdle()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "tab$i.png"))
            }
        }
    }

    @Test
    fun `result tables render`() {
        runDesktopComposeUiTest(1400, 420) {
            setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Surface {
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OpportunityKpis(stationTotals(stations, false, 100.0))
                            Panel("Opportunities", Modifier.fillMaxSize()) {
                                StationHeader(StationSortCol.NET_PROFIT, false) {}
                                stations.forEachIndexed { i, o -> StationRow(o, i, selected = i == 1, isActiveInGame = i == 2) }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "station.png"))
        }
        runDesktopComposeUiTest(1400, 360) {
            setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Surface {
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OpportunityKpis(regionTotals(regions, InterRegionTradeType.SELL_TO_BUY, false, 100.0))
                            Panel("Opportunities", Modifier.fillMaxSize()) {
                                RegionHeader(RegionSortCol.NET_VOL, false) {}
                                regions.forEachIndexed { i, o -> RegionRow(o, i, InterRegionTradeType.SELL_TO_BUY, false) }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "region.png"))
        }
    }

    private fun material(
        id: Int,
        name: String,
        price: Double,
        action: MaterialAction,
        held: MaterialPosition? = null,
        sell: SellTarget? = null,
    ) = AllocatedMaterial(
        MaterialCandidate(
            id,
            name,
            price,
            bestAsk = price * 1.02,
            avgPrice = price * 1.15,
            highPrice = price * 1.4,
            lowPrice = price * 0.9,
            vsAvgPct = -13.0,
            drawdownFromHighPct = -28.6,
            trendPct = -2.1,
            volatilityPct = 6.4,
            dailyVolume = 900_000,
            spikeDetected = id == 2,
            position = held,
            backtest = BacktestResult(14, 3, 11.0, -6.0),
        ),
        allocatedIsk = 25e6,
        toBuyIsk = if (action == MaterialAction.BUY) 18e6 else 0.0,
        ladder = if (action == MaterialAction.BUY) listOf(LadderLevel(1, price * 0.95, 9e6, 1000, false)) else emptyList(),
        sellTarget = sell,
        action = action,
    )

    @Test
    fun `materials table renders`() {
        val rows =
            listOf(
                material(1, "Isogen", 45.0, MaterialAction.BUY),
                material(2, "Nocxium", 380.0, MaterialAction.WAIT, MaterialPosition(40_000, 400.0)),
                material(3, "Zydrine", 900.0, MaterialAction.SELL, MaterialPosition(10_000, 700.0), SellTarget(10_000, 840.0, 1.6e6, 22.0)),
            )
        runDesktopComposeUiTest(1400, 380) {
            setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Surface {
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MaterialsKpis(rows, 100e6)
                            Panel("Candidates", Modifier.fillMaxSize()) {
                                MaterialsHeader(MaterialSortCol.ALLOCATED, false, false, {}, {})
                                rows.forEach { MaterialRow(it, it.action == MaterialAction.BUY, {}, it.candidate.typeId == 3, {}, {}) }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "materials.png"))
        }
    }
}
