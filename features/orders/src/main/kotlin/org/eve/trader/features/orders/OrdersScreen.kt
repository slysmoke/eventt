package org.eve.trader.features.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import org.eve.trader.core.database.OrderHistoryDao
import org.eve.trader.core.database.StaticDataDao
import org.eve.trader.core.database.WalletDao
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.ui.common.EmptyState
import org.eve.trader.ui.common.EsiRefreshButton
import org.eve.trader.ui.common.LoadingOverlay
import java.awt.datatransfer.StringSelection
import java.awt.Toolkit
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale

private val SELL_COLOR      = Color(0xFFFF6B6B)
private val BUY_COLOR       = Color(0xFF69DB7C)
private val VOL_SELL        = Color(0xFFFF8C00)
private val VOL_BUY         = Color(0xFF2E7D32)
private val PROFIT_COLOR    = Color(0xFF69DB7C)
private val LOSS_COLOR      = Color(0xFFFF6B6B)
private val UNDERCUT_COLOR  = Color(0xFFFF9800)  // orange — order has been beaten
private val ACTIVE_IN_GAME  = Color(0xFF4A90D9)  // blue — currently open in EVE client

private data class CharacterOrder(
    val orderId: Long,
    val typeId: Int,
    val typeName: String,
    val groupName: String,
    val locationId: Long,
    val regionId: Int,
    val stationName: String,
    val price: Double,
    val volumeTotal: Int,
    val volumeRemaining: Int,
    val isBuyOrder: Boolean,
    val duration: Int,
    val issued: String,
    val range: String,
    val minVolume: Int,
    val state: String,
) {
    val total: Double get() = price * volumeRemaining
    val timeLeftSeconds: Long get() {
        return try {
            val exp = OffsetDateTime.parse(issued).withOffsetSameInstant(ZoneOffset.UTC).plusDays(duration.toLong())
            ChronoUnit.SECONDS.between(OffsetDateTime.now(ZoneOffset.UTC), exp).coerceAtLeast(0)
        } catch (_: Exception) { 0L }
    }
    val orderAgeSeconds: Long get() {
        return try {
            val start = OffsetDateTime.parse(issued).withOffsetSameInstant(ZoneOffset.UTC)
            ChronoUnit.SECONDS.between(start, OffsetDateTime.now(ZoneOffset.UTC)).coerceAtLeast(0)
        } catch (_: Exception) { 0L }
    }
    val issuedFormatted: String get() = issued.take(16).replace("T", " ")
}

/** Best competing prices for a (typeId, regionId) pair, excluding the character's own orders. */
private data class MarketComparison(
    val bestSell: Double?,  // lowest sell from others — null means no sell competition
    val bestBuy: Double?,   // highest buy from others — null means no buy competition
)

private enum class SortCol { NAME, GROUP, PRICE, VOLUME, TOTAL, TIME_LEFT, ORDER_AGE, STATION }
private enum class SortDir { ASC, DESC }

