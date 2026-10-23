package org.eventt.features.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.eventt.core.database.ActiveOrderDao.ActiveOrderRecord
import org.eventt.features.orders.CostBasisService
import org.eventt.ui.theme.DarkColorScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.math.sin

// Renders the dashboard body headless from a synthetic snapshot: every tab draws, headers sort,
// and a PNG per tab lands in build/dashboard-screens/ for a look without launching the app.
@OptIn(ExperimentalTestApi::class)
class DashboardBodyTest {
    private val today = LocalDate.now()

    private fun snapshot(): Snapshot {
        val days =
            (89 downTo 0).map { off ->
                val i = 89 - off
                Day(
                    day = today.minusDays(off.toLong()).toString(),
                    wallet = 300e9 + 20e9 * sin(i / 9.0),
                    escrow = 90e9 + 5e9 * sin(i / 5.0),
                    stock = 380e9 + i * 0.3e9,
                    realized = if (i % 3 == 0) 1.5e9 * sin(i / 4.0) + 0.6e9 else 0.0,
                    items = 450e9 + i * 0.2e9,
                )
            }
        val sells =
            days.filter { it.realized != 0.0 }.mapIndexed { i, d ->
                CostBasisService.RealizedSellTx(
                    "${d.day}T12:00:00",
                    34 + i % 4,
                    1000,
                    120.0,
                    100.0,
                    d.realized,
                    20.0,
                    heldDays =
                        3.0 + i % 9,
                )
            }
        val inventory =
            (0 until 6).associate { i ->
                val id = 34 + i
                id to
                    CostBasisService.InventoryItem(
                        id,
                        "Item $id",
                        1000 * (i + 1),
                        100.0 + i,
                        100.0 * 1000 * (i + 1),
                        oldestLotDate =
                            today.minusDays(i * 7L).toString(),
                    )
            }
        val fifo = CostBasisService.FifoResult(inventory, sells, CostBasisService.TaxConfig())
        val orders =
            listOf(true, false, true).mapIndexed { i, buy ->
                OrderRow(
                    ActiveOrderRecord(
                        orderId = i.toLong(),
                        typeId = 34 + i,
                        typeName = "Item ${34 + i}",
                        locationId = 60003760L,
                        regionId = 10000002,
                        stationName = "Jita",
                        price = 100.0 + i,
                        volumeTotal = 1000,
                        volumeRemaining = 500,
                        isBuyOrder = buy,
                        duration = 90,
                        issued = "2026-01-01T00:00:00Z",
                        state = "active",
                        issuedByCharId = null,
                        characterId = 1,
                        corporationId = null,
                    ),
                    best = 101.0 + i,
                    outbid = i == 0,
                    expiresIn = Duration.ofHours(if (i == 1) 20 else 400),
                )
            }
        val txs =
            sells.map {
                mapOf(
                    "date" to it.date,
                    "type_id" to it.typeId,
                    "type_name" to "Item ${it.typeId}",
                    "quantity" to it.qty,
                    "unit_price" to it.sellPrice,
                    "total" to it.sellPrice * it.qty,
                    "is_buy" to false,
                    "client_name" to "Client ${it.typeId % 3}",
                )
            }
        return Snapshot(
            days,
            fifo,
            emptyMap(),
            txs,
            orders,
            (34..40).associateWith { "Item $it" },
            mapOf(
                34 to "Mineral",
                35 to "Mineral",
                36 to "Ice Product",
            ),
        )
    }

    @Test
    fun `every tab renders, headers sort, and a chart click filters trades by day`() =
        runComposeUiTest {
            val s = snapshot()
            var tab by mutableStateOf(Tab.POSITIONS)
            setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Surface {
                        Box(Modifier.size(1400.dp, 860.dp)) {
                            DashboardBody(
                                s,
                                prices = (34..39).associateWith { 115.0 },
                                range = Range.M3,
                                stacked = false,
                                tab = tab,
                                onTab = { tab = it },
                                onOpenItem = { _, _ -> },
                                onOpenOrder = {},
                            )
                        }
                    }
                }
            }
            val out = File("build/dashboard-screens").apply { mkdirs() }
            Tab.entries.forEach { t ->
                tab = t
                waitForIdle()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "${t.name.lowercase()}.png"))
            }
            tab = Tab.POSITIONS
            waitForIdle()
            // Click "Item" to sort by name instead of the default column.
            onNodeWithText("Item", substring = false, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Item ▼", useUnmergedTree = true).assertExists()

            // A plain click inside the chart jumps to Trades, filtered to the clicked day.
            onRoot().performMouseInput { click(Offset(300f, 250f)) }
            waitForIdle()
            assertEquals(Tab.TRADES, tab)
            onNodeWithText("Day ", substring = true, useUnmergedTree = true).assertExists()
        }
}
