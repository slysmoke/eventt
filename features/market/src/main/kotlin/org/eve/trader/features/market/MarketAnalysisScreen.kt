package org.eve.trader.features.market

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
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
    val bestSell: Double,           // cheapest sell order = what you pay
    val bestBuy: Double,            // highest buy order = what you receive
    val grossProfit: Double,
    val netProfit: Double,          // after broker fee + sales tax
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
    val buyPrice: Double,           // cheapest sell order in buy region
    val sellPrice: Double,          // highest buy order in sell region
    val grossProfit: Double,
    val netProfit: Double,          // after fees + shipping
    val marginPct: Double,
    val itemVolumeM3: Double,
    val shippingCostPerUnit: Double,
    val dailyVolume: Long,
)

// ─── Sort enums ───────────────────────────────────────────────────────────

private enum class StationSortCol { NAME, BUY_PRICE, SELL_PRICE, MARGIN, NET_PROFIT, VOLUME, DAILY_PROFIT }
private enum class RegionSortCol  { NAME, BUY_PRICE, SELL_PRICE, MARGIN, ITEM_VOL, SHIPPING, NET_PROFIT, VOLUME }

private fun sortStation(list: List<StationOpportunity>, col: StationSortCol, asc: Boolean): List<StationOpportunity> {
    val cmp: Comparator<StationOpportunity> = when (col) {
        StationSortCol.NAME         -> compareBy { it.typeName }
        StationSortCol.BUY_PRICE    -> compareBy { it.bestSell }
        StationSortCol.SELL_PRICE   -> compareBy { it.bestBuy }
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
            Tab(
                selected = selectedTab == 0, onClick = { selectedTab = 0 },
                text = { Text("Station Trading") },
                icon = { Icon(Icons.Default.Store, null, Modifier.size(16.dp)) },
            )
            Tab(
                selected = selectedTab == 1, onClick = { selectedTab = 1 },
                text = { Text("Inter-Region") },
                icon = { Icon(Icons.AutoMirrored.Filled.CompareArrows, null, Modifier.size(16.dp)) },
            )
        }
        when (selectedTab) {
            0 -> StationTradingTab(allRegions, topGroups)
            1 -> InterRegionTab(allRegions, topGroups)
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
    var minMargin        by remember { mutableStateOf(5f) }
    var minDailyVol      by remember { mutableStateOf("10") }
    var maxBuyPrice      by remember { mutableStateOf("500000000") }
    var minNetProfit     by remember { mutableStateOf("100000") }
    var brokerFee        by remember { mutableStateOf(3f) }
    var salesTax         by remember { mutableStateOf(8f) }
    var isAnalyzing      by remember { mutableStateOf(false) }
    var statusMsg        by remember { mutableStateOf("") }
    var results          by remember { mutableStateOf<List<StationOpportunity>>(emptyList()) }
    var sortCol          by remember { mutableStateOf(StationSortCol.NET_PROFIT) }
    var sortAsc          by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTopGroup) {
        subGroups = if (selectedTopGroup != null)
            withContext(Dispatchers.IO) { StaticDataDao.getChildMarketGroups(selectedTopGroup!!.marketGroupId) }
        else emptyList()
        selectedSubGroup = null
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // ── Filters sidebar ────────────────────────────────────────
        Surface(
            modifier = Modifier.width(250.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        ) {
            Column(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                Text("Filters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                SectionLabel("Region")
                RegionPicker(allRegions = allRegions, selectedRegionId = regionId) { regionId = it }

                Spacer(Modifier.height(10.dp))
                SectionLabel("Category (optional)")
                CategoryFilter(
                    topGroups = topGroups, subGroups = subGroups,
                    selectedTopGroup = selectedTopGroup, onTopGroupSelected = { selectedTopGroup = it },
                    selectedSubGroup = selectedSubGroup, onSubGroupSelected = { selectedSubGroup = it },
                )

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                SliderFilter("Min Margin", minMargin, 0f..50f, "${minMargin.toInt()}%") { minMargin = it }
                SliderFilter("Broker Fee", brokerFee, 0f..10f, "${String.format("%.1f", brokerFee)}%") { brokerFee = it }
                SliderFilter("Sales Tax",  salesTax,  0f..12f, "${String.format("%.1f", salesTax)}%")  { salesTax  = it }

                Spacer(Modifier.height(8.dp))
                FilterField("Min Daily Volume",    minDailyVol)  { minDailyVol  = it }
                Spacer(Modifier.height(6.dp))
                FilterField("Max Buy Price (ISK)", maxBuyPrice)  { maxBuyPrice  = it }
                Spacer(Modifier.height(6.dp))
                FilterField("Min Net Profit/unit", minNetProfit) { minNetProfit = it }

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        scope.launch {
                            isAnalyzing = true
                            results = emptyList()
                            val regionName = allRegions.find { it.regionId == regionId }?.name ?: "region"
                            try {
                                statusMsg = "Fetching orders from $regionName…"
                                val allOrders = withContext(Dispatchers.IO) {
                                    EsiClient.getMarketRegionOrders(regionId)
                                }
                                val filterGroupId = selectedSubGroup?.marketGroupId ?: selectedTopGroup?.marketGroupId
                                val filterGroupIds = if (filterGroupId != null)
                                    withContext(Dispatchers.IO) { buildGroupSubtree(filterGroupId) }
                                else null
                                statusMsg = "Analyzing ${allOrders.size} orders…"
                                results = withContext(Dispatchers.IO) {
                                    computeStation(
                                        allOrders, regionId,
                                        filterMarketGroupIds = filterGroupIds,
                                        minMarginPct  = minMargin.toDouble(),
                                        minDailyVol   = minDailyVol.toLongOrNull() ?: 0L,
                                        maxBuyPrice   = maxBuyPrice.toDoubleOrNull() ?: Double.MAX_VALUE,
                                        minNetProfit  = minNetProfit.toDoubleOrNull() ?: 0.0,
                                        brokerFeePct  = brokerFee.toDouble(),
                                        salesTaxPct   = salesTax.toDouble(),
                                    )
                                }
                                statusMsg = "${results.size} opportunities found"
                            } catch (e: Exception) {
                                statusMsg = "Error: ${e.message}"
                            }
                            isAnalyzing = false
                        }
                    },
                    enabled = !isAnalyzing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isAnalyzing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Search, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isAnalyzing) "Analyzing…" else "Analyze")
                }

                if (statusMsg.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(statusMsg, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Margin = (sell − buy) / sell\nNet profit accounts for broker fee on both orders and sales tax on sell.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        }

        // ── Results ────────────────────────────────────────────────
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (isAnalyzing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

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
                    itemsIndexed(sorted, key = { _, it -> it.typeId }) { idx, opp ->
                        StationRow(opp, idx)
                    }
                }
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
    var selectedTopGroup by remember { mutableStateOf<StaticMarketGroupModel?>(null) }
    var selectedSubGroup by remember { mutableStateOf<StaticMarketGroupModel?>(null) }
    var subGroups        by remember { mutableStateOf<List<StaticMarketGroupModel>>(emptyList()) }
    var iskPerM3         by remember { mutableStateOf("1000") }
    var maxCargoM3       by remember { mutableStateOf("10000") }
    var minMargin        by remember { mutableStateOf(5f) }
    var minNetProfit     by remember { mutableStateOf("5000000") }
    var brokerFee        by remember { mutableStateOf(3f) }
    var salesTax         by remember { mutableStateOf(8f) }
    var isAnalyzing      by remember { mutableStateOf(false) }
    var statusMsg        by remember { mutableStateOf("") }
    var results          by remember { mutableStateOf<List<RegionOpportunity>>(emptyList()) }
    var sortCol          by remember { mutableStateOf(RegionSortCol.NET_PROFIT) }
    var sortAsc          by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTopGroup) {
        subGroups = if (selectedTopGroup != null)
            withContext(Dispatchers.IO) { StaticDataDao.getChildMarketGroups(selectedTopGroup!!.marketGroupId) }
        else emptyList()
        selectedSubGroup = null
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // ── Filters sidebar ────────────────────────────────────────
        Surface(
            modifier = Modifier.width(250.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        ) {
            Column(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                Text("Filters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                SectionLabel("Buy Region (buy cheap here)")
                RegionPicker(allRegions = allRegions, selectedRegionId = buyRegionId) { buyRegionId = it }

                Spacer(Modifier.height(8.dp))
                SectionLabel("Sell Region (sell here)")
                RegionPicker(allRegions = allRegions, selectedRegionId = sellRegionId) { sellRegionId = it }

                Spacer(Modifier.height(10.dp))
                SectionLabel("Category (optional)")
                CategoryFilter(
                    topGroups = topGroups, subGroups = subGroups,
                    selectedTopGroup = selectedTopGroup, onTopGroupSelected = { selectedTopGroup = it },
                    selectedSubGroup = selectedSubGroup, onSubGroupSelected = { selectedSubGroup = it },
                )

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                SliderFilter("Min Margin", minMargin, 0f..50f, "${minMargin.toInt()}%") { minMargin = it }
                SliderFilter("Broker Fee", brokerFee, 0f..10f, "${String.format("%.1f", brokerFee)}%") { brokerFee = it }
                SliderFilter("Sales Tax",  salesTax,  0f..12f, "${String.format("%.1f", salesTax)}%")  { salesTax  = it }

                Spacer(Modifier.height(8.dp))
                FilterField("Shipping (ISK/m³)",   iskPerM3)    { iskPerM3    = it }
                Spacer(Modifier.height(6.dp))
                FilterField("Max Cargo (m³)",       maxCargoM3)  { maxCargoM3  = it }
                Spacer(Modifier.height(6.dp))
                FilterField("Min Net Profit/unit",  minNetProfit){ minNetProfit = it }

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (buyRegionId == sellRegionId) {
                            statusMsg = "Buy and sell regions must differ"
                            return@Button
                        }
                        scope.launch {
                            isAnalyzing = true
                            results = emptyList()
                            val buyName  = allRegions.find { it.regionId == buyRegionId  }?.name ?: "buy region"
                            val sellName = allRegions.find { it.regionId == sellRegionId }?.name ?: "sell region"
                            val filterGroupId = selectedSubGroup?.marketGroupId ?: selectedTopGroup?.marketGroupId
                            val filterGroupIds = if (filterGroupId != null)
                                withContext(Dispatchers.IO) { buildGroupSubtree(filterGroupId) }
                            else null
                            try {
                                statusMsg = "Fetching orders from $buyName…"
                                val buyOrders = withContext(Dispatchers.IO) {
                                    EsiClient.getMarketRegionOrders(buyRegionId)
                                }
                                statusMsg = "Fetching orders from $sellName…"
                                val sellOrders = withContext(Dispatchers.IO) {
                                    EsiClient.getMarketRegionOrders(sellRegionId)
                                }
                                statusMsg = "Analyzing ${buyOrders.size + sellOrders.size} orders…"
                                results = withContext(Dispatchers.IO) {
                                    computeRegion(
                                        buyOrders, sellOrders,
                                        buyRegionId, sellRegionId,
                                        buyName, sellName,
                                        filterMarketGroupIds = filterGroupIds,
                                        iskPerM3      = iskPerM3.toDoubleOrNull() ?: 1000.0,
                                        maxCargoM3    = maxCargoM3.toDoubleOrNull() ?: 10000.0,
                                        minMarginPct  = minMargin.toDouble(),
                                        minNetProfit  = minNetProfit.toDoubleOrNull() ?: 0.0,
                                        brokerFeePct  = brokerFee.toDouble(),
                                        salesTaxPct   = salesTax.toDouble(),
                                    )
                                }
                                statusMsg = "${results.size} opportunities found"
                            } catch (e: Exception) {
                                statusMsg = "Error: ${e.message}"
                            }
                            isAnalyzing = false
                        }
                    },
                    enabled = !isAnalyzing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isAnalyzing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.CompareArrows, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isAnalyzing) "Analyzing…" else "Analyze")
                }

                if (statusMsg.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        statusMsg,
                        style = MaterialTheme.typography.labelSmall,
                        color = if ("Error" in statusMsg || "differ" in statusMsg) Color(0xFFFF6B6B) else Color.Gray,
                    )
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Net profit = sell_price − buy_price − shipping − fees.\nShipping = item m³ × ISK/m³.\nSell price is the highest active buy order in sell region.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        }

        // ── Results ────────────────────────────────────────────────
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (isAnalyzing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

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
                    itemsIndexed(sorted, key = { _, it -> it.typeId }) { idx, opp ->
                        RegionRow(opp, idx)
                    }
                }
            }
        }
    }
}

