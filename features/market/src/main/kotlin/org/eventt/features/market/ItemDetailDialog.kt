package org.eventt.features.market

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.database.MarketDao
import org.eventt.core.database.WalletDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.MarketHistoryModel
import org.eventt.core.model.PLEX_MARKET_REGION_ID
import org.eventt.core.model.PLEX_TYPE_ID
import org.eventt.ui.common.ContentCard
import org.eventt.ui.common.EmptyState
import org.eventt.ui.common.formatIsk
import org.eventt.ui.common.formatPriceAbbr
import org.eventt.ui.common.formatVolume
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor

private const val CHART_DAYS = 30

// Always hits ESI directly (which has its own response-level cache, so this doesn't spam the
// network) rather than reusing MarketAnalysisCompute's fetchHistory — that one prefers whatever's
// already in the local market_history table over re-fetching, which is right for a bulk region
// scan (thousands of items, avoid re-hitting ESI for each) but wrong here: this dialog only ever
// makes one history call per region, and preferring stale/partial cached rows meant the two
// regions' series could desync in date coverage, making the price/spread lines look scattered.
// Falls back to whatever's cached only if the live call itself fails (e.g. no network).
private fun fetchLiveHistory(
    typeId: Int,
    regionId: Int,
): List<MarketHistoryModel> {
    val effectiveRegionId = if (typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else regionId
    return try {
        EsiClient.getMarketRegionHistory(effectiveRegionId, typeId).mapNotNull { entry ->
            val date = entry["date"] as? String ?: return@mapNotNull null
            MarketHistoryModel(
                typeId = typeId,
                regionId = effectiveRegionId,
                date = date,
                average = (entry["average"] as? Number)?.toDouble() ?: 0.0,
                volume = (entry["volume"] as? Number)?.toLong() ?: 0L,
                orderCount = (entry["order_count"] as? Number)?.toLong() ?: 0L,
                highest = (entry["highest"] as? Number)?.toDouble() ?: 0.0,
                lowest = (entry["lowest"] as? Number)?.toDouble() ?: 0.0,
            )
        }
    } catch (_: Exception) {
        MarketDao.getHistory(typeId, effectiveRegionId, CHART_DAYS)
    }
}

/**
 * Everything useful for "should I trade this item right now," shared by Station Trading and
 * Inter-Region: price history for one or two regions (plus their spread), the live order book on
 * each side, and a quick glance at whether/how much of it you're already holding or have traded.
 * [secondaryRegionId] is null for Station Trading (a single region/station); Inter-Region always
 * passes both sides of its route.
 */
@Composable
internal fun ItemDetailDialog(
    typeId: Int,
    typeName: String,
    primaryRegionId: Int,
    primaryRegionName: String,
    primaryStationId: Long?,
    secondaryRegionId: Int? = null,
    secondaryRegionName: String? = null,
    secondaryStationId: Long? = null,
    charId: Int?,
    onDismiss: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(true) }
    var primaryOrders by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var secondaryOrders by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var primaryHistory by remember { mutableStateOf<List<MarketHistoryModel>>(emptyList()) }
    var secondaryHistory by remember { mutableStateOf<List<MarketHistoryModel>>(emptyList()) }
    var position by remember { mutableStateOf<MyPosition?>(null) }

    LaunchedEffect(typeId, primaryRegionId, secondaryRegionId) {
        isLoading = true
        withContext(Dispatchers.IO) {
            primaryOrders = runCatching { EsiClient.getMarketRegionOrders(primaryRegionId, typeId = typeId) }.getOrDefault(emptyList())
            primaryHistory = fetchLiveHistory(typeId, primaryRegionId)
            if (secondaryRegionId != null) {
                secondaryOrders =
                    runCatching { EsiClient.getMarketRegionOrders(secondaryRegionId, typeId = typeId) }.getOrDefault(emptyList())
                secondaryHistory = fetchLiveHistory(typeId, secondaryRegionId)
            } else {
                secondaryOrders = emptyList()
                secondaryHistory = emptyList()
            }
            position = charId?.let { computeMyPosition(it, typeId) }
        }
        isLoading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.99f).fillMaxHeight(0.94f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(typeName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }
                Spacer(Modifier.height(12.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ContentCard("Price — ${CHART_DAYS}d") {
                            DualRegionPriceChart(
                                primaryHistory,
                                primaryRegionName,
                                secondaryHistory,
                                secondaryRegionName,
                                modifier = Modifier.fillMaxWidth().height(220.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OrderBookCard(
                                primaryRegionName,
                                primaryOrders,
                                primaryStationId,
                                primaryHistory,
                                modifier = Modifier.weight(1f),
                            )
                            if (secondaryRegionId != null) {
                                OrderBookCard(
                                    secondaryRegionName ?: "",
                                    secondaryOrders,
                                    secondaryStationId,
                                    secondaryHistory,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (charId != null) {
                            Spacer(Modifier.height(12.dp))
                            MyPositionCard(position)
                        }
                    }
                }
            }
        }
    }
}

// ─── My position (average-cost, not FIFO — a quick glance, not the precise cost basis the
// Orders tab computes) ──────────────────────────────────────────────────────────────────────

private data class MyPosition(
    val qtyHeld: Long,
    val avgBuyPrice: Double?,
    val timesSold: Int,
    val avgSellPrice: Double?,
    val realizedPnl: Double?,
)

private fun computeMyPosition(
    charId: Int,
    typeId: Int,
): MyPosition {
    val txs = WalletDao.getAllTransactions(characterId = charId).filter { it.typeId == typeId }
    val bought = txs.filter { it.isBuy }
    val sold = txs.filter { !it.isBuy }
    val boughtQty = bought.sumOf { it.quantity }
    val soldQty = sold.sumOf { it.quantity }
    val avgBuy = if (boughtQty > 0) bought.sumOf { it.unitPrice * it.quantity } / boughtQty else null
    val avgSell = if (soldQty > 0) sold.sumOf { it.unitPrice * it.quantity } / soldQty else null
    val realized = if (avgBuy != null && soldQty > 0) (avgSell!! - avgBuy) * soldQty else null
    return MyPosition(
        qtyHeld = (boughtQty - soldQty).toLong(),
        avgBuyPrice = avgBuy,
        timesSold = sold.size,
        avgSellPrice = avgSell,
        realizedPnl = realized,
    )
}

@Composable
private fun MyPositionCard(position: MyPosition?) {
    ContentCard("My Position") {
        if (position == null || (position.qtyHeld <= 0 && position.timesSold == 0)) {
            Text(
                "No trading history for this item.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Held", formatVolume(position.qtyHeld), Modifier.weight(1f))
                StatCard("Avg Buy", position.avgBuyPrice?.let { formatIsk(it) } ?: "—", Modifier.weight(1f))
                StatCard("# Sold", position.timesSold.toString(), Modifier.weight(1f))
                StatCard(
                    "Realized*",
                    position.realizedPnl?.let { formatIsk(it) } ?: "—",
                    Modifier.weight(1f),
                    valueColor =
                        position.realizedPnl?.let { if (it >= 0) positiveColor else negativeColor }
                            ?: Color.Unspecified,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "* average-cost estimate, not FIFO — the exact cost basis the Orders tab computes.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

// ─── Order book + volume/competition snapshot for one side ────────────────────────────────

@Composable
private fun OrderBookCard(
    label: String,
    orders: List<Map<String, Any?>>,
    stationId: Long?,
    history: List<MarketHistoryModel>,
    modifier: Modifier = Modifier,
) {
    fun Map<String, Any?>.price() = (get("price") as? Number)?.toDouble() ?: 0.0

    fun Map<String, Any?>.isBuyOrd() = get("is_buy_order") as? Boolean == true

    fun Map<String, Any?>.loc() = (get("location_id") as? Number)?.toLong()

    fun Map<String, Any?>.vol() = (get("volume_remain") as? Number)?.toLong() ?: 0L

    val filtered = if (stationId != null) orders.filter { it.loc() == stationId } else orders
    val sellOrders = filtered.filter { !it.isBuyOrd() }.sortedBy { it.price() }
    val buyOrders = filtered.filter { it.isBuyOrd() }.sortedByDescending { it.price() }
    val vol7d = medianDailyVolume(history, windowDays = 7)
    val vol30d = medianDailyVolume(history, windowDays = 30)

    ContentCard(label, modifier = modifier) {
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Sell Ords", sellOrders.size.toString(), Modifier.weight(1f))
                StatCard("Buy Ords", buyOrders.size.toString(), Modifier.weight(1f))
                StatCard("Vol 7d", formatVolume(vol7d), Modifier.weight(1f))
                StatCard("Vol 30d", formatVolume(vol30d), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Top Sell", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = negativeColor)
                    if (sellOrders.isEmpty()) {
                        Text("—", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    } else {
                        sellOrders.take(5).forEach { o ->
                            Text("${formatIsk(o.price())}  ×${o.vol()}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Top Buy", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = positiveColor)
                    if (buyOrders.isEmpty()) {
                        Text("—", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    } else {
                        buyOrders.take(5).forEach { o ->
                            Text("${formatIsk(o.price())}  ×${o.vol()}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

// ─── Price chart: one line per region, plus the spread between them on a right-hand % axis ──

@Composable
private fun DualRegionPriceChart(
    primaryHistory: List<MarketHistoryModel>,
    primaryLabel: String,
    secondaryHistory: List<MarketHistoryModel>,
    secondaryLabel: String?,
    modifier: Modifier = Modifier,
) {
    val today = java.time.LocalDate.now()
    val days = remember { (CHART_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()).toString() } }
    val primaryByDate = remember(primaryHistory) { primaryHistory.associate { it.date.take(10) to it.average } }
    val secondaryByDate = remember(secondaryHistory) { secondaryHistory.associate { it.date.take(10) to it.average } }
    val primarySeries = remember(primaryByDate) { days.map { primaryByDate[it] } }
    val secondarySeries = remember(secondaryByDate) { days.map { secondaryByDate[it] } }
    val spreadSeries =
        remember(primarySeries, secondarySeries) {
            primarySeries.indices.map { i ->
                val p = primarySeries[i]
                val s = secondarySeries[i]
                if (p != null && s != null && p > 0) (s - p) / p * 100.0 else null
            }
        }

    if (primarySeries.all { it == null } && secondarySeries.all { it == null }) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.ShowChart,
            title = "No Price History",
            description = "No market history yet for this item.",
        )
        return
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.tertiary
    // Fixed rather than a theme role: warningColor/primary render as near-identical orange in the
    // EVE theme, making the spread line indistinguishable from the primary-region price line.
    val spreadColor = Color(0xFFEC407A)
    val labelColor = Color(0xFF777777)
    val textMeasurer = rememberTextMeasurer()

    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(primaryColor, primaryLabel)
            if (secondaryLabel != null) {
                LegendDot(secondaryColor, secondaryLabel)
                LegendDot(spreadColor, "Spread %")
            }
        }
        Spacer(Modifier.height(6.dp))
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val lPad = 56.dp.toPx()
            val rPad = if (secondaryLabel != null) 44.dp.toPx() else 8.dp.toPx()
            val tPad = 8.dp.toPx()
            val bPad = 8.dp.toPx()
            val chartW = size.width - lPad - rPad
            val chartH = size.height - tPad - bPad
            if (chartW <= 0 || chartH <= 0) return@Canvas

            val priceValues = (primarySeries + secondarySeries).filterNotNull()
            if (priceValues.isEmpty()) return@Canvas
            val priceMin = priceValues.min() * 0.98
            val priceMax = priceValues.max() * 1.02
            val priceRange = (priceMax - priceMin).coerceAtLeast(1e-9)

            fun yForPrice(v: Double) = tPad + (1f - ((v - priceMin) / priceRange)).toFloat() * chartH

            fun xFor(i: Int) = lPad + i.toFloat() / (days.size - 1).coerceAtLeast(1) * chartW

            // One continuous curve across every real (non-null) point, connecting straight over a
            // no-trade day rather than breaking the line there — a thin item's gaps otherwise read
            // as scattered, disconnected dots instead of a readable trend. Consecutive points are
            // joined with a cubic bezier (control points at the horizontal midpoint) rather than a
            // straight lineTo, which is what actually makes the line a smooth curve instead of a
            // jagged zig-zag; round caps/joins alone only softens the corners of straight segments.
            fun drawSeries(
                series: List<Double?>,
                color: Color,
                yOf: (Double) -> Float,
            ) {
                val points = series.mapIndexedNotNull { i, v -> v?.let { Offset(xFor(i), yOf(it)) } }
                if (points.isEmpty()) return
                val path =
                    Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            val p0 = points[i - 1]
                            val p1 = points[i]
                            val midX = (p0.x + p1.x) / 2f
                            cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                        }
                    }
                drawPath(
                    path,
                    color,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                points.forEach { drawCircle(color, radius = 2.dp.toPx(), center = it) }
            }

            drawSeries(primarySeries, primaryColor) { yForPrice(it) }
            if (secondaryLabel != null) {
                drawSeries(secondarySeries, secondaryColor) { yForPrice(it) }

                val spreadValues = spreadSeries.filterNotNull()
                if (spreadValues.isNotEmpty()) {
                    val spreadMin = minOf(spreadValues.min(), 0.0)
                    val spreadMax = maxOf(spreadValues.max(), 0.0)
                    val spreadRange = (spreadMax - spreadMin).coerceAtLeast(1e-9)

                    fun yForSpread(v: Double) = tPad + (1f - ((v - spreadMin) / spreadRange)).toFloat() * chartH
                    drawSeries(spreadSeries, spreadColor) { yForSpread(it) }

                    val rLabelX = size.width - rPad + 4.dp.toPx()
                    val lmMax = textMeasurer.measure("%.0f%%".format(spreadMax), TextStyle(fontSize = 9.sp, color = labelColor))
                    drawText(lmMax, topLeft = Offset(rLabelX, tPad))
                    val lmMin = textMeasurer.measure("%.0f%%".format(spreadMin), TextStyle(fontSize = 9.sp, color = labelColor))
                    drawText(lmMin, topLeft = Offset(rLabelX, tPad + chartH - lmMin.size.height))
                }
            }

            val lmMax = textMeasurer.measure(formatPriceAbbr(priceMax), TextStyle(fontSize = 9.sp, color = labelColor))
            drawText(lmMax, topLeft = Offset(lPad - lmMax.size.width - 6.dp.toPx(), tPad))
            val lmMin = textMeasurer.measure(formatPriceAbbr(priceMin), TextStyle(fontSize = 9.sp, color = labelColor))
            drawText(lmMin, topLeft = Offset(lPad - lmMin.size.width - 6.dp.toPx(), tPad + chartH - lmMin.size.height))
        }
    }
}

@Composable
private fun LegendDot(
    color: Color,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}
