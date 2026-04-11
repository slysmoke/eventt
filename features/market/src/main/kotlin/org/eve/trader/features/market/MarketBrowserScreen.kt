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
import androidx.compose.material.icons.automirrored.filled.ShowChart
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eve.trader.core.database.MarketDao
import org.eve.trader.core.database.AlertDao
import org.eve.trader.core.database.StaticDataDao
import org.eve.trader.core.database.WatchlistDao
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.core.image.EveImageServer
import org.eve.trader.core.staticdata.StaticDataImporter
import org.eve.trader.core.model.MarketHistoryModel
import org.eve.trader.core.model.PriceAlertModel
import org.eve.trader.core.model.StaticMarketGroupModel
import org.eve.trader.core.model.StaticTypeModel
import org.eve.trader.core.model.WatchlistEntryModel
import org.eve.trader.ui.common.*

// Trade hub regions
val TRADE_HUBS = listOf(
    10000002 to "The Forge (Jita)",
    10000043 to "Domain (Amarr)",
    10000032 to "Sinq Laison (Dodixie)",
    10000030 to "Metropolis (Hek)",
    10000042 to "Heimatar (Rens)",
)

// View modes for market browser
sealed class MarketView {
    data object Tree : MarketView()
    data class Group(val groupId: Int, val name: String) : MarketView()
    data class Type(val type: StaticTypeModel) : MarketView()
}

