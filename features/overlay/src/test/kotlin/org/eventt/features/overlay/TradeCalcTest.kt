package org.eventt.features.overlay

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.eventt.core.model.eveUndercutPrice
import org.eventt.ui.theme.DarkColorScheme
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class TradeCalcTest {
    private val fees = Fees(broker = 0.03, tax = 0.08)

    @Test
    fun `a flip pays broker on both orders and tax on the sell`() {
        val f = flip(sell = 120.0, buy = 100.0, fees)
        f.profit shouldBe (120 * 0.89 - 103.0 plusOrMinus 1e-9)
        f.marginPct shouldBe (3.8 / 120 * 100 plusOrMinus 1e-9)
        f.roiPct shouldBe (3.8 / 103 * 100 plusOrMinus 1e-9)
    }

    @Test
    fun `a position breaks even after fees and compares relisting with dumping`() {
        val p = position(cost = 100.0, sell = 120.0, buy = 110.0, fees)
        p.breakEvenOrder shouldBe (100 / 0.89 plusOrMinus 1e-9)
        p.breakEvenInstant shouldBe (100 / 0.92 plusOrMinus 1e-9)
        p.relist!! shouldBe (eveUndercutPrice(120.0) * 0.89 - 100 plusOrMinus 1e-9)
        p.dump!! shouldBe (110 * 0.92 - 100 plusOrMinus 1e-9)
        position(100.0, sell = null, buy = null, fees).relist shouldBe null
    }

    @Test
    fun `wall average takes the closest depth share of orders`() {
        val book = (1..20).map { it.toDouble() to 10L }
        avgTopPrice(book, cheapestFirst = true, depthPct = 5) shouldBe 1.0
        avgTopPrice(book, cheapestFirst = true, depthPct = 10) shouldBe 1.5
        avgTopPrice(book, cheapestFirst = false, depthPct = 10) shouldBe 19.5
    }

    // Writes PNGs to build/trade-calc-screens/ for a look without launching the app.
    @Test
    fun `the calc renders quotes, flip, position, depth and settings`() {
        val sellBook = listOf(5_420_000.0 to 12L, 5_430_000.0 to 40L, 5_600_000.0 to 200L)
        val buyBook = listOf(4_910_000.0 to 30L, 4_800_000.0 to 120L)
        val station = "Jita IV - Moon 4 - Caldari Navy Assembly Plant"
        val settings = CalcSettings(units = "50", autoCopy = AutoCopy.SELL)
        val out = File("build/trade-calc-screens").apply { mkdirs() }
        runDesktopComposeUiTest(420, 760) {
            setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Surface {
                        TradeCalcBody(
                            sell = Quote(5_420_000.0, station, PriceSource.FILE, sellBook),
                            buy = Quote(4_910_000.0, station, PriceSource.FILE, buyBook),
                            fees = fees,
                            holding = Holding(qty = 120, avgCost = 4_700_000.0),
                            itemKnown = true,
                            manifest = Manifest(1.25e9, 7, listOf("Odd Thing")),
                            settings = settings,
                            onSettings = {},
                            onSetSell = {},
                            onSetBuy = {},
                            onCopy = {},
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText("FIFO", substring = true).assertExists()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "trade-calc.png"))
        }
        runDesktopComposeUiTest(420, 520) {
            setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Surface { CalcSettingsPanel(settings.copy(costMode = CostMode.MANUAL, manualCost = "4700000"), fees) {} }
                }
            }
            waitForIdle()
            onNodeWithText("4 700 000").assertExists()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "trade-calc-settings.png"))
        }
    }
}
