package org.eve.trader.features.market

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eve.trader.core.database.DatabaseManager
import org.eve.trader.core.database.MarketDao
import org.eve.trader.core.database.StaticDataDao
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.core.model.MarketHistoryModel
import org.eve.trader.core.model.StaticStationModel
import org.eve.trader.ui.common.*

// Trade hub regions
val TRADE_HUBS = listOf(
    10000002 to "The Forge (Jita)",
    10000043 to "Domain (Amarr)",
    10000032 to "Sinq Laison (Dodixie)",
    10000030 to "Metropolis (Hek)",
    10000042 to "Heimatar (Rens)",
)

@Composable
fun MarketBrowserScreen() {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<org.eve.trader.core.model.StaticTypeModel>>(emptyList()) }
    var selectedType by remember { mutableStateOf<org.eve.trader.core.model.StaticTypeModel?>(null) }
    var selectedRegionId by remember { mutableStateOf(10000002) }
    var orderBook by remember { mutableStateOf<Pair<List<MarketOrder>, List<MarketOrder>>>(emptyList<MarketOrder>() to emptyList()) }
    var history by remember { mutableStateOf<List<MarketHistoryModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showOrderBook by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO) { DatabaseManager.initialize() } } catch (e: Exception) {}
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        Text("Market Browser", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(8.dp))

        // Search
        SearchField(
            query = searchQuery,
            onQueryChange = { query ->
                searchQuery = query
                if (query.length >= 2) {
                    scope.launch(Dispatchers.IO) {
                        searchResults = StaticDataDao.searchTypes(query, limit = 20)
                    }
                } else {
                    searchResults = emptyList()
                }
            },
            placeholder = "Search items...",
            modifier = Modifier.fillMaxWidth(0.6f),
        )

        // Search results dropdown
        if (searchResults.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(0.6f).heightIn(max = 250.dp),
            ) {
                LazyColumn {
                    items(searchResults) { type ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedType = type
                                searchQuery = ""
                                searchResults = emptyList()
                                scope.launch { loadMarketData(selectedRegionId, type.typeId, ordersCallback = { orderBook = it }, historyCallback = { history = it }) }
                            }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Extension, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(type.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Region selector
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Region:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(TRADE_HUBS) { (id, name) ->
                    FilterChip(
                        selected = selectedRegionId == id,
                        onClick = {
                            selectedRegionId = id
                            selectedType?.let { type ->
                                scope.launch { loadMarketData(id, type.typeId, ordersCallback = { orderBook = it }, historyCallback = { history = it }) }
                            }
                        },
                        label = { Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Selected type info
        selectedType?.let { type ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(type.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Row {
                    FilterChip(selected = showOrderBook, onClick = { showOrderBook = true }, label = { Text("Orders") })
                    Spacer(modifier = Modifier.width(4.dp))
                    FilterChip(selected = !showOrderBook, onClick = { showOrderBook = false }, label = { Text("History") })
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Content
        if (selectedType == null) {
            EmptyState(
                icon = Icons.Default.Store,
                title = "Select an Item",
                description = "Search for an item to view market data.",
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (showOrderBook) {
            OrderBookView(orderBook, selectedType!!)
        } else {
            HistoryChartView(history)
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Loading market data...")
}

@Composable
private fun OrderBookView(
    orders: Pair<List<MarketOrder>, List<MarketOrder>>,
    type: org.eve.trader.core.model.StaticTypeModel,
) {
    val (sellOrders, buyOrders) = orders

    Row(modifier = Modifier.fillMaxWidth().height(300.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Sell orders (red)
        Column(modifier = Modifier) {
            Text("Sell Orders", style = MaterialTheme.typography.titleMedium, color = Color(0xFFFF6B6B))
            Spacer(modifier = Modifier.height(4.dp))
            OrderTableHeader()
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                sellOrders.take(30).forEach { order ->
                    OrderRow(order, isSell = true)
                }
                if (sellOrders.isEmpty()) {
                    Text("No sell orders", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(8.dp))
                }
            }
        }

        // Buy orders (green)
        Column(modifier = Modifier) {
            Text("Buy Orders", style = MaterialTheme.typography.titleMedium, color = Color(0xFF69DB7C))
            Spacer(modifier = Modifier.height(4.dp))
            OrderTableHeader()
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                buyOrders.take(30).forEach { order ->
                    OrderRow(order, isSell = false)
                }
                if (buyOrders.isEmpty()) {
                    Text("No buy orders", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun OrderTableHeader() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Price", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier)
            Text("Volume", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier)
            Text("Location", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier)
        }
    }
}

@Composable
private fun OrderRow(order: MarketOrder, isSell: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            formatPrice(order.price),
            style = MaterialTheme.typography.bodySmall,
            color = if (isSell) Color(0xFFFF6B6B) else Color(0xFF69DB7C),
            fontWeight = FontWeight.Medium,
            modifier = Modifier,
        )
        Text(order.volumeRemaining.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier)
        Text("Station", style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier)
    }
}

@Composable
private fun HistoryChartView(history: List<MarketHistoryModel>) {
    if (history.isEmpty()) {
        EmptyState(
            icon = Icons.Default.ShowChart,
            title = "No History Data",
            description = "Click refresh to load history.",
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    Column(modifier = Modifier.fillMaxWidth().height(300.dp).verticalScroll(rememberScrollState())) {
        // Price chart
        ContentCard("Average Price") {
            SparklineChart(
                data = history.reversed().map { it.average },
                labels = history.reversed().map { it.date.take(10) },
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Volume chart
        ContentCard("Volume") {
            SparklineChart(
                data = history.reversed().map { it.volume.toDouble() },
                labels = history.reversed().map { it.date.take(10) },
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Stats
        val avgPrice = history.averageOf { it.average }
        val highestPrice = history.maxOfOrNull { it.highest } ?: 0.0
        val lowestPrice = history.minOfOrNull { it.lowest } ?: 0.0
        val totalVolume = history.sumOf { it.volume }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatCard("Avg Price", formatPrice(avgPrice))
            StatCard("Highest", formatPrice(highestPrice))
            StatCard("Lowest", formatPrice(lowestPrice))
            StatCard("Total Vol", formatVolume(totalVolume))
        }
    }
}

@Composable
private fun SparklineChart(
    data: List<Double>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (data.isEmpty()) return

    val maxVal = data.maxOrNull() ?: 0.0
    val minVal = data.minOrNull() ?: 0.0
    val range = maxVal - minVal

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val padding = 8f
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2

        // Draw line
        val path = Path()
        data.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (data.size - 1).coerceAtLeast(1)) * chartWidth
            val normalizedValue = if (range > 0) (value - minVal) / range else 0.5f
            val y = padding + (1 - normalizedValue.toFloat()) * chartHeight

            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2f, cap = StrokeCap.Round),
        )

        // Draw fill
        val lastX = padding + chartWidth
        val fillPath = Path().apply {
            addPath(path)
            lineTo(lastX, height - padding)
            lineTo(padding, height - padding)
            close()
        }
        drawPath(
            path = fillPath,
            color = color.copy(alpha = 0.1f),
        )
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Card(
        modifier = Modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

private data class MarketOrder(
    val price: Double,
    val volumeRemaining: Int,
    val volumeTotal: Int,
    val isBuyOrder: Boolean,
)

private suspend fun loadMarketData(
    regionId: Int,
    typeId: Int,
    ordersCallback: (Pair<List<MarketOrder>, List<MarketOrder>>) -> Unit,
    historyCallback: (List<MarketHistoryModel>) -> Unit,
) {
    // Load history from cache first
    val cachedHistory = MarketDao.getHistory(typeId, regionId)
    if (cachedHistory.isNotEmpty()) {
        historyCallback(cachedHistory)
    }

    // Load orders
    try {
        val rawOrders = EsiClient.getMarketRegionOrders(regionId, typeId = typeId)
        val sellOrders = rawOrders.filter { (it["is_buy_order"] as? Boolean) == false }
            .sortedBy { (it["price"] as? Number)?.toDouble() ?: 0.0 }
            .mapNotNull { raw ->
                MarketOrder(
                    price = (raw["price"] as? Number)?.toDouble() ?: 0.0,
                    volumeRemaining = (raw["volume_remain"] as? Number)?.toInt() ?: 0,
                    volumeTotal = (raw["volume_total"] as? Number)?.toInt() ?: 0,
                    isBuyOrder = false,
                )
            }
        val buyOrders = rawOrders.filter { (it["is_buy_order"] as? Boolean) == true }
            .sortedByDescending { (it["price"] as? Number)?.toDouble() ?: 0.0 }
            .mapNotNull { raw ->
                MarketOrder(
                    price = (raw["price"] as? Number)?.toDouble() ?: 0.0,
                    volumeRemaining = (raw["volume_remain"] as? Number)?.toInt() ?: 0,
                    volumeTotal = (raw["volume_total"] as? Number)?.toInt() ?: 0,
                    isBuyOrder = true,
                )
            }
        ordersCallback(sellOrders to buyOrders)
    } catch (e: Exception) {
        println("Error loading orders: ${e.message}")
    }

    // Fetch and save history
    try {
        val rawHistory = EsiClient.getMarketRegionHistory(regionId, typeId)
        val models = rawHistory.mapNotNull { raw ->
            val date = raw["date"] as? String ?: return@mapNotNull null
            val avg = (raw["average"] as? Number)?.toDouble() ?: 0.0
            MarketHistoryModel(
                typeId = typeId,
                regionId = regionId,
                date = date,
                average = avg,
                volume = (raw["volume"] as? Number)?.toLong() ?: 0,
                orderCount = (raw["order_count"] as? Number)?.toLong() ?: 0,
                highest = (raw["highest"] as? Number)?.toDouble() ?: 0.0,
                lowest = (raw["lowest"] as? Number)?.toDouble() ?: 0.0,
            )
        }
        models.forEach { MarketDao.insertHistory(it) }
        historyCallback(models)
    } catch (e: Exception) {
        println("Error loading history: ${e.message}")
    }
}

private fun formatPrice(price: Double): String {
    return String.format("%,.2f", price)
}

private fun formatVolume(vol: Long): String {
    return when {
        vol >= 1_000_000_000 -> String.format("%.1fB", vol / 1_000_000_000.0)
        vol >= 1_000_000 -> String.format("%.1fM", vol / 1_000_000.0)
        vol >= 1_000 -> String.format("%.1fK", vol / 1_000.0)
        else -> vol.toString()
    }
}

private fun <T> List<T>.averageOf(selector: (T) -> Double): Double {
    if (isEmpty()) return 0.0
    return sumOf(selector) / size
}
