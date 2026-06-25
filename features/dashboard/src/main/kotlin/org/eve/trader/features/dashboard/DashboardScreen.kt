package org.eve.trader.features.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eve.trader.core.database.*
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.core.model.PriceAlertModel
import java.time.LocalDate

private val POSITIVE = Color(0xFF69DB7C)
private val NEGATIVE = Color(0xFFFF6B6B)
private val ACCENT   = Color(0xFF4A90D9)

@Composable
fun DashboardScreen(charId: Int?, refreshTrigger: Int = 0) {
    var walletBalance   by remember { mutableStateOf(0.0) }
    var txBreakdown     by remember { mutableStateOf<List<org.eve.trader.core.model.DailyWalletEntry>>(emptyList()) }
    var assetValue      by remember { mutableStateOf(0.0) }
    var recentTx        by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var triggeredAlerts by remember { mutableStateOf<List<PriceAlertModel>>(emptyList()) }
    var isLoading       by remember { mutableStateOf(false) }

    LaunchedEffect(charId, refreshTrigger) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val since90 = LocalDate.now().minusDays(90).toString()
            try {
                walletBalance   = WalletDao.getWalletSummary(characterId = charId).balance
                txBreakdown     = WalletDao.getTransactionBreakdown(characterId = charId, since = since90)
                assetValue      = if (charId != null) AssetDao.getTotalValue(characterId = charId) else 0.0
                recentTx        = WalletDao.getTransactions(characterId = charId, limit = 12)
                triggeredAlerts = AlertDao.getEnabled().filter { it.triggered }
            } catch (_: Exception) { }

            if (charId != null) {
                try {
                    walletBalance = EsiClient.getCharacterWallet(charId)
                } catch (e: Exception) { println("Dashboard: wallet ESI error: ${e.message}") }

                try {
                    val journalEntries = EsiClient.getCharacterJournal(charId)
                    journalEntries.forEach { entry ->
                        try {
                            WalletDao.insertJournalEntry(
                                entryId       = (entry["id"]             as? Number)?.toLong()  ?: 0,
                                date          = entry["date"]            as? String              ?: "",
                                amount        = (entry["amount"]         as? Number)?.toDouble() ?: 0.0,
                                balance       = (entry["balance"]        as? Number)?.toDouble() ?: 0.0,
                                reason        = entry["reason"]          as? String              ?: "",
                                refType       = entry["ref_type"]        as? String              ?: "",
                                firstPartyId  = (entry["first_party_id"] as? Number)?.toInt()  ?: 0,
                                firstPartyName  = "",
                                secondPartyId = (entry["second_party_id"] as? Number)?.toInt() ?: 0,
                                secondPartyName = "",
                                taxAmount     = (entry["tax"]            as? Number)?.toDouble(),
                                isCorp        = false,
                                characterId   = charId,
                                corporationId = null,
                                divisionId    = null,
                            )
                        } catch (_: Exception) {}
                    }
                    walletBalance = WalletDao.getWalletSummary(characterId = charId).balance
                    txBreakdown   = WalletDao.getTransactionBreakdown(characterId = charId, since = since90)
                } catch (e: Exception) { println("Dashboard: journal ESI error: ${e.message}") }

                try {
                    val txList = EsiClient.getCharacterTransactions(charId)
                    val typeNames = txList.mapNotNull { (it["type_id"] as? Number)?.toInt() }.toSet()
                        .associateWith { id -> StaticDataDao.getTypeName(id) ?: "" }
                    txList.forEach { tx ->
                        val typeId    = (tx["type_id"]   as? Number)?.toInt()    ?: 0
                        val unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0
                        val quantity  = (tx["quantity"]   as? Number)?.toInt()    ?: 0
                        try {
                            WalletDao.insertTransaction(
                                transactionId = (tx["transaction_id"] as? Number)?.toLong() ?: 0,
                                date          = tx["date"] as? String ?: "",
                                typeId        = typeId,
                                typeName      = typeNames[typeId] ?: "",
                                quantity      = quantity,
                                unitPrice     = unitPrice,
                                total         = unitPrice * quantity,
                                isBuy         = (tx["is_buy"] as? Boolean) ?: false,
                                clientId      = (tx["client_id"]   as? Number)?.toInt()  ?: 0,
                                clientName    = "",
                                locationId    = (tx["location_id"] as? Number)?.toLong() ?: 0L,
                                locationName  = "",
                                isCorp        = false,
                                characterId   = charId,
                                corporationId = null,
                            )
                        } catch (_: Exception) {}
                    }
                    recentTx    = WalletDao.getTransactions(characterId = charId, limit = 12)
                    txBreakdown = WalletDao.getTransactionBreakdown(characterId = charId, since = since90)
                } catch (e: Exception) { println("Dashboard: transactions ESI error: ${e.message}") }
            }
        }
        isLoading = false
    }

    if (charId == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No character selected", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        return
    }

    val today        = LocalDate.now().toString()
    val week7Start   = LocalDate.now().minusDays(7).toString()
    val month30Start = LocalDate.now().minusDays(30).toString()

    val todayPL   = txBreakdown.firstOrNull { it.date == today }?.net ?: 0.0
    val week7PL   = txBreakdown.filter { it.date >= week7Start }.sumOf { it.net }
    val month30PL = txBreakdown.filter { it.date >= month30Start }.sumOf { it.net }
    val income30d = txBreakdown.filter { it.date >= month30Start }.sumOf { it.income }
    val spend30d  = txBreakdown.filter { it.date >= month30Start }.sumOf { it.expenses }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Dashboard", style = MaterialTheme.typography.headlineMedium)
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        // Top KPI row
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KpiCard(
                    modifier    = Modifier.weight(1f),
                    icon        = Icons.Default.AccountBalance,
                    label       = "Wallet Balance",
                    value       = formatIsk(walletBalance),
                    color       = ACCENT,
                )
                KpiCard(
                    modifier    = Modifier.weight(1f),
                    icon        = Icons.Default.Storage,
                    label       = "Asset Value",
                    value       = formatIsk(assetValue),
                    color       = Color(0xFF66D9E8),
                )
                KpiCard(
                    modifier    = Modifier.weight(1f),
                    icon        = Icons.Default.TrendingUp,
                    label       = "P&L 30d",
                    value       = formatIsk(month30PL, showSign = true),
                    color       = if (month30PL >= 0) POSITIVE else NEGATIVE,
                    valueColor  = if (month30PL >= 0) POSITIVE else NEGATIVE,
                )
            }
        }

        // P&L mini cards
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PnlMiniCard(modifier = Modifier.weight(1f), label = "P&L Today",    value = todayPL,  showSign = true)
                PnlMiniCard(modifier = Modifier.weight(1f), label = "P&L 7 days",   value = week7PL,  showSign = true)
                PnlMiniCard(modifier = Modifier.weight(1f), label = "Income 30d",   value = income30d, forceColor = POSITIVE)
                PnlMiniCard(modifier = Modifier.weight(1f), label = "Expenses 30d", value = spend30d,  forceColor = NEGATIVE)
            }
        }

        // Transactions + Alerts
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Card(
                    modifier = Modifier.weight(0.6f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionHeader("Recent Transactions", count = recentTx.size.takeIf { it > 0 })
                        Spacer(Modifier.height(8.dp))
                        if (recentTx.isEmpty()) {
                            EmptyHint("No transactions recorded")
                        } else {
                            recentTx.forEachIndexed { i, tx ->
                                TransactionRow(tx)
                                if (i < recentTx.lastIndex) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    )
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.weight(0.4f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionHeader("Triggered Alerts", badge = triggeredAlerts.size.takeIf { it > 0 })
                        Spacer(Modifier.height(8.dp))
                        if (triggeredAlerts.isEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Default.CheckCircle, null, tint = POSITIVE, modifier = Modifier.size(16.dp))
                                Text(
                                    "No triggered alerts",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                )
                            }
                        } else {
                            triggeredAlerts.forEachIndexed { i, alert ->
                                AlertRow(alert)
                                if (i < triggeredAlerts.lastIndex) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Sub-composables ──────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, count: Int? = null, badge: Int? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        when {
            badge != null -> Badge { Text("$badge") }
            count != null -> Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    )
}

@Composable
private fun KpiCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    color: Color,
    valueColor: Color = Color.Unspecified,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Surface(
                    color = color.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxSize(),
                ) {}
                Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            }
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Text(
                    value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor,
                )
            }
        }
    }
}

