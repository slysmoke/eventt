package org.eventt.features.assets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import org.eventt.core.database.AssetDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.ViewContext
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.AssetModel
import org.eventt.core.model.StaticStationModel
import org.eventt.ui.common.*

@Composable
fun AssetViewerScreen(context: ViewContext?) {
    val scope = rememberCoroutineScope()
    val charId = (context as? ViewContext.Character)?.charId
    val corpId = (context as? ViewContext.Corporation)?.corporationId
    val actingCharId = context?.actingCharId
    var assets by remember { mutableStateOf<List<AssetModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(context) {
        loadAssets(charId, corpId) { list -> assets = list }
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
                // The character/corp switcher already decided which of the two to fetch —
                // no per-click choice needed, unlike the old dual-button dialog.
                IconButton(
                    enabled = actingCharId != null && !isLoading,
                    onClick = {
                        val acting = actingCharId ?: return@IconButton
                        scope.launch(Dispatchers.IO) {
                            isLoading = true
                            try {
                                if (corpId != null) {
                                    fetchCorporationAssets(corpId, acting)
                                } else if (charId != null) {
                                    fetchCharacterAssets(charId)
                                }
                                withContext(Dispatchers.Main) {
                                    loadAssets(charId, corpId) { list -> assets = list }
                                }
                            } catch (e: Exception) {
                                println("[Assets] Error fetching assets: ${e.message}")
                            } finally {
                                withContext(Dispatchers.Main) { isLoading = false }
                            }
                        }
                    },
                ) {
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
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Summary bar
        val filteredAssets =
            if (searchQuery.isBlank()) {
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
            AssetByLocationView(filteredAssets)
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Fetching assets from ESI...")
}

@Composable
private fun AssetByLocationView(assets: List<AssetModel>) {
    val groupedByLocation =
        assets
            .groupBy {
                val locationLabel =
                    if (it.stationName.isNotEmpty()) {
                        it.stationName
                    } else if (it.systemName.isNotEmpty()) {
                        it.systemName
                    } else {
                        "Unknown Location"
                    }
                "${it.regionName} → $locationLabel"
            }.entries
            .sortedByDescending { (_, items) -> items.sumOf { it.estimatedPrice * it.quantity } }

    // Groups start collapsed — only entries explicitly toggled to `true` are expanded.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        groupedByLocation.forEach { (location, locationAssets) ->
            val isExpanded = expanded[location] == true
            val sortedAssets = locationAssets.sortedByDescending { it.estimatedPrice * it.quantity }
            item(key = "header:$location") {
                AssetGroupHeader(
                    title = location,
                    count = locationAssets.size,
                    totalValue = locationAssets.sumOf { it.estimatedPrice * it.quantity },
                    collapsed = !isExpanded,
                    onClick = { expanded[location] = !isExpanded },
                )
            }
            if (isExpanded) {
                items(sortedAssets, key = { it.itemId }) { asset ->
                    AssetRow(asset)
                }
            }
        }
    }
}

@Composable
private fun AssetGroupHeader(
    title: String,
    count: Int,
    totalValue: Double,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
                    contentDescription = if (collapsed) "Expand" else "Collapse",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    "($count)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
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
                Text(
                    formatIsk(asset.estimatedPrice * asset.quantity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun loadAssets(
    characterId: Int? = null,
    corporationId: Int? = null,
    callback: (List<AssetModel>) -> Unit,
) {
    try {
        val list =
            when {
                characterId != null -> AssetDao.getByCharacter(characterId)
                corporationId != null -> AssetDao.getByCorporation(corporationId)
                else -> emptyList()
            }
        callback(list)
    } catch (e: Exception) {
        println("Error loading assets: ${e.message}")
    }
}

// Character/corp asset lists are a tree, not a flat list: an item stowed in a ship, container,
// or corp hangar division has location_type "item" and location_id pointing at that *parent's*
// own item_id — not a physical place. To find where something actually sits, walk up the
// location_id chain (item -> its container -> ... ) until reaching a real station/citadel/solar
// system. byItemId indexes this same asset batch by item_id so each hop is a plain map lookup;
// the hop cap guards against a malformed/cyclic chain.
private fun resolveRootLocation(
    data: Map<String, Any?>,
    byItemId: Map<Long, Map<String, Any?>>,
): Pair<Long, String> {
    var locationId = (data["location_id"] as? Number)?.toLong() ?: 0L
    var locationType = (data["location_type"] as? String) ?: ""
    var hops = 0
    while (locationType == "item" && hops < 16) {
        val parent = byItemId[locationId] ?: break
        locationId = (parent["location_id"] as? Number)?.toLong() ?: break
        locationType = (parent["location_type"] as? String) ?: ""
        hops++
    }
    return locationId to locationType
}

// Resolves a location_id to a name/system/region — covers both NPC stations and player
// structures/citadels, since CitadelService stores citadel names in the same static_stations
// table (see core/staticdata/CitadelService.kt). Callers must pass the *root* location_type
// (see resolveRootLocation above), not an item's raw one, or every nested item will just be "item".
private fun resolveLocation(
    locationId: Long,
    locationType: String,
    characterId: Int?,
): StaticStationModel? {
    StaticDataDao.getStationById(locationId)?.let { return it }

    if (locationType != "station") {
        println("[Assets] Not resolving $locationId — location_type is '$locationType', not 'station'")
        return null
    }
    if (characterId == null) {
        println("[Assets] Skipping ESI structure lookup for $locationId — no character token available")
        return null
    }

    return try {
        val info = EsiClient.getStructureInfo(locationId, characterId)
        if (info == null) {
            println("[Assets] getStructureInfo($locationId) returned null (no docking access or lookup failed)")
            return null
        }
        val name = info["name"] as? String
        if (name == null) {
            println("[Assets] getStructureInfo($locationId) response had no name: $info")
            return null
        }
        val systemId = (info["solar_system_id"] as? Number)?.toInt() ?: 0
        val typeId = (info["type_id"] as? Number)?.toInt() ?: 0
        val system = StaticDataDao.getSystemById(systemId)
        val region = system?.regionId?.let { StaticDataDao.getRegionById(it) }

        val station =
            StaticStationModel(
                stationId = locationId,
                name = name,
                systemId = systemId,
                systemName = system?.name ?: "",
                regionId = region?.regionId ?: 0,
                regionName = region?.name ?: "",
                typeId = typeId,
            )
        StaticDataDao.bulkInsertStations(listOf(station))
        println("[Assets] Resolved structure $locationId -> $name")
        station
    } catch (e: Exception) {
        println("[Assets] resolveLocation($locationId) threw: ${e.message}")
        null
    }
}

private suspend fun fetchCharacterAssets(characterId: Int) {
    val rawAssets = EsiClient.getCharacterAssets(characterId)
    val prices = EsiClient.getMarketPrices()
    val byItemId = rawAssets.mapNotNull { d -> (d["item_id"] as? Number)?.toLong()?.let { it to d } }.toMap()
    // Memoize per resolved root location_id within this fetch — many items typically share the
    // same handful of stations/citadels, so this avoids repeating the DB (or ESI) lookup per item.
    val locationCache = mutableMapOf<Long, StaticStationModel?>()

    val models =
        rawAssets.mapNotNull { data ->
            val typeId = (data["type_id"] as? Number)?.toInt() ?: return@mapNotNull null
            val itemId = (data["item_id"] as? Number)?.toLong() ?: return@mapNotNull null
            val quantity = (data["quantity"] as? Number)?.toInt() ?: 0
            val locationId = (data["location_id"] as? Number)?.toLong() ?: 0L
            val locationFlag = (data["location_flag"] as? String) ?: ""
            val isSingleton = (data["is_singleton"] as? Boolean) ?: true

            val staticType = StaticDataDao.getTypeById(typeId)
            val typeName = staticType?.name ?: ""
            val (rootLocationId, rootLocationType) = resolveRootLocation(data, byItemId)
            val location = locationCache.getOrPut(rootLocationId) { resolveLocation(rootLocationId, rootLocationType, characterId) }

            AssetModel(
                itemId = itemId,
                typeId = typeId,
                typeName = typeName,
                quantity = quantity,
                locationId = locationId,
                locationName = location?.name ?: "",
                regionId = location?.regionId ?: 0,
                regionName = location?.regionName ?: "",
                systemId = location?.systemId ?: 0,
                systemName = location?.systemName ?: "",
                stationId = if (location != null) rootLocationId else 0,
                stationName = location?.name ?: "",
                locationFlag = locationFlag,
                isSingleton = isSingleton,
                estimatedPrice = prices[typeId] ?: 0.0,
                isCorpAsset = false,
                characterId = characterId,
            )
        }

    AssetDao.bulkUpsert(models)
}

private suspend fun fetchCorporationAssets(
    corporationId: Int,
    actingCharacterId: Int,
) {
    // actingCharacterId both authorizes the corp-scope ESI call and stands in for structure
    // (citadel) name lookups, which need docking access — ESI's structure endpoint doesn't
    // require them to be a director, just a member present in the structure.
    val rawAssets = EsiClient.getCorporationAssets(corporationId, actingCharacterId)
    val prices = EsiClient.getMarketPrices()
    val byItemId = rawAssets.mapNotNull { d -> (d["item_id"] as? Number)?.toLong()?.let { it to d } }.toMap()
    val locationCache = mutableMapOf<Long, StaticStationModel?>()

    val models =
        rawAssets.mapNotNull { data ->
            val typeId = (data["type_id"] as? Number)?.toInt() ?: return@mapNotNull null
            val itemId = (data["item_id"] as? Number)?.toLong() ?: return@mapNotNull null
            val quantity = (data["quantity"] as? Number)?.toInt() ?: 0
            val locationId = (data["location_id"] as? Number)?.toLong() ?: 0L
            val locationFlag = (data["location_flag"] as? String) ?: ""
            val isSingleton = (data["is_singleton"] as? Boolean) ?: true

            val staticType = StaticDataDao.getTypeById(typeId)
            val typeName = staticType?.name ?: ""
            val (rootLocationId, rootLocationType) = resolveRootLocation(data, byItemId)
            val location = locationCache.getOrPut(rootLocationId) { resolveLocation(rootLocationId, rootLocationType, actingCharacterId) }

            AssetModel(
                itemId = itemId,
                typeId = typeId,
                typeName = typeName,
                quantity = quantity,
                locationId = locationId,
                locationName = location?.name ?: "",
                regionId = location?.regionId ?: 0,
                regionName = location?.regionName ?: "",
                systemId = location?.systemId ?: 0,
                systemName = location?.systemName ?: "",
                stationId = if (location != null) rootLocationId else 0,
                stationName = location?.name ?: "",
                locationFlag = locationFlag,
                isSingleton = isSingleton,
                estimatedPrice = prices[typeId] ?: 0.0,
                isCorpAsset = true,
                corporationId = corporationId,
            )
        }

    AssetDao.bulkUpsert(models)
}
