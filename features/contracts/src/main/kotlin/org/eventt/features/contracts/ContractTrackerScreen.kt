package org.eventt.features.contracts

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.ContractDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.ViewContext
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.ContractItemModel
import org.eventt.core.model.ContractModel
import org.eventt.core.model.CorpFeature
import org.eventt.ui.common.*
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor

private const val SHOW_ALL_CONTRACTS_SETTING = "contracts.show_all"

private enum class SortField { ISSUED, COMPLETED }

private enum class SortDirection { ASC, DESC }

@Composable
fun ContractTrackerScreen(context: ViewContext?) {
    val scope = rememberCoroutineScope()
    val charId = (context as? ViewContext.Character)?.charId
    val corpId = (context as? ViewContext.Corporation)?.corporationId
    val actingCharId = context?.actingCharId

    var contracts by remember { mutableStateOf<List<ContractModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf("all") }
    var typeFilter by remember { mutableStateOf("all") }
    var sortField by remember { mutableStateOf(SortField.ISSUED) }
    var sortDirection by remember { mutableStateOf(SortDirection.DESC) }
    var expandedContractId by remember { mutableStateOf<Int?>(null) }
    // Ignores the character/corp scope below and shows every locally-stored contract, across
    // every character and corporation — mirrors Dashboard's "Combine all" switch. Persisted.
    var showAll by remember { mutableStateOf(false) }
    // Persisted; also gates ContractWatchService's background sweep (see there for why it's
    // opt-in rather than always-on).
    var autoRefresh by remember { mutableStateOf(false) }
    var refreshAvailableAt by remember { mutableStateOf<Long?>(null) }
    var deniedFeatures by remember { mutableStateOf<Set<CorpFeature>>(emptySet()) }

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

    fun refresh(clearDenied: Boolean = false) {
        val acting = actingCharId ?: return
        isLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                if (clearDenied) {
                    CorpFeature.entries.forEach { CharacterDao.setCorpFeatureDenied(acting, it, denied = false) }
                }
                ContractSyncService.refresh(characterId = charId, corporationId = corpId, actingCharId = acting)
                val expiry = contractsEndpoint()?.let { EsiClient.getEndpointExpiry(it) }
                val denied = if (corpId != null) CharacterDao.getDeniedCorpFeatures(acting) else emptySet()
                withContext(Dispatchers.Main) {
                    reload()
                    refreshAvailableAt = expiry
                    deniedFeatures = denied
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
        deniedFeatures =
            if (corpId != null && actingCharId != null) {
                withContext(Dispatchers.IO) { CharacterDao.getDeniedCorpFeatures(actingCharId) }
            } else {
                emptySet()
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

    fun toggleSort(field: SortField) {
        if (sortField == field) {
            sortDirection = if (sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
        } else {
            sortField = field
            sortDirection = SortDirection.DESC
        }
    }

    val visibleContracts =
        remember(contracts, typeFilter, sortField, sortDirection) {
            val filtered = if (typeFilter == "all") contracts else contracts.filter { it.type == typeFilter }
            val sorted =
                when (sortField) {
                    SortField.ISSUED -> filtered.sortedBy { it.dateIssued }

                    // Contracts with no completion date (still outstanding/in progress) sort last
                    // regardless of direction — interleaving them by absence of a date is meaningless.
                    SortField.COMPLETED -> filtered.sortedWith(compareBy(nullsLast()) { it.dateCompleted })
                }
            if (sortDirection == SortDirection.DESC) sorted.reversed() else sorted
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
                        onClick = { refresh() },
                        label = "Refresh",
                    )
                }
            }
        }

        if (corpId != null && deniedFeatures.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            CorpAccessNotice(deniedFeatures, onRetry = { refresh(clearDenied = true) })
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

        Spacer(modifier = Modifier.height(4.dp))

        // Type filters
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(
                listOf(
                    "all" to "All types",
                    "item_exchange" to "Item Exchange",
                    "courier" to "Courier",
                    "auction" to "Auction",
                    "loan" to "Loan",
                ),
            ) { (key, label) ->
                FilterChip(
                    selected = typeFilter == key,
                    onClick = { typeFilter = key },
                    label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Sort controls
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SortChip("Issued", SortField.ISSUED, sortField, sortDirection) { toggleSort(SortField.ISSUED) }
            SortChip("Completed", SortField.COMPLETED, sortField, sortDirection) { toggleSort(SortField.COMPLETED) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Contract list
        if (visibleContracts.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Folder,
                title = "No Contracts",
                description = "Select a character to fetch contracts from ESI.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(visibleContracts, key = { it.contractId }) { contract ->
                    ContractCard(
                        contract,
                        actingCharId = actingCharId,
                        expanded = expandedContractId == contract.contractId,
                        onToggleExpand = {
                            expandedContractId = if (expandedContractId == contract.contractId) null else contract.contractId
                        },
                    )
                }
            }
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Fetching contracts from ESI...")
}

@Composable
private fun SortChip(
    label: String,
    field: SortField,
    activeField: SortField,
    direction: SortDirection,
    onClick: () -> Unit,
) {
    val active = field == activeField
    FilterChip(
        selected = active,
        onClick = onClick,
        label = {
            Text(
                if (active) "$label ${if (direction == SortDirection.ASC) "↑" else "↓"}" else label,
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
}

private val autoRefreshIcon: @Composable () -> Unit = {
    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
}

@Composable
private fun ContractCard(
    contract: ContractModel,
    actingCharId: Int?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    var items by remember(contract.contractId) { mutableStateOf<List<ContractItemModel>?>(null) }
    var partyNames by remember(contract.contractId) { mutableStateOf<Map<Int, String>>(emptyMap()) }

    // Fetched lazily on first expand (cached locally afterward) rather than for every visible
    // card up front — items/party names are only useful once you're actually looking at one
    // contract's details, and fetching them for a whole scrollable list would mean an ESI call
    // per contract on every load.
    LaunchedEffect(expanded) {
        if (!expanded || items != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val cached = ContractDao.getItemsForContract(contract.contractId)
            // ESI's items endpoint is always character-or-corporation scoped, never bare by
            // contract id — a personal contract uses its own owning character, a corp contract
            // (no per-row owner) falls back to whichever character is currently acting for it.
            val effectiveCharId = contract.characterId ?: actingCharId
            items =
                cached.ifEmpty {
                    if (effectiveCharId == null) {
                        emptyList()
                    } else {
                        val raw =
                            runCatching {
                                EsiClient.getContractItems(
                                    contract.contractId,
                                    characterId = effectiveCharId,
                                    corporationId = contract.corporationId,
                                )
                            }.getOrDefault(emptyList())
                        raw
                            .mapNotNull { it.toContractItemModel(contract.contractId) }
                            .also { parsed -> parsed.forEach { ContractDao.insertContractItem(it) } }
                    }
                }
            val partyIds = listOf(contract.issuerId, contract.assigneeId, contract.acceptorId).filter { it > 0 }.distinct()
            partyNames = if (partyIds.isEmpty()) emptyMap() else runCatching { EsiClient.resolveNames(partyIds) }.getOrDefault(emptyMap())
        }
    }

    val statusColor =
        when (contract.status) {
            "outstanding" -> Color(0xFFB197FC)
            "in_progress" -> warningColor
            "finished" -> positiveColor
            "cancelled" -> negativeColor
            else -> Color.Gray
        }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpand),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Column {
                        if (contract.title.isNotEmpty()) {
                            Text(contract.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = "${contract.type.replace("_", " ").capitalize()} • ${contract.status.replace("_", " ").capitalize()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor,
                        )
                    }
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
                if (contract.price > 0) InfoItem("Price", formatIsk(contract.price))
                if (contract.reward > 0) InfoItem("Reward", formatIsk(contract.reward))
                if (contract.collateral > 0) InfoItem("Collateral", formatIsk(contract.collateral))
                if (contract.buyout > 0) InfoItem("Buyout", formatIsk(contract.buyout))
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

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ContractDetails(contract, partyNames)

                Spacer(modifier = Modifier.height(8.dp))
                val currentItems = items
                if (currentItems == null) {
                    Text("Loading items…", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                } else {
                    ContractItemsSection(currentItems)
                }
            }
        }
    }
}

@Composable
private fun ContractDetails(
    contract: ContractModel,
    partyNames: Map<Int, String>,
) {
    fun partyLabel(id: Int) = if (id <= 0) null else partyNames[id] ?: "#$id"
    val startLocation = StaticDataDao.getStationById(contract.startStationId)?.name ?: "Station #${contract.startStationId}"
    val endLocation = StaticDataDao.getStationById(contract.endStationId)?.name ?: "Station #${contract.endStationId}"

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (contract.description.isNotBlank()) {
            Text(contract.description, style = MaterialTheme.typography.bodySmall)
        }

        FlowInfoRow {
            contract.dateAccepted?.let { InfoItem("Accepted", it.take(10)) }
            contract.dateCompleted?.let { InfoItem("Completed", it.take(10)) }
            if (contract.numDays > 0) InfoItem("Duration", "${contract.numDays}d")
            InfoItem("For corp", if (contract.forCorp) "Yes" else "No")
        }

        FlowInfoRow {
            InfoItem("From", startLocation)
            if (contract.endStationId != contract.startStationId) InfoItem("To", endLocation)
        }

        FlowInfoRow {
            partyLabel(contract.issuerId)?.let { InfoItem("Issuer", it) }
            partyLabel(contract.assigneeId)?.let { InfoItem("Assignee", it) }
            partyLabel(contract.acceptorId)?.let { InfoItem("Acceptor", it) }
        }
    }
}

@Composable
private fun FlowInfoRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { content() }
}

@Composable
private fun ContractItemsSection(items: List<ContractItemModel>) {
    if (items.isEmpty()) {
        Text("No items on this contract", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        return
    }
    val included = items.filter { it.isIncluded }
    val requested = items.filter { !it.isIncluded }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (included.isNotEmpty()) {
            Text("Items", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            included.forEach { ContractItemRow(it) }
        }
        if (requested.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Requested in exchange", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            requested.forEach { ContractItemRow(it) }
        }
    }
}

@Composable
private fun ContractItemRow(item: ContractItemModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypeIcon(item.typeId, size = 24.dp)
            Text(item.typeName, style = MaterialTheme.typography.bodySmall)
        }
        Text("x${item.quantity}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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

private fun Map<String, Any?>.toContractItemModel(contractId: Int): ContractItemModel? {
    val typeId = (this["type_id"] as? Number)?.toInt() ?: return null
    return ContractItemModel(
        contractId = contractId,
        recordId = (this["record_id"] as? Number)?.toInt() ?: 0,
        typeId = typeId,
        typeName = StaticDataDao.getTypeById(typeId)?.name ?: "Type #$typeId",
        quantity = (this["quantity"] as? Number)?.toInt() ?: 0,
        rawQuantity = (this["raw_quantity"] as? Number)?.toInt() ?: 0,
        isIncluded = (this["is_included"] as? Boolean) ?: true,
        isSingleton = (this["is_singleton"] as? Boolean) ?: false,
        estimatedPrice = 0.0,
    )
}

private fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
