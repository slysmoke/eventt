package org.eve.trader.features.wallet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eve.trader.core.database.DatabaseManager
import org.eve.trader.core.database.WalletDao
import org.eve.trader.core.database.CharacterDao
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.ui.common.*

@Composable
fun WalletScreen() {
    val scope = rememberCoroutineScope()
    var selectedCharacter by remember { mutableStateOf<org.eve.trader.core.model.CharacterModel?>(null) }
    var balance by remember { mutableStateOf(0.0) }
    var dailyBreakdown by remember { mutableStateOf<List<org.eve.trader.core.model.DailyWalletEntry>>(emptyList()) }
    var transactions by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var journal by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val chars = withContext(Dispatchers.IO) { CharacterDao.getAll() }
        if (chars.isNotEmpty()) {
            selectedCharacter = chars[0]
            loadWalletData(chars[0].id,
                balanceCallback = { balance = it },
                dailyCallback = { dailyBreakdown = it },
                transactionsCallback = { transactions = it },
                journalCallback = { journal = it },
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Wallet", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(8.dp))

        // Character selector
        val characters = remember {
            try { CharacterDao.getAll() } catch (e: Exception) { emptyList() }
        }
        if (characters.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(characters) { char ->
                    FilterChip(
                        selected = selectedCharacter?.id == char.id,
                        onClick = {
                            selectedCharacter = char
                            scope.launch {
                                loadWalletData(char.id,
                                    balanceCallback = { balance = it },
                                    dailyCallback = { dailyBreakdown = it },
                                    transactionsCallback = { transactions = it },
                                    journalCallback = { journal = it },
                                )
                            }
                        },
                        label = { Text(char.name, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Balance card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Balance", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    text = "${formatIsk(balance)} ISK",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val totalEarned = dailyBreakdown.sumOf { it.income }
                    val totalSpent = dailyBreakdown.sumOf { it.expenses }
                    Text("Earned: ${formatIsk(totalEarned)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF69DB7C))
                    Text("Spent: ${formatIsk(totalSpent)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF6B6B))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tabs
        TabRow(selectedTabIndex = activeTab) {
            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text("Transactions") })
            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }, text = { Text("Journal") })
            Tab(selected = activeTab == 2, onClick = { activeTab = 2 }, text = { Text("P&L") })
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tab content
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                0 -> TransactionList(transactions)
                1 -> JournalList(journal)
                2 -> PnlChart(dailyBreakdown)
            }
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Loading wallet data...")
}

@Composable
private fun TransactionList(transactions: List<Map<String, Any?>>) {
    if (transactions.isEmpty()) {
        EmptyState(icon = Icons.Default.Receipt, title = "No Transactions", description = "Click refresh to fetch from ESI.")
        return
    }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(transactions) { tx ->
            val isBuy = tx["is_buy"] as? Boolean ?: false
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isBuy) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        contentDescription = null,
                        tint = if (isBuy) Color(0xFF69DB7C) else Color(0xFFFF6B6B),
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(tx["type_name"]?.toString() ?: "Unknown", style = MaterialTheme.typography.bodyMedium)
                        Text(tx["date"]?.toString()?.take(10) ?: "", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("×${tx["quantity"]}", style = MaterialTheme.typography.bodySmall)
                    val total = (tx["total"] as? Number)?.toDouble() ?: 0.0
                    Text(formatIsk(total), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun JournalList(journal: List<Map<String, Any?>>) {
    if (journal.isEmpty()) {
        EmptyState(icon = Icons.AutoMirrored.Filled.List, title = "No Journal Entries", description = "Fetch journal from ESI to see entries.")
        return
    }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(journal) { entry ->
            val amount = (entry["amount"] as? Number)?.toDouble() ?: 0.0
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry["ref_type"]?.toString() ?: "", style = MaterialTheme.typography.bodyMedium)
                    Text(entry["date"]?.toString()?.take(10) ?: "", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                Text(
                    text = "${if (amount >= 0) "+" else ""}${formatIsk(amount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (amount >= 0) Color(0xFF69DB7C) else Color(0xFFFF6B6B),
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun PnlChart(dailyBreakdown: List<org.eve.trader.core.model.DailyWalletEntry>) {
    if (dailyBreakdown.isEmpty()) {
        EmptyState(icon = Icons.AutoMirrored.Filled.ShowChart, title = "No P&L Data", description = "Need journal entries to calculate P&L.")
        return
    }

    ContentCard("Daily Net P&L") {
        val netValues = dailyBreakdown.reversed().map { it.net }
        SparklineBarChart(
            data = netValues,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
    }
}

@Composable
private fun SparklineBarChart(data: List<Double>, modifier: Modifier = Modifier) {
    if (data.isEmpty()) return

    val maxAbs = data.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0

    androidx.compose.foundation.Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barWidth = (width / data.size.coerceAtLeast(1)) - 2f
        val centerY = height / 2

        data.forEachIndexed { index, value ->
            val x = index * (barWidth + 2f)
            val barHeight = if (maxAbs > 0) (kotlin.math.abs(value) / maxAbs * centerY).toFloat() else 0f
            val isPositive = value >= 0
            val y = if (isPositive) centerY - barHeight else centerY
            val color = if (isPositive) Color(0xFF69DB7C) else Color(0xFFFF6B6B)

            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
            )
        }
    }
}

private suspend fun loadWalletData(
    characterId: Int,
    balanceCallback: (Double) -> Unit,
    dailyCallback: (List<org.eve.trader.core.model.DailyWalletEntry>) -> Unit,
    transactionsCallback: (List<Map<String, Any?>>) -> Unit,
    journalCallback: (List<Map<String, Any?>>) -> Unit,
) {
    withContext(Dispatchers.IO) {
        // Load from DB
        val summary = WalletDao.getWalletSummary(characterId = characterId)
        balanceCallback(summary.balance)
        dailyCallback(summary.dailyBreakdown)
        transactionsCallback(WalletDao.getTransactions(characterId = characterId, limit = 50))
        journalCallback(WalletDao.getJournalEntries(characterId = characterId, limit = 50))

        // Fetch from ESI
        try {
            val walletBalance = EsiClient.getCharacterWallet(characterId)
            balanceCallback(walletBalance)
        } catch (e: Exception) {
            println("Error fetching wallet: ${e.message}")
        }

        try {
            val journalEntries = EsiClient.getCharacterJournal(characterId)
            journalEntries.forEach { entry ->
                try {
                    WalletDao.insertJournalEntry(
                        entryId = (entry["id"] as? Number)?.toLong() ?: 0,
                        date = entry["date"] as? String ?: "",
                        amount = (entry["amount"] as? Number)?.toDouble() ?: 0.0,
                        balance = (entry["balance"] as? Number)?.toDouble() ?: 0.0,
                        reason = entry["reason"] as? String ?: "",
                        refType = entry["ref_type"] as? String ?: "",
                        firstPartyId = (entry["first_party_id"] as? Number)?.toInt() ?: 0,
                        firstPartyName = "",
                        secondPartyId = (entry["second_party_id"] as? Number)?.toInt() ?: 0,
                        secondPartyName = "",
                        taxAmount = (entry["tax"] as? Number)?.toDouble(),
                        isCorp = false,
                        characterId = characterId,
                        corporationId = null,
                        divisionId = null,
                    )
                } catch (e: Exception) { /* skip duplicates or bad entries */ }
            }
            journalCallback(WalletDao.getJournalEntries(characterId = characterId, limit = 50))
        } catch (e: Exception) {
            println("Error fetching journal: ${e.message}")
        }

        try {
            val txList = EsiClient.getCharacterTransactions(characterId)
            txList.forEach { tx ->
                try {
                    WalletDao.insertTransaction(
                        transactionId = (tx["transaction_id"] as? Number)?.toLong() ?: 0,
                        date = tx["date"] as? String ?: "",
                        typeId = (tx["type_id"] as? Number)?.toInt() ?: 0,
                        typeName = "",
                        quantity = (tx["quantity"] as? Number)?.toInt() ?: 0,
                        unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0,
                        total = (tx["total"] as? Number)?.toDouble() ?: 0.0,
                        isBuy = (tx["is_buy"] as? Boolean) ?: false,
                        clientId = (tx["client_id"] as? Number)?.toInt() ?: 0,
                        clientName = "",
                        locationId = (tx["location_id"] as? Number)?.toLong() ?: 0,
                        locationName = "",
                        isCorp = false,
                        characterId = characterId,
                        corporationId = null,
                    )
                } catch (e: Exception) { /* skip duplicates */ }
            }
            transactionsCallback(WalletDao.getTransactions(characterId = characterId, limit = 50))
        } catch (e: Exception) {
            println("Error fetching transactions: ${e.message}")
        }
    }
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
