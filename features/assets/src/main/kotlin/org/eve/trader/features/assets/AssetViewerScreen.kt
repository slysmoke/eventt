package org.eve.trader.features.assets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.eve.trader.core.database.AssetDao
import org.eve.trader.core.database.DatabaseManager
import org.eve.trader.core.database.StaticDataDao
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.core.model.AssetModel
import org.eve.trader.ui.common.*

@Composable
fun AssetViewerScreen() {
    val scope = rememberCoroutineScope()
    var assets by remember { mutableStateOf<List<AssetModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var groupByLocation by remember { mutableStateOf(true) }
    var selectedCharacterId by remember { mutableStateOf<Int?>(null) }
    var selectedCorporationId by remember { mutableStateOf<Int?>(null) }
    var showRefreshDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loadAssets(selectedCharacterId, selectedCorporationId) { list -> assets = list }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Asset Viewer", style = MaterialTheme.typography.headlineMedium)
            Row {
                IconButton(onClick = { showRefreshDialog = true }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh from ESI")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onClear = { searchQuery = "" },
                placeholder = "Search assets...",
                modifier = Modifier.weight(1f),
            )

            FilterChip(
                selected = groupByLocation,
                onClick = { groupByLocation = true },
                label = { Text("By Location") },
                leadingIcon = { Icon(Icons.Default.LocationOn, null, modifier = Modifier.size(18.dp)) },
            )

            FilterChip(
                selected = !groupByLocation,
                onClick = { groupByLocation = false },
                label = { Text("By Type") },
                leadingIcon = { Icon(Icons.Default.Category, null, modifier = Modifier.size(18.dp)) },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Summary bar
        val filteredAssets = if (searchQuery.isBlank()) {
            assets
        } else {
            assets.filter { it.typeName.contains(searchQuery, ignoreCase = true) }
        }
        val totalValue = filteredAssets.sumOf { it.estimatedPrice * it.quantity }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(
                    text = "${filteredAssets.size} items",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Total: ${formatIsk(totalValue)} ISK",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Content
        if (filteredAssets.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Inventory,
                title = "No Assets Found",
                description = if (assets.isEmpty()) "Click refresh to fetch assets from ESI." else "No assets match your search.",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            if (groupByLocation) {
                AssetByLocationView(filteredAssets)
            } else {
                AssetByTypeView(filteredAssets)
            }
        }
    }

    // Refresh dialog
    if (showRefreshDialog) {
        RefreshAssetDialog(
            onDismiss = { showRefreshDialog = false },
            onRefreshCharacter = { charId ->
                scope.launch(Dispatchers.IO) {
                    isLoading = true
                    try {
                        fetchCharacterAssets(charId)
                        withContext(Dispatchers.Main) {
                            loadAssets(charId, null) { list -> assets = list }
                            isLoading = false
                            showRefreshDialog = false
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { isLoading = false }
                    }
                }
            },
            onRefreshCorporation = { corpId ->
                scope.launch(Dispatchers.IO) {
                    isLoading = true
                    try {
                        fetchCorporationAssets(corpId)
                        withContext(Dispatchers.Main) {
                            loadAssets(null, corpId) { list -> assets = list }
                            isLoading = false
                            showRefreshDialog = false
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { isLoading = false }
                    }
                }
            },
        )
    }

    LoadingOverlay(isLoading = isLoading, message = "Fetching assets from ESI...")
}

@Composable
private fun AssetByLocationView(assets: List<AssetModel>) {
    val groupedByLocation = assets.groupBy {
        val locationLabel = if (it.stationName.isNotEmpty()) it.stationName
        else if (it.systemName.isNotEmpty()) it.systemName
        else "Unknown Location"
        "${it.regionName} → $locationLabel"
    }.toSortedMap()

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        groupedByLocation.forEach { (location, locationAssets) ->
            item {
                AssetGroupHeader(location, locationAssets.size, locationAssets.sumOf { it.estimatedPrice * it.quantity })
            }
            items(locationAssets) { asset ->
                AssetRow(asset)
            }
        }
    }
}

@Composable
private fun AssetByTypeView(assets: List<AssetModel>) {
    val groupedByType = assets.groupBy { it.typeName }.toSortedMap()

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        groupedByType.forEach { (typeName, typeAssets) ->
            item {
                val totalQty = typeAssets.sumOf { it.quantity }
                val totalValue = typeAssets.sumOf { it.estimatedPrice * it.quantity }
                AssetGroupHeader(typeName, totalQty, totalValue)
            }
            items(typeAssets) { asset ->
                AssetRow(asset)
            }
        }
    }
}

@Composable
private fun AssetGroupHeader(title: String, count: Int, totalValue: Double) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text("($count)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            Text(formatIsk(totalValue), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AssetRow(asset: AssetModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon placeholder (would load from EveImageServer)
            Surface(
                modifier = Modifier.size(24.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Gray)
                }
            }

            Column {
                Text(asset.typeName, style = MaterialTheme.typography.bodyMedium)
                if (asset.locationName.isNotEmpty() && asset.stationName.isEmpty()) {
                    Text(asset.locationName, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("×${asset.quantity}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (asset.estimatedPrice > 0) {
                Text(formatIsk(asset.estimatedPrice * asset.quantity), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun RefreshAssetDialog(
    onDismiss: () -> Unit,
    onRefreshCharacter: (Int) -> Unit,
    onRefreshCorporation: (Int) -> Unit,
) {
    val characters = remember {
        try {
            org.eve.trader.core.database.CharacterDao.getAll()
        } catch (e: Exception) { emptyList() }
    }
    var selectedCharId by remember { mutableStateOf<Int?>(characters.firstOrNull()?.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Refresh Assets") },
        text = {
            Column {
                Text("Choose what to fetch from ESI:")
                Spacer(modifier = Modifier.height(8.dp))

                if (characters.isNotEmpty()) {
                    Text("Character:", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(characters) { char ->
                            FilterChip(
                                selected = selectedCharId == char.id,
                                onClick = { selectedCharId = char.id },
                                label = { Text(char.name, style = MaterialTheme.typography.bodySmall) },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { selectedCharId?.let { onRefreshCharacter(it) } },
                    enabled = selectedCharId != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Person, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Fetch Character Assets")
                }

                Button(
                    onClick = { selectedCharId?.let { charId ->
                        val char = characters.find { it.id == charId }
                        char?.corporationId?.let { onRefreshCorporation(it) }
                    } },
                    enabled = characters.any { it.corporationId != null },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                ) {
                    Icon(Icons.Default.Business, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Fetch Corporation Assets")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun loadAssets(
    characterId: Int? = null,
    corporationId: Int? = null,
    callback: (List<AssetModel>) -> Unit,
) {
    try {
        val list = when {
            characterId != null -> AssetDao.getByCharacter(characterId)
            corporationId != null -> AssetDao.getByCorporation(corporationId)
            else -> emptyList()
        }
        callback(list)
    } catch (e: Exception) {
        println("Error loading assets: ${e.message}")
    }
}

private suspend fun fetchCharacterAssets(characterId: Int) {
    val accessToken = org.eve.trader.core.auth.SsoAuthManager.ensureTokenFresh(characterId)
    val rawAssets = EsiClient.getCharacterAssets(characterId, accessToken ?: "")
    val prices = EsiClient.getMarketPrices()

    val models = rawAssets.mapNotNull { data ->
        val typeId = (data["type_id"] as? Number)?.toInt() ?: return@mapNotNull null
        val itemId = (data["item_id"] as? Number)?.toLong() ?: return@mapNotNull null
        val quantity = (data["quantity"] as? Number)?.toInt() ?: 0
        val locationId = (data["location_id"] as? Number)?.toLong() ?: 0L
        val locationFlag = (data["location_flag"] as? String) ?: ""
        val isSingleton = (data["is_singleton"] as? Boolean) ?: true

        val staticType = StaticDataDao.getTypeById(typeId)
        val typeName = staticType?.name ?: ""

        AssetModel(
            itemId = itemId,
            typeId = typeId,
            typeName = typeName,
            quantity = quantity,
            locationId = locationId,
            locationFlag = locationFlag,
            isSingleton = isSingleton,
            estimatedPrice = prices[typeId] ?: 0.0,
            isCorpAsset = false,
            characterId = characterId,
        )
    }

    AssetDao.bulkUpsert(models)
}

private suspend fun fetchCorporationAssets(corporationId: Int) {
    val rawAssets = EsiClient.getCorporationAssets(corporationId)
    val prices = EsiClient.getMarketPrices()

    val models = rawAssets.mapNotNull { data ->
        val typeId = (data["type_id"] as? Number)?.toInt() ?: return@mapNotNull null
        val itemId = (data["item_id"] as? Number)?.toLong() ?: return@mapNotNull null
        val quantity = (data["quantity"] as? Number)?.toInt() ?: 0
        val locationId = (data["location_id"] as? Number)?.toLong() ?: 0L
        val locationFlag = (data["location_flag"] as? String) ?: ""
        val isSingleton = (data["is_singleton"] as? Boolean) ?: true

        val staticType = StaticDataDao.getTypeById(typeId)
        val typeName = staticType?.name ?: ""

        AssetModel(
            itemId = itemId,
            typeId = typeId,
            typeName = typeName,
            quantity = quantity,
            locationId = locationId,
            locationFlag = locationFlag,
            isSingleton = isSingleton,
            estimatedPrice = prices[typeId] ?: 0.0,
            isCorpAsset = true,
            corporationId = corporationId,
        )
    }

    AssetDao.bulkUpsert(models)
}

private fun formatIsk(value: Double): String {
    return when {
        value >= 1_000_000_000_000 -> String.format("%.2fT", value / 1_000_000_000_000)
        value >= 1_000_000_000 -> String.format("%.2fB", value / 1_000_000_000)
        value >= 1_000_000 -> String.format("%.2fM", value / 1_000_000)
        value >= 1_000 -> String.format("%.2fK", value / 1_000)
        else -> String.format("%.2f", value)
    }
}
