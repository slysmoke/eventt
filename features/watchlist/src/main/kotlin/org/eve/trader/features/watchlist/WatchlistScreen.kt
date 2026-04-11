package org.eve.trader.features.watchlist

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
import org.eve.trader.core.database.DatabaseManager
import org.eve.trader.core.database.StaticDataDao
import org.eve.trader.core.database.WatchlistDao
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.core.model.WatchlistEntryModel
import org.eve.trader.ui.common.*

@Composable
fun WatchlistScreen() {
    val scope = rememberCoroutineScope()
    var watchlists by remember { mutableStateOf<Map<String, List<WatchlistEntryModel>>>(emptyMap()) }
    var selectedWatchlist by remember { mutableStateOf("Default") }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<org.eve.trader.core.model.StaticTypeModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO) { DatabaseManager.initialize() } } catch (e: Exception) {}
        loadWatchlists { watchlists = it; if (it.isNotEmpty()) selectedWatchlist = it.keys.first() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Watchlist", style = MaterialTheme.typography.headlineMedium)

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
                        // Add new watchlist
                        val newName = "Watchlist ${watchlists.size + 1}"
                        watchlists = watchlists + (newName to emptyList())
                        selectedWatchlist = newName
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
                        searchResults = StaticDataDao.searchTypes(query, limit = 15)
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
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope.launch(Dispatchers.IO) {
                                    WatchlistDao.insert(
                                        WatchlistEntryModel(
                                            typeId = type.typeId,
                                            typeName = type.name,
                                            watchlistName = selectedWatchlist,
                                        )
                                    )
                                    withContext(Dispatchers.Main) {
                                        loadWatchlists { watchlists = it }
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
            WatchlistTable(entries)
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Loading watchlist data...")
}

@Composable
private fun WatchlistTable(entries: List<WatchlistEntryModel>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Header
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth()) {
                    Text("Item", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f))
                    Text("Buy", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Sell", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("24h", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("7d", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Trend", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
            }
        }

        items(entries) { entry ->
            WatchlistRow(entry)
        }
    }
}

@Composable
private fun WatchlistRow(entry: WatchlistEntryModel) {
    val price = remember(entry.typeId) {
        try {
            WatchlistDao.getLatestPrice(entry.typeId, entry.stationId)
        } catch (e: Exception) { null }
    }

    val change24h = price?.changePercent24h ?: 0.0
    val change7d = price?.changePercent7d ?: 0.0

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

        // 24h change
        ChangeBadge(change24h, modifier = Modifier.weight(1f))

        // 7d change
        ChangeBadge(change7d, modifier = Modifier.weight(1f))

        // Sparkline
        if (price != null && price.sparklineData.isNotEmpty()) {
            MiniSparkline(
                data = price.sparklineData.map { it.second },
                modifier = Modifier.weight(1f).height(32.dp),
            )
        } else {
            Text("—", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ChangeBadge(value: Double, modifier: Modifier = Modifier) {
    val color = when {
        value > 0 -> Color(0xFF69DB7C)
        value < 0 -> Color(0xFFFF6B6B)
        else -> Color.Gray
    }
    Text(
        text = "${if (value > 0) "+" else ""}${String.format("%.1f", value)}%",
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun MiniSparkline(data: List<Double>, modifier: Modifier = Modifier) {
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

private fun loadWatchlists(callback: (Map<String, List<WatchlistEntryModel>>) -> Unit) {
    try {
        callback(WatchlistDao.getAllWatchlists())
    } catch (e: Exception) {
        println("Error loading watchlists: ${e.message}")
    }
}

private fun formatPrice(price: Double): String {
    return when {
        price >= 1_000_000_000 -> String.format("%.1fB", price / 1_000_000_000)
        price >= 1_000_000 -> String.format("%.1fM", price / 1_000_000)
        price >= 1_000 -> String.format("%.1fK", price / 1_000)
        else -> String.format("%,.2f", price)
    }
}