@Composable
fun OrdersScreen(charId: Int?) {
    val scope = rememberCoroutineScope()
    var orders             by remember { mutableStateOf<List<CharacterOrder>>(emptyList()) }
    var historyOrders      by remember { mutableStateOf<List<OrderHistoryDao.OrderHistoryRecord>>(emptyList()) }
    var fifoResult         by remember { mutableStateOf<CostBasisService.FifoResult?>(null) }
    var isLoading          by remember { mutableStateOf(false) }
    var refreshAvailableAt by remember { mutableStateOf<Long?>(null) }
    var activeTab          by remember { mutableStateOf(0) }
    var sortCol            by remember { mutableStateOf(SortCol.NAME) }
    var sortDir            by remember { mutableStateOf(SortDir.ASC) }

    // Market comparison data — loaded after orders, shown as overbid indicators
    var marketComparisons  by remember { mutableStateOf<Map<Pair<Int, Int>, MarketComparison>>(emptyMap()) }
    var isLoadingMarket    by remember { mutableStateOf(false) }

    // Selected order for hotkey action
    var selectedOrderId    by remember { mutableStateOf<Long?>(null) }

    // Order currently open in the EVE client via the global hotkey
    val activeOrderId by PendingOrdersQueue.currentOrderId.collectAsState()

    val focusRequester = remember { FocusRequester() }

    fun fetchMarketComparisons(activeOrders: List<CharacterOrder>) {
        if (activeOrders.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoadingMarket = true }
            try {
                val uniquePairs = activeOrders
                    .filter { it.regionId > 0 && it.state == "active" }
                    .map { it.typeId to it.regionId }
                    .toSet()

                val result = mutableMapOf<Pair<Int, Int>, MarketComparison>()
                for ((typeId, regionId) in uniquePairs) {
                    try {
                        val ownIds = activeOrders
                            .filter { it.typeId == typeId && it.regionId == regionId }
                            .map { it.orderId }
                            .toSet()
                        val all = EsiClient.getMarketRegionOrders(regionId, "all", typeId)
                        val bestSell = all
                            .filter { !(it["is_buy_order"] as? Boolean ?: false) && (it["order_id"] as? Number)?.toLong() !in ownIds }
                            .mapNotNull { (it["price"] as? Number)?.toDouble() }
                            .minOrNull()
                        val bestBuy = all
                            .filter { (it["is_buy_order"] as? Boolean ?: false) && (it["order_id"] as? Number)?.toLong() !in ownIds }
                            .mapNotNull { (it["price"] as? Number)?.toDouble() }
                            .maxOrNull()
                        result[typeId to regionId] = MarketComparison(bestSell, bestBuy)
                    } catch (_: Exception) {}
                }
                withContext(Dispatchers.Main) { marketComparisons = result }
            } finally {
                withContext(Dispatchers.Main) { isLoadingMarket = false }
            }
        }
    }

    fun loadOrders(charId: Int) {
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true }
            try {
                val raw = EsiClient.getCharacterOrders(charId)
                val parsed = raw.map { m ->
                    val typeId     = (m["type_id"]     as? Number)?.toInt()  ?: 0
                    val locationId = (m["location_id"] as? Number)?.toLong() ?: 0L
                    CharacterOrder(
                        orderId         = (m["order_id"]      as? Number)?.toLong()  ?: 0L,
                        typeId          = typeId,
                        typeName        = StaticDataDao.getTypeName(typeId)           ?: "Unknown ($typeId)",
                        groupName       = StaticDataDao.getGroupNameForType(typeId)   ?: "",
                        locationId      = locationId,
                        regionId        = if (locationId < 1_000_000_000_000L) StaticDataDao.getStationById(locationId)?.regionId ?: 0 else 0,
                        stationName     = StaticDataDao.getStationById(locationId)?.name ?: locationId.toString(),
                        price           = (m["price"]          as? Number)?.toDouble() ?: 0.0,
                        volumeTotal     = (m["volume_total"]   as? Number)?.toInt()   ?: 0,
                        volumeRemaining = (m["volume_remain"]  as? Number)?.toInt()   ?: 0,
                        isBuyOrder      = (m["is_buy_order"]   as? Boolean)           ?: false,
                        duration        = (m["duration"]       as? Number)?.toInt()   ?: 0,
                        issued          = (m["issued"]         as? String)            ?: "",
                        range           = (m["range"]          as? String)            ?: "",
                        minVolume       = (m["min_volume"]     as? Number)?.toInt()   ?: 1,
                        state           = (m["state"]          as? String)            ?: "active",
                    )
                }
                withContext(Dispatchers.Main) { orders = parsed }

                val rawHistory = EsiClient.getCharacterOrdersHistory(charId)
                val historyRecords = rawHistory.map { m ->
                    val typeId     = (m["type_id"]     as? Number)?.toInt()  ?: 0
                    val locationId = (m["location_id"] as? Number)?.toLong() ?: 0L
                    OrderHistoryDao.OrderHistoryRecord(
                        orderId         = (m["order_id"]       as? Number)?.toLong()  ?: 0L,
                        typeId          = typeId,
                        typeName        = StaticDataDao.getTypeName(typeId)            ?: "Unknown ($typeId)",
                        locationId      = locationId,
                        stationName     = StaticDataDao.getStationById(locationId)?.name ?: locationId.toString(),
                        price           = (m["price"]          as? Number)?.toDouble() ?: 0.0,
                        volumeTotal     = (m["volume_total"]   as? Number)?.toInt()   ?: 0,
                        volumeRemaining = (m["volume_remain"]  as? Number)?.toInt()   ?: 0,
                        isBuyOrder      = (m["is_buy_order"]   as? Boolean)            ?: false,
                        duration        = (m["duration"]       as? Number)?.toInt()   ?: 0,
                        issued          = (m["issued"]         as? String)            ?: "",
                        range           = (m["range"]          as? String)            ?: "station",
                        minVolume       = (m["min_volume"]     as? Number)?.toInt()   ?: 1,
                        state           = (m["state"]          as? String)            ?: "expired",
                        characterId     = charId,
                    )
                }
                OrderHistoryDao.upsertAll(historyRecords)
                val stored = OrderHistoryDao.getAll(charId)
                withContext(Dispatchers.Main) { historyOrders = stored }

                // Sync wallet transactions to DB before recomputing FIFO.
                // EsiClient serves from the ESI cache during cooldown, so no extra HTTP call is made.
                // This ensures newly fulfilled orders get correct P&L once the wallet cache expires.
                try {
                    val txList = EsiClient.getCharacterTransactions(charId)
                    txList.forEach { tx ->
                        val typeId    = (tx["type_id"]    as? Number)?.toInt()    ?: 0
                        val qty       = (tx["quantity"]   as? Number)?.toInt()    ?: 0
                        val unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0
                        runCatching {
                            WalletDao.insertTransaction(
                                transactionId = (tx["transaction_id"] as? Number)?.toLong() ?: 0L,
                                date          = tx["date"] as? String ?: "",
                                typeId        = typeId,
                                typeName      = StaticDataDao.getTypeName(typeId) ?: "",
                                quantity      = qty,
                                unitPrice     = unitPrice,
                                total         = unitPrice * qty,
                                isBuy         = (tx["is_buy"] as? Boolean) ?: false,
                                clientId      = (tx["client_id"]   as? Number)?.toInt()  ?: 0,
                                clientName    = "",
                                locationId    = (tx["location_id"] as? Number)?.toLong() ?: 0L,
                                locationName  = "",
                                isCorp        = false,
                                characterId   = charId,
                                corporationId = null,
                            )
                        }
                    }
                } catch (_: Exception) {}

                val taxConfig = CostBasisService.TaxConfig(
                    salesTaxPct  = StaticDataDao.getCharSalesTax(charId),
                    brokerFeePct = StaticDataDao.getCharBrokersFee(charId),
                )
                val fifo   = CostBasisService.compute(charId, taxConfig)
                val expiry = EsiClient.getEndpointExpiry("/characters/$charId/orders/")
                withContext(Dispatchers.Main) {
                    fifoResult         = fifo
                    refreshAvailableAt = expiry
                }

                // Load market comparison data after orders are parsed
                fetchMarketComparisons(parsed)
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    // Hotkey action: open market window in-game + copy overbid price to clipboard
    fun triggerOrderAction(order: CharacterOrder) {
        val comp = marketComparisons[order.typeId to order.regionId]
        val overbidPrice = if (order.isBuyOrder) {
            comp?.bestBuy?.let { it + 0.01 } ?: order.price
        } else {
            comp?.bestSell?.let { it - 0.01 } ?: order.price
        }
        scope.launch(Dispatchers.IO) {
            try { charId?.let { EsiClient.openMarketWindow(it, order.typeId) } } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                val text = String.format(Locale.US, "%.2f", overbidPrice)
                val sel = StringSelection(text)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            }
        }
    }

    fun recalculateFifo(charId: Int) {
        scope.launch(Dispatchers.IO) {
            val taxConfig = CostBasisService.TaxConfig(
                salesTaxPct  = StaticDataDao.getCharSalesTax(charId),
                brokerFeePct = StaticDataDao.getCharBrokersFee(charId),
            )
            val fifo = CostBasisService.compute(charId, taxConfig)
            withContext(Dispatchers.Main) { fifoResult = fifo }
        }
    }

    LaunchedEffect(charId) {
        charId?.let { id ->
            val stored = withContext(Dispatchers.IO) { OrderHistoryDao.getAll(id) }
            historyOrders = stored
            val taxConfig = withContext(Dispatchers.IO) {
                CostBasisService.TaxConfig(
                    salesTaxPct  = StaticDataDao.getCharSalesTax(id),
                    brokerFeePct = StaticDataDao.getCharBrokersFee(id),
                )
            }
            val fifo = withContext(Dispatchers.IO) { CostBasisService.compute(id, taxConfig) }
            fifoResult = fifo
            loadOrders(id)
        } ?: PendingOrdersQueue.clear()
    }

    // Keep the global hotkey queue in sync with current orders + market comparison data.
    // Beaten orders sort first in the queue so the most urgent ones cycle first.
    LaunchedEffect(charId, orders, marketComparisons) {
        val cid = charId ?: return@LaunchedEffect
        val pending = orders
            .filter { it.state == "active" }
            .map { order ->
                val comp = marketComparisons[order.typeId to order.regionId]
                val isBeaten = if (order.isBuyOrder) {
                    comp?.bestBuy != null && comp.bestBuy > order.price
                } else {
                    comp?.bestSell != null && comp.bestSell < order.price
                }
                PendingOrder(
                    charId             = cid,
                    orderId            = order.orderId,
                    typeId             = order.typeId,
                    typeName           = order.typeName,
                    isBuyOrder         = order.isBuyOrder,
                    regionId           = order.regionId,
                    ownPrice           = order.price,
                    bestCompetingPrice = if (order.isBuyOrder) comp?.bestBuy else comp?.bestSell,
                    isBeaten           = isBeaten,
                )
            }
        PendingOrdersQueue.update(pending)
    }

    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                    val sel = selectedOrderId
                    if (sel != null) {
                        orders.find { it.orderId == sel }?.let { triggerOrderAction(it) }
                        true
                    } else false
                } else false
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Orders", style = MaterialTheme.typography.headlineMedium)
                if (isLoadingMarket) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    Text("loading market…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Global hotkey queue indicator
                val queueSize = orders.count { it.state == "active" }
                val beatenCount = orders.count { o ->
                    val comp = marketComparisons[o.typeId to o.regionId]
                    if (o.isBuyOrder) comp?.bestBuy?.let { it > o.price } ?: false
                    else comp?.bestSell?.let { it < o.price } ?: false
                }
                if (queueSize > 0 && charId != null) {
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "Ctrl+Z",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "$queueSize orders",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (beatenCount > 0) {
                                Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "$beatenCount beaten",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = UNDERCUT_COLOR,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
            charId?.let { id ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = { recalculateFifo(id) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Autorenew,
                            contentDescription = "Recalculate P&L from wallet",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    EsiRefreshButton(
                        isLoading = isLoading,
                        expiresAtMs = refreshAvailableAt,
                        onClick = { loadOrders(id) },
                    )
                }
            }
        }

        val sellOrders = orders.filter { !it.isBuyOrder }
        val buyOrders  = orders.filter {  it.isBuyOrder }
        val inventory  = fifoResult?.inventory ?: emptyMap()

        // Fallback cost map from completed buy orders in history.
        // Used when wallet transactions haven't been synced yet (ESI cooldown).
        // Weighted average order price × buyMultiplier to match the FIFO cost convention.
        val tax = fifoResult?.taxConfig ?: CostBasisService.TaxConfig()
        val historyCostBasis: Map<Int, Double> = remember(historyOrders, tax) {
            historyOrders
                .filter { it.isBuyOrder && (it.volumeTotal - it.volumeRemaining) > 0 }
                .groupBy { it.typeId }
                .mapValues { (_, orders) ->
                    val totalFilled = orders.sumOf { it.volumeTotal - it.volumeRemaining }.toDouble()
                    val weightedAvg = orders.sumOf { (it.volumeTotal - it.volumeRemaining) * it.price } / totalFilled
                    weightedAvg * tax.buyMultiplier
                }
        }

        TabRow(selectedTabIndex = activeTab, modifier = Modifier.fillMaxWidth()) {
            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                Text("Sell (${sellOrders.size})", modifier = Modifier.padding(8.dp))
            }
            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                Text("Buy (${buyOrders.size})", modifier = Modifier.padding(8.dp))
            }
            Tab(selected = activeTab == 2, onClick = { activeTab = 2 }) {
                Text("History (${historyOrders.size})", modifier = Modifier.padding(8.dp))
            }
            Tab(selected = activeTab == 3, onClick = { activeTab = 3 }) {
                Text("Inventory (${inventory.size})", modifier = Modifier.padding(8.dp))
            }
        }

        fun onSort(col: SortCol) {
            if (sortCol == col) sortDir = if (sortDir == SortDir.ASC) SortDir.DESC else SortDir.ASC
            else { sortCol = col; sortDir = SortDir.ASC }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                2 -> {
                    if (historyOrders.isEmpty() && !isLoading) {
                        EmptyState(
                            icon = Icons.Default.History,
                            title = "No Order History",
                            description = if (charId == null) "Add a character to view order history." else "No completed orders found.",
                        )
                    } else {
                        OrderHistoryTable(historyOrders, fifoResult)
                    }
                }
                3 -> {
                    if (inventory.isEmpty() && !isLoading) {
                        EmptyState(
                            icon = Icons.Default.Inventory2,
                            title = "No Inventory",
                            description = if (charId == null) "Add a character to view inventory." else "No items in FIFO inventory.",
                        )
                    } else {
                        InventoryTable(inventory, sellOrders, fifoResult)
                    }
                }
                else -> {
                    val filtered = if (activeTab == 0) sellOrders else buyOrders
                    val sorted   = applySort(filtered, sortCol, sortDir)
                    if (sorted.isEmpty() && !isLoading) {
                        EmptyState(
                            icon = Icons.Default.Receipt,
                            title = if (activeTab == 0) "No Sell Orders" else "No Buy Orders",
                            description = if (charId == null) "Add a character to view orders." else "No active orders.",
                        )
                    } else if (activeTab == 0) {
                        SellOrdersTable(
                            orders = sorted,
                            sortCol = sortCol,
                            sortDir = sortDir,
                            onSort = ::onSort,
                            inventory = inventory,
                            taxConfig = tax,
                            historyCostBasis = historyCostBasis,
                            comparisons = marketComparisons,
                            selectedOrderId = selectedOrderId,
                            activeOrderId = activeOrderId,
                            onSelect = { id -> selectedOrderId = if (selectedOrderId == id) null else id },
                            onAction = { order -> triggerOrderAction(order) },
                        )
                    } else {
                        BuyOrdersTable(
                            orders = sorted,
                            sortCol = sortCol,
                            sortDir = sortDir,
                            onSort = ::onSort,
                            comparisons = marketComparisons,
                            selectedOrderId = selectedOrderId,
                            activeOrderId = activeOrderId,
                            onSelect = { id -> selectedOrderId = if (selectedOrderId == id) null else id },
                            onAction = { order -> triggerOrderAction(order) },
                        )
                    }
                }
            }
        }

        when (activeTab) {
            3 -> fifoResult?.let { InventorySummaryBar(it, inventory) }
            2 -> if (historyOrders.isNotEmpty()) HistorySummaryBar(historyOrders, fifoResult)
            else -> {
                val filtered = if (activeTab == 0) sellOrders else buyOrders
                if (filtered.isNotEmpty()) {
                    val tax = fifoResult?.taxConfig ?: CostBasisService.TaxConfig()
                    OrdersSummaryBar(filtered, if (activeTab == 0) inventory else null, tax)
                }
            }
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Loading orders…")
}

