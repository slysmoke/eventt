package org.eve.trader.features.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eve.trader.core.database.*
import org.eve.trader.core.model.DashboardSummary
import org.eve.trader.ui.common.LoadingOverlay

@Composable
fun DashboardScreen() {
    var summary by remember { mutableStateOf<DashboardSummary?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading = true
        withContext(Dispatchers.IO) {
            summary = loadDashboardSummary()
        }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Dashboard", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(16.dp))

        summary?.let { s ->
            // Top row: Key metrics
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { DashboardCard(Icons.Default.AccountBalance, "Total Assets", formatIsk(s.totalAssetValue), Color(0xFF4A90D9)) }
                item { DashboardCard(Icons.Default.ShoppingCart, "Active Orders", s.activeOrderCount.toString(), Color(0xFF69DB7C)) }
                item { DashboardCard(Icons.Default.Receipt, "Active Contracts", s.activeContractCount.toString(), Color(0xFFB197FC)) }
                item { DashboardCard(Icons.Default.Notifications, "Price Alerts", s.triggeredAlertCount.toString(), Color(0xFFFF8C00)) }
                item { DashboardCard(Icons.Default.ShowChart, "Watchlist Items", s.watchlistCount.toString(), Color(0xFFE599F7)) }
                item { DashboardCard(Icons.Default.Person, "Characters", s.characterCount.toString(), Color(0xFF66D9E8)) }
                item { DashboardCard(Icons.Default.Business, "Corporations", s.corporationCount.toString(), Color(0xFFFCC419)) }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // P&L Summary
            ContentCard("Profit & Loss") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PnlCard("Today", s.todayPL)
                    PnlCard("This Week", s.weekPL)
                    PnlCard("This Month", s.monthPL)
                }
            }
        } ?: run {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Loading dashboard...")
}

@Composable
private fun DashboardCard(icon: ImageVector, label: String, value: String, color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        }
    }
}

@Composable
private fun PnlCard(label: String, value: Double) {
    val color = when {
        value > 0 -> Color(0xFF69DB7C)
        value < 0 -> Color(0xFFFF6B6B)
        else -> Color.Gray
    }

    Card(
        modifier = Modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Text(
                text = "${if (value >= 0) "+" else ""}${formatIsk(value)} ISK",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
    }
}

@Composable
private fun ContentCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

private fun loadDashboardSummary(): DashboardSummary {
    val characters = try { CharacterDao.getAll() } catch (e: Exception) { emptyList() }
    val corporations = try { CorporationDao.getAll() } catch (e: Exception) { emptyList() }

    val totalAssetValue = characters.sumOf { char ->
        try { AssetDao.getTotalValue(characterId = char.id) } catch (e: Exception) { 0.0 }
    } + corporations.sumOf { corp ->
        try { AssetDao.getTotalValue(corporationId = (corp["id"] as? Number)?.toInt()) } catch (e: Exception) { 0.0 }
    }

    val watchlistCount = try {
        WatchlistDao.getAllWatchlists().values.sumOf { it.size }
    } catch (e: Exception) { 0 }

    val alertCount = try {
        AlertDao.getEnabled().size
    } catch (e: Exception) { 0 }

    return DashboardSummary(
        totalAssetValue = totalAssetValue,
        activeOrderCount = 0,
        watchlistCount = watchlistCount,
        triggeredAlertCount = alertCount,
        characterCount = characters.size,
        corporationCount = corporations.size,
    )
}

private fun formatIsk(value: Double): String {
    return when {
        kotlin.math.abs(value) >= 1_000_000_000_000 -> String.format("%.2fT", value / 1_000_000_000_000)
        kotlin.math.abs(value) >= 1_000_000_000 -> String.format("%.2fB", value / 1_000_000_000)
        kotlin.math.abs(value) >= 1_000_000 -> String.format("%.2fM", value / 1_000_000)
        kotlin.math.abs(value) >= 1_000 -> String.format("%.2fK", value / 1_000)
        else -> String.format("%,.2f", value)
    }
}
