package org.eventt.features.market

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.eventt.core.database.DatabaseManager
import org.eventt.core.model.MarketHistoryModel
import org.eventt.core.model.StaticTypeModel
import org.eventt.ui.theme.DarkColorScheme
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import javax.imageio.ImageIO

// Book stacking and market stats from synthetic orders, plus a headless render of the body written
// to build/market-screens/market.png for a look without launching the app.
@OptIn(ExperimentalTestApi::class)
class MarketBodyTest {
    private val today = LocalDate.parse("2026-10-24")

    private fun order(
        id: Long,
        price: Double,
        qty: Int,
        buy: Boolean,
        location: Long = 60003760L,
    ) = MarketOrder(
        id,
        price,
        qty,
        qty * 2,
        1,
        buy,
        location,
        if (location == 60003760L) "Jita IV - Moon 4 - Caldari Navy Assembly Plant" else "Perimeter - Tranquility Trading Tower",
        30000142,
        Instant.now().minusSeconds(86400).toString(),
        90,
        if (buy) "region" else "station",
    )

    private val sells = (0 until 12).map { order(100L + it, 105.0 + it, 1000 * (it + 1), false, if (it % 4 == 3) 1L else 60003760L) }
    private val buys = (0 until 12).map { order(200L + it, 100.0 - it, 800 * (it + 1), true) }
    private val history =
        (0 until 60).map {
            MarketHistoryModel(
                typeId = 34,
                regionId = 10000002,
                date = today.minusDays(it.toLong()).toString(),
                average = 100.0 + it % 5,
                volume = 50_000,
                orderCount = 900,
                highest = 110.0,
                lowest = 95.0 - it % 3,
            )
        }

    @Test
    fun `book stacks volume from the spread`() {
        val rows = stack(buys.reversed(), isBuy = true)
        rows.first().o.price shouldBe 100.0
        rows.take(3).map { it.cumulative } shouldBe listOf(800L, 2400L, 4800L)
        stack(sells, isBuy = false).first().o.price shouldBe 105.0
    }

    @Test
    fun `stats cover the book and the last 30 days`() {
        val s = marketStats(sells, buys, history, today)
        s.bestAsk shouldBe 105.0
        s.bestBid shouldBe 100.0
        s.spreadPct shouldBe 100.0 * 5 / 105
        s.askOrders shouldBe 12
        s.bidVolume shouldBe 800L * 78
        // 31 history days fall on or after the cutoff (today − 30).
        s.volPerDay30 shouldBe 31 * 50_000 / 30.0
        s.high30 shouldBe 110.0
        s.low30 shouldBe 93.0
    }

    @Test
    fun `body renders`() {
        DatabaseManager.close()
        DatabaseManager.initialize(":memory:")
        runDesktopComposeUiTest(1160, 760) {
            setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Surface { MarketBody(StaticTypeModel(34, "Tritanium", 18, 4), HUBS.first(), sells to buys, history, today = today) {} }
                }
            }
            waitForIdle()
            onAllNodesWithText("Jita IV - Moon 4", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() shouldBe true
            val out = File("build/market-screens").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "market.png"))
        }
    }
}
