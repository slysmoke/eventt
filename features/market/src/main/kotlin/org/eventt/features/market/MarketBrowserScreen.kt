package org.eventt.features.market

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.eventt.ui.common.formatPriceAbbr
import org.eventt.ui.common.formatVolume
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

// Trade hub regions
val TRADE_HUBS =
    listOf(
        10000002 to "The Forge (Jita)",
        10000043 to "Domain (Amarr)",
        10000032 to "Sinq Laison (Dodixie)",
        10000030 to "Metropolis (Hek)",
        10000042 to "Heimatar (Rens)",
    )

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

@Composable
fun MarketBrowserScreen() {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<StaticTypeModel>>(emptyList()) }
    var selectedType by remember { mutableStateOf<StaticTypeModel?>(null) }
    var selectedRegionId by remember { mutableStateOf(10000002) }
    var orderBook by remember { mutableStateOf<Pair<List<MarketOrder>, List<MarketOrder>>>(emptyList<MarketOrder>() to emptyList()) }
    var history by remember { mutableStateOf<List<MarketHistoryModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showOrderBook by remember { mutableStateOf(true) }
    var topGroups by remember { mutableStateOf<List<StaticMarketGroupModel>>(emptyList()) }
    var expandedGroups by remember { mutableStateOf(setOf<Int>()) }
    var isSdeImporting by remember { mutableStateOf(false) }
    var showAddToAlert by remember { mutableStateOf(false) }
    var contextMenuOrder by remember { mutableStateOf<MarketOrder?>(null) }

    // Deep link from another tab (e.g. Orders' "view price" button) — preselect the requested
    // item exactly as a search-result click would, then clear the one-shot signal.
    val pendingTypeId by AppState.pendingMarketTypeId.collectAsState()
    LaunchedEffect(pendingTypeId) {
        val typeId = pendingTypeId ?: return@LaunchedEffect
        val type = withContext(Dispatchers.IO) { StaticDataDao.getTypeById(typeId) }
        if (type != null) {
            selectedType = type
            searchQuery = ""
            searchResults = emptyList()
            loadMarketData(selectedRegionId, type.typeId, ordersCallback = { orderBook = it }, historyCallback = { history = it })
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

    Column(modifier = Modifier.fillMaxSize()) {
        // ─── Top Bar ──────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.market_browser), style = MaterialTheme.typography.headlineMedium)

                    // Region selector
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.region_colon),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(TRADE_HUBS) { (id, name) ->
                                FilterChip(
                                    selected = selectedRegionId == id,
                                    onClick = {
                                        selectedRegionId = id
                                        selectedType?.let { type ->
                                            scope.launch {
                                                loadMarketData(id, type.typeId, ordersCallback = { orderBook = it }, historyCallback = {
                                                    history =
                                                        it
                                                })
                                            }
                                        }
                                    },
                                    label = {
                                        Text(
                                            name,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Search
                SearchField(
                    query = searchQuery,
                    onQueryChange = { query ->
                        searchQuery = query
                        if (query.length >= 2) {
                            scope.launch(Dispatchers.IO) {
                                searchResults = StaticDataDao.searchMarketTypes(query, limit = 30)
                            }
                        } else {
                            searchResults = emptyList()
                        }
                    },
                    placeholder = stringResource(Res.string.search_items),
                    modifier = Modifier.fillMaxWidth(0.5f),
                )

                // Search results dropdown
                if (searchResults.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.5f).heightIn(max = 250.dp),
                    ) {
                        LazyColumn {
                            items(searchResults) { type ->
                                SearchRow(
                                    type = type,
                                    onClick = {
                                        selectedType = type
                                        searchQuery = ""
                                        searchResults = emptyList()
                                        scope.launch {
                                            loadMarketData(selectedRegionId, type.typeId, ordersCallback = {
                                                orderBook = it
                                            }, historyCallback = {
                                                history =
                                                    it
                                            })
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ─── Main Content ─────────────────────────────────────────
        Row(modifier = Modifier.fillMaxSize()) {
            // Sidebar: Market Group Tree
            Surface(
                modifier = Modifier.width(260.dp).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ) {
                Column(modifier = Modifier.padding(4.dp)) {
                    Text(
                        stringResource(Res.string.categories),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    MarketGroupTree(
                        topGroups = topGroups,
                        expandedGroups = expandedGroups,
                        selectedTypeId = selectedType?.typeId,
                        onToggleExpand = { groupId ->
                            expandedGroups = if (groupId in expandedGroups) expandedGroups - groupId else expandedGroups + groupId
                        },
                        onTypeClick = { type ->
                            selectedType = type
                            scope.launch {
                                loadMarketData(selectedRegionId, type.typeId, ordersCallback = { orderBook = it }, historyCallback = {
                                    history =
                                        it
                                })
                            }
                        },
                    )
                }
            }

            // Main panel
            Surface(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                    if (selectedType != null) {
                        TypeMarketHeader(
                            type = selectedType!!,
                            orderBook = orderBook,
                            showOrderBook = showOrderBook,
                            onToggleView = { showOrderBook = !showOrderBook },
                            onAddToAlert = { showAddToAlert = true },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (showOrderBook) {
                            OrderBookView(orderBook, onCreateAlert = { order -> contextMenuOrder = order })
                        } else {
                            HistoryChartView(history, selectedType!!, selectedRegionId, orderBook)
                        }
                    } else {
                        EmptyState(
                            icon = Icons.Default.Store,
                            title = stringResource(Res.string.browse_market),
                            description = stringResource(Res.string.browse_market_desc),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    LoadingOverlay(isLoading = isLoading, message = stringResource(Res.string.loading_market_data))

    // SDE Import overlay
    if (isSdeImporting) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.padding(32.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Downloading,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(Res.string.importing_sde), style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.downloading_sde),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    CircularProgressIndicator()
                }
            }
        }
    }

    // Add to Alert dialog (from header button)
    if (showAddToAlert && selectedType != null) {
        AddToAlertDialog(
            type = selectedType!!,
            orderBook = orderBook,
            onDismiss = { showAddToAlert = false },
            onAdded = { showAddToAlert = false },
        )
    }

    // Add to Alert dialog (from right-click on order row)
    if (contextMenuOrder != null && selectedType != null) {
        AddToAlertDialog(
            type = selectedType!!,
            orderBook = orderBook,
            prefilledPrice = contextMenuOrder!!.price,
            onDismiss = { contextMenuOrder = null },
            onAdded = { contextMenuOrder = null },
        )
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

// ─── Type Market Header ──────────────────────────────────────────────────

@Composable
private fun TypeMarketHeader(
    type: StaticTypeModel,
    orderBook: Pair<List<MarketOrder>, List<MarketOrder>>,
    showOrderBook: Boolean,
    onToggleView: () -> Unit,
    onAddToAlert: () -> Unit,
) {
    val (sellOrders, buyOrders) = orderBook
    val bestSell = sellOrders.minOfOrNull { it.price }
    val bestBuy = buyOrders.maxOfOrNull { it.price }
    val spread =
        if (bestSell != null && bestBuy != null && bestSell > 0) {
            ((bestSell - bestBuy) / bestSell) * 100
        } else {
            null
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeIcon(type.typeId, size = 32.dp)

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Text(type.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(Res.string.region_label, TRADE_HUBS.find { it.first == 10000002 }?.second ?: "The Forge"),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                )
            }
        }

        Row {
            FilterChip(selected = showOrderBook, onClick = onToggleView, label = { Text(stringResource(Res.string.orders)) })
            Spacer(modifier = Modifier.width(4.dp))
            FilterChip(selected = !showOrderBook, onClick = onToggleView, label = { Text(stringResource(Res.string.history)) })
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onAddToAlert, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Icon(Icons.Default.Notifications, null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(Res.string.alert), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    // Spread bar
    if (bestSell != null && bestBuy != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SpreadItem(stringResource(Res.string.best_sell), formatPriceAbbr(bestSell), negativeColor)
                SpreadItem(stringResource(Res.string.best_buy), formatPriceAbbr(bestBuy), positiveColor)
                SpreadItem(stringResource(Res.string.spread), "${String.format(Locale.US, "%.2f", spread ?: 0.0)}%", warningColor)
                SpreadItem(stringResource(Res.string.sell_orders), sellOrders.size.toString(), MaterialTheme.colorScheme.onSurface)
                SpreadItem(stringResource(Res.string.buy_orders), buyOrders.size.toString(), MaterialTheme.colorScheme.onSurface)
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun SpreadItem(
    label: String,
    value: String,
    color: Color,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = color)
    }
}

// ─── Order Book ───────────────────────────────────────────────────────────

private enum class OrderSortColumn {
    PRICE,
    REMAIN,
    TOTAL_ISK,
    MIN_VOL,
    RANGE,
    LOCATION,
    ISSUED,
    EXPIRES,
}

private fun sortOrders(
    orders: List<MarketOrder>,
    col: OrderSortColumn,
    asc: Boolean,
): List<MarketOrder> {
    val cmp: Comparator<MarketOrder> =
        when (col) {
            OrderSortColumn.PRICE -> {
                compareBy { it.price }
            }

            OrderSortColumn.REMAIN -> {
                compareBy { it.volumeRemaining }
            }

            OrderSortColumn.TOTAL_ISK -> {
                compareBy { it.price * it.volumeRemaining }
            }

            OrderSortColumn.MIN_VOL -> {
                compareBy { it.minVolume }
            }

            OrderSortColumn.RANGE -> {
                compareBy { it.range }
            }

            OrderSortColumn.LOCATION -> {
                compareBy { it.locationName }
            }

            OrderSortColumn.ISSUED -> {
                compareBy { it.issued }
            }

            OrderSortColumn.EXPIRES -> {
                compareBy {
                    try {
                        java.time.Instant
                            .parse(it.issued)
                            .plusSeconds(it.duration * 86400L)
                            .epochSecond
                    } catch (e: Exception) {
                        0L
                    }
                }
            }
        }
    return if (asc) orders.sortedWith(cmp) else orders.sortedWith(cmp.reversed())
}

@Composable
private fun OrderBookView(
    orders: Pair<List<MarketOrder>, List<MarketOrder>>,
    onCreateAlert: ((MarketOrder) -> Unit)? = null,
) {
    val (rawSell, rawBuy) = orders

    var sellSort by remember { mutableStateOf(OrderSortColumn.PRICE) }
    var sellAsc by remember { mutableStateOf(true) }
    var buySort by remember { mutableStateOf(OrderSortColumn.PRICE) }
    var buyAsc by remember { mutableStateOf(false) }

    val sellOrders = remember(rawSell, sellSort, sellAsc) { sortOrders(rawSell, sellSort, sellAsc) }
    val buyOrders = remember(rawBuy, buySort, buyAsc) { sortOrders(rawBuy, buySort, buyAsc) }

    Column(modifier = Modifier.fillMaxSize()) {
        OrderTable(
            title = stringResource(Res.string.sell_orders),
            count = sellOrders.size,
            titleColor = negativeColor,
            orders = sellOrders,
            isBuy = false,
            sortCol = sellSort,
            ascending = sellAsc,
            modifier = Modifier.weight(1f),
            onSort = { col ->
                if (sellSort == col) {
                    sellAsc = !sellAsc
                } else {
                    sellSort = col
                    sellAsc = true
                }
            },
            onCreateAlert = onCreateAlert,
        )
        HorizontalDivider(thickness = 2.dp)
        OrderTable(
            title = stringResource(Res.string.buy_orders),
            count = buyOrders.size,
            titleColor = positiveColor,
            orders = buyOrders,
            isBuy = true,
            sortCol = buySort,
            ascending = buyAsc,
            modifier = Modifier.weight(1f),
            onSort = { col ->
                if (buySort == col) {
                    buyAsc = !buyAsc
                } else {
                    buySort = col
                    buyAsc = false
                }
            },
            onCreateAlert = onCreateAlert,
        )
    }
}

@Composable
private fun OrderTable(
    title: String,
    count: Int,
    titleColor: Color,
    orders: List<MarketOrder>,
    isBuy: Boolean,
    sortCol: OrderSortColumn,
    ascending: Boolean,
    modifier: Modifier = Modifier,
    onSort: (OrderSortColumn) -> Unit,
    onCreateAlert: ((MarketOrder) -> Unit)? = null,
) {
    Column(modifier = modifier) {
        // Section title bar
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$title ($count)",
                style = MaterialTheme.typography.labelMedium,
                color = titleColor,
                fontWeight = FontWeight.Bold,
            )
        }

        // Sortable column header
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortableCol(stringResource(Res.string.c_price), OrderSortColumn.PRICE, sortCol, ascending, onSort, Modifier.width(90.dp))
            SortableCol(
                stringResource(Res.string.c_remain_total),
                OrderSortColumn.REMAIN,
                sortCol,
                ascending,
                onSort,
                Modifier.width(120.dp),
            )
            SortableCol(
                stringResource(Res.string.c_isk_total),
                OrderSortColumn.TOTAL_ISK,
                sortCol,
                ascending,
                onSort,
                Modifier.width(90.dp),
            )
            SortableCol(stringResource(Res.string.c_min), OrderSortColumn.MIN_VOL, sortCol, ascending, onSort, Modifier.width(50.dp))
            if (isBuy) {
                SortableCol(
                    stringResource(Res.string.c_range),
                    OrderSortColumn.RANGE,
                    sortCol,
                    ascending,
                    onSort,
                    Modifier.width(80.dp),
                )
            }
            SortableCol(stringResource(Res.string.c_location), OrderSortColumn.LOCATION, sortCol, ascending, onSort, Modifier.weight(1f))
            SortableCol(stringResource(Res.string.c_issued), OrderSortColumn.ISSUED, sortCol, ascending, onSort, Modifier.width(82.dp))
            SortableCol(stringResource(Res.string.c_expires), OrderSortColumn.EXPIRES, sortCol, ascending, onSort, Modifier.width(56.dp))
        }

        // Rows
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            if (orders.isEmpty()) {
                Text(
                    stringResource(Res.string.no_orders),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                orders.forEachIndexed { index, order ->
                    OrderRow(order = order, isBuy = isBuy, index = index, onCreateAlert = onCreateAlert)
                }
            }
        }
    }
}

@Composable
private fun SortableCol(
    label: String,
    col: OrderSortColumn,
    current: OrderSortColumn,
    ascending: Boolean,
    onSort: (OrderSortColumn) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = col == current
    Row(
        modifier =
            modifier
                .clickable { onSort(col) }
                .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color =
                if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
            maxLines = 1,
        )
        if (active) {
            Icon(
                imageVector = if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun OrderRow(
    order: MarketOrder,
    isBuy: Boolean,
    index: Int,
    onCreateAlert: ((MarketOrder) -> Unit)? = null,
) {
    val priceColor = if (!isBuy) negativeColor else positiveColor
    val expiry = remember(order.issued, order.duration) { computeExpiry(order.issued, order.duration) }
    val expDaysLeft = remember(order.issued, order.duration) { daysLeftUntilExpiry(order.issued, order.duration) }
    val expColor =
        when {
            expDaysLeft == null -> Color.Gray
            expDaysLeft <= 1 -> negativeColor
            expDaysLeft <= 7 -> warningColor
            else -> Color(0xFF888888)
        }
    val rowBg =
        if (index % 2 == 0) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.025f)
        }
    var showContextMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(rowBg)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                    showContextMenu = true
                                }
                            }
                        }
                    },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatPriceAbbr(order.price),
                style = MaterialTheme.typography.bodySmall,
                color = priceColor,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(90.dp),
            )
            Text(
                "${formatVolume(order.volumeRemaining.toLong())} / ${formatVolume(order.volumeTotal.toLong())}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(120.dp),
            )
            Text(
                formatPriceAbbr(order.price * order.volumeRemaining),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.width(90.dp),
            )
            Text(
                formatVolume(order.minVolume.toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.width(50.dp),
            )
            if (isBuy) {
                Text(
                    order.range,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.width(80.dp),
                )
            }
            Text(
                order.locationName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                order.issued.take(10),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.width(82.dp),
            )
            Text(
                expiry,
                style = MaterialTheme.typography.labelSmall,
                color = expColor,
                fontWeight = if (expDaysLeft != null && expDaysLeft <= 1) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.width(56.dp),
            )
        }
        DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.create_price_alert)) },
                leadingIcon = { Icon(Icons.Default.Notifications, null, modifier = Modifier.size(16.dp)) },
                onClick = {
                    showContextMenu = false
                    onCreateAlert?.invoke(order)
                },
            )
        }
    } // Box
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
}

// ─── History Chart ────────────────────────────────────────────────────────

@Composable
private fun HistoryChartView(
    history: List<MarketHistoryModel>,
    type: StaticTypeModel,
    regionId: Int,
    orderBook: Pair<List<MarketOrder>, List<MarketOrder>>,
) {
    if (history.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.ShowChart,
            title = stringResource(Res.string.no_history_data),
            description = stringResource(Res.string.history_hint),
        )
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
    val sorted = remember(history) { history.sortedBy { it.date } }

    Column(modifier = Modifier.fillMaxSize()) {
        ItemPriceChart(
            typeId = type.typeId,
            regionId = effRegion,
            stationId = null,
            history = sorted,
            trades = trades,
            bestBid = orderBook.second.maxOfOrNull { it.price },
            bestAsk = orderBook.first.minOfOrNull { it.price },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Last-30-day summary — vol/day uses calendar days as denominator, not trading-day count.
        val cutoff =
            java.time.LocalDate
                .now()
                .minusDays(30)
                .toString()
        val recent = sorted.filter { it.date.take(10) >= cutoff }
        val totalVolume = recent.sumOf { it.volume }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(stringResource(Res.string.avg_price_30d), formatPriceAbbr(recent.averageOf { it.average }), Modifier.weight(1f))
            StatCard(stringResource(Res.string.highest_30d), formatPriceAbbr(recent.maxOfOrNull { it.highest } ?: 0.0), Modifier.weight(1f))
            StatCard(stringResource(Res.string.lowest_30d), formatPriceAbbr(recent.minOfOrNull { it.lowest } ?: 0.0), Modifier.weight(1f))
            StatCard(stringResource(Res.string.total_vol_30d), formatVolume(totalVolume), Modifier.weight(1f))
            StatCard(stringResource(Res.string.vol_day), formatVolPerDay(totalVolume, 30), Modifier.weight(1f))
            StatCard(stringResource(Res.string.orders_30d), formatVolume(recent.sumOf { it.orderCount }), Modifier.weight(1f))
        }
    }
}

@Composable
internal fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = valueColor)
        }
    }
}

