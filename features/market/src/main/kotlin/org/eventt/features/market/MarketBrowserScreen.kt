package org.eventt.features.market

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.AlertDao
import org.eventt.core.database.AppState
import org.eventt.core.database.MarketDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.ViewContext
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.MarketHistoryModel
import org.eventt.core.model.PLEX_MARKET_REGION_ID
import org.eventt.core.model.PLEX_TYPE_ID
import org.eventt.core.model.PriceAlertModel
import org.eventt.core.model.StaticMarketGroupModel
import org.eventt.core.model.StaticTypeModel
import org.eventt.core.model.stringBlocking
import org.eventt.core.staticdata.StaticDataImporter
import org.eventt.market.generated.resources.*
import org.eventt.ui.common.*
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.stringResource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

private sealed class TreeNode {
    data class GroupNode(
        val group: StaticMarketGroupModel,
        val depth: Int,
    ) : TreeNode()

    data class TypeNode(
        val type: StaticTypeModel,
        val depth: Int,
    ) : TreeNode()
}

private typealias Book = Pair<List<MarketOrder>, List<MarketOrder>>

@Composable
fun MarketBrowserScreen() {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<StaticTypeModel>>(emptyList()) }
    var selectedType by remember { mutableStateOf<StaticTypeModel?>(null) }
    var hub by remember { mutableStateOf(HUBS.first()) }
    var orderBook by remember { mutableStateOf<Book>(emptyList<MarketOrder>() to emptyList()) }
    var history by remember { mutableStateOf<List<MarketHistoryModel>>(emptyList()) }
    var topGroups by remember { mutableStateOf<List<StaticMarketGroupModel>>(emptyList()) }
    var expandedGroups by remember { mutableStateOf(setOf<Int>()) }
    var isSdeImporting by remember { mutableStateOf(false) }
    // null = closed; NaN = opened from the header (no price picked); a number = prefilled from a book row.
    var alertPrice by remember { mutableStateOf<Double?>(null) }

    fun open(
        type: StaticTypeModel,
        h: Hub = hub,
    ) {
        selectedType = type
        orderBook = emptyList<MarketOrder>() to emptyList()
        history = emptyList()
        scope.launch { loadMarketData(h.regionId, type.typeId, ordersCallback = { orderBook = it }, historyCallback = { history = it }) }
    }

    // Deep link from another tab (e.g. Orders' "view price" button) — preselect the requested
    // item exactly as a search-result click would, then clear the one-shot signal.
    val pendingTypeId by AppState.pendingMarketTypeId.collectAsState()
    LaunchedEffect(pendingTypeId) {
        val typeId = pendingTypeId ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { StaticDataDao.getTypeById(typeId) }?.let {
            searchQuery = ""
            searchResults = emptyList()
            open(it)
        }
        AppState.clearPendingMarketTypeId()
    }

    LaunchedEffect(Unit) {
        val groups = withContext(Dispatchers.IO) { StaticDataDao.getTopMarketGroups() }
        topGroups = groups
        if (groups.isEmpty()) {
            val typeCount = withContext(Dispatchers.IO) { StaticDataDao.countTypes() }
            if (typeCount == 0) {
                isSdeImporting = true
                withContext(Dispatchers.IO) { StaticDataImporter.importAll() }
                isSdeImporting = false
                topGroups = withContext(Dispatchers.IO) { StaticDataDao.getTopMarketGroups() }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.market_browser), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            HUBS.forEach { h ->
                Segment(h.name, hub == h) {
                    hub = h
                    selectedType?.let { open(it, h) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Panel(stringResource(Res.string.categories), Modifier.width(280.dp).fillMaxHeight()) {
                TerminalSearch(
                    searchQuery,
                    { query ->
                        searchQuery = query
                        if (query.length >= 2) {
                            scope.launch(Dispatchers.IO) { searchResults = StaticDataDao.searchMarketTypes(query, limit = 50) }
                        } else {
                            searchResults = emptyList()
                        }
                    },
                    stringResource(Res.string.search_items),
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                // While searching, the matches replace the tree.
                if (searchQuery.length >= 2) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(searchResults, key = { it.typeId }) { type ->
                            SearchRow(type, type.typeId == selectedType?.typeId) { open(type) }
                        }
                    }
                } else {
                    MarketGroupTree(
                        topGroups = topGroups,
                        expandedGroups = expandedGroups,
                        selectedTypeId = selectedType?.typeId,
                        onToggleExpand = { groupId ->
                            expandedGroups = if (groupId in expandedGroups) expandedGroups - groupId else expandedGroups + groupId
                        },
                        onTypeClick = { open(it) },
                    )
                }
            }
            val type = selectedType
            if (type != null) {
                MarketBody(type, hub, orderBook, history, Modifier.weight(1f)) { alertPrice = it ?: Double.NaN }
            } else {
                EmptyState(
                    icon = Icons.Default.Store,
                    title = stringResource(Res.string.browse_market),
                    description = stringResource(Res.string.browse_market_desc),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }

    // SDE Import overlay
    if (isSdeImporting) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.padding(32.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Downloading, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(Res.string.importing_sde), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(Res.string.downloading_sde),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator()
                }
            }
        }
    }

    val type = selectedType
    val price = alertPrice
    if (type != null && price != null) {
        AddToAlertDialog(
            type = type,
            orderBook = orderBook,
            prefilledPrice = price.takeIf { !it.isNaN() },
            onDismiss = { alertPrice = null },
            onAdded = { alertPrice = null },
        )
    }
}

/** Item header, KPI strip and the book or history — no DB or ESI here except the chart's own trades. */
@Composable
internal fun MarketBody(
    type: StaticTypeModel,
    hub: Hub,
    book: Book,
    history: List<MarketHistoryModel>,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    // null = from the header button, else the clicked order's price.
    onAlert: (Double?) -> Unit,
) {
    var showHistory by remember { mutableStateOf(false) }
    // Region-wide by default (as ESI returns it); on = only the hub station, where the trading is.
    var hubOnly by remember { mutableStateOf(false) }
    val (allSells, allBuys) = book
    val sells = remember(allSells, hubOnly, hub) { if (hubOnly) allSells.filter { it.locationId == hub.stationId } else allSells }
    val buys = remember(allBuys, hubOnly, hub) { if (hubOnly) allBuys.filter { it.locationId == hub.stationId } else allBuys }
    val stats = remember(sells, buys, history) { marketStats(sells, buys, history, today) }

    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypeIcon(type.typeId, size = 28.dp)
            Column(Modifier.weight(1f)) {
                Text(type.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    stringResource(Res.string.region_label, if (type.typeId == PLEX_TYPE_ID) "PLEX" else hub.name),
                    style = MaterialTheme.typography.labelSmall,
                    color = AXIS_COLOR,
                )
            }
            Segment(stringResource(Res.string.orders), !showHistory) { showHistory = false }
            Segment(stringResource(Res.string.history), showHistory) { showHistory = true }
            Spacer(Modifier.width(8.dp))
            Segment(stringResource(Res.string.hub_only, hub.name), hubOnly) { hubOnly = !hubOnly }
            Segment("+ " + stringResource(Res.string.alert), false) { onAlert(null) }
        }
        StatsStrip(stats)
        if (showHistory) {
            Panel(null, Modifier.fillMaxWidth().weight(1f)) { HistoryChartView(history, type, hub.regionId, book) }
        } else {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BookPanel(stringResource(Res.string.sell_orders), sells, isBuy = false, Modifier.weight(1f), onAlert)
                BookPanel(stringResource(Res.string.buy_orders), buys, isBuy = true, Modifier.weight(1f), onAlert)
            }
        }
    }
}

