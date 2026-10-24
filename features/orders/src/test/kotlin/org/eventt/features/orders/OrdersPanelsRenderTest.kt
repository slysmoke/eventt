package org.eventt.features.orders

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.eventt.core.database.DatabaseManager
import org.eventt.ui.common.Panel
import org.eventt.ui.common.TabBar
import org.eventt.ui.common.TabItem
import org.eventt.ui.theme.DarkColorScheme
import org.junit.jupiter.api.Test
import java.io.File
import java.time.OffsetDateTime
import javax.imageio.ImageIO

// Draws the Buy tab — tabs, KPI strip and table — headless from synthetic orders (one
// outbid, one standing far under the bid, one on top) and writes build/orders-screens/buy.png.
@OptIn(ExperimentalTestApi::class)
class OrdersPanelsRenderTest {
    private fun order(
        id: Long,
        name: String,
        price: Double,
    ) = CharacterOrder(
        orderId = id,
        typeId = 34 + id.toInt(),
        typeName = name,
        locationId = 60003760L,
        regionId = 10000002,
        stationName = "Jita IV - Moon 4 - Caldari Navy Assembly Plant",
        price = price,
        volumeTotal = 1000,
        volumeRemaining = 600,
        isBuyOrder = true,
        duration = 90,
        issued = OffsetDateTime.now().minusDays(3).toString(),
        state = "active",
    )

    @Test
    fun `buy tab pieces render and tell outbid from standing`() {
        DatabaseManager.close()
        DatabaseManager.initialize(":memory:")
        val orders = listOf(order(1, "Outbid Item", 99.0), order(2, "Dip Item", 80.0), order(3, "Top Item", 100.0))
        val comps =
            mapOf(
                (35 to 60003760L) to MarketComparison(null, 100.0),
                (36 to 60003760L) to MarketComparison(null, 100.0),
                (37 to 60003760L) to MarketComparison(null, 99.5),
            )
        val tax = CostBasisService.TaxConfig()
        runDesktopComposeUiTest(1440, 700) {
            setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Surface {
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TabBar(
                                listOf(
                                    TabItem("Sell orders", 11, SELL_COLOR),
                                    TabItem("Buy orders", 3, BUY_COLOR),
                                    TabItem("History", 120),
                                    TabItem("Inventory", 49),
                                ),
                                1,
                                {
                                },
                            )
                            OrdersSummaryBar(orders, null, tax, comps)
                            Panel(null, Modifier.fillMaxWidth().weight(1f)) {
                                BuyOrdersTable(
                                    orders,
                                    SortCol.DISTANCE,
                                    SortDir.DESC,
                                    {},
                                    tax,
                                    comps,
                                    emptyMap(),
                                    false,
                                    5.0,
                                    orders[0].orderId,
                                    null,
                                    {},
                                    {},
                                )
                            }
                        }
                    }
                }
            }
            waitForIdle()
            onAllNodesWithText("+1.0%", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() shouldBe true
            val out = File("build/orders-screens").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "buy.png"))
        }
    }
}
