package org.eventt.features.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import org.eventt.features.tools.pricing.PricingResult
import org.eventt.features.tools.pricing.PricingTotalsStrip
import org.eventt.features.tools.pricing.ResultsTable
import org.eventt.features.tools.splitter.Split
import org.eventt.features.tools.splitter.SplitCard
import org.eventt.features.tools.splitter.SplitLineItem
import org.eventt.ui.common.Panel
import org.eventt.ui.theme.DarkColorScheme
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class ToolsScreenTest {
    private fun result(
        cost: Double?,
        final: Double?,
        qty: Int,
        undercut: Boolean = false,
    ) = PricingResult(34, "x", qty, cost, null, null, null, final, undercut, salesTaxPct = 5.0, brokerFeePct = 5.0)

    @Test
    fun `pricing totals net fees over known cost only`() {
        // Nets 90% of the list price: 200 → 180 vs 100 cost = +80/unit; 80 → 72 vs 100 = −28/unit.
        val t = pricingTotals(listOf(result(100.0, 200.0, 2), result(100.0, 80.0, 1, undercut = true), result(null, 50.0, 4)))

        t.items shouldBe 3
        t.listValue shouldBe 680.0
        t.cost shouldBe 300.0
        t.netProfit shouldBe (2 * 80.0 - 28.0 plusOrMinus 1e-9)
        t.belowCost shouldBe 1
        t.undercut shouldBe 1
    }

    @Test
    fun `results render`() {
        val results =
            listOf(
                PricingResult(34, "Tritanium", 100_000, 3.9, 6.1, 5.2, 5.19, 5.19, true, 8.0, 3.0),
                PricingResult(587, "Rifter", 12, 410_000.0, 640_000.0, 720_000.0, 719_900.0, 640_000.0, false, 8.0, 3.0),
                PricingResult(29668, "PLEX", 50, 5_100_000.0, 7_900_000.0, 5_000_000.0, 4_999_000.0, 4_999_000.0, true, 8.0, 3.0),
                PricingResult(11399, "Morphite", 300, null, null, 21_000.0, 20_990.0, 20_990.0, true, 8.0, 3.0),
            )
        val split =
            Split(
                1,
                listOf(
                    SplitLineItem(34, "Tritanium", 2_000_000, 4.0, 0.01),
                    SplitLineItem(587, "Rifter", 30, 650_000.0, 2_500.0),
                ),
            )
        runDesktopComposeUiTest(1280, 600) {
            setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Surface {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PricingTotalsStrip(pricingTotals(results))
                            Panel("Results", Modifier.fillMaxWidth().height(160.dp)) { ResultsTable(results) }
                            SplitCard(split, 3.5e9, 320_000.0, null, canPush = true) {}
                        }
                    }
                }
            }
            waitForIdle()
            val out = File("build/tools-screens").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "results.png"))
        }
    }

    @Test
    fun `both tools render empty`() {
        DatabaseManager.close()
        DatabaseManager.initialize(":memory:")
        runDesktopComposeUiTest(1280, 760) {
            setContent { MaterialTheme(colorScheme = DarkColorScheme) { Surface { ToolsScreen(null) } } }
            val out = File("build/tools-screens").apply { mkdirs() }
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "splitter.png"))
            onNodeWithText("Sell Pricing").performClick()
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "pricing.png"))
        }
    }
}