// ─── Helper Composables ───────────────────────────────────────────────────

@Composable
private fun SearchRow(
    type: StaticTypeModel,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeIcon(type.typeId, size = 18.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(type.name, style = MaterialTheme.typography.bodyMedium)
    }
}

// ─── Data Models ──────────────────────────────────────────────────────────

private data class MarketOrder(
    val orderId: Long,
    val price: Double,
    val volumeRemaining: Int,
    val volumeTotal: Int,
    val minVolume: Int,
    val isBuyOrder: Boolean,
    val locationId: Long,
    val locationName: String,
    val systemId: Int,
    val issued: String,
    val duration: Int,
    val range: String,
)

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

private fun daysLeftUntilExpiry(
    issued: String,
    durationDays: Int,
): Long? =
    try {
        val issuedInstant = java.time.Instant.parse(issued)
        val expiryInstant = issuedInstant.plusSeconds(durationDays * 86400L)
        (
            expiryInstant.epochSecond -
                java.time.Instant
                    .now()
                    .epochSecond
        ) / 86400
    } catch (e: Exception) {
        null
    }

// Deprecated local formatPrice removed. Use formatPriceAbbr from FormatUtils.

// Deprecated local formatVolume removed. Use formatVolume(vol) from FormatUtils.

private fun <T> List<T>.averageOf(selector: (T) -> Double): Double {
    if (isEmpty()) return 0.0
    return sumOf(selector) / size
}

private fun formatVolPerDay(
    totalVol: Long,
    calendarDays: Int,
): String {
    val perDay = totalVol.toDouble() / calendarDays.coerceAtLeast(1)
    return when {
        perDay >= 1_000_000 -> String.format(Locale.US, "%.1fM", perDay / 1_000_000)
        perDay >= 1_000 -> String.format(Locale.US, "%.1fK", perDay / 1_000)
        perDay >= 10 -> String.format(Locale.US, "%.0f", perDay)
        perDay >= 1 -> String.format(Locale.US, "%.1f", perDay)
        else -> String.format(Locale.US, "%.2f", perDay)
    }
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
                    label = { Text(stringResource(Res.string.target_price_isk)) },
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