@Composable
private fun StatsStrip(s: MarketStats) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.dp, outline(), MaterialTheme.shapes.extraSmall)) {
        Kpi(
            stringResource(Res.string.best_sell),
            s.bestAsk?.let(::formatPriceAbbr) ?: "—",
            stringResource(Res.string.n_orders_vol, s.askOrders.toString(), formatVolume(s.askVolume)),
            null,
            negativeColor,
        )
        Kpi(
            stringResource(Res.string.best_buy),
            s.bestBid?.let(::formatPriceAbbr) ?: "—",
            stringResource(Res.string.n_orders_vol, s.bidOrders.toString(), formatVolume(s.bidVolume)),
            null,
            positiveColor,
        )
        Kpi(
            stringResource(Res.string.spread),
            s.spreadPct?.let { String.format(Locale.US, "%.2f%%", it) } ?: "—",
            s.bestAsk?.let { a -> s.bestBid?.let { b -> formatPriceAbbr(a - b) } },
            null,
            null,
            Res.string.tip_spread,
            valueColor = warningColor.takeIf { (s.spreadPct ?: 0.0) >= 5 },
        )
        Kpi(
            stringResource(Res.string.avg_price_30d),
            s.avg30?.let(::formatPriceAbbr) ?: "—",
            s.midVsAvgPct?.let { stringResource(Res.string.mid_vs_avg, pct(it)) },
            null,
            null,
            Res.string.tip_mid_vs_avg,
        )
        Kpi(
            stringResource(Res.string.range_30d),
            if (s.low30 != null && s.high30 != null) "${formatPriceAbbr(s.low30)} – ${formatPriceAbbr(s.high30)}" else "—",
            null,
            null,
            null,
        )
        Kpi(stringResource(Res.string.vol_day), formatVolPerDay(s.volPerDay30), null, null, null, Res.string.tip_vol_day, last = true)
    }
}