// ── Sorting ───────────────────────────────────────────────────────────────

private fun applySort(list: List<CharacterOrder>, col: SortCol, dir: SortDir): List<CharacterOrder> {
    val sorted = when (col) {
        SortCol.NAME      -> list.sortedBy { it.typeName }
        SortCol.GROUP     -> list.sortedBy { it.groupName }
        SortCol.PRICE     -> list.sortedBy { it.price }
        SortCol.VOLUME    -> list.sortedBy { it.volumeRemaining }
        SortCol.TOTAL     -> list.sortedBy { it.total }
        SortCol.TIME_LEFT -> list.sortedBy { it.timeLeftSeconds }
        SortCol.ORDER_AGE -> list.sortedBy { it.orderAgeSeconds }
        SortCol.STATION   -> list.sortedBy { it.stationName }
    }
    return if (dir == SortDir.DESC) sorted.reversed() else sorted
}

// ── Tables ────────────────────────────────────────────────────────────────

@Composable
private fun SellOrdersTable(
    orders: List<CharacterOrder>,
    sortCol: SortCol,
    sortDir: SortDir,
    onSort: (SortCol) -> Unit,
    inventory: Map<Int, CostBasisService.InventoryItem>,
    taxConfig: CostBasisService.TaxConfig,
    historyCostBasis: Map<Int, Double>,
    comparisons: Map<Pair<Int, Int>, MarketComparison>,
    selectedOrderId: Long?,
    activeOrderId: Long?,
    onSelect: (Long) -> Unit,
    onAction: (CharacterOrder) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortHeader("Name",      SortCol.NAME,      sortCol, sortDir, onSort, Modifier.weight(3f))
            SortHeader("Group",     SortCol.GROUP,     sortCol, sortDir, onSort, Modifier.weight(1.5f))
            StaticHeader("Price / Best", Modifier.weight(2.4f))
            StaticHeader("Cost",    Modifier.weight(1.8f))
            StaticHeader("Profit",  Modifier.weight(1.8f))
            StaticHeader("Margin",  Modifier.weight(1.2f))
            SortHeader("Volume",    SortCol.VOLUME,    sortCol, sortDir, onSort, Modifier.weight(2.5f))
            SortHeader("Time Left", SortCol.TIME_LEFT, sortCol, sortDir, onSort, Modifier.weight(1.5f))
            SortHeader("Station",   SortCol.STATION,   sortCol, sortDir, onSort, Modifier.weight(2.5f))
            StaticHeader("",        Modifier.width(36.dp))
        }
        HorizontalDivider()
        LazyColumn {
            items(orders, key = { it.orderId }) { order ->
                val comp = comparisons[order.typeId to order.regionId]
                SellOrderRow(
                    order = order,
                    inv = inventory[order.typeId],
                    taxConfig = taxConfig,
                    fallbackCost = historyCostBasis[order.typeId],
                    comparison = comp,
                    isSelected = selectedOrderId == order.orderId,
                    isActiveInGame = activeOrderId == order.orderId,
                    onSelect = { onSelect(order.orderId) },
                    onAction = { onAction(order) },
                )
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun BuyOrdersTable(
    orders: List<CharacterOrder>,
    sortCol: SortCol,
    sortDir: SortDir,
    onSort: (SortCol) -> Unit,
    comparisons: Map<Pair<Int, Int>, MarketComparison>,
    selectedOrderId: Long?,
    activeOrderId: Long?,
    onSelect: (Long) -> Unit,
    onAction: (CharacterOrder) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortHeader("Name",      SortCol.NAME,      sortCol, sortDir, onSort, Modifier.weight(3f))
            SortHeader("Group",     SortCol.GROUP,     sortCol, sortDir, onSort, Modifier.weight(2f))
            StaticHeader("Price / Best", Modifier.weight(2.4f))
            SortHeader("Volume",    SortCol.VOLUME,    sortCol, sortDir, onSort, Modifier.weight(2.5f))
            SortHeader("Total",     SortCol.TOTAL,     sortCol, sortDir, onSort, Modifier.weight(2f))
            StaticHeader("Range",   Modifier.weight(1.5f))
            StaticHeader("Min Qty", Modifier.weight(1f))
            SortHeader("Time Left", SortCol.TIME_LEFT, sortCol, sortDir, onSort, Modifier.weight(1.5f))
            SortHeader("Order Age", SortCol.ORDER_AGE, sortCol, sortDir, onSort, Modifier.weight(1.5f))
            SortHeader("Station",   SortCol.STATION,   sortCol, sortDir, onSort, Modifier.weight(3f))
            StaticHeader("",        Modifier.width(36.dp))
        }
        HorizontalDivider()
        LazyColumn {
            items(orders, key = { it.orderId }) { order ->
                val comp = comparisons[order.typeId to order.regionId]
                BuyOrderRow(
                    order = order,
                    comparison = comp,
                    isSelected = selectedOrderId == order.orderId,
                    isActiveInGame = activeOrderId == order.orderId,
                    onSelect = { onSelect(order.orderId) },
                    onAction = { onAction(order) },
                )
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun OrderHistoryTable(
    orders: List<OrderHistoryDao.OrderHistoryRecord>,
    fifoResult: CostBasisService.FifoResult?,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaticHeader("Name",    Modifier.weight(3f))
            StaticHeader("Type",    Modifier.weight(1f))
            StaticHeader("State",   Modifier.weight(1.5f))
            StaticHeader("Price",   Modifier.weight(2f))
            StaticHeader("Profit",  Modifier.weight(2f))
            StaticHeader("Margin",  Modifier.weight(1.2f))
            StaticHeader("Volume",  Modifier.weight(2f))
            StaticHeader("Issued",  Modifier.weight(2f))
            StaticHeader("Station", Modifier.weight(2.5f))
        }
        HorizontalDivider()
        LazyColumn {
            items(orders, key = { it.orderId }) { order ->
                val (pnl, margin) = historyPnl(order, fifoResult)
                OrderHistoryRow(order, pnl, margin)
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun InventoryTable(
    inventory: Map<Int, CostBasisService.InventoryItem>,
    sellOrders: List<CharacterOrder>,
    fifoResult: CostBasisService.FifoResult?,
) {
    val sellByType = sellOrders.filter { !it.isBuyOrder && it.state == "active" }.groupBy { it.typeId }
    val realizedByType = fifoResult?.realizedByType ?: emptyMap()
    val items = inventory.values.sortedBy { it.typeName }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaticHeader("Name",         Modifier.weight(3f))
            StaticHeader("Qty",          Modifier.weight(1.5f))
            StaticHeader("Avg Cost",     Modifier.weight(2f))
            StaticHeader("Total Cost",   Modifier.weight(2f))
            StaticHeader("Sell Price",   Modifier.weight(2f))
            StaticHeader("Profit/unit",  Modifier.weight(2f))
            StaticHeader("Margin",       Modifier.weight(1.2f))
            StaticHeader("Realized P&L", Modifier.weight(2f))
        }
        HorizontalDivider()
        val tax = fifoResult?.taxConfig ?: CostBasisService.TaxConfig()
        LazyColumn {
            items(items, key = { it.typeId }) { item ->
                val activeOrder = sellByType[item.typeId]?.maxByOrNull { it.price }
                val realized    = realizedByType[item.typeId]?.sumOf { it.profit }
                InventoryRow(item, activeOrder, realized, tax)
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

// ── Rows ──────────────────────────────────────────────────────────────────

@Composable
private fun SellOrderRow(
    order: CharacterOrder,
    inv: CostBasisService.InventoryItem?,
    taxConfig: CostBasisService.TaxConfig,
    fallbackCost: Double?,
    comparison: MarketComparison?,
    isSelected: Boolean,
    isActiveInGame: Boolean,
    onSelect: () -> Unit,
    onAction: () -> Unit,
) {
    val costBasis     = inv?.avgCostBasis ?: fallbackCost
    val isEstimated   = inv == null && costBasis != null
    val netSellPrice  = order.price * taxConfig.sellMultiplier
    val profitPerUnit = costBasis?.let { netSellPrice - it }
    val marginPct     = costBasis?.let { if (it > 0) (netSellPrice - it) / it * 100 else null }
    val profitColor   = profitPerUnit?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant

    // Undercut: another sell order is cheaper than ours
    val isUndercut    = comparison?.bestSell != null && comparison.bestSell < order.price
    val rowBg         = when {
        isActiveInGame -> ACTIVE_IN_GAME.copy(alpha = 0.15f)
        isSelected     -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else           -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable { onSelect() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name + status dot
        Row(modifier = Modifier.weight(3f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusDot(order.state)
            if (isUndercut) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Undercut", modifier = Modifier.size(11.dp), tint = UNDERCUT_COLOR)
            }
            Text(order.typeName, style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis, maxLines = 1)
        }
        Text(order.groupName, modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis, maxLines = 1)

        // Price column: order price + competing price below if undercut
        Column(modifier = Modifier.weight(2.4f)) {
            Text(formatIsk(order.price), style = MaterialTheme.typography.bodyMedium, color = if (isUndercut) UNDERCUT_COLOR else SELL_COLOR)
            if (isUndercut && comparison?.bestSell != null) {
                Text(
                    "Best: ${formatIsk(comparison.bestSell)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = UNDERCUT_COLOR.copy(alpha = 0.8f),
                )
            }
        }

        Text(
            costBasis?.let { if (isEstimated) "~${formatIsk(it)}" else formatIsk(it) } ?: "—",
            modifier = Modifier.weight(1.8f),
            style = MaterialTheme.typography.bodySmall,
            color = if (isEstimated) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            profitPerUnit?.let { (if (isEstimated) "~" else "") + formatIsk(it) } ?: "—",
            modifier = Modifier.weight(1.8f),
            style = MaterialTheme.typography.bodySmall,
            color = if (isEstimated) profitColor.copy(alpha = 0.7f) else profitColor,
            fontWeight = if (profitPerUnit != null) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            marginPct?.let { (if (isEstimated) "~" else "") + "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall,
            color = if (isEstimated) profitColor.copy(alpha = 0.7f) else profitColor,
        )
        VolumeBar(order.volumeRemaining, order.volumeTotal, isSell = true, modifier = Modifier.weight(2.5f).padding(horizontal = 4.dp))
        Text(formatDuration(order.timeLeftSeconds), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall, color = timeLeftColor(order.timeLeftSeconds))
        Text(order.stationName, modifier = Modifier.weight(2.5f).padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis, maxLines = 1)

        // Action button: open market in-game + copy overbid price
        IconButton(modifier = Modifier.size(36.dp), onClick = onAction) {
            Icon(
                Icons.Default.OpenInBrowser,
                contentDescription = "Open in game & copy price",
                modifier = Modifier.size(16.dp),
                tint = when {
                    isActiveInGame -> ACTIVE_IN_GAME
                    isUndercut     -> UNDERCUT_COLOR
                    else           -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun BuyOrderRow(
    order: CharacterOrder,
    comparison: MarketComparison?,
    isSelected: Boolean,
    isActiveInGame: Boolean,
    onSelect: () -> Unit,
    onAction: () -> Unit,
) {
    // Overbid: another buy order pays more than ours
    val isOverbid = comparison?.bestBuy != null && comparison.bestBuy > order.price
    val rowBg     = when {
        isActiveInGame -> ACTIVE_IN_GAME.copy(alpha = 0.15f)
        isSelected     -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else           -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable { onSelect() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(3f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusDot(order.state)
            if (isOverbid) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Overbid", modifier = Modifier.size(11.dp), tint = UNDERCUT_COLOR)
            }
            Text(order.typeName, style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis, maxLines = 1)
        }
        Text(order.groupName, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis, maxLines = 1)

        // Price column: order price + competing price below if overbid
        Column(modifier = Modifier.weight(2.4f)) {
            Text(formatIsk(order.price), style = MaterialTheme.typography.bodyMedium, color = if (isOverbid) UNDERCUT_COLOR else BUY_COLOR)
            if (isOverbid && comparison?.bestBuy != null) {
                Text(
                    "Best: ${formatIsk(comparison.bestBuy)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = UNDERCUT_COLOR.copy(alpha = 0.8f),
                )
            }
        }

        VolumeBar(order.volumeRemaining, order.volumeTotal, isSell = false, modifier = Modifier.weight(2.5f).padding(horizontal = 4.dp))
        Text(formatIsk(order.total), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
        Text(formatRange(order.range), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
        Text(formatNumber(order.minVolume), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(formatDuration(order.timeLeftSeconds), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall, color = timeLeftColor(order.timeLeftSeconds))
        Text(formatDuration(order.orderAgeSeconds), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(order.stationName, modifier = Modifier.weight(3f).padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis, maxLines = 1)

        IconButton(modifier = Modifier.size(36.dp), onClick = onAction) {
            Icon(
                Icons.Default.OpenInBrowser,
                contentDescription = "Open in game & copy price",
                modifier = Modifier.size(16.dp),
                tint = when {
                    isActiveInGame -> ACTIVE_IN_GAME
                    isOverbid      -> UNDERCUT_COLOR
                    else           -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun OrderHistoryRow(
    order: OrderHistoryDao.OrderHistoryRecord,
    pnl: Double?,
    marginPct: Double?,
) {
    val stateColor  = when (order.state) {
        "fulfilled" -> Color(0xFF69DB7C)
        "cancelled" -> Color(0xFFFF6B6B)
        else        -> Color(0xFFFFD43B)
    }
    val profitColor = pnl?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(order.typeName, modifier = Modifier.weight(3f), style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis, maxLines = 1)
        Text(
            if (order.isBuyOrder) "Buy" else "Sell",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (order.isBuyOrder) BUY_COLOR else SELL_COLOR,
        )
        Text(
            order.state.replaceFirstChar { it.uppercase() },
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.bodySmall,
            color = stateColor,
        )
        Text(formatIsk(order.price), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
        Text(
            pnl?.let { formatIsk(it) } ?: "—",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = profitColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (pnl != null) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            marginPct?.let { "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall,
            color = profitColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${formatNumber(order.volumeRemaining)}/${formatNumber(order.volumeTotal)}",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            order.issued.take(16).replace("T", " "),
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(order.stationName, modifier = Modifier.weight(2.5f).padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis, maxLines = 1)
    }
}

@Composable
private fun InventoryRow(
    item: CostBasisService.InventoryItem,
    activeOrder: CharacterOrder?,
    realizedPnl: Double?,
    taxConfig: CostBasisService.TaxConfig,
) {
    val netSellPrice  = activeOrder?.let { it.price * taxConfig.sellMultiplier }
    val profitPerUnit = netSellPrice?.let { it - item.avgCostBasis }
    val marginPct     = profitPerUnit?.let { if (item.avgCostBasis > 0) it / item.avgCostBasis * 100 else null }
    val profitColor   = profitPerUnit?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val realizedColor = realizedPnl?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.typeName, modifier = Modifier.weight(3f), style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis, maxLines = 1)
        Text(formatNumber(item.remainingQty), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodyMedium)
        Text(formatIsk(item.avgCostBasis), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatIsk(item.totalCostBasis), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
        Text(
            activeOrder?.let { formatIsk(it.price) } ?: "—",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = if (activeOrder != null) SELL_COLOR else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            profitPerUnit?.let { formatIsk(it) } ?: "—",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = profitColor,
            fontWeight = if (profitPerUnit != null) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            marginPct?.let { "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall,
            color = profitColor,
        )
        Text(
            realizedPnl?.let { formatIsk(it) } ?: "—",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = realizedColor,
            fontWeight = if (realizedPnl != null) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ── Sub-components ────────────────────────────────────────────────────────

@Composable
private fun VolumeBar(remaining: Int, total: Int, isSell: Boolean, modifier: Modifier) {
    val fraction = if (total > 0) remaining.toFloat() / total.toFloat() else 0f
    val barColor = if (isSell) VOL_SELL else VOL_BUY

    Box(
        modifier = modifier
            .height(18.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(barColor)
                .align(Alignment.CenterStart),
        )
        Text(
            "${formatNumber(remaining)}/${formatNumber(total)}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatusDot(state: String) {
    val color = when (state) {
        "active"    -> Color(0xFF69DB7C)
        "cancelled" -> Color(0xFFFF6B6B)
        "expired"   -> Color(0xFFFF6B6B)
        "pending"   -> Color(0xFF74C0FC)
        else        -> Color.Gray
    }
    Box(modifier = Modifier.size(7.dp).background(color, shape = MaterialTheme.shapes.small))
}

@Composable
private fun SortHeader(
    label: String, col: SortCol, currentCol: SortCol, dir: SortDir,
    onSort: (SortCol) -> Unit, modifier: Modifier, rightAlign: Boolean = false,
) {
    val isActive   = currentCol == col
    val labelColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.clickable { onSort(col) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (rightAlign) Arrangement.End else Arrangement.Start,
    ) {
        if (isActive && rightAlign) {
            Icon(if (dir == SortDir.ASC) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, Modifier.size(12.dp), tint = labelColor)
            Spacer(Modifier.width(2.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal, color = labelColor)
        if (isActive && !rightAlign) {
            Spacer(Modifier.width(2.dp))
            Icon(if (dir == SortDir.ASC) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, Modifier.size(12.dp), tint = labelColor)
        }
    }
}

@Composable
private fun StaticHeader(label: String, modifier: Modifier, rightAlign: Boolean = false) {
    Text(label, modifier = modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (rightAlign) TextAlign.End else TextAlign.Start)
}

// ── Summary bars ──────────────────────────────────────────────────────────

@Composable
private fun OrdersSummaryBar(
    orders: List<CharacterOrder>,
    inventory: Map<Int, CostBasisService.InventoryItem>?,
    taxConfig: CostBasisService.TaxConfig = CostBasisService.TaxConfig(),
) {
    val active        = orders.filter { it.state == "active" }
    val totalRemain   = active.sumOf { it.volumeRemaining }
    val totalEntered  = active.sumOf { it.volumeTotal }
    val pct           = if (totalEntered > 0) totalRemain * 100.0 / totalEntered else 0.0
    val totalIsk      = active.sumOf { it.total }
    val totalProfit   = inventory?.let {
        active.sumOf { o ->
            val cb = it[o.typeId]?.avgCostBasis ?: return@sumOf 0.0
            (o.price * taxConfig.sellMultiplier - cb) * o.volumeRemaining
        }.takeIf { it != 0.0 }
    }

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryItem("Active orders", active.size.toString())
            SummaryItem("Volume", "${formatNumber(totalRemain)}/${formatNumber(totalEntered)} (${String.format("%.1f", pct)}%)")
            SummaryItem("Total ISK", "${formatIsk(totalIsk)} ISK")
            if (totalProfit != null) {
                val color = if (totalProfit >= 0) PROFIT_COLOR else LOSS_COLOR
                SummaryItem("Expected profit", "${formatIsk(totalProfit)} ISK", color)
            }
        }
    }
}

@Composable
private fun HistorySummaryBar(orders: List<OrderHistoryDao.OrderHistoryRecord>, fifoResult: CostBasisService.FifoResult?) {
    val fulfilled  = orders.count { it.state == "fulfilled" }
    val cancelled  = orders.count { it.state == "cancelled" }
    val expired    = orders.count { it.state == "expired" }
    val totalSold  = orders.filter { !it.isBuyOrder && it.state == "fulfilled" }
        .sumOf { it.price * (it.volumeTotal - it.volumeRemaining) }
    val totalPnl   = fifoResult?.totalRealizedPnl

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryItem("Total", orders.size.toString())
            SummaryItem("Fulfilled", fulfilled.toString())
            SummaryItem("Cancelled", cancelled.toString())
            SummaryItem("Expired", expired.toString())
            if (totalSold > 0) SummaryItem("Sell volume", "${formatIsk(totalSold)} ISK")
            if (totalPnl != null) {
                val color = if (totalPnl >= 0) PROFIT_COLOR else LOSS_COLOR
                SummaryItem("Total realized P&L", "${formatIsk(totalPnl)} ISK", color)
            }
        }
    }
}

@Composable
private fun InventorySummaryBar(fifoResult: CostBasisService.FifoResult, inventory: Map<Int, CostBasisService.InventoryItem>) {
    val totalCost  = inventory.values.sumOf { it.totalCostBasis }
    val totalPnl   = fifoResult.totalRealizedPnl
    val pnlColor   = if (totalPnl >= 0) PROFIT_COLOR else LOSS_COLOR

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryItem("Items", inventory.size.toString())
            SummaryItem("Inventory cost", "${formatIsk(totalCost)} ISK")
            SummaryItem("All-time realized P&L", "${formatIsk(totalPnl)} ISK", pnlColor)
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label + ":", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

// ── History P&L helper ────────────────────────────────────────────────────

private fun historyPnl(
    order: OrderHistoryDao.OrderHistoryRecord,
    fifoResult: CostBasisService.FifoResult?,
): Pair<Double?, Double?> {
    if (order.isBuyOrder || fifoResult == null) return null to null
    val filled = order.volumeTotal - order.volumeRemaining
    if (filled <= 0) return null to null  // nothing actually sold (cancelled/expired with no fills)

    val taxConfig    = fifoResult.taxConfig
    val netSellPrice = order.price * taxConfig.sellMultiplier

    val fifoProfit = CostBasisService.pnlForOrder(fifoResult, order.typeId, order.issued, filled)
    if (fifoProfit != null) {
        val cb     = netSellPrice - fifoProfit / filled
        val margin = if (cb > 0) (netSellPrice - cb) / cb * 100 else 0.0
        return fifoProfit to margin
    }

    val cb = fifoResult.avgCostBasisForType(order.typeId) ?: return null to null
    val profit = (netSellPrice - cb) * filled
    val margin = if (cb > 0) (netSellPrice - cb) / cb * 100 else 0.0
    return profit to margin
}

// ── Formatters ────────────────────────────────────────────────────────────

private fun formatRange(range: String): String = when (range) {
    "station"     -> "Station"
    "solarsystem" -> "System"
    "region"      -> "Region"
    else          -> range.toIntOrNull()?.let { "$it jumps" } ?: range
}

private fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "—"
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else  -> "${m}m"
    }
}

private fun timeLeftColor(seconds: Long): Color = when {
    seconds <= 0    -> Color(0xFFFF6B6B)
    seconds < 86400 -> Color(0xFFFFD43B)
    else            -> Color.Unspecified
}

private fun formatNumber(value: Int): String = "%,d".format(value)

private fun formatIsk(value: Double): String = when {
    kotlin.math.abs(value) >= 1_000_000_000_000 -> "%.2fT".format(value / 1_000_000_000_000)
    kotlin.math.abs(value) >= 1_000_000_000     -> "%.2fB".format(value / 1_000_000_000)
    kotlin.math.abs(value) >= 1_000_000         -> "%.2fM".format(value / 1_000_000)
    kotlin.math.abs(value) >= 1_000             -> "%.2fK".format(value / 1_000)
    else                                         -> "%,.2f".format(value)
}
