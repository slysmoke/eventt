package org.eventt.features.contracts

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.ContractDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.ViewContext
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.ContractModel
import org.eventt.ui.common.*
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor

private const val SHOW_ALL_CONTRACTS_SETTING = "contracts.show_all"

@Composable
fun ContractTrackerScreen(context: ViewContext?) {
    val scope = rememberCoroutineScope()
    val charId = (context as? ViewContext.Character)?.charId
    val corpId = (context as? ViewContext.Corporation)?.corporationId
    val actingCharId = context?.actingCharId

    var contracts by remember { mutableStateOf<List<ContractModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf("all") }
    // Ignores the character/corp scope below and shows every locally-stored contract, across
    // every character and corporation — mirrors Dashboard's "Combine all" switch. Persisted.
    var showAll by remember { mutableStateOf(false) }
    // Persisted; also gates ContractWatchService's background sweep (see there for why it's
    // opt-in rather than always-on).
    var autoRefresh by remember { mutableStateOf(false) }
    var refreshAvailableAt by remember { mutableStateOf<Long?>(null) }

    fun contractsEndpoint(): String? =
        when {
            corpId != null -> "/corporations/$corpId/contracts/"
            charId != null -> "/characters/$charId/contracts/"
            else -> null
        }

    fun reload() {
        contracts =
            when {
                showAll && statusFilter == "all" -> ContractDao.getAll()
                showAll -> ContractDao.getByStatus(statusFilter)
                statusFilter == "all" -> ContractDao.getAll(characterId = charId, corporationId = corpId)
                else -> ContractDao.getByStatus(statusFilter, characterId = charId, corporationId = corpId)
            }
    }

    fun refresh() {
        val acting = actingCharId ?: return
        isLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                ContractSyncService.refresh(characterId = charId, corporationId = corpId, actingCharId = acting)
                val expiry = contractsEndpoint()?.let { EsiClient.getEndpointExpiry(it) }
                withContext(Dispatchers.Main) {
                    reload()
                    refreshAvailableAt = expiry
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            showAll = StaticDataDao.getSetting(SHOW_ALL_CONTRACTS_SETTING) == "true"
            autoRefresh = StaticDataDao.getSetting(CONTRACTS_AUTO_REFRESH_SETTING) == "true"
        }
    }

    LaunchedEffect(context, showAll, statusFilter) {
        withContext(Dispatchers.IO) { reload() }
        refreshAvailableAt =
            if (showAll) {
                null
            } else {
                withContext(Dispatchers.IO) { contractsEndpoint()?.let { EsiClient.getEndpointExpiry(it) } }
            }
    }

    // Keeps refreshing this context's contracts on an interval while the toggle is on and this
    // screen is open — ContractWatchService's own sweep covers the "screen not open" case.
    LaunchedEffect(autoRefresh, context, showAll) {
        if (!autoRefresh || showAll || actingCharId == null) return@LaunchedEffect
        while (true) {
            delay(CONTRACTS_REFRESH_INTERVAL_MILLIS)
            refresh()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Contract Tracker", style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = autoRefresh,
                    onClick = {
                        autoRefresh = !autoRefresh
                        scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(CONTRACTS_AUTO_REFRESH_SETTING, autoRefresh.toString()) }
                    },
                    label = { Text("Auto-refresh", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = if (autoRefresh) autoRefreshIcon else null,
                )
                FilterChip(
                    selected = showAll,
                    onClick = {
                        showAll = !showAll
                        scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(SHOW_ALL_CONTRACTS_SETTING, showAll.toString()) }
                    },
                    label = { Text("Show All", style = MaterialTheme.typography.bodySmall) },
                )
                if (!showAll && actingCharId != null) {
                    EsiRefreshButton(
                        isLoading = isLoading,
                        expiresAtMs = refreshAvailableAt,
                        onClick = ::refresh,
                        label = "Refresh",
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status filters
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(
                listOf(
                    "all" to "All",
                    "outstanding" to "Outstanding",
                    "in_progress" to "In Progress",
                    "finished" to "Finished",
                    "cancelled" to "Cancelled",
                ),
            ) { (key, label) ->
                FilterChip(
                    selected = statusFilter == key,
                    onClick = { statusFilter = key },
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
                items(contracts, key = { it.contractId }) { contract ->
                    ContractCard(contract)
                }
            }
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Fetching contracts from ESI...")
}

private val autoRefreshIcon: @Composable () -> Unit = {
    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
}

@Composable
private fun ContractCard(contract: ContractModel) {
    val statusColor =
        when (contract.status) {
            "outstanding" -> Color(0xFFB197FC)
            "in_progress" -> warningColor
            "finished" -> positiveColor
            "cancelled" -> negativeColor
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
private fun InfoItem(
    label: String,
    value: String,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