private fun shortLocation(name: String) = name.split(" - ").take(2).joinToString(" - ")

// One side of the book, best price first, with the volume stacked from the spread. Clicking a
// row opens a price alert at that price.
@Composable
private fun BookPanel(
    title: String,
    orders: List<MarketOrder>,
    isBuy: Boolean,
    modifier: Modifier,
    onAlert: (Double?) -> Unit,
) {
    val rows = remember(orders, isBuy) { stack(orders, isBuy) }
    val now = remember(orders) { Instant.now() }
    val side = if (isBuy) positiveColor else negativeColor
    Panel(
        title,
        modifier.fillMaxHeight(),
        header = {
            Box(Modifier.size(6.dp).background(side, MaterialTheme.shapes.extraSmall))
            Text(orders.size.toString(), style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = AXIS_COLOR)
            Spacer(Modifier.weight(1f))
            Text(stringResource(Res.string.click_for_alert), style = MaterialTheme.typography.labelSmall, color = AXIS_COLOR)
        },
    ) {
        val cols =
            buildList<Col<BookRow>> {
                add(Col(stringResource(Res.string.c_price), 1f) { it.o.price })
                add(Col(stringResource(Res.string.c_qty), 0.8f) { it.o.volumeRemaining })
                add(Col(stringResource(Res.string.c_cumulative), 0.8f) { it.cumulative })
                add(Col(stringResource(Res.string.c_isk_total), 0.9f) { it.o.price * it.o.volumeRemaining })
                if (isBuy) {
                    add(Col(stringResource(Res.string.c_min), 0.5f) { it.o.minVolume })
                    add(Col(stringResource(Res.string.c_range), 0.7f, end = false) { it.o.range })
                }
                add(Col(stringResource(Res.string.c_location), 1.8f, end = false) { it.o.locationName })
                add(Col(stringResource(Res.string.c_expires), 0.6f) { it.o.expiresAt() })
            }
        Table(
            cols = cols,
            rows = rows,
            empty = stringResource(Res.string.no_orders),
            defaultSort = 0,
            defaultDesc = isBuy,
            key = { it.o.orderId },
            onClick = { onAlert(it.o.price) },
        ) { r ->
            val left = r.o.expiresAt()?.let { Duration.between(now, it) }
            buildList {
                add(Cell(formatPriceAbbr(r.o.price), side))
                add(Cell(formatVolume(r.o.volumeRemaining.toLong())))
                add(Cell(formatVolume(r.cumulative), AXIS_COLOR))
                add(Cell(formatPriceAbbr(r.o.price * r.o.volumeRemaining), AXIS_COLOR))
                if (isBuy) {
                    add(Cell(if (r.o.minVolume > 1) formatVolume(r.o.minVolume.toLong()) else "", AXIS_COLOR))
                    add(Cell(r.o.range, AXIS_COLOR))
                }
                add(Cell(shortLocation(r.o.locationName), AXIS_COLOR))
                add(
                    Cell(
                        computeExpiry(r.o.issued, r.o.duration),
                        when {
                            left == null -> AXIS_COLOR
                            left.toDays() < 1 -> negativeColor
                            left.toDays() < 7 -> warningColor
                            else -> AXIS_COLOR
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun HistoryChartView(
    history: List<MarketHistoryModel>,
    type: StaticTypeModel,
    regionId: Int,
    orderBook: Book,
) {
    if (history.isEmpty()) {
        Hint(stringResource(Res.string.history_hint))
        return
    }
    val effRegion = if (type.typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else regionId
    // Your fills/orders for whichever character or corp is selected in the sidebar.
    val context by AppState.selectedContext.collectAsState()
    val trades by produceState<MyTrades?>(null, type.typeId, effRegion, context) {
        value =
            withContext(Dispatchers.IO) {
                context?.let { ctx ->
                    runCatching {
                        loadMyTrades(ctx.actingCharId, (ctx as? ViewContext.Corporation)?.corporationId, type.typeId, effRegion)
                    }.getOrNull()
                }
            }
    }
    ItemPriceChart(
        typeId = type.typeId,
        regionId = effRegion,
        stationId = null,
        history = remember(history) { history.sortedBy { it.date } },
        trades = trades,
        bestBid = orderBook.second.maxOfOrNull { it.price },
        bestAsk = orderBook.first.minOfOrNull { it.price },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun SearchRow(
    type: StaticTypeModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TypeIcon(type.typeId, size = 18.dp)
        Text(type.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ─── Market Group Tree ────────────────────────────────────────────────────

private fun buildFlatTree(
    groups: List<StaticMarketGroupModel>,
    expandedGroups: Set<Int>,
    depth: Int = 0,
): List<TreeNode> {
    val result = mutableListOf<TreeNode>()
    groups.forEach { group ->
        result.add(TreeNode.GroupNode(group, depth))
        if (group.marketGroupId in expandedGroups) {
            val children = StaticDataDao.getChildMarketGroups(group.marketGroupId)
            result.addAll(buildFlatTree(children, expandedGroups, depth + 1))
            val types = StaticDataDao.getTypesByMarketGroup(group.marketGroupId, limit = 200)
            types.forEach { result.add(TreeNode.TypeNode(it, depth + 1)) }
        }
    }
    return result
}

@Composable
private fun MarketGroupTree(
    topGroups: List<StaticMarketGroupModel>,
    expandedGroups: Set<Int>,
    selectedTypeId: Int?,
    onToggleExpand: (Int) -> Unit,
    onTypeClick: (StaticTypeModel) -> Unit,
) {
    val flatNodes =
        remember(topGroups, expandedGroups) {
            buildFlatTree(topGroups, expandedGroups)
        }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(
            items = flatNodes,
            key = { node ->
                when (node) {
                    is TreeNode.GroupNode -> "g_${node.group.marketGroupId}"
                    is TreeNode.TypeNode -> "t_${node.type.typeId}"
                }
            },
        ) { node ->
            when (node) {
                is TreeNode.GroupNode -> {
                    GroupTreeRow(
                        group = node.group,
                        depth = node.depth,
                        isExpanded = node.group.marketGroupId in expandedGroups,
                        onToggle = { onToggleExpand(node.group.marketGroupId) },
                    )
                }

                is TreeNode.TypeNode -> {
                    TypeTreeRow(
                        type = node.type,
                        depth = node.depth,
                        isSelected = node.type.typeId == selectedTypeId,
                        onClick = { onTypeClick(node.type) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupTreeRow(
    group: StaticMarketGroupModel,
    depth: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = (8 + depth * 16).dp, top = 3.dp, bottom = 3.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = group.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (depth == 0) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TypeTreeRow(
    type: StaticTypeModel,
    depth: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
                .padding(start = (8 + depth * 16 + 20).dp, top = 2.dp, bottom = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = type.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─── Data Loading ─────────────────────────────────────────────────────────

private suspend fun loadMarketData(
    regionId: Int,
    typeId: Int,
    ordersCallback: (Pair<List<MarketOrder>, List<MarketOrder>>) -> Unit,
    historyCallback: (List<MarketHistoryModel>) -> Unit,
) {
    val effectiveRegionId = if (typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else regionId
    // Load history from cache first
    val cachedHistory = MarketDao.getHistory(typeId, effectiveRegionId)
    if (cachedHistory.isNotEmpty()) {
        historyCallback(cachedHistory)
    }

    // Load orders
    try {
        val rawOrders = EsiClient.getMarketRegionOrders(effectiveRegionId, typeId = typeId)

        val locationIds = rawOrders.mapNotNull { (it["location_id"] as? Number)?.toLong() }.toSet()
        val locationNames = resolveLocationNames(locationIds)

        fun parseOrder(
            raw: Map<String, Any?>,
            isBuy: Boolean,
        ): MarketOrder? {
            val locationId = (raw["location_id"] as? Number)?.toLong() ?: return null
            return MarketOrder(
                orderId = (raw["order_id"] as? Number)?.toLong() ?: 0,
                price = (raw["price"] as? Number)?.toDouble() ?: return null,
                volumeRemaining = (raw["volume_remain"] as? Number)?.toInt() ?: 0,
                volumeTotal = (raw["volume_total"] as? Number)?.toInt() ?: 0,
                minVolume = (raw["min_volume"] as? Number)?.toInt() ?: 1,
                isBuyOrder = isBuy,
                locationId = locationId,
                locationName = locationNames[locationId] ?: stringBlocking(Res.string.unknown_location),
                systemId = (raw["system_id"] as? Number)?.toInt() ?: 0,
                issued = raw["issued"] as? String ?: "",
                duration = (raw["duration"] as? Number)?.toInt() ?: 90,
                range = raw["range"] as? String ?: "station",
            )
        }

        val sellOrders =
            rawOrders
                .filter { (it["is_buy_order"] as? Boolean) == false }
                .sortedBy { (it["price"] as? Number)?.toDouble() ?: 0.0 }
                .mapNotNull { parseOrder(it, false) }
        val buyOrders =
            rawOrders
                .filter { (it["is_buy_order"] as? Boolean) == true }
                .sortedByDescending { (it["price"] as? Number)?.toDouble() ?: 0.0 }
                .mapNotNull { parseOrder(it, true) }
        ordersCallback(sellOrders to buyOrders)
    } catch (e: Exception) {
        println("[Market] Error loading orders: ${e.message}")
    }

    // Fetch and save history
    try {
        val rawHistory = EsiClient.getMarketRegionHistory(effectiveRegionId, typeId)
        val models =
            rawHistory.mapNotNull { raw ->
                val date = raw["date"] as? String ?: return@mapNotNull null
                val avg = (raw["average"] as? Number)?.toDouble() ?: 0.0
                MarketHistoryModel(
                    typeId = typeId,
                    regionId = effectiveRegionId,
                    date = date,
                    average = avg,
                    volume = (raw["volume"] as? Number)?.toLong() ?: 0,
                    orderCount = (raw["order_count"] as? Number)?.toLong() ?: 0,
                    highest = (raw["highest"] as? Number)?.toDouble() ?: 0.0,
                    lowest = (raw["lowest"] as? Number)?.toDouble() ?: 0.0,
                )
            }
        models.forEach { MarketDao.insertHistory(it) }
        historyCallback(models)
    } catch (e: Exception) {
        println("[Market] Error loading history: ${e.message}")
    }
}

private suspend fun resolveLocationNames(locationIds: Set<Long>): Map<Long, String> =
    withContext(Dispatchers.IO) {
        val result = mutableMapOf<Long, String>()
        val npcIds = locationIds.filter { it <= Int.MAX_VALUE }
        if (npcIds.isNotEmpty()) {
            try {
                EsiClient
                    .resolveNames(npcIds.map { it.toInt() })
                    .forEach { (id, name) -> result[id.toLong()] = name }
            } catch (e: Exception) {
                println("[Market] resolveNames failed: ${e.message}")
            }
        }
        locationIds.filter { it > Int.MAX_VALUE }.forEach { id ->
            val name = StaticDataDao.getStationById(id)?.name
            result[id] = name ?: stringBlocking(Res.string.unknown_structure, id)
        }
        result
    }

private fun computeExpiry(
    issued: String,
    durationDays: Int,
): String =
    try {
        val issuedInstant = java.time.Instant.parse(issued)
        val expiryInstant = issuedInstant.plusSeconds(durationDays * 86400L)
        val secondsLeft =
            expiryInstant.epochSecond -
                java.time.Instant
                    .now()
                    .epochSecond
        when {
            secondsLeft <= 0 -> stringBlocking(Res.string.expired)
            secondsLeft < 3600 -> stringBlocking(Res.string.dur_m, secondsLeft / 60)
            secondsLeft < 86400 -> stringBlocking(Res.string.dur_h, secondsLeft / 3600)
            else -> stringBlocking(Res.string.dur_d, secondsLeft / 86400)
        }
    } catch (e: Exception) {
        stringBlocking(Res.string.dur_d, durationDays)
    }

private fun formatVolPerDay(perDay: Double): String =
    when {
        perDay >= 1_000_000 -> String.format(Locale.US, "%.1fM", perDay / 1_000_000)
        perDay >= 1_000 -> String.format(Locale.US, "%.1fK", perDay / 1_000)
        perDay >= 10 -> String.format(Locale.US, "%.0f", perDay)
        perDay >= 1 -> String.format(Locale.US, "%.1f", perDay)
        else -> String.format(Locale.US, "%.2f", perDay)
    }

// ─── Add to Alert Dialog ────────────────────────────────────────────────

@Composable
private fun AddToAlertDialog(
    type: StaticTypeModel,
    orderBook: Pair<List<MarketOrder>, List<MarketOrder>>,
    prefilledPrice: Double? = null,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var targetPrice by remember { mutableStateOf(prefilledPrice?.let { String.format(Locale.US, "%.2f", it) } ?: "") }
    var condition by remember { mutableStateOf("below") }

    val (sellOrders, buyOrders) = orderBook
    val bestSell = sellOrders.minOfOrNull { it.price }
    val bestBuy = buyOrders.maxOfOrNull { it.price }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.create_price_alert)) },
        text = {
            Column {
                Text(type.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                if (bestSell != null || bestBuy != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (bestSell !=
                            null
                        ) {
                            Text(
                                stringResource(Res.string.best_sell_price, formatPriceAbbr(bestSell)),
                                style = MaterialTheme.typography.labelSmall,
                                color = negativeColor,
                            )
                        }
                        if (bestBuy !=
                            null
                        ) {
                            Text(
                                stringResource(Res.string.best_buy_price, formatPriceAbbr(bestBuy)),
                                style = MaterialTheme.typography.labelSmall,
                                color = positiveColor,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = condition == "below",
                        onClick = { condition = "below" },
                        label = { Text(stringResource(Res.string.below)) },
                    )
                    FilterChip(
                        selected = condition == "above",
                        onClick = { condition = "above" },
                        label = { Text(stringResource(Res.string.above)) },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = targetPrice,
                    onValueChange = { targetPrice = it },
                    label = { Tip(stringResource(Res.string.tip_al_target)) { Text(stringResource(Res.string.target_price_isk)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val price = targetPrice.toDoubleOrNull() ?: return@Button
                    scope.launch(Dispatchers.IO) {
                        AlertDao.insert(
                            PriceAlertModel(
                                typeId = type.typeId,
                                typeName = type.name,
                                targetPrice = price,
                                condition = condition,
                                regionId = if (type.typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else 10000002,
                            ),
                        )
                        withContext(Dispatchers.Main) { onAdded() }
                    }
                },
                enabled = targetPrice.toDoubleOrNull() != null,
            ) { Text(stringResource(Res.string.create_alert)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
