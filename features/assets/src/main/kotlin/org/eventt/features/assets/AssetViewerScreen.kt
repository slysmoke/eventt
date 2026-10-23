package org.eventt.features.assets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.eventt.assets.generated.resources.*
import org.eventt.core.database.AssetDao
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.CorporationDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.ViewContext
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.AppLog
import org.eventt.core.model.AssetModel
import org.eventt.core.model.CorpFeature
import org.eventt.core.model.PLEX_MARKET_REGION_ID
import org.eventt.core.model.PLEX_TYPE_ID
import org.eventt.core.model.StaticStationModel
import org.eventt.features.market.ItemDetailDialog
import org.eventt.ui.common.*
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

private const val THE_FORGE_REGION_ID = 10000002
private const val JITA_44_STATION_ID = 60003760L

// ESI signals a blueprint's kind by overloading its own `quantity` field instead of exposing a
// real flag: -1 = a single original (already assigned/used, so it's its own distinct item), -2 =
// a copy (https://forums.eveonline.com/t/202261). A *packaged* original (still boxed, never
// assigned) isn't singled out that way at all -- it reports a real positive count instead, same
// as any other stackable tradeable item, since packaged BPOs really do stack and really do trade
// on the market.
//
// Deliberately NOT gated on the type's SDE category: no non-blueprint item ever reports a
// negative quantity from ESI, so the sign alone is already a fully reliable signal, and gating on
// local static-data (which might not have every type's category resolved, e.g. before/without a
// full SDE import) would only add a way for a real BPC to silently go undetected.
private fun blueprintAwareQuantity(rawQuantity: Int): Pair<Int, Boolean> =
    when (rawQuantity) {
        -1 -> 1 to false
        -2 -> 1 to true
        else -> rawQuantity to false // packaged original stack -- real count, prices like any item
    }

