package org.eve.trader.features.market

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.eve.trader.core.database.MarketDao
import org.eve.trader.core.database.StaticDataDao
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.core.model.StaticMarketGroupModel
import org.eve.trader.core.model.StaticRegionModel

// ─── Data models ──────────────────────────────────────────────────────────

data class StationOpportunity(
    val typeId: Int,
    val typeName: String,
    val bestSell: Double,
    val bestBuy: Double,
    val grossProfit: Double,
    val netProfit: Double,
    val marginPct: Double,
    val dailyVolume: Long,
    val sellOrderCount: Int,
    val buyOrderCount: Int,
    val estimatedDailyProfit: Double,
)

data class RegionOpportunity(
    val typeId: Int,
    val typeName: String,
    val buyRegionName: String,
    val sellRegionName: String,
    val buyPrice: Double,
    val sellPrice: Double,
    val grossProfit: Double,
    val netProfit: Double,
    val marginPct: Double,
    val itemVolumeM3: Double,
    val shippingCostPerUnit: Double,
    val dailyVolume: Long,
)

// ─── Sort / trade-type enums ───────────────────────────────────────────────

private enum class StationSortCol { NAME, BUY_PRICE, SELL_PRICE, MARGIN, NET_PROFIT, VOLUME, DAILY_PROFIT }
private enum class RegionSortCol  { NAME, BUY_PRICE, SELL_PRICE, MARGIN, ITEM_VOL, SHIPPING, NET_PROFIT, VOLUME }

private enum class InterRegionTradeType(val label: String) {
    SELL_TO_BUY ("Sell → Buy (instant)"),
    SELL_TO_SELL("Sell → Sell Order"),
    BUY_TO_BUY  ("Buy Order → Buy"),
    BUY_TO_SELL ("Buy → Sell (orders)"),
}

private fun sortStation(list: List<StationOpportunity>, col: StationSortCol, asc: Boolean): List<StationOpportunity> {
    val cmp: Comparator<StationOpportunity> = when (col) {
        StationSortCol.NAME         -> compareBy { it.typeName }
        StationSortCol.BUY_PRICE    -> compareBy { it.bestBuy }
        StationSortCol.SELL_PRICE   -> compareBy { it.bestSell }
        StationSortCol.MARGIN       -> compareBy { it.marginPct }
        StationSortCol.NET_PROFIT   -> compareBy { it.netProfit }
        StationSortCol.VOLUME       -> compareBy { it.dailyVolume }
        StationSortCol.DAILY_PROFIT -> compareBy { it.estimatedDailyProfit }
    }
    return if (asc) list.sortedWith(cmp) else list.sortedWith(cmp.reversed())
}

private fun sortRegion(list: List<RegionOpportunity>, col: RegionSortCol, asc: Boolean): List<RegionOpportunity> {
    val cmp: Comparator<RegionOpportunity> = when (col) {
        RegionSortCol.NAME       -> compareBy { it.typeName }
        RegionSortCol.BUY_PRICE  -> compareBy { it.buyPrice }
        RegionSortCol.SELL_PRICE -> compareBy { it.sellPrice }
        RegionSortCol.MARGIN     -> compareBy { it.marginPct }
        RegionSortCol.ITEM_VOL   -> compareBy { it.itemVolumeM3 }
        RegionSortCol.SHIPPING   -> compareBy { it.shippingCostPerUnit }
        RegionSortCol.NET_PROFIT -> compareBy { it.netProfit }
        RegionSortCol.VOLUME     -> compareBy { it.dailyVolume }
    }
    return if (asc) list.sortedWith(cmp) else list.sortedWith(cmp.reversed())
}

// ─── Settings helpers ─────────────────────────────────────────────────────

private object S {
    // Station keys
    const val ST_REGION      = "analysis.s.region"
    const val ST_CAT_TOP     = "analysis.s.catTop"
    const val ST_CAT_SUB     = "analysis.s.catSub"
    const val ST_MARGIN      = "analysis.s.margin"
    const val ST_BROKER      = "analysis.s.broker"
    const val ST_TAX         = "analysis.s.tax"
    const val ST_MIN_VOL     = "analysis.s.minVol"
    const val ST_MAX_PRICE   = "analysis.s.maxPrice"
    const val ST_MIN_PROFIT  = "analysis.s.minProfit"
    // Inter-region keys
    const val IR_BUY_REGION  = "analysis.r.buyRegion"
    const val IR_SELL_REGION = "analysis.r.sellRegion"
    const val IR_TRADE_TYPE  = "analysis.r.tradeType"
    const val IR_CAT_TOP     = "analysis.r.catTop"
    const val IR_CAT_SUB     = "analysis.r.catSub"
    const val IR_MARGIN      = "analysis.r.margin"
    const val IR_BROKER      = "analysis.r.broker"
    const val IR_TAX         = "analysis.r.tax"
    const val IR_ISK_PER_M3  = "analysis.r.iskPerM3"
    const val IR_MAX_CARGO   = "analysis.r.maxCargo"
    const val IR_MIN_PROFIT  = "analysis.r.minProfit"

    fun get(key: String): String? = StaticDataDao.getSetting(key)
    fun set(key: String, value: String) = StaticDataDao.setSetting(key, value)
}

// ─── Main screen ──────────────────────────────────────────────────────────

