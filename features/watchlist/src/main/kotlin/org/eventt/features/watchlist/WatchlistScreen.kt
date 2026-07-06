package org.eventt.features.watchlist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.WatchlistDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.WatchlistEntryModel
import org.eventt.core.model.WatchlistPriceSnapshot
import org.eventt.ui.common.*
import java.util.Locale

// The Forge (Jita) is the default trade hub for price lookups
private const val DEFAULT_REGION_ID = 10000002
private const val PLEX_REGION_ID = 19000001
private const val PLEX_TYPE_ID = 44992

@Composable
fun WatchlistScreen() {
    val scope = rememberCoroutineScope()
    var watchlists by remember { mutableStateOf<Map<String, List<WatchlistEntryModel>>>(emptyMap()) }
    var selectedWatchlist by remember { mutableStateOf("Default") }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<org.eventt.core.model.StaticTypeModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<WatchlistEntryModel?>(null) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { WatchlistDao.getAllWatchlists() }
        watchlists = loaded
        if (loaded.isNotEmpty()) selectedWatchlist = loaded.keys.first()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Watchlist", style = MaterialTheme.typography.headlineMedium)
            Button(
                onClick = {
                    isLoading = true
                    scope.launch(Dispatchers.IO) {
                        refreshAllPrices(watchlists)
                        withContext(Dispatchers.Main) { isLoading = false }
                    }
                },
                enabled = !isLoading,
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Refresh Prices")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Watchlist tabs
        if (watchlists.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(watchlists.keys.toList()) { name ->
                    FilterChip(
                        selected = selectedWatchlist == name,
                        onClick = { selectedWatchlist = name },
                        label = { Text(name) },
                    )
                }
                item {
                    OutlinedButton(onClick = {
                        val newName = "Watchlist ${watchlists.size + 1}"
                        scope.launch(Dispatchers.IO) {
                            // Insert a dummy entry to create the list, then remove it
                            val dummyId =
                                WatchlistDao.insert(
                                    org.eventt.core.model.WatchlistEntryModel(
                                        typeId = 0,
                                        typeName = "",
                                        watchlistName = newName,
                                    ),
                                )
                            WatchlistDao.delete(dummyId)
                            val loaded = WatchlistDao.getAllWatchlists()
                            withContext(Dispatchers.Main) {
                                watchlists = loaded
                                selectedWatchlist = newName
                            }
                        }
                    }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Add item search
        SearchField(
            query = searchQuery,
            onQueryChange = { query ->
                searchQuery = query
                if (query.length >= 2) {
                    scope.launch(Dispatchers.IO) {
                        searchResults = StaticDataDao.searchMarketTypes(query, limit = 15)
                    }
                } else {
                    searchResults = emptyList()
                }
            },
            placeholder = "Add item to watchlist...",
            modifier = Modifier.fillMaxWidth(0.6f),
        )

        if (searchResults.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(0.6f).heightIn(max = 200.dp)) {
                LazyColumn {
                    items(searchResults) { type ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch(Dispatchers.IO) {
                                            WatchlistDao.insert(
                                                WatchlistEntryModel(
                                                    typeId = type.typeId,
                                                    typeName = type.name,
                                                    watchlistName = selectedWatchlist,
                                                ),
                                            )
                                            val loaded = WatchlistDao.getAllWatchlists()
                                            withContext(Dispatchers.Main) {
                                                watchlists = loaded
                                                searchQuery = ""
                                                searchResults = emptyList()
                                            }
                                        }
                                    }.padding(12.dp),
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add: ${type.name}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Watchlist content
        val entries = watchlists[selectedWatchlist] ?: emptyList()
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Visibility,
                title = "Watchlist Empty",
                description = "Search for items to add to your watchlist.",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            WatchlistTable(
                entries = entries,
                onDelete = { entryToDelete = it },
                onRefresh = { entry ->
                    scope.launch(Dispatchers.IO) {
                        refreshEntryPrice(entry)
                        val loaded = WatchlistDao.getAllWatchlists()
                        withContext(Dispatchers.Main) { watchlists = loaded }
                    }
                },
            )
        }
    }

    // Delete confirmation
    entryToDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = { Text("Remove from Watchlist") },
            text = { Text("Remove '${entry.typeName}' from $selectedWatchlist?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            WatchlistDao.delete(entry.id)
                            val loaded = WatchlistDao.getAllWatchlists()
                            withContext(Dispatchers.Main) {
                                watchlists = loaded
                                entryToDelete = null
                            }
                        }
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) { Text("Cancel") }
            },
        )
    }

    LoadingOverlay(isLoading = isLoading, message = "Refreshing prices from ESI...")
}

@Composable
private fun WatchlistTable(
    entries: List<WatchlistEntryModel>,
    onDelete: (WatchlistEntryModel) -> Unit,
    onRefresh: (WatchlistEntryModel) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Header
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth()) {
                    Text("Item", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f))
                    Text("Buy", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Sell", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        "Spread",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text("Trend", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.5f))
                }
            }
        }

        items(entries) { entry ->
            WatchlistRow(entry, onDelete = { onDelete(entry) }, onRefresh = { onRefresh(entry) })
        }
    }
}