// Live Jita sell price (PLEX: its own global market) for every unique type in one batch, instead
// of pricing off ESI's /markets/prices/ adjusted/average price -- a 30-day rolling insurance-style
// number that's often nowhere close to what these items would actually fetch right now. Falls
// back to that average only for a type with no live Jita sell orders at all.
private suspend fun jitaSellPricesByType(typeIds: Set<Int>): Map<Int, Double> =
    coroutineScope {
        val semaphore = Semaphore(8)
        typeIds
            .map { typeId ->
                async {
                    semaphore.withPermit {
                        val regionId = if (typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else THE_FORGE_REGION_ID
                        typeId to
                            runCatching {
                                EsiClient
                                    .getMarketRegionOrders(regionId, orderType = "sell", typeId = typeId)
                                    .filter { typeId == PLEX_TYPE_ID || (it["location_id"] as? Number)?.toLong() == JITA_44_STATION_ID }
                                    .mapNotNull { (it["price"] as? Number)?.toDouble() }
                                    .minOrNull()
                            }.getOrNull()
                    }
                }
            }.awaitAll()
            .mapNotNull { (typeId, price) -> price?.let { typeId to it } }
            .toMap()
    }

private const val COMBINE_ALL_SETTING = "assets.combine_all"

private enum class AssetTab(
    val label: StringResource,
) {
    ITEMS(Res.string.tab_items),
    CATEGORIES(Res.string.tab_categories),
}

@Composable
fun AssetViewerScreen(context: ViewContext?) {
    val scope = rememberCoroutineScope()
    val charId = (context as? ViewContext.Character)?.charId
    val corpId = (context as? ViewContext.Corporation)?.corporationId
    val actingCharId = context?.actingCharId
    var combined by remember { mutableStateOf(false) }
    var assetLines by remember { mutableStateOf<List<AssetLine>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var deniedFeatures by remember { mutableStateOf<Set<CorpFeature>>(emptySet()) }
    var autoRefresh by remember { mutableStateOf(false) }
    var refreshAvailableAt by remember { mutableStateOf<Long?>(null) }
    // Bumped after an ESI refresh so the lines are rebuilt from the fresh rows.
    var reloadNonce by remember { mutableStateOf(0) }
    val unknownPlace = stringResource(Res.string.unknown_location)

    fun assetsEndpoint(): String? =
        when {
            corpId != null -> "/corporations/$corpId/assets/"
            charId != null -> "/characters/$charId/assets/"
            else -> null
        }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            autoRefresh = StaticDataDao.getSetting(ASSETS_AUTO_REFRESH_SETTING) == "true"
            combined = StaticDataDao.getSetting(COMBINE_ALL_SETTING) == "true"
        }
    }

    LaunchedEffect(context, combined, reloadNonce) {
        assetLines =
            withContext(Dispatchers.IO) {
                runCatching { loadLines(if (combined) null else charId, if (combined) null else corpId, combined, unknownPlace) }
                    .onFailure { AppLog.warn("Assets", it) }
                    .getOrDefault(emptyList())
            }
        deniedFeatures = withContext(Dispatchers.IO) { actingCharId?.let { CharacterDao.getDeniedCorpFeatures(it) } ?: emptySet() }
        refreshAvailableAt = withContext(Dispatchers.IO) { assetsEndpoint()?.let { EsiClient.getEndpointExpiry(it) } }
    }

    fun refreshFromEsi(clearDenied: Boolean) {
        val acting = actingCharId ?: return
        scope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                if (clearDenied) {
                    CorpFeature.entries.forEach { CharacterDao.setCorpFeatureDenied(acting, it, denied = false) }
                }
                if (corpId != null) {
                    fetchCorporationAssets(corpId, acting)
                } else if (charId != null) {
                    fetchCharacterAssets(charId)
                }
                withContext(Dispatchers.Main) { reloadNonce++ }
            } catch (e: Exception) {
                AppLog.warn("Assets", "fetch", e)
            } finally {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    // Keeps refreshing this context's assets on an interval while the toggle is on and this
    // screen is open — AssetWatchService's own sweep covers the "screen not open" case.
    LaunchedEffect(autoRefresh, context) {
        if (!autoRefresh || actingCharId == null) return@LaunchedEffect
        while (true) {
            delay(ASSETS_REFRESH_INTERVAL_MILLIS)
            refreshFromEsi(clearDenied = false)
        }
    }

    var chartLine by remember { mutableStateOf<AssetLine?>(null) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.asset_viewer), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Segment(stringResource(Res.string.combine_all), combined) {
                combined = !combined
                scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(COMBINE_ALL_SETTING, combined.toString()) }
            }
            Segment(stringResource(Res.string.auto_refresh), autoRefresh) {
                autoRefresh = !autoRefresh
                scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(ASSETS_AUTO_REFRESH_SETTING, autoRefresh.toString()) }
            }
            // The character/corp switcher already decided which of the two to fetch.
            EsiRefreshButton(
                isLoading = isLoading,
                expiresAtMs = refreshAvailableAt,
                enabled = actingCharId != null && !combined,
                onClick = { refreshFromEsi(clearDenied = false) },
            )
        }

        if (corpId != null && !combined && deniedFeatures.isNotEmpty()) {
            CorpAccessNotice(deniedFeatures, onRetry = { refreshFromEsi(clearDenied = true) })
        }

        val all = assetLines
        when {
            all == null -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }

            all.isEmpty() -> {
                EmptyState(
                    icon = Icons.Default.Inventory,
                    title = stringResource(Res.string.no_assets_found),
                    description = stringResource(Res.string.assets_click_refresh),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            else -> {
                AssetsBody(all, showOwner = combined, modifier = Modifier.weight(1f)) { chartLine = it }
            }
        }
    }

    LoadingOverlay(isLoading = isLoading, message = stringResource(Res.string.fetching_assets))

    chartLine?.let { l ->
        val regionId = l.place.regionId.takeIf { it > 0 } ?: THE_FORGE_REGION_ID
        val regionName by produceState("", regionId) {
            value = withContext(Dispatchers.IO) { StaticDataDao.getRegionById(regionId)?.name ?: "" }
        }
        ItemDetailDialog(
            typeId = l.typeId,
            typeName = l.name,
            primaryRegionId = regionId,
            primaryRegionName = regionName,
            primaryStationId = l.place.stationId,
            charId = actingCharId,
            corporationId = corpId.takeIf { !combined },
            onDismiss = { chartLine = null },
        )
    }
}