@Composable
fun MarketAnalysisScreen() {
    var selectedTab by remember { mutableStateOf(0) }

    val allRegions by produceState(initialValue = emptyList<StaticRegionModel>()) {
        value = withContext(Dispatchers.IO) { StaticDataDao.getAllRegions() }
    }
    val topGroups by produceState(initialValue = emptyList<StaticMarketGroupModel>()) {
        value = withContext(Dispatchers.IO) { StaticDataDao.getTopMarketGroups() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                text = { Text("Station Trading") },
                icon = { Icon(Icons.Default.Store, null, Modifier.size(16.dp)) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                text = { Text("Inter-Region") },
                icon = { Icon(Icons.AutoMirrored.Filled.CompareArrows, null, Modifier.size(16.dp)) })
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = if (selectedTab == 0) Modifier.fillMaxSize() else Modifier.requiredSize(0.dp).clipToBounds()) {
                StationTradingTab(allRegions, topGroups)
            }
            Box(modifier = if (selectedTab == 1) Modifier.fillMaxSize() else Modifier.requiredSize(0.dp).clipToBounds()) {
                InterRegionTab(allRegions, topGroups)
            }
        }
    }
}

// ─── Station Trading ──────────────────────────────────────────────────────

@Composable
private fun StationTradingTab(
    allRegions: List<StaticRegionModel>,
    topGroups: List<StaticMarketGroupModel>,
) {
    val scope = rememberCoroutineScope()

    var regionId         by remember { mutableStateOf(10000002) }
    var selectedTopGroup by remember { mutableStateOf<StaticMarketGroupModel?>(null) }
    var selectedSubGroup by remember { mutableStateOf<StaticMarketGroupModel?>(null) }
    var subGroups        by remember { mutableStateOf<List<StaticMarketGroupModel>>(emptyList()) }
    var minMargin        by remember { mutableStateOf("5") }
    var brokerFee        by remember { mutableStateOf("3") }
    var salesTax         by remember { mutableStateOf("8") }
    var minDailyVol      by remember { mutableStateOf("10") }
    var maxBuyPrice      by remember { mutableStateOf("500000000") }
    var minNetProfit     by remember { mutableStateOf("100000") }
    var isAnalyzing      by remember { mutableStateOf(false) }
    var statusMsg        by remember { mutableStateOf("") }
    var results          by remember { mutableStateOf<List<StationOpportunity>>(emptyList()) }
    var sortCol          by remember { mutableStateOf(StationSortCol.NET_PROFIT) }
    var sortAsc          by remember { mutableStateOf(false) }

    // Load persisted settings
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            S.get(S.ST_REGION)?.toIntOrNull()?.let     { regionId    = it }
            S.get(S.ST_MARGIN)?.let                    { minMargin   = it }
            S.get(S.ST_BROKER)?.let                    { brokerFee   = it }
            S.get(S.ST_TAX)?.let                       { salesTax    = it }
            S.get(S.ST_MIN_VOL)?.let                   { minDailyVol = it }
            S.get(S.ST_MAX_PRICE)?.let                 { maxBuyPrice = it }
            S.get(S.ST_MIN_PROFIT)?.let                { minNetProfit= it }
        }
    }

    // Restore category selection after groups load
    LaunchedEffect(topGroups) {
        if (topGroups.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val topId = S.get(S.ST_CAT_TOP)?.toIntOrNull() ?: return@withContext
            val top = topGroups.find { it.marketGroupId == topId } ?: return@withContext
            selectedTopGroup = top
            val subs = StaticDataDao.getChildMarketGroups(topId)
            subGroups = subs
            val subId = S.get(S.ST_CAT_SUB)?.toIntOrNull()
            selectedSubGroup = subs.find { it.marketGroupId == subId }
        }
    }

    // Load subgroups when top group changes (user interaction)
    LaunchedEffect(selectedTopGroup) {
        val top = selectedTopGroup ?: run { subGroups = emptyList(); selectedSubGroup = null; return@LaunchedEffect }
        val subs = withContext(Dispatchers.IO) { StaticDataDao.getChildMarketGroups(top.marketGroupId) }
        subGroups = subs
        if (selectedSubGroup?.marketGroupId !in subs.map { it.marketGroupId }) selectedSubGroup = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Filter bar ─────────────────────────────────────────────
        FilterBar {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                RegionPicker(allRegions, regionId, width = 180.dp) {
                    regionId = it
                    scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_REGION, it.toString()) } }
                }
                FilterDivider()
                GroupDropdown("Category", topGroups, selectedTopGroup, "All categories", 150.dp) { g ->
                    selectedTopGroup = g; selectedSubGroup = null
                    scope.launch { withContext(Dispatchers.IO) {
                        S.set(S.ST_CAT_TOP, g?.marketGroupId?.toString() ?: "")
                        S.set(S.ST_CAT_SUB, "")
                    }}
                }
                if (subGroups.isNotEmpty()) {
                    GroupDropdown("Subcategory", subGroups, selectedSubGroup, "All", 140.dp) { g ->
                        selectedSubGroup = g
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_CAT_SUB, g?.marketGroupId?.toString() ?: "") } }
                    }
                }
                FilterDivider()
                ParamField("Margin %",  minMargin,    68.dp)  { minMargin   = it; scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_MARGIN,     it) } } }
                ParamField("Broker %",  brokerFee,    68.dp)  { brokerFee   = it; scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_BROKER,     it) } } }
                ParamField("Tax %",     salesTax,     60.dp)  { salesTax    = it; scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_TAX,        it) } } }
                ParamField("Min Vol",   minDailyVol,  72.dp)  { minDailyVol = it; scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_MIN_VOL,    it) } } }
                ParamField("Max Buy",   maxBuyPrice,  105.dp) { maxBuyPrice  = it; scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_MAX_PRICE,  it) } } }
                ParamField("Min Net",   minNetProfit, 100.dp) { minNetProfit = it; scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_MIN_PROFIT, it) } } }
                FilterDivider()
                // Align button to bottom of the row (matching field bottom)
                Column(verticalArrangement = Arrangement.Bottom) {
                    Spacer(Modifier.height(19.dp)) // matches label height + gap
                    Button(
                        onClick = {
                            scope.launch {
                                isAnalyzing = true; results = emptyList()
                                val regionName = allRegions.find { it.regionId == regionId }?.name ?: "region"
                                try {
                                    statusMsg = "Fetching orders from $regionName…"
                                    val allOrders = withContext(Dispatchers.IO) { EsiClient.getMarketRegionOrders(regionId) }
                                    val filterGroupId = selectedSubGroup?.marketGroupId ?: selectedTopGroup?.marketGroupId
                                    val filterGroupIds = filterGroupId?.let { withContext(Dispatchers.IO) { buildGroupSubtree(it) } }
                                    statusMsg = "Analyzing ${allOrders.size} orders…"
                                    results = withContext(Dispatchers.IO) {
                                        computeStation(
                                            allOrders, regionId,
                                            filterMarketGroupIds = filterGroupIds,
                                            minMarginPct  = minMargin.toDoubleOrNull() ?: 5.0,
                                            minDailyVol   = minDailyVol.toLongOrNull() ?: 0L,
                                            maxBuyPrice   = maxBuyPrice.toDoubleOrNull() ?: Double.MAX_VALUE,
                                            minNetProfit  = minNetProfit.toDoubleOrNull() ?: 0.0,
                                            brokerFeePct  = brokerFee.toDoubleOrNull() ?: 3.0,
                                            salesTaxPct   = salesTax.toDoubleOrNull() ?: 8.0,
                                        )
                                    }
                                    statusMsg = "${results.size} opportunities found"
                                } catch (e: Exception) { statusMsg = "Error: ${e.message}" }
                                isAnalyzing = false
                            }
                        },
                        enabled = !isAnalyzing,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(48.dp),
                    ) {
                        if (isAnalyzing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Search, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isAnalyzing) "Analyzing…" else "Analyze")
                    }
                }
                if (statusMsg.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.Bottom) {
                        Spacer(Modifier.height(19.dp))
                        Text(
                            statusMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color = if ("Error" in statusMsg) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.height(48.dp).wrapContentHeight(Alignment.CenterVertically),
                        )
                    }
                }
            }
        }

        if (isAnalyzing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        // ── Results ────────────────────────────────────────────────
        if (results.isEmpty() && !isAnalyzing) {
            AnalysisEmptyState(
                icon = { Icon(Icons.Default.Store, null, Modifier.size(52.dp), tint = Color.Gray) },
                primary = "Configure filters and click Analyze",
                secondary = "Finds items with profitable spread between buy and sell orders at the same station",
            )
        } else {
            val sorted = remember(results, sortCol, sortAsc) { sortStation(results, sortCol, sortAsc) }
            StationHeader(sortCol, sortAsc) { col ->
                if (sortCol == col) sortAsc = !sortAsc else { sortCol = col; sortAsc = false }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(sorted, key = { _, it -> it.typeId }) { idx, opp -> StationRow(opp, idx) }
            }
        }
    }
}