@Composable
fun MarketBrowserScreen() {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<StaticTypeModel>>(emptyList()) }
    var selectedType by remember { mutableStateOf<StaticTypeModel?>(null) }
    var selectedRegionId by remember { mutableStateOf(10000002) }
    var orderBook by remember { mutableStateOf<Pair<List<MarketOrder>, List<MarketOrder>>>(emptyList<MarketOrder>() to emptyList()) }
    var history by remember { mutableStateOf<List<MarketHistoryModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showOrderBook by remember { mutableStateOf(true) }
    var expandedGroups by remember { mutableStateOf<MutableSet<Int>>(mutableSetOf()) }
    var currentMarketGroup by remember { mutableStateOf<StaticMarketGroupModel?>(null) }
    var groupTypes by remember { mutableStateOf<List<StaticTypeModel>>(emptyList()) }
    var childGroups by remember { mutableStateOf<List<StaticMarketGroupModel>>(emptyList()) }
    var isSdeImporting by remember { mutableStateOf(false) }
    var sdeImportProgress by remember { mutableStateOf("") }
    var showAddToWatchlist by remember { mutableStateOf(false) }
    var showAddToAlert by remember { mutableStateOf(false) }

    // Load top-level market groups on start
    LaunchedEffect(Unit) {
        loadTopMarketGroups(expanded = expandedGroups) { groups ->
            childGroups = groups
        }
        // After initial load, check if SDE needs importing
        if (childGroups.isEmpty()) {
            val typeCount = StaticDataDao.countTypes()
            if (typeCount == 0) {
                // SDE not imported — trigger import
                isSdeImporting = true
                StaticDataImporter.importAll()
                isSdeImporting = false
                // Reload after import
                loadTopMarketGroups(expanded = expandedGroups) { reloadedGroups ->
                    childGroups = reloadedGroups
                }
                // If still empty after import, show message
                if (childGroups.isEmpty()) {
                    val typeCount2 = StaticDataDao.countTypes()
                    println("[Market] After import: types=$typeCount2, marketGroups=${StaticDataDao.getTopMarketGroups().size}")
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ─── Top Bar ──────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Market Browser", style = MaterialTheme.typography.headlineMedium)

                    // Region selector
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Region:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 6.dp))
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
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Search
                SearchField(
                    query = searchQuery,
                    onQueryChange = { query ->
                        searchQuery = query
                        if (query.length >= 2) {
                            scope.launch(Dispatchers.IO) {
                                searchResults = StaticDataDao.searchMarketTypes(query, limit = 30)
                            }
                        } else {
                            searchResults = emptyList()
                        }
                    },
                    placeholder = "Search items...",
                    modifier = Modifier.fillMaxWidth(0.5f),
                )

                // Search results dropdown
                if (searchResults.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.5f).heightIn(max = 250.dp),
                    ) {
                        LazyColumn {
                            items(searchResults) { type ->
                                SearchRow(
                                    type = type,
                                    onClick = {
                                        selectedType = type
                                        currentMarketGroup = null
                                        searchQuery = ""
                                        searchResults = emptyList()
                                        scope.launch { loadMarketData(selectedRegionId, type.typeId, ordersCallback = { orderBook = it }, historyCallback = { history = it }) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ─── Main Content ─────────────────────────────────────────
        Row(modifier = Modifier.fillMaxSize()) {
            // Sidebar: Market Group Tree
            Surface(
                modifier = Modifier.width(260.dp).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ) {
                Column(modifier = Modifier.padding(4.dp)) {
                    Text("Categories", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    MarketGroupTree(
                        groups = childGroups,
                        expandedGroups = expandedGroups,
                        onToggleExpand = { groupId ->
                            if (expandedGroups.contains(groupId)) {
                                expandedGroups.remove(groupId)
                            } else {
                                expandedGroups.add(groupId)
                            }
                            // Reload children
                            val topGroups = StaticDataDao.getTopMarketGroups()
                            val allGroups = buildExpandedTree(topGroups, expandedGroups)
                            childGroups = allGroups
                        },
                        onGroupClick = { group ->
                            currentMarketGroup = group
                            selectedType = null
                            scope.launch(Dispatchers.IO) {
                                val types = StaticDataDao.getTypesByMarketGroup(group.marketGroupId, limit = 500)
                                val children = StaticDataDao.getChildMarketGroups(group.marketGroupId)
                                withContext(Dispatchers.Main) {
                                    groupTypes = types
                                    childGroups = children
                                }
                            }
                        },
                        onTypeClick = { type ->
                            selectedType = type
                            currentMarketGroup = null
                            scope.launch { loadMarketData(selectedRegionId, type.typeId, ordersCallback = { orderBook = it }, historyCallback = { history = it }) }
                        },
                    )
                }
            }

            // Main panel
            Surface(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                    when {
                        selectedType != null -> {
                            // Show market data for selected type
                            TypeMarketHeader(
                                type = selectedType!!,
                                orderBook = orderBook,
                                showOrderBook = showOrderBook,
                                onToggleView = { showOrderBook = !showOrderBook },
                                onAddToWatchlist = { showAddToWatchlist = true },
                                onAddToAlert = { showAddToAlert = true },
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            if (showOrderBook) {
                                OrderBookView(orderBook)
                            } else {
                                HistoryChartView(history, selectedType!!)
                            }
                        }
                        currentMarketGroup != null && groupTypes.isNotEmpty() -> {
                            // Show types in current market group
                            Text(currentMarketGroup!!.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyColumn {
                                items(groupTypes) { type ->
                                    TypeRow(
                                        type = type,
                                        onClick = {
                                            selectedType = type
                                            currentMarketGroup = null
                                            scope.launch { loadMarketData(selectedRegionId, type.typeId, ordersCallback = { orderBook = it }, historyCallback = { history = it }) }
                                        },
                                    )
                                }
                            }
                        }
                        else -> {
                            EmptyState(
                                icon = Icons.Default.Store,
                                title = "Browse the Market",
                                description = "Select a category from the sidebar or search for an item.",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Loading market data...")

    // SDE Import overlay
    if (isSdeImporting) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.padding(32.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Downloading,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Importing SDE Data", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Downloading EVE Static Data Export…\nThis may take a minute.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    CircularProgressIndicator()
                }
            }
        }
    }

    // Add to Watchlist dialog
    if (showAddToWatchlist && selectedType != null) {
        AddToWatchlistDialog(
            type = selectedType!!,
            onDismiss = { showAddToWatchlist = false },
            onAdded = { showAddToWatchlist = false },
        )
    }

    // Add to Alert dialog
    if (showAddToAlert && selectedType != null) {
        AddToAlertDialog(
            type = selectedType!!,
            orderBook = orderBook,
            onDismiss = { showAddToAlert = false },
            onAdded = { showAddToAlert = false },
        )
    }
}

// ─── Market Group Tree ────────────────────────────────────────────────────

@Composable
private fun MarketGroupTree(
    groups: List<StaticMarketGroupModel>,
    expandedGroups: Set<Int>,
    onToggleExpand: (Int) -> Unit,
    onGroupClick: (StaticMarketGroupModel) -> Unit,
    onTypeClick: (StaticTypeModel) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(groups) { group ->
            MarketGroupTreeNode(
                group = group,
                isExpanded = expandedGroups.contains(group.marketGroupId),
                onToggleExpand = { onToggleExpand(group.marketGroupId) },
                onGroupClick = { onGroupClick(group) },
                onTypeClick = onTypeClick,
                depth = 0,
            )
        }
    }
}

@Composable
private fun MarketGroupTreeNode(
    group: StaticMarketGroupModel,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onGroupClick: () -> Unit,
    onTypeClick: (StaticTypeModel) -> Unit,
    depth: Int,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onGroupClick() }
                .padding(start = (depth * 16).dp, top = 2.dp, bottom = 2.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Expand/collapse icon
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )

            Spacer(modifier = Modifier.width(2.dp))

            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = group.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier,
            )
        }

        if (isExpanded) {
            // Load and show children
            val childGroups = remember(group.marketGroupId) {
                StaticDataDao.getChildMarketGroups(group.marketGroupId)
            }
            childGroups.forEach { child ->
                MarketGroupTreeNode(
                    group = child,
                    isExpanded = false, // TODO: track per-group
                    onToggleExpand = { /* handled by parent */ },
                    onGroupClick = { onGroupClick() },
                    onTypeClick = onTypeClick,
                    depth = depth + 1,
                )
            }

            // Show types in this group
            val types = remember(group.marketGroupId) {
                StaticDataDao.getTypesByMarketGroup(group.marketGroupId, limit = 100)
            }
            types.forEach { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTypeClick(type) }
                        .padding(start = ((depth + 1) * 16 + 20).dp, top = 1.dp, bottom = 1.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = type.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier,
                    )
                }
            }
        }
    }
}

// ─── Type Market Header ──────────────────────────────────────────────────

@Composable
private fun TypeMarketHeader(
    type: StaticTypeModel,
    orderBook: Pair<List<MarketOrder>, List<MarketOrder>>,
    showOrderBook: Boolean,
    onToggleView: () -> Unit,
    onAddToWatchlist: () -> Unit,
    onAddToAlert: () -> Unit,
) {
    val (sellOrders, buyOrders) = orderBook
    val bestSell = sellOrders.minOfOrNull { it.price }
    val bestBuy = buyOrders.maxOfOrNull { it.price }
    val spread = if (bestSell != null && bestBuy != null && bestSell > 0) {
        ((bestSell - bestBuy) / bestSell) * 100
    } else null

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Type icon
            Surface(
                modifier = Modifier.size(32.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Extension, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Text(type.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Region: ${TRADE_HUBS.find { it.first == 10000002 }?.second ?: "The Forge"}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }

        Row {
            FilterChip(selected = showOrderBook, onClick = onToggleView, label = { Text("Orders") })
            Spacer(modifier = Modifier.width(4.dp))
            FilterChip(selected = !showOrderBook, onClick = onToggleView, label = { Text("History") })
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onAddToWatchlist, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Icon(Icons.Default.Visibility, null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Watchlist", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = onAddToAlert, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Icon(Icons.Default.Notifications, null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Alert", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    // Spread bar
    if (bestSell != null && bestBuy != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SpreadItem("Best Sell", formatPrice(bestSell), Color(0xFFFF6B6B))
                SpreadItem("Best Buy", formatPrice(bestBuy), Color(0xFF69DB7C))
                SpreadItem("Spread", "${String.format("%.2f", spread ?: 0.0)}%", Color(0xFFFF8C00))
                SpreadItem("Sell Orders", sellOrders.size.toString(), MaterialTheme.colorScheme.onSurface)
                SpreadItem("Buy Orders", buyOrders.size.toString(), MaterialTheme.colorScheme.onSurface)
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun SpreadItem(label: String, value: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = color)
    }
}

// ─── Order Book ───────────────────────────────────────────────────────────

@Composable
private fun OrderBookView(orders: Pair<List<MarketOrder>, List<MarketOrder>>) {
    val (sellOrders, buyOrders) = orders

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // ─── Sell Orders (top, red) ────────────────────────────────
        Text("Sell Orders", style = MaterialTheme.typography.titleMedium, color = Color(0xFFFF6B6B))
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("Price", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f))
                Text("Volume", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Total", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (sellOrders.isEmpty()) {
                Text("No sell orders", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(8.dp))
            } else {
                sellOrders.forEach { order -> OrderRow(order, isSell = true) }
            }
        }

        HorizontalDivider(thickness = 2.dp)

        // ─── Buy Orders (bottom, green) ────────────────────────────
        Text("Buy Orders", style = MaterialTheme.typography.titleMedium, color = Color(0xFF69DB7C))
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("Price", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f))
                Text("Volume", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Total", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (buyOrders.isEmpty()) {
                Text("No buy orders", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(8.dp))
            } else {
                buyOrders.forEach { order -> OrderRow(order, isSell = false) }
            }
        }
    }
}

@Composable
private fun OrderRow(order: MarketOrder, isSell: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(
            formatPrice(order.price),
            style = MaterialTheme.typography.bodySmall,
            color = if (isSell) Color(0xFFFF6B6B) else Color(0xFF69DB7C),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1.5f),
        )
        Text(formatVolume(order.volumeRemaining.toLong()), style = MaterialTheme.typography.bodySmall, modifier = Modifier)
        Text(formatPrice(order.price * order.volumeRemaining), style = MaterialTheme.typography.bodySmall, modifier = Modifier)
    }
}

// ─── History Chart ────────────────────────────────────────────────────────

@Composable
private fun HistoryChartView(history: List<MarketHistoryModel>, type: StaticTypeModel) {
    var historyDays by remember { mutableStateOf(90) }

    if (history.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.ShowChart,
            title = "No History Data",
            description = "History will be loaded when you select a type.",
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Days selector
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(30, 90, 180, 365).forEach { days ->
                FilterChip(
                    selected = historyDays == days,
                    onClick = { historyDays = days },
                    label = { Text("${days}d") },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val filteredHistory = history.take(historyDays)

        // Price chart
        ContentCard("Average Price — ${type.name}") {
            SparklineChart(
                data = filteredHistory.reversed().map { it.average },
                labels = filteredHistory.reversed().map { it.date.take(10) },
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Volume chart
        ContentCard("Volume") {
            BarChart(
                data = filteredHistory.reversed().map { it.volume.toDouble() },
                labels = filteredHistory.reversed().map { it.date.take(10) },
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Stats
        val avgPrice = filteredHistory.averageOf { it.average }
        val highestPrice = filteredHistory.maxOfOrNull { it.highest } ?: 0.0
        val lowestPrice = filteredHistory.minOfOrNull { it.lowest } ?: 0.0
        val totalVolume = filteredHistory.sumOf { it.volume }
        val orderCount = filteredHistory.sumOf { it.orderCount }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Avg Price", formatPrice(avgPrice), Modifier.weight(1f))
            StatCard("Highest", formatPrice(highestPrice), Modifier.weight(1f))
            StatCard("Lowest", formatPrice(lowestPrice), Modifier.weight(1f))
            StatCard("Total Vol", formatVolume(totalVolume), Modifier.weight(1f))
            StatCard("Orders", formatVolume(orderCount), Modifier.weight(1f))
        }
    }
}

// ─── Charts ───────────────────────────────────────────────────────────────

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

        val path = Path()
        data.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (data.size - 1).coerceAtLeast(1)) * chartWidth
            val normalizedValue = if (range > 0) (value - minVal) / range else 0.5f
            val y = padding + (1 - normalizedValue.toFloat()) * chartHeight
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = color, style = Stroke(width = 2f, cap = StrokeCap.Round))

        // Fill
        val lastX = padding + chartWidth
        val fillPath = Path().apply {
            addPath(path)
            lineTo(lastX, height - padding)
            lineTo(padding, height - padding)
            close()
        }
        drawPath(path = fillPath, color = color.copy(alpha = 0.1f))
    }
}

@Composable
private fun BarChart(
    data: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
    labels: List<String> = emptyList(),
) {
    if (data.isEmpty()) return

    val maxVal = data.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val padding = 4f
        val barWidth = ((width - padding * 2) / data.size).coerceAtLeast(1f)
        val gap = 1f

        data.forEachIndexed { index, value ->
            val x = padding + index * (barWidth + gap)
            val barHeight = (value / maxVal * (height - padding * 2)).toFloat()
            val y = height - padding - barHeight

            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

// ─── Helper Composables ───────────────────────────────────────────────────

@Composable
private fun SearchRow(type: StaticTypeModel, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Extension, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(8.dp))
        Text(type.name, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TypeRow(type: StaticTypeModel, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(type.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier)
        if (type.volume > 0) {
            Text("${String.format("%.1f", type.volume)}m³", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

// ─── Data Models ──────────────────────────────────────────────────────────

private data class MarketOrder(
    val price: Double,
    val volumeRemaining: Int,
    val volumeTotal: Int,
    val isBuyOrder: Boolean,
)

// ─── Data Loading ─────────────────────────────────────────────────────────

private fun loadTopMarketGroups(
    expanded: Set<Int>,
    callback: (List<StaticMarketGroupModel>) -> Unit,
) {
    callback(StaticDataDao.getTopMarketGroups())
}

private fun buildExpandedTree(
    topGroups: List<StaticMarketGroupModel>,
    expanded: Set<Int>,
): List<StaticMarketGroupModel> {
    val result = mutableListOf<StaticMarketGroupModel>()
    fun addGroup(group: StaticMarketGroupModel) {
        result.add(group)
        if (expanded.contains(group.marketGroupId)) {
            val children = StaticDataDao.getChildMarketGroups(group.marketGroupId)
            children.forEach { addGroup(it) }
        }
    }
    topGroups.forEach { addGroup(it) }
    return result
}

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
        println("[Market] Error loading orders: ${e.message}")
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
        println("[Market] Error loading history: ${e.message}")
    }
}

private fun formatPrice(price: Double): String {
    return when {
        price >= 1_000_000_000 -> String.format("%.2fB", price / 1_000_000_000)
        price >= 1_000_000 -> String.format("%.2fM", price / 1_000_000)
        price >= 1_000 -> String.format("%,.2f", price)
        else -> String.format("%.4f", price)
    }
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

// ─── Add to Watchlist Dialog ────────────────────────────────────────────

@Composable
private fun AddToWatchlistDialog(
    type: StaticTypeModel,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var watchlistName by remember { mutableStateOf("Default") }
    val watchlists = remember {
        try { WatchlistDao.getAllWatchlists().keys.toList() }
        catch (e: Exception) { listOf("Default") }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Watchlist") },
        text = {
            Column {
                Text(type.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Select watchlist:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(watchlists) { name ->
                        FilterChip(
                            selected = watchlistName == name,
                            onClick = { watchlistName = name },
                            label = { Text(name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    WatchlistDao.insert(WatchlistEntryModel(
                        typeId = type.typeId, typeName = type.name, watchlistName = watchlistName,
                    ))
                    withContext(Dispatchers.Main) { onAdded() }
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ─── Add to Alert Dialog ────────────────────────────────────────────────

@Composable
private fun AddToAlertDialog(
    type: StaticTypeModel,
    orderBook: Pair<List<MarketOrder>, List<MarketOrder>>,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var targetPrice by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf("below") }

    val (sellOrders, buyOrders) = orderBook
    val bestSell = sellOrders.minOfOrNull { it.price }
    val bestBuy = buyOrders.maxOfOrNull { it.price }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Price Alert") },
        text = {
            Column {
                Text(type.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                if (bestSell != null || bestBuy != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (bestSell != null) Text("Best Sell: ${formatPrice(bestSell)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF6B6B))
                        if (bestBuy != null) Text("Best Buy: ${formatPrice(bestBuy)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF69DB7C))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = condition == "below", onClick = { condition = "below" }, label = { Text("Below") })
                    FilterChip(selected = condition == "above", onClick = { condition = "above" }, label = { Text("Above") })
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = targetPrice,
                    onValueChange = { targetPrice = it },
                    label = { Text("Target Price (ISK)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val price = targetPrice.toDoubleOrNull() ?: return@Button
                    scope.launch(Dispatchers.IO) {
                        AlertDao.insert(PriceAlertModel(
                            typeId = type.typeId, typeName = type.name,
                            targetPrice = price, condition = condition,
                        ))
                        withContext(Dispatchers.Main) { onAdded() }
                    }
                },
                enabled = targetPrice.toDoubleOrNull() != null,
            ) { Text("Create Alert") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