// ─── Region picker ────────────────────────────────────────────────────────

@Composable
private fun RegionPicker(
    allRegions: List<StaticRegionModel>,
    selectedRegionId: Int,
    onSelect: (Int) -> Unit,
) {
    var searchText by remember { mutableStateOf("") }
    var expanded   by remember { mutableStateOf(false) }

    // Sync display text when regions load or selection changes
    LaunchedEffect(selectedRegionId, allRegions.size) {
        val name = allRegions.find { it.regionId == selectedRegionId }?.name
        if (!name.isNullOrEmpty()) searchText = name
    }

    val filtered = remember(searchText, allRegions) {
        when {
            allRegions.isEmpty() -> emptyList()
            searchText.isEmpty() -> allRegions.take(12)
            else -> allRegions.filter { it.name.contains(searchText, ignoreCase = true) }.take(12)
        }
    }

    Box {
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it; expanded = true },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            placeholder = { Text("Search region…", style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
            trailingIcon = {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).clickable { expanded = !expanded },
                )
            },
        )
        DropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(226.dp),
        ) {
            filtered.forEach { region ->
                DropdownMenuItem(
                    text = { Text(region.name, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        searchText = region.name
                        onSelect(region.regionId)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ─── Category / subcategory filter ────────────────────────────────────────

@Composable
private fun CategoryFilter(
    topGroups: List<StaticMarketGroupModel>,
    subGroups: List<StaticMarketGroupModel>,
    selectedTopGroup: StaticMarketGroupModel?,
    onTopGroupSelected: (StaticMarketGroupModel?) -> Unit,
    selectedSubGroup: StaticMarketGroupModel?,
    onSubGroupSelected: (StaticMarketGroupModel?) -> Unit,
) {
    GroupDropdown(
        label = "Category",
        groups = topGroups,
        selected = selectedTopGroup,
        placeholder = "All categories",
        onSelect = onTopGroupSelected,
    )
    if (subGroups.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        GroupDropdown(
            label = "Subcategory",
            groups = subGroups,
            selected = selectedSubGroup,
            placeholder = "All subcategories",
            onSelect = onSubGroupSelected,
        )
    }
}

@Composable
private fun GroupDropdown(
    label: String,
    groups: List<StaticMarketGroupModel>,
    selected: StaticMarketGroupModel?,
    placeholder: String,
    onSelect: (StaticMarketGroupModel?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedTextField(
            value = selected?.name ?: "",
            onValueChange = { },
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected != null) {
                        Icon(
                            Icons.Default.Clear, contentDescription = null,
                            modifier = Modifier.size(14.dp).clickable { onSelect(null) },
                            tint = Color.Gray,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).clickable { expanded = !expanded },
                    )
                }
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(226.dp).heightIn(max = 280.dp),
        ) {
            DropdownMenuItem(
                text = { Text(placeholder, style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                onClick = { onSelect(null); expanded = false },
            )
            HorizontalDivider()
            groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.name, style = MaterialTheme.typography.bodySmall) },
                    onClick = { onSelect(group); expanded = false },
                    leadingIcon = if (group == selected) {
                        { Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                )
            }
        }
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
            Text("Orders", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.width(65.dp))
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
        Text(opp.typeName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        PriceText(opp.bestSell,  Color(0xFFFF6B6B), Modifier.width(95.dp))
        PriceText(opp.bestBuy,   Color(0xFF69DB7C), Modifier.width(95.dp))
        MarginText(opp.marginPct, Modifier.width(65.dp))
        PriceText(opp.netProfit, Color(0xFF69DB7C),  Modifier.width(95.dp))
        Text(
            if (opp.dailyVolume > 0) fVol(opp.dailyVolume) else "—",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.width(75.dp),
        )
        Text(
            if (opp.estimatedDailyProfit > 0) fPrice(opp.estimatedDailyProfit) else "—",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(95.dp),
        )
        Text(
            "${opp.sellOrderCount}s / ${opp.buyOrderCount}b",
            style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.width(65.dp),
        )
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
        Text(opp.typeName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        PriceText(opp.buyPrice,  Color(0xFFFF6B6B), Modifier.width(95.dp))
        PriceText(opp.sellPrice, Color(0xFF69DB7C), Modifier.width(95.dp))
        MarginText(opp.marginPct, Modifier.width(65.dp))
        Text(
            "${String.format("%.2f", opp.itemVolumeM3)}m³",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.width(70.dp),
        )
        PriceText(opp.shippingCostPerUnit, Color(0xFFFF8C00), Modifier.width(90.dp))
        PriceText(opp.netProfit, Color(0xFF69DB7C), Modifier.width(95.dp), bold = true)
        Text(
            if (opp.dailyVolume > 0) fVol(opp.dailyVolume) else "—",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.width(70.dp),
        )
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
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
        )
        if (active) Icon(
            if (asc) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
            null, Modifier.size(10.dp), tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SliderFilter(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(display, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = value, onValueChange = onValue, valueRange = range, modifier = Modifier.padding(vertical = 0.dp))
}

@Composable
private fun FilterField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onValue,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = true,
    )
}

@Composable
private fun PriceText(value: Double, color: Color, modifier: Modifier, bold: Boolean = false) {
    Text(
        fPrice(value),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        modifier = modifier,
    )
}

@Composable
private fun MarginText(pct: Double, modifier: Modifier) {
    val color = when {
        pct >= 20 -> Color(0xFF69DB7C)
        pct >= 10 -> Color(0xFFFF8C00)
        else      -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    }
    Text(
        "${String.format("%.1f", pct)}%",
        style = MaterialTheme.typography.bodySmall,
        color = color,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
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

        val history     = MarketDao.getHistory(typeId, regionId, 30)
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
    filterMarketGroupIds: Set<Int>?,
    iskPerM3: Double,
    maxCargoM3: Double,
    minMarginPct: Double,
    minNetProfit: Double,
    brokerFeePct: Double,
    salesTaxPct: Double,
): List<RegionOpportunity> {
    val cheapestSell = buyOrders
        .filter { (it["is_buy_order"] as? Boolean) == false }
        .groupBy { (it["type_id"] as? Number)?.toInt() ?: 0 }
        .mapValues { (_, o) -> o.minOf { (it["price"] as? Number)?.toDouble() ?: Double.MAX_VALUE } }

    val highestBuy = sellOrders
        .filter { (it["is_buy_order"] as? Boolean) == true }
        .groupBy { (it["type_id"] as? Number)?.toInt() ?: 0 }
        .mapValues { (_, o) -> o.maxOf { (it["price"] as? Number)?.toDouble() ?: 0.0 } }

    val common  = cheapestSell.keys.intersect(highestBuy.keys)
    val results = mutableListOf<RegionOpportunity>()

    common.forEach { typeId ->
        if (typeId == 0) return@forEach
        val buyPrice  = cheapestSell[typeId] ?: return@forEach
        val sellPrice = highestBuy[typeId]   ?: return@forEach
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

        val history = MarketDao.getHistory(typeId, sellRegionId, 30)
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