// ─── Inter-Region ─────────────────────────────────────────────────────────

@Composable
private fun InterRegionTab(
    allRegions: List<StaticRegionModel>,
    topGroups: List<StaticMarketGroupModel>,
) {
    val scope = rememberCoroutineScope()

    var buyRegionId      by remember { mutableStateOf(10000002) }
    var sellRegionId     by remember { mutableStateOf(10000043) }
    var tradeType        by remember { mutableStateOf(InterRegionTradeType.SELL_TO_BUY) }
    var selectedTopGroup by remember { mutableStateOf<StaticMarketGroupModel?>(null) }
    var selectedSubGroup by remember { mutableStateOf<StaticMarketGroupModel?>(null) }
    var subGroups        by remember { mutableStateOf<List<StaticMarketGroupModel>>(emptyList()) }
    var minMargin        by remember { mutableStateOf("5") }
    var brokerFee        by remember { mutableStateOf("3") }
    var salesTax         by remember { mutableStateOf("8") }
    var iskPerM3         by remember { mutableStateOf("1000") }
    var maxCargoM3       by remember { mutableStateOf("10000") }
    var minNetProfit     by remember { mutableStateOf("5000000") }
    var isAnalyzing      by remember { mutableStateOf(false) }
    var statusMsg        by remember { mutableStateOf("") }
    var results          by remember { mutableStateOf<List<RegionOpportunity>>(emptyList()) }
    var sortCol          by remember { mutableStateOf(RegionSortCol.NET_PROFIT) }
    var sortAsc          by remember { mutableStateOf(false) }

    // Load persisted settings
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            S.get(S.IR_BUY_REGION)?.toIntOrNull()?.let  { buyRegionId  = it }
            S.get(S.IR_SELL_REGION)?.toIntOrNull()?.let { sellRegionId = it }
            S.get(S.IR_TRADE_TYPE)?.let { name ->
                InterRegionTradeType.entries.find { it.name == name }?.let { tradeType = it }
            }
            S.get(S.IR_MARGIN)?.let     { minMargin    = it }
            S.get(S.IR_BROKER)?.let     { brokerFee    = it }
            S.get(S.IR_TAX)?.let        { salesTax     = it }
            S.get(S.IR_ISK_PER_M3)?.let { iskPerM3     = it }
            S.get(S.IR_MAX_CARGO)?.let  { maxCargoM3   = it }
            S.get(S.IR_MIN_PROFIT)?.let { minNetProfit = it }
        }
    }

    LaunchedEffect(topGroups) {
        if (topGroups.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val topId = S.get(S.IR_CAT_TOP)?.toIntOrNull() ?: return@withContext
            val top = topGroups.find { it.marketGroupId == topId } ?: return@withContext
            selectedTopGroup = top
            val subs = StaticDataDao.getChildMarketGroups(topId)
            subGroups = subs
            val subId = S.get(S.IR_CAT_SUB)?.toIntOrNull()
            selectedSubGroup = subs.find { it.marketGroupId == subId }
        }
    }

    LaunchedEffect(selectedTopGroup) {
        val top = selectedTopGroup ?: run { subGroups = emptyList(); selectedSubGroup = null; return@LaunchedEffect }
        val subs = withContext(Dispatchers.IO) { StaticDataDao.getChildMarketGroups(top.marketGroupId) }
        subGroups = subs
        if (selectedSubGroup?.marketGroupId !in subs.map { it.marketGroupId }) selectedSubGroup = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Filter bar ─────────────────────────────────────────────
        FilterBar {
            // Row 1: regions + trade type + categories
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                RegionPicker(allRegions, buyRegionId, width = 168.dp, label = "Buy Region") {
                    buyRegionId = it
                    scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_BUY_REGION, it.toString()) } }
                }
                RegionPicker(allRegions, sellRegionId, width = 168.dp, label = "Sell Region") {
                    sellRegionId = it
                    scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_SELL_REGION, it.toString()) } }
                }
                FilterDivider()
                TradeTypeChip(tradeType) {
                    tradeType = it
                    scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_TRADE_TYPE, it.name) } }
                }
                FilterDivider()
                GroupDropdown("Category", topGroups, selectedTopGroup, "All categories", 145.dp) { g ->
                    selectedTopGroup = g; selectedSubGroup = null
                    scope.launch { withContext(Dispatchers.IO) {
                        S.set(S.IR_CAT_TOP, g?.marketGroupId?.toString() ?: "")
                        S.set(S.IR_CAT_SUB, "")
                    }}
                }
                if (subGroups.isNotEmpty()) {
                    GroupDropdown("Subcategory", subGroups, selectedSubGroup, "All", 135.dp) { g ->
                        selectedSubGroup = g
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_CAT_SUB, g?.marketGroupId?.toString() ?: "") } }
                    }
                }
            }
            // Row 2: numeric params + button
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                ParamField("Margin %",  minMargin,    68.dp)  { minMargin    = it; scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_MARGIN,     it) } } }
                ParamField("Broker %",  brokerFee,    68.dp)  { brokerFee    = it; scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_BROKER,     it) } } }
                ParamField("Tax %",     salesTax,     60.dp)  { salesTax     = it; scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_TAX,        it) } } }
                ParamField("ISK/m³",    iskPerM3,     88.dp)  { iskPerM3     = it; scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_ISK_PER_M3, it) } } }
                ParamField("Max m³",    maxCargoM3,   88.dp)  { maxCargoM3   = it; scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_MAX_CARGO,  it) } } }
                ParamField("Min Net",   minNetProfit, 108.dp) { minNetProfit = it; scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_MIN_PROFIT, it) } } }
                FilterDivider()
                Column(verticalArrangement = Arrangement.Bottom) {
                    Spacer(Modifier.height(19.dp))
                    Button(
                        onClick = {
                            if (buyRegionId == sellRegionId) { statusMsg = "Regions must differ"; return@Button }
                            scope.launch {
                                isAnalyzing = true; results = emptyList()
                                val buyName  = allRegions.find { it.regionId == buyRegionId  }?.name ?: "buy"
                                val sellName = allRegions.find { it.regionId == sellRegionId }?.name ?: "sell"
                                val filterGroupId = selectedSubGroup?.marketGroupId ?: selectedTopGroup?.marketGroupId
                                val filterGroupIds = filterGroupId?.let { withContext(Dispatchers.IO) { buildGroupSubtree(it) } }
                                try {
                                    statusMsg = "Fetching $buyName…"
                                    val buyOrders  = withContext(Dispatchers.IO) { EsiClient.getMarketRegionOrders(buyRegionId)  }
                                    statusMsg = "Fetching $sellName…"
                                    val sellOrders = withContext(Dispatchers.IO) { EsiClient.getMarketRegionOrders(sellRegionId) }
                                    statusMsg = "Analyzing ${buyOrders.size + sellOrders.size} orders…"
                                    results = withContext(Dispatchers.IO) {
                                        computeRegion(
                                            buyOrders, sellOrders,
                                            buyRegionId, sellRegionId,
                                            buyName, sellName,
                                            tradeType            = tradeType,
                                            filterMarketGroupIds = filterGroupIds,
                                            iskPerM3             = iskPerM3.toDoubleOrNull() ?: 1000.0,
                                            maxCargoM3           = maxCargoM3.toDoubleOrNull() ?: 10000.0,
                                            minMarginPct         = minMargin.toDoubleOrNull() ?: 5.0,
                                            minNetProfit         = minNetProfit.toDoubleOrNull() ?: 0.0,
                                            brokerFeePct         = brokerFee.toDoubleOrNull() ?: 3.0,
                                            salesTaxPct          = salesTax.toDoubleOrNull() ?: 8.0,
                                        )
                                    }
                                    statusMsg = "${results.size} opportunities found"
                                } catch (e: Exception) { statusMsg = "Error: ${e.message}" }
                                isAnalyzing = false
                            }
                        },
                        enabled = !isAnalyzing,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(48.dp),
                    ) {
                        if (isAnalyzing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.CompareArrows, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isAnalyzing) "Analyzing…" else "Analyze")
                    }
                }
                if (statusMsg.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.Bottom) {
                        Spacer(Modifier.height(19.dp))
                        Text(
                            statusMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color = if ("Error" in statusMsg || "differ" in statusMsg) Color(0xFFFF6B6B)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.height(48.dp).wrapContentHeight(Alignment.CenterVertically),
                        )
                    }
                }
            }
        }

        if (isAnalyzing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        // ── Results ────────────────────────────────────────────────
        if (results.isEmpty() && !isAnalyzing) {
            AnalysisEmptyState(
                icon = { Icon(Icons.AutoMirrored.Filled.CompareArrows, null, Modifier.size(52.dp), tint = Color.Gray) },
                primary = "Select regions and click Analyze",
                secondary = "Finds items priced low in the buy region that sell for more in the sell region",
            )
        } else {
            val sorted = remember(results, sortCol, sortAsc) { sortRegion(results, sortCol, sortAsc) }
            RegionHeader(sortCol, sortAsc) { col ->
                if (sortCol == col) sortAsc = !sortAsc else { sortCol = col; sortAsc = false }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(sorted, key = { _, it -> it.typeId }) { idx, opp -> RegionRow(opp, idx) }
            }
        }
    }
}

