package org.eve.trader.features.wallet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import org.eve.trader.core.database.WalletDao
import org.eve.trader.core.database.StaticDataDao
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.ui.common.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background

@Composable
fun WalletScreen(charId: Int?) {
    val scope = rememberCoroutineScope()
    var balance by remember { mutableStateOf(0.0) }
    var dailyBreakdown by remember { mutableStateOf<List<org.eve.trader.core.model.DailyWalletEntry>>(emptyList()) }
    var transactions by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var journal by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(0) }

    LaunchedEffect(charId) {
        if (charId != null) {
            loadWalletData(charId,
                balanceCallback = { balance = it },
                dailyCallback = { dailyBreakdown = it },
                transactionsCallback = { transactions = it },
                journalCallback = { journal = it },
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Wallet", style = MaterialTheme.typography.headlineMedium)
            charId?.let { id ->
                IconButton(onClick = {
                    scope.launch {
                        loadWalletData(id,
                            balanceCallback = { balance = it },
                            dailyCallback = { dailyBreakdown = it },
                            transactionsCallback = { transactions = it },
                            journalCallback = { journal = it },
                        )
                    }
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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
        EmptyState(icon = Icons.Default.Receipt, title = "No Transactions", description = "Select a character to load transactions.")
        return
    }

    Column {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TxHeader("Date",       Modifier.weight(1.8f))
            TxHeader("B/S",        Modifier.weight(0.6f))
            TxHeader("Item",       Modifier.weight(3f))
            TxHeader("Qty",        Modifier.weight(1f), rightAlign = true)
            TxHeader("Unit Price", Modifier.weight(2f), rightAlign = true)
            TxHeader("Total",      Modifier.weight(2f), rightAlign = true)
            TxHeader("Client",     Modifier.weight(2f))
            TxHeader("Station",    Modifier.weight(2.5f))
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(transactions) { tx ->
                val isBuy = tx["is_buy"] as? Boolean ?: false
                val unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0
                val quantity = (tx["quantity"] as? Number)?.toInt() ?: 0
                val total = (tx["total"] as? Number)?.toDouble()
                    ?.takeIf { it > 0 } ?: (unitPrice * quantity)
                val typeName = tx["type_name"]?.toString()?.ifEmpty { null }
                    ?: "Unknown (${tx["type_id"]})"
                val clientName = tx["client_name"]?.toString()?.ifEmpty { null }
                    ?: tx["client_id"]?.let { "#$it" } ?: ""
                val locationName = tx["location_name"]?.toString()?.ifEmpty { null }
                    ?: tx["location_id"]?.toString() ?: ""
                val dateStr = tx["date"]?.toString()?.take(16)?.replace("T", " ") ?: ""
                val buyColor = Color(0xFF69DB7C)
                val sellColor = Color(0xFFFF6B6B)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(dateStr, modifier = Modifier.weight(1.8f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(
                        modifier = Modifier.weight(0.6f),
                        color = (if (isBuy) buyColor else sellColor).copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            if (isBuy) "Buy" else "Sell",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isBuy) buyColor else sellColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(typeName, modifier = Modifier.weight(3f).padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis, maxLines = 1)
                    Text("%,d".format(quantity), modifier = Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
                    Text(formatIsk(unitPrice), modifier = Modifier.weight(2f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
                    Text(
                        formatIsk(total),
                        modifier = Modifier.weight(2f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (isBuy) sellColor else buyColor,
                    )
                    Text(clientName, modifier = Modifier.weight(2f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, overflow = TextOverflow.Ellipsis, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(locationName, modifier = Modifier.weight(2.5f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, overflow = TextOverflow.Ellipsis, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun TxHeader(label: String, modifier: Modifier, rightAlign: Boolean = false) {
    Text(
        label,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (rightAlign) TextAlign.End else TextAlign.Start,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun JournalList(journal: List<Map<String, Any?>>) {
    if (journal.isEmpty()) {
        EmptyState(icon = Icons.AutoMirrored.Filled.List, title = "No Journal Entries", description = "Select a character to load journal.")
        return
    }

    val totalTax    = journal.filter { it["ref_type"] == "transaction_tax" }.sumOf { (it["amount"] as? Number)?.toDouble() ?: 0.0 }
    val totalBroker = journal.filter { it["ref_type"] == "brokers_fee"     }.sumOf { (it["amount"] as? Number)?.toDouble() ?: 0.0 }

    Column {
        // Tax summary
        if (totalTax != 0.0 || totalBroker != 0.0) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text("Taxes paid (shown entries):", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    if (totalTax != 0.0) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Sales Tax", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(formatIsk(totalTax), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFFFF6B6B))
                        }
                    }
                    if (totalBroker != 0.0) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Broker's Fee", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(formatIsk(totalBroker), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFFFF6B6B))
                        }
                    }
                    if (totalTax != 0.0 && totalBroker != 0.0) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(formatIsk(totalTax + totalBroker), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFFFF6B6B))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Table header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TxHeader("Date",        Modifier.weight(1.8f))
            TxHeader("Type",        Modifier.weight(2.5f))
            TxHeader("Description", Modifier.weight(3f))
            TxHeader("Amount",      Modifier.weight(2f), rightAlign = true)
            TxHeader("Tax",         Modifier.weight(1.5f), rightAlign = true)
            TxHeader("Balance",     Modifier.weight(2f), rightAlign = true)
        }
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(journal) { entry ->
                val amount    = (entry["amount"]     as? Number)?.toDouble() ?: 0.0
                val taxAmount = (entry["tax_amount"] as? Number)?.toDouble()
                val balance   = (entry["balance"]    as? Number)?.toDouble() ?: 0.0
                val refType   = entry["ref_type"]?.toString() ?: ""
                val reason    = entry["reason"]?.toString()?.trim() ?: ""
                val dateStr   = entry["date"]?.toString()?.take(16)?.replace("T", " ") ?: ""
                val amountColor = if (amount >= 0) Color(0xFF69DB7C) else Color(0xFFFF6B6B)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(dateStr,                 modifier = Modifier.weight(1.8f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatRefType(refType),  modifier = Modifier.weight(2.5f), style = MaterialTheme.typography.bodySmall, overflow = TextOverflow.Ellipsis, maxLines = 1)
                    Text(reason,                  modifier = Modifier.weight(3f).padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis, maxLines = 1)
                    Text(
                        "${if (amount >= 0) "+" else ""}${formatIsk(amount)}",
                        modifier = Modifier.weight(2f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = amountColor,
                    )
                    Text(
                        if (taxAmount != null && taxAmount != 0.0) formatIsk(taxAmount) else "—",
                        modifier = Modifier.weight(1.5f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (taxAmount != null && taxAmount != 0.0) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatIsk(balance),
                        modifier = Modifier.weight(2f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

private fun formatRefType(refType: String): String = when (refType) {
    "transaction_tax"                  -> "Sales Tax"
    "brokers_fee"                      -> "Broker's Fee"
    "market_transaction"               -> "Market Transaction"
    "market_escrow"                    -> "Buy Order Escrow"
    "market_escrow_refund"             -> "Escrow Refund"
    "player_trading"                   -> "Trade"
    "contract_price"                   -> "Contract"
    "contract_reward"                  -> "Contract Reward"
    "contract_deposit"                 -> "Contract Deposit"
    "contract_deposit_refund"          -> "Contract Deposit Refund"
    "contract_price_payment_corp"      -> "Corp Contract"
    "bounty_prizes"                    -> "Bounty"
    "industry_job_tax"                 -> "Industry Tax"
    "manufacturing"                    -> "Manufacturing"
    "reprocessing_tax"                 -> "Reprocessing Tax"
    "jump_clone_installation_fee"      -> "Clone Jump Fee"
    "planetary_import_tax"             -> "PI Import Tax"
    "planetary_export_tax"             -> "PI Export Tax"
    "corporation_account_withdrawal"   -> "Corp Withdrawal"
    "corporation_dividend_payment"     -> "Dividend"
    "structure_gate_jump"              -> "Jump Gate"
    "asset_safety_recovery_tax"        -> "Asset Safety Tax"
    "skill_purchase"                   -> "Skill Purchase"
    "agent_mission_reward"             -> "Mission Reward"
    "agent_mission_time_bonus_reward"  -> "Mission Bonus"
    else -> refType.replace('_', ' ').split(' ')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
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
        // Load from DB, resolve names (local + ESI fallback for old records)
        val summary = WalletDao.getWalletSummary(characterId = characterId)
        balanceCallback(summary.balance)
        dailyCallback(summary.dailyBreakdown)
        transactionsCallback(resolveAllNames(WalletDao.getTransactions(characterId = characterId, limit = 200)))
        journalCallback(WalletDao.getJournalEntries(characterId = characterId, limit = 200))

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
            journalCallback(WalletDao.getJournalEntries(characterId = characterId, limit = 200))
        } catch (e: Exception) {
            println("Error fetching journal: ${e.message}")
        }

        try {
            val txList = EsiClient.getCharacterTransactions(characterId)

            // Batch-resolve names before inserting
            val typeIds = txList.mapNotNull { (it["type_id"] as? Number)?.toInt() }.toSet()
            val typeNames = typeIds.associateWith { id -> StaticDataDao.getTypeName(id) ?: "" }

            val locationIds = txList.mapNotNull { (it["location_id"] as? Number)?.toLong() }.toSet()
            val locationNames = locationIds.associateWith { id -> StaticDataDao.getStationById(id)?.name ?: "" }

            val clientIds = txList.mapNotNull { (it["client_id"] as? Number)?.toInt() }.filter { it > 0 }.toSet()
            val clientNames = if (clientIds.isNotEmpty()) EsiClient.resolveNames(clientIds.toList()) else emptyMap()

            txList.forEach { tx ->
                val typeId = (tx["type_id"] as? Number)?.toInt() ?: 0
                val locationId = (tx["location_id"] as? Number)?.toLong() ?: 0L
                val clientId = (tx["client_id"] as? Number)?.toInt() ?: 0
                val unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0
                val quantity = (tx["quantity"] as? Number)?.toInt() ?: 0
                try {
                    WalletDao.insertTransaction(
                        transactionId = (tx["transaction_id"] as? Number)?.toLong() ?: 0,
                        date = tx["date"] as? String ?: "",
                        typeId = typeId,
                        typeName = typeNames[typeId] ?: "",
                        quantity = quantity,
                        unitPrice = unitPrice,
                        total = unitPrice * quantity,
                        isBuy = (tx["is_buy"] as? Boolean) ?: false,
                        clientId = clientId,
                        clientName = clientNames[clientId] ?: "",
                        locationId = locationId,
                        locationName = locationNames[locationId] ?: "",
                        isCorp = false,
                        characterId = characterId,
                        corporationId = null,
                    )
                } catch (_: Exception) {}
            }
            transactionsCallback(resolveAllNames(WalletDao.getTransactions(characterId = characterId, limit = 200)))
        } catch (e: Exception) {
            println("Error fetching transactions: ${e.message}")
        }
    }
}

// Resolves type/station names locally, then falls back to ESI /universe/names/ for anything
// still missing (old DB records inserted before name-resolution was in place).
// Persists resolved names back to the DB so subsequent loads don't need ESI.
private fun resolveAllNames(rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
    val afterLocal = resolveLocalNames(rows)

    // Collect IDs that are still unresolved
    val missingClientIds = afterLocal
        .filter { (it["client_name"] as? String).isNullOrEmpty() }
        .mapNotNull { (it["client_id"] as? Number)?.toInt() }
        .filter { it > 0 }.distinct()

    // Only NPC station IDs fit in Int; citadels (> 10^12) must come from static_stations
    val missingLocationIds = afterLocal
        .filter { (it["location_name"] as? String).isNullOrEmpty() }
        .mapNotNull { tx ->
            val id = (tx["location_id"] as? Number)?.toLong() ?: return@mapNotNull null
            if (id > Int.MAX_VALUE.toLong()) null else id.toInt()
        }
        .filter { it > 0 }.distinct()

    val toResolve = (missingClientIds + missingLocationIds).distinct()
    if (toResolve.isEmpty()) return afterLocal

    val esiNames = try { EsiClient.resolveNames(toResolve) } catch (_: Exception) { emptyMap() }
    if (esiNames.isEmpty()) return afterLocal

    val clientSet   = missingClientIds.toSet()
    val locationSet = missingLocationIds.toSet()

    return afterLocal.map { tx ->
        val txId = (tx["transaction_id"] as? Number)?.toLong() ?: return@map tx

        val newClient = if ((tx["client_name"] as? String).isNullOrEmpty()) {
            val id = (tx["client_id"] as? Number)?.toInt() ?: 0
            if (id in clientSet) esiNames[id] else null
        } else null

        val newLocation = if ((tx["location_name"] as? String).isNullOrEmpty()) {
            val lid = (tx["location_id"] as? Number)?.toLong() ?: 0L
            if (lid <= Int.MAX_VALUE && lid.toInt() in locationSet) esiNames[lid.toInt()] else null
        } else null

        if (newClient == null && newLocation == null) return@map tx

        // Persist so next load skips ESI
        WalletDao.updateTransactionNames(txId, clientName = newClient, locationName = newLocation)

        tx.toMutableMap().apply {
            newClient?.let   { put("client_name",   it) }
            newLocation?.let { put("location_name", it) }
        }
    }
}

private fun resolveLocalNames(rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
    return rows.map { tx ->
        val needsType = (tx["type_name"] as? String).isNullOrEmpty()
        val needsLocation = (tx["location_name"] as? String).isNullOrEmpty()
        if (!needsType && !needsLocation) return@map tx
        val updated = tx.toMutableMap()
        if (needsType) {
            val typeId = (tx["type_id"] as? Number)?.toInt() ?: 0
            updated["type_name"] = StaticDataDao.getTypeName(typeId) ?: ""
        }
        if (needsLocation) {
            val locationId = (tx["location_id"] as? Number)?.toLong() ?: 0L
            updated["location_name"] = StaticDataDao.getStationById(locationId)?.name ?: ""
        }
        updated
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
