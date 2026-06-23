package org.eve.trader.features.contracts

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eve.trader.core.database.ContractDao
import org.eve.trader.core.database.DatabaseManager
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.core.model.ContractModel
import org.eve.trader.ui.common.*

@Composable
fun ContractTrackerScreen(charId: Int?) {
    val scope = rememberCoroutineScope()
    var contracts by remember { mutableStateOf<List<ContractModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf("all") }

    LaunchedEffect(Unit) {
        loadContracts { contracts = it }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Contract Tracker", style = MaterialTheme.typography.headlineMedium)
            charId?.let { id ->
                Button(
                    onClick = {
                        isLoading = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val rawContracts = EsiClient.getCharacterContracts(id)
                                val models = rawContracts.mapNotNull { raw ->
                                    val contractId = (raw["contract_id"] as? Number)?.toInt() ?: return@mapNotNull null
                                    ContractModel(
                                        contractId = contractId,
                                        issuerId = (raw["issuer_id"] as? Number)?.toInt() ?: 0,
                                        issuerCorpId = (raw["issuer_corporation_id"] as? Number)?.toInt() ?: 0,
                                        assigneeId = (raw["assignee_id"] as? Number)?.toInt() ?: 0,
                                        acceptorId = (raw["acceptor_id"] as? Number)?.toInt() ?: 0,
                                        startStationId = (raw["start_location_id"] as? Number)?.toLong() ?: 0,
                                        endStationId = (raw["end_location_id"] as? Number)?.toLong() ?: 0,
                                        type = raw["type"] as? String ?: "unknown",
                                        status = raw["status"] as? String ?: "outstanding",
                                        title = raw["title"] as? String ?: "",
                                        description = raw["description"] as? String ?: "",
                                        dateIssued = raw["date_issued"] as? String ?: "",
                                        dateExpired = raw["date_expired"] as? String ?: "",
                                        dateAccepted = raw["date_accepted"] as? String,
                                        dateCompleted = raw["date_completed"] as? String,
                                        numDays = (raw["num_days"] as? Number)?.toInt() ?: 0,
                                        price = (raw["price"] as? Number)?.toDouble() ?: 0.0,
                                        reward = (raw["reward"] as? Number)?.toDouble() ?: 0.0,
                                        collateral = (raw["collateral"] as? Number)?.toDouble() ?: 0.0,
                                        buyout = (raw["buyout"] as? Number)?.toDouble() ?: 0.0,
                                        forCorp = (raw["for_corporation"] as? Boolean) ?: false,
                                        isCorp = false,
                                        characterId = id,
                                    )
                                }
                                models.forEach { ContractDao.upsert(it) }
                                withContext(Dispatchers.Main) {
                                    loadContracts { contracts = it }
                                    isLoading = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { isLoading = false }
                            }
                        }
                    },
                    enabled = !isLoading,
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Refresh")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status filters
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(listOf("all" to "All", "outstanding" to "Outstanding", "in_progress" to "In Progress", "finished" to "Finished", "cancelled" to "Cancelled")) { (key, label) ->
                FilterChip(
                    selected = statusFilter == key,
                    onClick = {
                        statusFilter = key
                        if (key == "all") {
                            loadContracts { contracts = it }
                        } else {
                            loadContractsByStatus(key) { contracts = it }
                        }
                    },
                    label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Contract list
        if (contracts.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Folder,
                title = "No Contracts",
                description = "Select a character to fetch contracts from ESI.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(contracts) { contract ->
                    ContractCard(contract)
                }
            }
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Fetching contracts from ESI...")
}

@Composable
private fun ContractCard(contract: ContractModel) {
    val statusColor = when (contract.status) {
        "outstanding" -> Color(0xFFB197FC)
        "in_progress" -> Color(0xFFFF8C00)
        "finished" -> Color(0xFF69DB7C)
        "cancelled" -> Color(0xFFFF6B6B)
        else -> Color.Gray
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (contract.title.isNotEmpty()) {
                        Text(contract.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "${contract.type.replace("_", " ").capitalize()} • ${contract.status.replace("_", " ").capitalize()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                    )
                }

                Surface(color = statusColor.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
                    Text(
                        text = contract.status.replace("_", " ").capitalize(),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Financial info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (contract.price > 0) {
                    InfoItem("Price", formatIsk(contract.price))
                }
                if (contract.reward > 0) {
                    InfoItem("Reward", formatIsk(contract.reward))
                }
                if (contract.collateral > 0) {
                    InfoItem("Collateral", formatIsk(contract.collateral))
                }
            }

            // Dates
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Issued: ${contract.dateIssued.take(10)}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text("Expires: ${contract.dateExpired.take(10)}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun loadContracts(callback: (List<ContractModel>) -> Unit) {
    try {
        callback(ContractDao.getAll())
    } catch (e: Exception) {
        println("Error loading contracts: ${e.message}")
    }
}

private fun loadContractsByStatus(status: String, callback: (List<ContractModel>) -> Unit) {
    try {
        callback(ContractDao.getByStatus(status))
    } catch (e: Exception) {
        println("Error loading contracts by status: ${e.message}")
    }
}

private fun String.capitalize(): String {
    return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

private fun formatIsk(value: Double): String {
    return when {
        value >= 1_000_000_000_000 -> String.format("%.2fT", value / 1_000_000_000_000)
        value >= 1_000_000_000 -> String.format("%.2fB", value / 1_000_000_000)
        value >= 1_000_000 -> String.format("%.2fM", value / 1_000_000)
        else -> String.format("%,.2f", value)
    }
}