// Reads the scope's assets (null ids + combined = everyone's) and folds them into table lines.
internal fun loadLines(
    characterId: Int?,
    corporationId: Int?,
    combined: Boolean,
    unknownPlace: String,
): List<AssetLine> {
    val assets =
        when {
            combined -> AssetDao.getAll()
            characterId != null -> AssetDao.getByCharacter(characterId)
            corporationId != null -> AssetDao.getByCorporation(corporationId)
            else -> emptyList()
        }
    val chars = if (combined) CharacterDao.getAll().associate { it.id to it.name } else emptyMap()
    val corps = if (combined) CorporationDao.getAll().associate { (it["id"] as Int) to (it["name"] as? String ?: "") } else emptyMap()
    val owner: (AssetModel) -> String? = { a ->
        if (!combined) null else a.corporationId?.let { corps[it] ?: "#$it" } ?: a.characterId?.let { chars[it] ?: "#$it" }
    }
    return lines(assets, StaticDataDao.getTypeInfo(assets.map { it.typeId }), owner, unknownPlace)
}

/** Everything under the header for already-built lines — no DB or ESI here. */
@Composable
internal fun AssetsBody(
    all: List<AssetLine>,
    showOwner: Boolean,
    modifier: Modifier = Modifier,
    onOpen: (AssetLine) -> Unit,
) {
    var placeKey by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(AssetTab.ITEMS) }
    var query by remember { mutableStateOf("") }
    val other = stringResource(Res.string.other)
    val inPlace = remember(all, placeKey) { if (placeKey == null) all else all.filter { it.place.key == placeKey } }
    val shown =
        remember(inPlace, category, query) {
            inPlace.filter { l ->
                (category == null || l.category.ifEmpty { other } == category) &&
                    (
                        query.isBlank() ||
                            listOf(l.name, l.group, l.place.name, l.owner.orEmpty()).any { it.contains(query.trim(), ignoreCase = true) }
                    )
            }
        }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TotalsStrip(totals(all))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Panel(stringResource(Res.string.panel_locations), Modifier.weight(0.3f).fillMaxHeight()) {
                SliceList(
                    slices = remember(all) { byPlace(all) },
                    allLabel = stringResource(Res.string.all_locations),
                    allValue = all.sumOf { it.value },
                    selected = placeKey,
                    color = MaterialTheme.colorScheme.primary,
                    showM3 = true,
                ) { placeKey = it }
            }
            Panel(
                null,
                Modifier.weight(0.7f).fillMaxHeight(),
                header = {
                    AssetTab.entries.forEach { t -> TabLabel(stringResource(t.label), t == tab) { tab = t } }
                    Spacer(Modifier.weight(1f))
                    placeKey?.let { k ->
                        Segment("${all.first { it.place.key == k }.place.name}  ✕", true) { placeKey = null }
                    }
                    category?.let { c -> Segment("$c  ✕", true) { category = null } }
                },
            ) {
                when (tab) {
                    AssetTab.ITEMS -> {
                        TerminalSearch(query, { query = it }, stringResource(Res.string.search_assets), Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        ItemsTable(
                            shown,
                            showPlace = placeKey == null,
                            showOwner = showOwner,
                            total = all.sumOf { it.value },
                            onOpen = onOpen,
                        )
                    }

                    AssetTab.CATEGORIES -> {
                        SliceList(
                            slices = remember(inPlace) { byCategory(inPlace, other) },
                            allLabel = stringResource(Res.string.all_categories),
                            allValue = inPlace.sumOf { it.value },
                            selected = category,
                            color = STOCK_BAR,
                            showM3 = false,
                        ) {
                            category = it
                            if (it != null) tab = AssetTab.ITEMS
                        }
                    }
                }
            }
        }
    }
}

private val STOCK_BAR =
    androidx.compose.ui.graphics
        .Color(0xFF4DB6AC)

@Composable
private fun TotalsStrip(t: AssetTotals) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), MaterialTheme.shapes.extraSmall),
    ) {
        Kpi(stringResource(Res.string.kpi_value), formatIsk(t.value), stringResource(Res.string.kpi_types, t.types.toString()), null, null)
        Kpi(stringResource(Res.string.kpi_lines), t.lines.toString(), null, null, null)
        Kpi(stringResource(Res.string.kpi_places), t.places.toString(), null, null, null)
        Kpi(
            stringResource(Res.string.kpi_in_jita),
            formatIsk(t.jitaValue),
            if (t.value > 0) String.format(Locale.US, "%.0f%%", t.jitaValue / t.value * 100) else null,
            null,
            null,
        )
        Kpi(
            stringResource(Res.string.kpi_outside_jita),
            formatIsk(t.outsideValue),
            stringResource(Res.string.kpi_m3, formatVolume(t.outsideM3)),
            null,
            null,
            Res.string.tip_outside_jita,
        )
        Kpi(stringResource(Res.string.kpi_bpcs), t.bpcs.toString(), null, null, null, Res.string.tip_bpcs)
        Kpi(
            stringResource(Res.string.kpi_unpriced),
            t.unpriced.toString(),
            null,
            null,
            null,
            Res.string.tip_unpriced,
            valueColor = warningColor.takeIf { t.unpriced > 0 },
            last = true,
        )
    }
}