@Composable
private fun PnlMiniCard(
    modifier: Modifier = Modifier,
    label: String,
    value: Double,
    showSign: Boolean = false,
    forceColor: Color? = null,
) {
    val color = forceColor ?: when {
        value > 0 -> POSITIVE
        value < 0 -> NEGATIVE
        else      -> Color.Gray
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                formatIsk(value, showSign = showSign),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

@Composable
private fun TransactionRow(tx: Map<String, Any?>) {
    val isBuy     = tx["is_buy"] as? Boolean ?: false
    val typeName  = tx["type_name"] as? String ?: "Unknown"
    val qty       = (tx["quantity"] as? Number)?.toInt() ?: 0
    val unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0
    val total     = (tx["total"] as? Number)?.toDouble() ?: 0.0
    val date      = (tx["date"] as? String)?.take(10) ?: ""
    val color     = if (isBuy) NEGATIVE else POSITIVE

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.extraSmall) {
            Text(
                if (isBuy) "BUY" else "SELL",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(typeName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${qty}x @ ${formatIsk(unitPrice)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatIsk(total),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = color,
            )
            Text(
                date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun AlertRow(alert: PriceAlertModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Notifications, null, tint = Color(0xFFFF8C00), modifier = Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(alert.typeName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${alert.condition} ${formatIsk(alert.targetPrice)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Surface(
            color = Color(0xFFFF8C00).copy(alpha = 0.15f),
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(
                alert.orderType.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFF8C00),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

// ─── Formatting ───────────────────────────────────────────────────────────

private fun formatIsk(value: Double, showSign: Boolean = false): String {
    val abs  = kotlin.math.abs(value)
    val sign = if (showSign && value > 0) "+" else if (value < 0) "-" else ""
    val formatted = when {
        abs >= 1_000_000_000_000.0 -> String.format("%.2fT", abs / 1_000_000_000_000.0)
        abs >= 1_000_000_000.0     -> String.format("%.2fB", abs / 1_000_000_000.0)
        abs >= 1_000_000.0         -> String.format("%.2fM", abs / 1_000_000.0)
        abs >= 1_000.0             -> String.format("%.2fK", abs / 1_000.0)
        else                       -> String.format("%,.2f", abs)
    }
    return "$sign$formatted"
}