// ─── Filter bar container ─────────────────────────────────────────────────

@Composable
private fun FilterBar(content: @Composable ColumnScope.() -> Unit) {
    Column {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    }
}

// ─── Shared chip surface (base for all filter dropdowns) ─────────────────

@Composable
private fun ChipSurface(
    onClick: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.width(width),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

// ─── Label + control column wrapper ──────────────────────────────────────

@Composable
private fun FilterControl(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        content()
    }
}

// ─── Visual separator between filter sections ─────────────────────────────

@Composable
private fun FilterDivider() {
    VerticalDivider(
        modifier = Modifier.height(36.dp).padding(horizontal = 4.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
    )
}

// ─── Region picker ────────────────────────────────────────────────────────

@Composable
private fun RegionPicker(
    allRegions: List<StaticRegionModel>,
    selectedRegionId: Int,
    width: Dp = 160.dp,
    label: String = "Region",
    onSelect: (Int) -> Unit,
) {
    var expanded    by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val selectedName = remember(selectedRegionId, allRegions) {
        allRegions.find { it.regionId == selectedRegionId }?.name ?: "—"
    }
    val filtered = remember(searchQuery, allRegions) {
        if (allRegions.isEmpty()) emptyList()
        else if (searchQuery.isBlank()) allRegions.take(14)
        else allRegions.filter { it.name.contains(searchQuery, ignoreCase = true) }.take(14)
    }

    FilterControl(label) {
        Box {
            ChipSurface(onClick = { expanded = true; searchQuery = "" }, width = width) {
                Text(
                    selectedName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false; searchQuery = "" },
                modifier = Modifier.width(width + 40.dp).heightIn(max = 360.dp),
            ) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        placeholder = { Text("Search…", style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) },
                    )
                }
                HorizontalDivider()
                if (filtered.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No match for \"$searchQuery\"", style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                        onClick = {},
                        enabled = false,
                    )
                } else {
                    filtered.forEach { region ->
                        DropdownMenuItem(
                            text = { Text(region.name, style = MaterialTheme.typography.bodySmall) },
                            onClick = { onSelect(region.regionId); expanded = false; searchQuery = "" },
                            leadingIcon = if (region.regionId == selectedRegionId) {
                                { Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary) }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

// ─── Group dropdown (category / subcategory) ─────────────────────────────

@Composable
private fun GroupDropdown(
    label: String,
    groups: List<StaticMarketGroupModel>,
    selected: StaticMarketGroupModel?,
    placeholder: String,
    width: Dp,
    onSelect: (StaticMarketGroupModel?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    FilterControl(label) {
        Box {
            ChipSurface(onClick = { expanded = !expanded }, width = width) {
                Text(
                    selected?.name ?: placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected == null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selected != null) {
                    Icon(
                        Icons.Default.Close, null,
                        Modifier.size(13.dp).clickable { onSelect(null) },
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(width + 20.dp).heightIn(max = 300.dp),
            ) {
                DropdownMenuItem(
                    text = { Text(placeholder, style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                    onClick = { onSelect(null); expanded = false },
                )
                HorizontalDivider()
                groups.forEach { g ->
                    DropdownMenuItem(
                        text = { Text(g.name, style = MaterialTheme.typography.bodySmall) },
                        onClick = { onSelect(g); expanded = false },
                        leadingIcon = if (g == selected) {
                            { Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                    )
                }
            }
        }
    }
}

// ─── Trade type chip ──────────────────────────────────────────────────────

@Composable
private fun TradeTypeChip(selected: InterRegionTradeType, onSelect: (InterRegionTradeType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    FilterControl("Trade Type") {
        Box {
            ChipSurface(onClick = { expanded = !expanded }, width = 175.dp) {
                Text(
                    selected.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(210.dp),
            ) {
                InterRegionTradeType.entries.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.label, style = MaterialTheme.typography.bodySmall) },
                        onClick = { onSelect(t); expanded = false },
                        leadingIcon = if (t == selected) {
                            { Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                    )
                }
            }
        }
    }
}

// ─── Compact number field ─────────────────────────────────────────────────

@Composable
private fun ParamField(label: String, value: String, width: Dp, onValue: (String) -> Unit) {
    FilterControl(label) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.width(width),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            shape = MaterialTheme.shapes.extraSmall,
        )
    }
}

// ─── Table headers ────────────────────────────────────────────────────────

@Composable
private fun StationHeader(sort: StationSortCol, asc: Boolean, onSort: (StationSortCol) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            ACol("Item",       StationSortCol.NAME,         sort, asc, onSort, Modifier.weight(1f))
            ACol("Buy At",     StationSortCol.BUY_PRICE,    sort, asc, onSort, Modifier.width(95.dp))
            ACol("Sell At",    StationSortCol.SELL_PRICE,   sort, asc, onSort, Modifier.width(95.dp))
            ACol("Margin",     StationSortCol.MARGIN,       sort, asc, onSort, Modifier.width(65.dp))
            ACol("Net/unit",   StationSortCol.NET_PROFIT,   sort, asc, onSort, Modifier.width(95.dp))
            ACol("Vol/day",    StationSortCol.VOLUME,       sort, asc, onSort, Modifier.width(75.dp))
            ACol("Est. Daily", StationSortCol.DAILY_PROFIT, sort, asc, onSort, Modifier.width(95.dp))
            Text("Orders", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.width(65.dp))
        }
    }
}

@Composable
private fun RegionHeader(sort: RegionSortCol, asc: Boolean, onSort: (RegionSortCol) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            ACol("Item",      RegionSortCol.NAME,       sort, asc, onSort, Modifier.weight(1f))
            ACol("Buy",       RegionSortCol.BUY_PRICE,  sort, asc, onSort, Modifier.width(95.dp))
            ACol("Sell",      RegionSortCol.SELL_PRICE, sort, asc, onSort, Modifier.width(95.dp))
            ACol("Margin",    RegionSortCol.MARGIN,     sort, asc, onSort, Modifier.width(65.dp))
            ACol("m³/unit",   RegionSortCol.ITEM_VOL,   sort, asc, onSort, Modifier.width(70.dp))
            ACol("Ship/unit", RegionSortCol.SHIPPING,   sort, asc, onSort, Modifier.width(90.dp))
            ACol("Net/unit",  RegionSortCol.NET_PROFIT, sort, asc, onSort, Modifier.width(95.dp))
            ACol("Vol/day",   RegionSortCol.VOLUME,     sort, asc, onSort, Modifier.width(70.dp))
        }
    }
}

// ─── Table rows ───────────────────────────────────────────────────────────

@Composable
private fun StationRow(opp: StationOpportunity, index: Int) {
    val bg = if (index % 2 == 0) Color.Transparent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.025f)
    Row(
        modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(opp.typeName, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        PriceText(opp.bestBuy,   Color(0xFFFF6B6B), Modifier.width(95.dp))
        PriceText(opp.bestSell,  Color(0xFF69DB7C), Modifier.width(95.dp))
        MarginText(opp.marginPct, Modifier.width(65.dp))
        PriceText(opp.netProfit, Color(0xFF69DB7C),  Modifier.width(95.dp))
        Text(if (opp.dailyVolume > 0) fVol(opp.dailyVolume) else "—",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.width(75.dp))
        Text(if (opp.estimatedDailyProfit > 0) fPrice(opp.estimatedDailyProfit) else "—",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(95.dp))
        Text("${opp.sellOrderCount}s / ${opp.buyOrderCount}b",
            style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.width(65.dp))
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
}

@Composable
private fun RegionRow(opp: RegionOpportunity, index: Int) {
    val bg = if (index % 2 == 0) Color.Transparent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.025f)
    Row(
        modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(opp.typeName, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        PriceText(opp.buyPrice,  Color(0xFFFF6B6B), Modifier.width(95.dp))
        PriceText(opp.sellPrice, Color(0xFF69DB7C), Modifier.width(95.dp))
        MarginText(opp.marginPct, Modifier.width(65.dp))
        Text("${String.format("%.2f", opp.itemVolumeM3)}m³",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.width(70.dp))
        PriceText(opp.shippingCostPerUnit, Color(0xFFFF8C00), Modifier.width(90.dp))
        PriceText(opp.netProfit, Color(0xFF69DB7C), Modifier.width(95.dp), bold = true)
        Text(if (opp.dailyVolume > 0) fVol(opp.dailyVolume) else "—",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.width(70.dp))
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
}

// ─── Shared UI helpers ────────────────────────────────────────────────────

@Composable
private fun <T> ACol(label: String, col: T, current: T, asc: Boolean, onSort: (T) -> Unit, modifier: Modifier = Modifier) {
    val active = col == current
    Row(
        modifier = modifier.clickable { onSort(col) }.padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1)
        if (active) Icon(
            if (asc) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
            null, Modifier.size(10.dp), tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PriceText(value: Double, color: Color, modifier: Modifier, bold: Boolean = false) {
    Text(fPrice(value), style = MaterialTheme.typography.bodySmall, color = color,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = modifier)
}

@Composable
private fun MarginText(pct: Double, modifier: Modifier) {
    val color = when {
        pct >= 20 -> Color(0xFF69DB7C)
        pct >= 10 -> Color(0xFFFF8C00)
        else      -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    }
    Text("${String.format("%.1f", pct)}%", style = MaterialTheme.typography.bodySmall,
        color = color, fontWeight = FontWeight.Medium, modifier = modifier)
}

@Composable
private fun AnalysisEmptyState(icon: @Composable () -> Unit, primary: String, secondary: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            icon()
            Text(primary,   style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            Text(secondary, style = MaterialTheme.typography.bodySmall,  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}

// ─── Analysis helpers (run on Dispatchers.IO) ─────────────────────────────

private fun buildGroupSubtree(rootGroupId: Int): Set<Int> {
    val result = mutableSetOf<Int>()
    val queue  = ArrayDeque<Int>()
    queue.add(rootGroupId)
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        result.add(id)
        StaticDataDao.getChildMarketGroups(id).forEach { queue.add(it.marketGroupId) }
    }
    return result
}

// ─── Analysis functions (run on Dispatchers.IO) ───────────────────────────

private fun computeStation(
    allOrders: List<Map<String, Any?>>,
    regionId: Int,
    filterMarketGroupIds: Set<Int>?,
    minMarginPct: Double,
    minDailyVol: Long,
    maxBuyPrice: Double,
    minNetProfit: Double,
    brokerFeePct: Double,
    salesTaxPct: Double,
): List<StationOpportunity> {
    val byType  = allOrders.groupBy { (it["type_id"] as? Number)?.toInt() ?: 0 }
    val results = mutableListOf<StationOpportunity>()

    byType.forEach { (typeId, orders) ->
        if (typeId == 0) return@forEach
        val sells = orders.filter { (it["is_buy_order"] as? Boolean) == false }
        val buys  = orders.filter { (it["is_buy_order"] as? Boolean) == true  }
        if (sells.isEmpty() || buys.isEmpty()) return@forEach

        val bestSell = sells.minOf { (it["price"] as? Number)?.toDouble() ?: Double.MAX_VALUE }
        val bestBuy  = buys.maxOf  { (it["price"] as? Number)?.toDouble() ?: 0.0 }

        if (bestSell > maxBuyPrice) return@forEach
        val grossProfit = bestSell - bestBuy
        if (grossProfit <= 0) return@forEach
        val marginPct = grossProfit / bestSell * 100.0
        if (marginPct < minMarginPct) return@forEach
        val fees      = (bestSell + bestBuy) * brokerFeePct / 100.0 + bestSell * salesTaxPct / 100.0
        val netProfit = grossProfit - fees
        if (netProfit < minNetProfit) return@forEach

        val type = StaticDataDao.getTypeById(typeId) ?: return@forEach
        if (filterMarketGroupIds != null && type.marketGroupId !in filterMarketGroupIds) return@forEach

        val history     = fetchHistory(typeId, regionId, filterMarketGroupIds != null)
        val avgDailyVol = if (history.isNotEmpty()) history.map { it.volume }.average().toLong() else 0L
        if (avgDailyVol < minDailyVol && minDailyVol > 0) return@forEach

        results += StationOpportunity(
            typeId               = typeId,
            typeName             = type.name,
            bestSell             = bestSell,
            bestBuy              = bestBuy,
            grossProfit          = grossProfit,
            netProfit            = netProfit,
            marginPct            = marginPct,
            dailyVolume          = avgDailyVol,
            sellOrderCount       = sells.size,
            buyOrderCount        = buys.size,
            estimatedDailyProfit = netProfit * avgDailyVol.coerceAtLeast(1),
        )
    }
    return results.sortedByDescending { it.netProfit }
}

private fun computeRegion(
    buyOrders: List<Map<String, Any?>>,
    sellOrders: List<Map<String, Any?>>,
    @Suppress("UNUSED_PARAMETER") buyRegionId: Int,
    sellRegionId: Int,
    buyRegionName: String,
    sellRegionName: String,
    tradeType: InterRegionTradeType,
    filterMarketGroupIds: Set<Int>?,
    iskPerM3: Double,
    maxCargoM3: Double,
    minMarginPct: Double,
    minNetProfit: Double,
    brokerFeePct: Double,
    salesTaxPct: Double,
): List<RegionOpportunity> {
    fun Map<String, Any?>.price()     = (get("price") as? Number)?.toDouble() ?: 0.0
    fun Map<String, Any?>.isBuyOrd()  = get("is_buy_order") as? Boolean == true
    fun Map<String, Any?>.tid()       = (get("type_id") as? Number)?.toInt() ?: 0

    val srcSell = buyOrders .filter { !it.isBuyOrd() }.groupBy { it.tid() }.mapValues { (_, o) -> o.minOf { it.price() } }
    val srcBuy  = buyOrders .filter {  it.isBuyOrd() }.groupBy { it.tid() }.mapValues { (_, o) -> o.maxOf { it.price() } }
    val dstBuy  = sellOrders.filter {  it.isBuyOrd() }.groupBy { it.tid() }.mapValues { (_, o) -> o.maxOf { it.price() } }
    val dstSell = sellOrders.filter { !it.isBuyOrd() }.groupBy { it.tid() }.mapValues { (_, o) -> o.minOf { it.price() } }

    val sourcePrices = when (tradeType) {
        InterRegionTradeType.SELL_TO_BUY, InterRegionTradeType.SELL_TO_SELL -> srcSell
        InterRegionTradeType.BUY_TO_BUY,  InterRegionTradeType.BUY_TO_SELL  -> srcBuy
    }
    val destPrices = when (tradeType) {
        InterRegionTradeType.SELL_TO_BUY, InterRegionTradeType.BUY_TO_BUY  -> dstBuy
        InterRegionTradeType.SELL_TO_SELL, InterRegionTradeType.BUY_TO_SELL -> dstSell
    }

    val common  = sourcePrices.keys.intersect(destPrices.keys)
    val results = mutableListOf<RegionOpportunity>()

    common.forEach { typeId ->
        if (typeId == 0) return@forEach
        val buyPrice  = sourcePrices[typeId] ?: return@forEach
        val sellPrice = destPrices[typeId]   ?: return@forEach
        if (sellPrice <= buyPrice) return@forEach

        val type = StaticDataDao.getTypeById(typeId) ?: return@forEach
        if (filterMarketGroupIds != null && type.marketGroupId !in filterMarketGroupIds) return@forEach

        val itemVol = type.packagedVolume.takeIf { it > 0 } ?: type.volume.takeIf { it > 0 } ?: 1.0
        if (itemVol > maxCargoM3) return@forEach

        val shipping    = itemVol * iskPerM3
        val grossProfit = sellPrice - buyPrice
        val fees        = sellPrice * (salesTaxPct + brokerFeePct) / 100.0
        val netProfit   = grossProfit - fees - shipping
        if (netProfit < minNetProfit) return@forEach
        val marginPct = grossProfit / buyPrice * 100.0
        if (marginPct < minMarginPct) return@forEach

        val history = fetchHistory(typeId, sellRegionId, filterMarketGroupIds != null)
        val avgVol  = if (history.isNotEmpty()) history.map { it.volume }.average().toLong() else 0L

        results += RegionOpportunity(
            typeId              = typeId,
            typeName            = type.name,
            buyRegionName       = buyRegionName,
            sellRegionName      = sellRegionName,
            buyPrice            = buyPrice,
            sellPrice           = sellPrice,
            grossProfit         = grossProfit,
            netProfit           = netProfit,
            marginPct           = marginPct,
            itemVolumeM3        = itemVol,
            shippingCostPerUnit = shipping,
            dailyVolume         = avgVol,
        )
    }
    return results.sortedByDescending { it.netProfit }
}

// ─── History helper ───────────────────────────────────────────────────────

private fun fetchHistory(typeId: Int, regionId: Int, fetchFromEsi: Boolean): List<org.eve.trader.core.model.MarketHistoryModel> {
    val effectiveRegionId = if (typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else regionId
    val dbHistory = MarketDao.getHistory(typeId, effectiveRegionId, 30)
    if (dbHistory.isNotEmpty() || !fetchFromEsi) return dbHistory
    return try {
        val entries = EsiClient.getMarketRegionHistory(effectiveRegionId, typeId)
        entries.forEach { entry ->
            runCatching {
                MarketDao.insertHistory(
                    org.eve.trader.core.model.MarketHistoryModel(
                        typeId     = typeId,
                        regionId   = effectiveRegionId,
                        date       = entry["date"] as? String ?: "",
                        average    = (entry["average"] as? Number)?.toDouble() ?: 0.0,
                        volume     = (entry["volume"] as? Number)?.toLong() ?: 0L,
                        orderCount = (entry["order_count"] as? Number)?.toLong() ?: 0L,
                        highest    = (entry["highest"] as? Number)?.toDouble() ?: 0.0,
                        lowest     = (entry["lowest"] as? Number)?.toDouble() ?: 0.0,
                    )
                )
            }
        }
        MarketDao.getHistory(typeId, effectiveRegionId, 30)
    } catch (_: Exception) { emptyList() }
}

// ─── Format helpers ───────────────────────────────────────────────────────

private fun fPrice(v: Double): String = when {
    v >= 1_000_000_000 -> String.format("%.2fB", v / 1_000_000_000)
    v >= 1_000_000     -> String.format("%.2fM", v / 1_000_000)
    v >= 1_000         -> String.format("%.1fK", v / 1_000)
    else               -> String.format("%.2f", v)
}

private fun fVol(v: Long): String = when {
    v >= 1_000_000 -> String.format("%.1fM", v / 1_000_000.0)
    v >= 1_000     -> String.format("%.1fK", v / 1_000.0)
    else           -> v.toString()
}