@Composable
private fun WatchlistRow(
    entry: WatchlistEntryModel,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
) {
    // Fetch price on first composition
    val priceState = remember(entry.id) { mutableStateOf<WatchlistPriceSnapshot?>(null) }

    LaunchedEffect(entry.typeId) {
        withContext(Dispatchers.IO) {
            priceState.value = WatchlistDao.getLatestPrice(entry.typeId)
        }
    }

    val price = priceState.value
    val spread = price?.spreadPercent ?: 0.0

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name
        Text(entry.typeName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(2f), maxLines = 1)

        // Buy price
        Text(
            price?.let { formatPrice(it.bestBuyPrice) } ?: "—",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )

        // Sell price
        Text(
            price?.let { formatPrice(it.bestSellPrice) } ?: "—",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )

        // Spread
        Text(
            if (price != null) "${String.format(Locale.US, "%.1f", spread)}%" else "—",
            style = MaterialTheme.typography.bodySmall,
            color = if (spread > 5) Color(0xFFFF8C00) else Color.Gray,
            modifier = Modifier.weight(1f),
        )

        // Sparkline (from history data if available)
        if (price != null && price.sparklineData.isNotEmpty()) {
            MiniSparkline(
                data = price.sparklineData.map { it.second },
                modifier = Modifier.weight(1f).height(32.dp),
            )
        } else {
            Text("—", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }

        // Actions
        IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp).weight(0.25f)) {
            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp), tint = Color.Gray)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp).weight(0.25f)) {
            Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp), tint = Color.Red)
        }
    }
}

@Composable
private fun MiniSparkline(
    data: List<Double>,
    modifier: Modifier = Modifier,
) {
    if (data.size < 2) return

    val maxVal = data.maxOrNull() ?: 0.0
    val minVal = data.minOrNull() ?: 0.0
    val range = maxVal - minVal
    val sparklineColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val width = size.width
        val heightVal = size.height
        val padding = 2f

        val path = Path()
        data.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (data.size - 1)) * (width - padding * 2)
            val normalized = if (range > 0) (value - minVal) / range else 0.5f
            val y = heightVal - padding - normalized.toFloat() * (heightVal - padding * 2)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = sparklineColor,
            style = Stroke(width = 1.5f),
        )
    }
}

// ─── Data Operations ────────────────────────────────────────────────────

/** Refresh prices for all entries in all watchlists. */
private suspend fun refreshAllPrices(watchlists: Map<String, List<WatchlistEntryModel>>) {
    val allEntries = watchlists.values.flatten().filter { it.typeId > 0 }
    allEntries.forEach { entry ->
        try {
            refreshEntryPrice(entry)
        } catch (e: Exception) {
            println("[Watchlist] Error refreshing ${entry.typeName}: ${e.message}")
        }
    }
}

/** Fetch market data for a single watchlist entry and save price snapshot. */
private suspend fun refreshEntryPrice(entry: WatchlistEntryModel) {
    if (entry.typeId <= 0) return

    val regionId = if (entry.typeId == PLEX_TYPE_ID) PLEX_REGION_ID else DEFAULT_REGION_ID

    val rawOrders = EsiClient.getMarketRegionOrders(regionId, typeId = entry.typeId)
    val sellOrders = rawOrders.filter { (it["is_buy_order"] as? Boolean) == false }
    val buyOrders = rawOrders.filter { (it["is_buy_order"] as? Boolean) == true }

    val bestSell = sellOrders.minOfOrNull { (it["price"] as? Number)?.toDouble() ?: 0.0 } ?: 0.0
    val bestBuy = buyOrders.maxOfOrNull { (it["price"] as? Number)?.toDouble() ?: 0.0 } ?: 0.0
    val spread = if (bestSell > 0) bestSell - bestBuy else 0.0
    val spreadPercent = if (bestSell > 0) (spread / bestSell) * 100 else 0.0
    val totalVolume = rawOrders.sumOf { (it["volume_remain"] as? Number)?.toLong() ?: 0 }

    // Fetch history for sparkline
    val rawHistory = EsiClient.getMarketRegionHistory(regionId, entry.typeId)
    val sparklineData =
        rawHistory.takeLast(30).map { hist ->
            (hist["date"] as? String ?: "") to ((hist["average"] as? Number)?.toDouble() ?: 0.0)
        }

    // Calculate changes using actual dates, not data-point offsets.
    // ESI omits days with no trades, so index-based offsets (e.g. size-8) are wrong
    // when there are gaps in the history.
    val currentAvg = sparklineData.lastOrNull()?.second ?: 0.0
    val dayAgoDate =
        java.time.LocalDate
            .now()
            .minusDays(1)
            .toString()
    val weekAgoDate =
        java.time.LocalDate
            .now()
            .minusDays(7)
            .toString()
    val dayAgo = sparklineData.filter { it.first <= dayAgoDate }.lastOrNull()?.second ?: 0.0
    val weekAgo = sparklineData.filter { it.first <= weekAgoDate }.lastOrNull()?.second ?: 0.0

    val change24h = if (dayAgo > 0) ((currentAvg - dayAgo) / dayAgo) * 100 else 0.0
    val change7d = if (weekAgo > 0) ((currentAvg - weekAgo) / weekAgo) * 100 else 0.0
    val change30d = 0.0

    val snapshot =
        WatchlistPriceSnapshot(
            typeId = entry.typeId,
            stationId = 0,
            bestBuyPrice = bestBuy,
            bestSellPrice = bestSell,
            spread = spread,
            spreadPercent = spreadPercent,
            volume24h = totalVolume,
            changePercent24h = change24h,
            changePercent7d = change7d,
            changePercent30d = change30d,
            sparklineData = sparklineData,
        )

    WatchlistDao.insertPriceSnapshot(snapshot)
}

private fun formatPrice(price: Double): String =
    when {
        price >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", price / 1_000_000_000)
        price >= 1_000_000 -> String.format(Locale.US, "%.1fM", price / 1_000_000)
        price >= 1_000 -> String.format(Locale.US, "%.1fK", price / 1_000)
        else -> String.format(Locale.US, "%,.2f", price)
    }