// "All" plus one row per slice with its share bar; clicking a row selects it, clicking it again
// (or "All") clears the selection.
@Composable
private fun SliceList(
    slices: List<Slice>,
    allLabel: String,
    allValue: Double,
    selected: String?,
    color: androidx.compose.ui.graphics.Color,
    showM3: Boolean,
    onSelect: (String?) -> Unit,
) {
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    LazyColumn {
        item(key = "__all") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (selected == null) Modifier.background(highlight) else Modifier)
                    .clickable { onSelect(null) }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
            ) {
                Text(allLabel, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(formatIsk(allValue), style = MaterialTheme.typography.bodySmall, fontFamily = Mono)
            }
        }
        items(slices, key = { it.key }) { s ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (selected == s.key) Modifier.background(highlight) else Modifier)
                    .clickable { onSelect(if (selected == s.key) null else s.key) }
                    .padding(vertical = 3.dp, horizontal = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        s.label,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatIsk(s.value), style = MaterialTheme.typography.bodySmall, fontFamily = Mono)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f).height(4.dp)) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(
                                    (s.sharePct / 100).toFloat().coerceIn(0.005f, 1f),
                                ).background(color.copy(alpha = 0.7f)),
                        )
                    }
                    Text(
                        listOfNotNull(
                            s.sub.takeIf { it.isNotEmpty() },
                            String.format(Locale.US, "%.1f%%", s.sharePct),
                            if (showM3) formatVolume(s.m3) + " m³" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = Mono,
                        color = AXIS_COLOR,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemsTable(
    lines: List<AssetLine>,
    showPlace: Boolean,
    showOwner: Boolean,
    total: Double,
    onOpen: (AssetLine) -> Unit,
) {
    val copy = stringResource(Res.string.copy_suffix)
    val cols =
        buildList<Col<AssetLine>> {
            add(Col(stringResource(Res.string.col_item), 2.6f, end = false) { it.name.lowercase() })
            add(Col(stringResource(Res.string.col_group), 1.3f, end = false) { it.group.lowercase() })
            if (showPlace) add(Col(stringResource(Res.string.col_location), 1.6f, end = false) { it.place.name.lowercase() })
            add(Col(stringResource(Res.string.col_placement), 0.9f, end = false) { it.placement.ordinal })
            if (showOwner) add(Col(stringResource(Res.string.col_owner), 1.1f, end = false) { it.owner?.lowercase() })
            add(Col(stringResource(Res.string.col_qty), 0.7f) { it.qty })
            add(Col(stringResource(Res.string.col_price), 0.8f) { it.unitPrice })
            add(Col(stringResource(Res.string.col_value), 0.9f) { it.value })
            add(Col("%", 0.5f) { it.value })
            add(Col("m³", 0.7f) { it.m3 })
            add(Col(stringResource(Res.string.col_isk_m3), 0.8f) { it.iskPerM3 })
        }
    Table(
        cols = cols,
        rows = lines,
        empty = stringResource(Res.string.assets_no_match),
        defaultSort = cols.indexOfFirst { it.title == stringResource(Res.string.col_value) },
        onClick = onOpen,
    ) { l ->
        buildList {
            add(
                Cell(l.name) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TypeIcon(l.typeId, size = 18.dp)
                        Text(
                            l.name + if (l.isBpc) " $copy" else "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
            add(Cell(l.group, AXIS_COLOR))
            if (showPlace) add(Cell(l.place.shortName, AXIS_COLOR))
            add(Cell(stringResource(l.placement.label), AXIS_COLOR))
            if (showOwner) add(Cell(l.owner.orEmpty(), AXIS_COLOR))
            add(Cell(formatVolume(l.qty)))
            add(Cell(if (l.unitPrice > 0) formatPriceAbbr(l.unitPrice) else "—", warningColor.takeIf { l.unitPrice <= 0 && !l.isBpc }))
            add(Cell(formatIsk(l.value)))
            add(Cell(if (total > 0) String.format(Locale.US, "%.1f", l.value / total * 100) else ""))
            add(Cell(formatVolume(l.m3)))
            add(Cell(l.iskPerM3?.let(::formatIsk) ?: "—", AXIS_COLOR))
        }
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

// Not private -- reused by features:orders' InventoryTable to refresh the assets snapshot before
// reconciling it against FIFO inventory (see AssetReconciliationService).
suspend fun fetchCharacterAssets(characterId: Int) {
    val rawAssets = EsiClient.getCharacterAssets(characterId)
    val averagePrices = EsiClient.getMarketPrices()
    val jitaSellPrices = jitaSellPricesByType(rawAssets.mapNotNull { (it["type_id"] as? Number)?.toInt() }.toSet())
    val byItemId = rawAssets.mapNotNull { d -> (d["item_id"] as? Number)?.toLong()?.let { it to d } }.toMap()
    // Memoize per resolved root location_id within this fetch — many items typically share the
    // same handful of stations/citadels, so this avoids repeating the DB (or ESI) lookup per item.
    val locationCache = mutableMapOf<Long, StaticStationModel?>()

    val models =
        rawAssets.mapNotNull { data ->
            val typeId = (data["type_id"] as? Number)?.toInt() ?: return@mapNotNull null
            val itemId = (data["item_id"] as? Number)?.toLong() ?: return@mapNotNull null
            val locationId = (data["location_id"] as? Number)?.toLong() ?: 0L
            val locationFlag = (data["location_flag"] as? String) ?: ""
            val isSingleton = (data["is_singleton"] as? Boolean) ?: true

            val staticType = StaticDataDao.getTypeById(typeId)
            val typeName = staticType?.name ?: ""
            val (quantity, isBlueprintCopy) = blueprintAwareQuantity((data["quantity"] as? Number)?.toInt() ?: 0)
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
                // A blueprint copy trades for nothing regardless of what its original sells for.
                estimatedPrice = if (isBlueprintCopy) 0.0 else jitaSellPrices[typeId] ?: averagePrices[typeId] ?: 0.0,
                isCorpAsset = false,
                isBlueprintCopy = isBlueprintCopy,
                characterId = characterId,
            )
        }

    AssetDao.replaceFor(characterId = characterId, assets = models)
}

suspend fun fetchCorporationAssets(
    corporationId: Int,
    actingCharacterId: Int,
) {
    // actingCharacterId both authorizes the corp-scope ESI call and stands in for structure
    // (citadel) name lookups, which need docking access — ESI's structure endpoint doesn't
    // require them to be a director, just a member present in the structure.
    val rawAssets = EsiClient.getCorporationAssets(corporationId, actingCharacterId)
    val averagePrices = EsiClient.getMarketPrices()
    val jitaSellPrices = jitaSellPricesByType(rawAssets.mapNotNull { (it["type_id"] as? Number)?.toInt() }.toSet())
    val byItemId = rawAssets.mapNotNull { d -> (d["item_id"] as? Number)?.toLong()?.let { it to d } }.toMap()
    val locationCache = mutableMapOf<Long, StaticStationModel?>()

    val models =
        rawAssets.mapNotNull { data ->
            val typeId = (data["type_id"] as? Number)?.toInt() ?: return@mapNotNull null
            val itemId = (data["item_id"] as? Number)?.toLong() ?: return@mapNotNull null
            val locationId = (data["location_id"] as? Number)?.toLong() ?: 0L
            val locationFlag = (data["location_flag"] as? String) ?: ""
            val isSingleton = (data["is_singleton"] as? Boolean) ?: true

            val staticType = StaticDataDao.getTypeById(typeId)
            val typeName = staticType?.name ?: ""
            val (quantity, isBlueprintCopy) = blueprintAwareQuantity((data["quantity"] as? Number)?.toInt() ?: 0)
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
                // A blueprint copy trades for nothing regardless of what its original sells for.
                estimatedPrice = if (isBlueprintCopy) 0.0 else jitaSellPrices[typeId] ?: averagePrices[typeId] ?: 0.0,
                isCorpAsset = true,
                isBlueprintCopy = isBlueprintCopy,
                corporationId = corporationId,
            )
        }

    AssetDao.replaceFor(corporationId = corporationId, assets = models)
}
