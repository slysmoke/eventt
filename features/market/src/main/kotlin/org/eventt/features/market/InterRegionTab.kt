package org.eventt.features.market

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.eventt.core.database.ActiveOrderDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.everef.EveRefService
import org.eventt.core.model.HotkeyBindings
import org.eventt.core.model.StaticMarketGroupModel
import org.eventt.core.model.StaticRegionModel
import org.eventt.core.model.StaticStationModel
import org.eventt.core.staticdata.JumpGraphService
import org.eventt.ui.common.ensureVisible
import java.util.Locale

// ─── Inter-Region ─────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InterRegionTab(
    allRegions: List<StaticRegionModel>,
    topGroups: List<StaticMarketGroupModel>,
    charId: Int?,
) {
    val scope = rememberCoroutineScope()

    var buyRegionId by remember { mutableStateOf(10000002) }
    var buyStationId by remember { mutableStateOf<Long?>(null) }
    var buyStations by remember { mutableStateOf<List<StaticStationModel>>(emptyList()) }
    var sellRegionId by remember { mutableStateOf(10000043) }
    var sellStationId by remember { mutableStateOf<Long?>(null) }
    var sellStations by remember { mutableStateOf<List<StaticStationModel>>(emptyList()) }
    var tradeType by remember { mutableStateOf(InterRegionTradeType.SELL_TO_BUY) }
    var selectedTopGroup by remember { mutableStateOf<StaticMarketGroupModel?>(null) }
    var selectedSubGroup by remember { mutableStateOf<StaticMarketGroupModel?>(null) }
    var subGroups by remember { mutableStateOf<List<StaticMarketGroupModel>>(emptyList()) }
    var minMargin by remember { mutableStateOf("5") }
    // Sanity-check the destination price against a cost-based ceiling instead of trusting
    // whatever the (possibly thin/stale) market shows — see where it's applied in
    // computeRegionOpportunityForType. Off by default: it's a "let me check if this profit is
    // real" toggle you switch on, not a permanent constraint.
    var marginLimitEnabled by remember { mutableStateOf(false) }
    var marginLimitPct by remember { mutableStateOf("30") }
    var iskPerM3 by remember { mutableStateOf("1000") }
    var maxCargoM3 by remember { mutableStateOf("10000") }
    var minNetProfit by remember { mutableStateOf("5000000") }
    var isAnalyzing by remember { mutableStateOf(false) }
    var analyzeJob by remember { mutableStateOf<Job?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RegionOpportunity>>(emptyList()) }
    var sortCol by remember { mutableStateOf(RegionSortCol.NET_VOL) }
    var sortAsc by remember { mutableStateOf(false) }
    var brokerFeePct by remember { mutableStateOf(3.0) }
    var salesTaxPct by remember { mutableStateOf(8.0) }
    var volCapEnabled by remember { mutableStateOf(false) }
    var volCapPct by remember { mutableStateOf("10") }
    var copyVolumeEnabled by remember { mutableStateOf(true) }
    var skipExistingOrders by remember { mutableStateOf(false) }
    var histSourceIsEsi by remember { mutableStateOf(false) }

    // Load persisted settings + character tax values
    LaunchedEffect(charId) {
        withContext(Dispatchers.IO) {
            histSourceIsEsi = EveRefService.getSelectedSource() == "esi"
            S.get(S.IR_BUY_REGION)?.toIntOrNull()?.let { buyRegionId = it }
            S.get(S.IR_BUY_STATION)?.toLongOrNull()?.let { buyStationId = it }
            S.get(S.IR_SELL_REGION)?.toIntOrNull()?.let { sellRegionId = it }
            S.get(S.IR_SELL_STATION)?.toLongOrNull()?.let { sellStationId = it }
            S.get(S.IR_TRADE_TYPE)?.let { name ->
                InterRegionTradeType.entries.find { it.name == name }?.let { tradeType = it }
            }
            S.get(S.IR_MARGIN)?.let { minMargin = it }
            S.get(S.IR_MARGIN_LIMIT_ENABLED)?.let { marginLimitEnabled = it == "true" }
            S.get(S.IR_MARGIN_LIMIT_PCT)?.let { marginLimitPct = it }
            S.get(S.IR_ISK_PER_M3)?.let { iskPerM3 = it }
            S.get(S.IR_MAX_CARGO)?.let { maxCargoM3 = it }
            S.get(S.IR_MIN_PROFIT)?.let { minNetProfit = it }
            S.get(S.IR_VOL_CAP_ENABLED)?.let { volCapEnabled = it == "true" }
            S.get(S.IR_VOL_CAP_PCT)?.let { volCapPct = it }
            S.get(S.IR_COPY_VOLUME)?.let { copyVolumeEnabled = it == "true" }
            S.get(S.IR_SKIP_EXISTING)?.let { skipExistingOrders = it == "true" }
            if (charId != null) {
                brokerFeePct = StaticDataDao.getCharBrokersFee(charId)
                salesTaxPct = StaticDataDao.getCharSalesTax(charId)
            }
        }
    }

    // Reload stations when buy region changes
    LaunchedEffect(buyRegionId) {
        val loaded = withContext(Dispatchers.IO) { StaticDataDao.getStationsByRegion(buyRegionId) }
        buyStations = loaded
        if (buyStationId != null && loaded.none { it.stationId == buyStationId }) {
            buyStationId = null
            scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_BUY_STATION, "") } }
        }
    }

    // Reload stations when sell region changes
    LaunchedEffect(sellRegionId) {
        val loaded = withContext(Dispatchers.IO) { StaticDataDao.getStationsByRegion(sellRegionId) }
        sellStations = loaded
        if (sellStationId != null && loaded.none { it.stationId == sellStationId }) {
            sellStationId = null
            scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_SELL_STATION, "") } }
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
        val top =
            selectedTopGroup ?: run {
                subGroups = emptyList()
                selectedSubGroup = null
                return@LaunchedEffect
            }
        val subs = withContext(Dispatchers.IO) { StaticDataDao.getChildMarketGroups(top.marketGroupId) }
        subGroups = subs
        if (selectedSubGroup?.marketGroupId !in subs.map { it.marketGroupId }) selectedSubGroup = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Filter bar ─────────────────────────────────────────────
        FilterBar {
            // Row 1: route (buy → sell) + trade type + categories on the left, fees info pinned right.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                RegionPicker(
                    allRegions,
                    buyRegionId,
                    width = 158.dp,
                    label = "Buy Region",
                    accentColor = MaterialTheme.colorScheme.primary,
                ) {
                    buyRegionId = it
                    scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_BUY_REGION, it.toString()) } }
                }
                StationPicker(buyStations, buyStationId, width = 190.dp, label = "Buy Station") {
                    buyStationId = it
                    scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_BUY_STATION, it?.toString() ?: "") } }
                }
                RouteArrow()
                RegionPicker(
                    allRegions,
                    sellRegionId,
                    width = 158.dp,
                    label = "Sell Region",
                    accentColor = MaterialTheme.colorScheme.tertiary,
                ) {
                    sellRegionId = it
                    scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_SELL_REGION, it.toString()) } }
                }
                StationPicker(sellStations, sellStationId, width = 190.dp, label = "Sell Station") {
                    sellStationId = it
                    scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_SELL_STATION, it?.toString() ?: "") } }
                }
                FilterDivider()
                TradeTypeChip(tradeType) {
                    tradeType = it
                    scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_TRADE_TYPE, it.name) } }
                }
                FilterDivider()
                GroupDropdown("Category", topGroups, selectedTopGroup, "All categories", 145.dp) { g ->
                    selectedTopGroup = g
                    selectedSubGroup = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            S.set(S.IR_CAT_TOP, g?.marketGroupId?.toString() ?: "")
                            S.set(S.IR_CAT_SUB, "")
                        }
                    }
                }
                if (subGroups.isNotEmpty()) {
                    GroupDropdown("Subcategory", subGroups, selectedSubGroup, "All", 135.dp) { g ->
                        selectedSubGroup = g
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_CAT_SUB, g?.marketGroupId?.toString() ?: "") } }
                    }
                }
                Spacer(Modifier.weight(1f))
                // Read-only tax display — informational, so it lives at the far edge with the
                // other non-inputs rather than crammed in with the editable filters.
                FilterControl("Fees") {
                    Text(
                        "Tax ${String.format(
                            Locale.US,
                            "%.2f",
                            salesTaxPct,
                        )}%  ·  Broker ${String.format(Locale.US, "%.2f", brokerFeePct)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                }
            }
            // Row 2: editable filters + behavior toggles on the left; the primary action
            // (Analyze, with Stop/status while running) pinned to the right edge, where the
            // eye expects a toolbar's main action — the weighted FlowRow absorbs the slack.
            Row(verticalAlignment = Alignment.Top) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    ParamField("Margin %", minMargin, 68.dp) {
                        minMargin = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_MARGIN, it) } }
                    }
                    // Same idea as Tools > Sell Pricing's "Apply margin limit" — caps the assumed
                    // sell price at buyPrice * (1 + this %), so a thin/unreliable destination
                    // market can't inflate the shown profit past what a realistic, cost-based
                    // margin would actually be.
                    CheckboxParamField(
                        label = "Margin Limit %",
                        checked = marginLimitEnabled,
                        onCheckedChange = {
                            marginLimitEnabled = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_MARGIN_LIMIT_ENABLED, it.toString()) } }
                        },
                        value = marginLimitPct,
                        onValueChange = { v ->
                            marginLimitPct = v
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_MARGIN_LIMIT_PCT, v) } }
                        },
                        fieldEnabled = true,
                    )
                    ParamField("ISK/m³", iskPerM3, 88.dp) {
                        iskPerM3 = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_ISK_PER_M3, it) } }
                    }
                    ParamField("Max m³", maxCargoM3, 88.dp) {
                        maxCargoM3 = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_MAX_CARGO, it) } }
                    }
                    ParamField("Min Net", minNetProfit, 108.dp) {
                        minNetProfit = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_MIN_PROFIT, it) } }
                    }
                    FilterDivider()
                    // The % always scales whichever side is currently selected as the volume basis:
                    // the source/buy region's daily volume when checked, the destination/sell
                    // region's when unchecked — so the field stays live either way, not just when
                    // "use source volume" is on.
                    CheckboxParamField(
                        label = if (volCapEnabled) "Src vol %" else "Dst vol %",
                        checked = volCapEnabled,
                        onCheckedChange = {
                            volCapEnabled = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_VOL_CAP_ENABLED, it.toString()) } }
                        },
                        value = volCapPct,
                        onValueChange = { v ->
                            volCapPct = v
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_VOL_CAP_PCT, v) } }
                        },
                        fieldEnabled = true,
                    )
                    FilterControl("Skip Orders") {
                        Checkbox(
                            checked = skipExistingOrders,
                            onCheckedChange = {
                                skipExistingOrders = it
                                scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_SKIP_EXISTING, it.toString()) } }
                            },
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    FilterDivider()
                    // Toggles whether the hotkey's second press copies the suggested volume, or just
                    // advances straight to the next item after copying the price.
                    FilterControl("Copy Vol") {
                        Switch(
                            checked = copyVolumeEnabled,
                            onCheckedChange = {
                                copyVolumeEnabled = it
                                scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_COPY_VOLUME, it.toString()) } }
                            },
                            modifier = Modifier.height(FilterFieldHeight),
                        )
                    }
                }
                if (statusMsg.isNotEmpty()) {
                    FilterActionSlot {
                        Text(
                            statusMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if ("Error" in statusMsg || "differ" in statusMsg) {
                                    Color(0xFFFF6B6B)
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                },
                            modifier = Modifier.height(FilterFieldHeight).wrapContentHeight(Alignment.CenterVertically),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
                if (isAnalyzing) {
                    FilterActionSlot {
                        OutlinedButton(
                            onClick = { analyzeJob?.cancel() },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            modifier = Modifier.height(FilterFieldHeight),
                        ) {
                            Icon(Icons.Default.Stop, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stop")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                FilterActionSlot {
                    Button(
                        onClick = {
                            if (buyRegionId == sellRegionId) {
                                statusMsg = "Regions must differ"
                                return@Button
                            }
                            val job =
                                scope.launch {
                                    isAnalyzing = true
                                    results = emptyList()
                                    val buyName = allRegions.find { it.regionId == buyRegionId }?.name ?: "buy"
                                    val sellName = allRegions.find { it.regionId == sellRegionId }?.name ?: "sell"
                                    val filterGroupId = selectedSubGroup?.marketGroupId ?: selectedTopGroup?.marketGroupId
                                    val filterGroupIds = filterGroupId?.let { withContext(Dispatchers.IO) { buildGroupSubtree(it) } }
                                    val iskPerM3D = iskPerM3.toDoubleOrNull() ?: 1000.0
                                    // Empty = no cap (matches Max Buy's convention in Station
                                    // Trading), not a silent revert to a nonzero 10000 m³ cap.
                                    val maxCargoM3D = maxCargoM3.toDoubleOrNull() ?: Double.MAX_VALUE
                                    val minMarginD = minMargin.toDoubleOrNull() ?: 0.0
                                    val minNetD = minNetProfit.toDoubleOrNull() ?: 0.0
                                    val marginLimitD = marginLimitPct.toDoubleOrNull() ?: 0.0
                                    val brokerFeeD = brokerFeePct
                                    val salesTaxD = salesTaxPct
                                    val buyStSnap = buyStationId
                                    val sellStSnap = sellStationId
                                    val histSrc = withContext(Dispatchers.IO) { EveRefService.getSelectedSource() }
                                    try {
                                        // Same citadel/jump-range reachability as Station Trading, built once up
                                        // front for whichever side(s) have a specific station chosen.
                                        val buySystemId =
                                            buyStSnap?.let {
                                                withContext(Dispatchers.IO) { StaticDataDao.getStationById(it)?.systemId }
                                            }
                                        val sellSystemId =
                                            sellStSnap?.let {
                                                withContext(Dispatchers.IO) { StaticDataDao.getStationById(it)?.systemId }
                                            }
                                        val buyDistances: Map<Int, Int> =
                                            if (buySystemId != null) {
                                                withContext(Dispatchers.IO) {
                                                    JumpGraphService.ensureRegionGraph(buyRegionId) { p ->
                                                        statusMsg = "Building buy-region jump graph: ${p.fetched}/${p.total}…"
                                                    }
                                                    JumpGraphService.bfsDistances(buySystemId, buyRegionId)
                                                }
                                            } else {
                                                emptyMap()
                                            }
                                        val sellDistances: Map<Int, Int> =
                                            if (sellSystemId != null) {
                                                withContext(Dispatchers.IO) {
                                                    JumpGraphService.ensureRegionGraph(sellRegionId) { p ->
                                                        statusMsg = "Building sell-region jump graph: ${p.fetched}/${p.total}…"
                                                    }
                                                    JumpGraphService.bfsDistances(sellSystemId, sellRegionId)
                                                }
                                            } else {
                                                emptyMap()
                                            }
                                        val locationSystemCache = java.util.concurrent.ConcurrentHashMap<Long, Int?>()

                                        val allTypeIds =
                                            withContext(Dispatchers.IO) {
                                                if (filterGroupIds != null) {
                                                    StaticDataDao.getTypeIdsByMarketGroups(filterGroupIds)
                                                } else {
                                                    StaticDataDao.getAllMarketTypeIds()
                                                }
                                            }
                                        // Region-scoped, not character-scoped, on EITHER leg: excludes
                                        // a type if any locally-known character/corp already has an
                                        // active order for it in the buy region or the sell region.
                                        val typeIds =
                                            if (skipExistingOrders) {
                                                val excluded =
                                                    withContext(Dispatchers.IO) {
                                                        ActiveOrderDao
                                                            .getAll()
                                                            .filter { it.regionId == buyRegionId || it.regionId == sellRegionId }
                                                            .map { it.typeId }
                                                            .toSet()
                                                    }
                                                allTypeIds.filter { it !in excluded }
                                            } else {
                                                allTypeIds
                                            }
                                        // Same bulk cutover as Station Trading: past the threshold two
                                        // paginated all-orders fetches (one per region) beat two HTTP
                                        // requests per type.
                                        val bulk: Pair<Map<Int, List<Map<String, Any?>>>, Map<Int, List<Map<String, Any?>>>>? =
                                            if (filterGroupIds == null || typeIds.size > BULK_ORDER_FETCH_THRESHOLD) {
                                                statusMsg = "Fetching all $buyName orders…"
                                                val buyAll = withContext(Dispatchers.IO) { EsiClient.getMarketRegionOrders(buyRegionId) }
                                                statusMsg = "Fetching all $sellName orders…"
                                                val sellAll = withContext(Dispatchers.IO) { EsiClient.getMarketRegionOrders(sellRegionId) }

                                                fun List<Map<String, Any?>>.byType() = groupBy { (it["type_id"] as? Number)?.toInt() ?: 0 }
                                                buyAll.byType() to sellAll.byType()
                                            } else {
                                                null
                                            }

                                        statusMsg = "0/${typeIds.size} types…"

                                        // Each permit fires 2 real HTTP requests (buy + sell region
                                        // orders), so this is already up to 8 concurrent ESI calls —
                                        // higher than it looks. Lower than Station Trading's single-
                                        // call-per-permit Semaphore(10) for the same reason.
                                        val semaphore = Semaphore(4)
                                        val mutex = Mutex()
                                        val found = mutableListOf<RegionOpportunity>()
                                        var checked = 0

                                        coroutineScope {
                                            typeIds
                                                .map { typeId ->
                                                    async(Dispatchers.IO) {
                                                        semaphore.withPermit {
                                                            runCatching {
                                                                // Both regions for this type fetched simultaneously
                                                                val (buyOrders, sellOrders) =
                                                                    if (bulk != null) {
                                                                        bulk.first[typeId].orEmpty() to bulk.second[typeId].orEmpty()
                                                                    } else {
                                                                        coroutineScope {
                                                                            val bDef =
                                                                                async {
                                                                                    EsiClient.getMarketRegionOrders(
                                                                                        buyRegionId,
                                                                                        typeId = typeId,
                                                                                    )
                                                                                }
                                                                            val sDef =
                                                                                async {
                                                                                    EsiClient.getMarketRegionOrders(
                                                                                        sellRegionId,
                                                                                        typeId = typeId,
                                                                                    )
                                                                                }
                                                                            bDef.await() to sDef.await()
                                                                        }
                                                                    }
                                                                val opp =
                                                                    computeRegionOpportunityForType(
                                                                        typeId,
                                                                        buyOrders,
                                                                        sellOrders,
                                                                        buyRegionId,
                                                                        sellRegionId,
                                                                        buyName,
                                                                        sellName,
                                                                        tradeType,
                                                                        filterGroupIds,
                                                                        iskPerM3D,
                                                                        maxCargoM3D,
                                                                        minMarginD,
                                                                        minNetD,
                                                                        brokerFeeD,
                                                                        salesTaxD,
                                                                        buyStSnap,
                                                                        sellStSnap,
                                                                        histSrc,
                                                                        marginLimitEnabled = marginLimitEnabled,
                                                                        marginLimitPct = marginLimitD,
                                                                        buyStationSystemId = buySystemId,
                                                                        buyDistanceFromStation = buyDistances,
                                                                        sellStationSystemId = sellSystemId,
                                                                        sellDistanceFromStation = sellDistances,
                                                                        locationSystemCache = locationSystemCache,
                                                                    )
                                                                val (sorted, c, f) =
                                                                    mutex.withLock {
                                                                        checked++
                                                                        if (opp != null) found.add(opp)
                                                                        Triple(
                                                                            if (opp !=
                                                                                null
                                                                            ) {
                                                                                found.sortedByDescending { it.netProfit }
                                                                            } else {
                                                                                null
                                                                            },
                                                                            checked,
                                                                            found.size,
                                                                        )
                                                                    }
                                                                withContext(Dispatchers.Main) {
                                                                    if (sorted != null) results = sorted
                                                                    statusMsg = "$c/${typeIds.size} checked, $f found"
                                                                }
                                                            }
                                                        }
                                                    }
                                                }.awaitAll()
                                        }
                                        statusMsg = "${found.size} opportunities found"
                                    } catch (e: CancellationException) {
                                        statusMsg = "Stopped — ${results.size} opportunities found so far"
                                        throw e
                                    } catch (e: Exception) {
                                        statusMsg = "Error: ${e.message}"
                                    } finally {
                                        isAnalyzing = false
                                    }
                                }
                            analyzeJob = job
                        },
                        enabled = !isAnalyzing,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(FilterFieldHeight),
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.AutoMirrored.Filled.CompareArrows, null, Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (isAnalyzing) "Analyzing…" else "Analyze")
                    }
                }
            }
            if (selectedTopGroup == null && histSourceIsEsi) {
                EveRefHint()
            }
        }

        if (isAnalyzing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        // ── Results ────────────────────────────────────────────────
        if (results.isEmpty() && !isAnalyzing) {
            AnalysisEmptyState(
                icon = Icons.AutoMirrored.Filled.CompareArrows,
                primary = "Select regions and click Analyze",
                secondary = "Finds items priced low in the buy region that sell for more in the sell region",
            )
        } else {
            // Not capped at 100 — this scales volume in either direction (50 halves it, 200 doubles it).
            val volCapPctVal = volCapPct.toDoubleOrNull()?.coerceIn(0.01, 1000.0) ?: 10.0
            // Live-reactive (not just at Analyze time) — matches volCapPctVal's own convention, so
            // adjusting Max m³ after a run re-caps quantities immediately without re-analyzing.
            val maxCargoM3Val = maxCargoM3.toDoubleOrNull() ?: Double.MAX_VALUE
            val sorted =
                remember(results, tradeType, sortCol, sortAsc, volCapEnabled, volCapPctVal, maxCargoM3Val) {
                    sortRegion(results, tradeType, sortCol, sortAsc, volCapEnabled, volCapPctVal, maxCargoM3Val)
                }
            var selectedIds by remember(results) { mutableStateOf(setOf<Int>()) }
            val listState = rememberLazyListState()
            var dragStartIdx by remember { mutableStateOf<Int?>(null) }
            var isDragging by remember { mutableStateOf(false) }
            val activeTypeId by InterRegionQueue.currentTypeId.collectAsState()

            // Keep the hotkey queue in sync with what's on screen — the selected subset if the
            // user has picked specific items, otherwise every currently sorted/filtered opportunity.
            LaunchedEffect(charId, sorted, selectedIds, tradeType, copyVolumeEnabled) {
                InterRegionQueue.copyVolume = copyVolumeEnabled
                val cid = charId
                if (cid == null) {
                    InterRegionQueue.clear()
                } else {
                    // SAFE_BUY_TO_SELL also places a buy order, but its price is a fixed formula
                    // (source sell price net of fees), not a bid against another buy order — so
                    // unlike BUY_TO_BUY/BUY_TO_SELL it must NOT get bumped up to outbid anyone.
                    val isCompetitiveBid = tradeType == InterRegionTradeType.BUY_TO_BUY || tradeType == InterRegionTradeType.BUY_TO_SELL
                    val source = if (selectedIds.isNotEmpty()) sorted.filter { it.typeId in selectedIds } else sorted
                    InterRegionQueue.update(
                        source.map { opp ->
                            PendingRegionItem(
                                charId = cid,
                                typeId = opp.typeId,
                                typeName = opp.typeName,
                                price = opp.buyPrice,
                                isCompetitiveBid = isCompetitiveBid,
                                volume = regionFinalVol(opp, tradeType, volCapEnabled, volCapPctVal, maxCargoM3Val),
                            )
                        },
                    )
                }
            }

            // The Ctrl+Z hotkey cycles activeTypeId through the list, but the highlighted row
            // moves independently of scroll position — without this, cycling can walk the active
            // row off-screen with no visual indication of where it went.
            LaunchedEffect(activeTypeId, sorted) {
                val idx = sorted.indexOfFirst { it.typeId == activeTypeId }
                if (idx >= 0) listState.ensureVisible(idx)
            }

            RegionHeader(sortCol, sortAsc) { col ->
                if (sortCol == col) {
                    sortAsc = !sortAsc
                } else {
                    sortCol = col
                    sortAsc = false
                }
            }
            if (charId != null && InterRegionQueue.size > 0) {
                val hotkeyLabel by HotkeyBindings.queueLabel.collectAsState()
                Text(
                    "$hotkeyLabel cycles ${InterRegionQueue.size} item(s) — position " +
                        "${InterRegionQueue.currentPosition}/${InterRegionQueue.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
            if (selectedIds.isNotEmpty()) {
                SelectionBar(
                    count = selectedIds.size,
                    onCopy = {
                        val text =
                            sorted
                                .filter { it.typeId in selectedIds }
                                .joinToString(
                                    "\n",
                                ) { opp ->
                                    "${opp.typeName}\t${regionFinalVol(
                                        opp,
                                        tradeType,
                                        volCapEnabled,
                                        volCapPctVal,
                                        maxCargoM3Val,
                                    )}"
                                }
                        copyToClipboard(text)
                    },
                    onClear = { selectedIds = emptySet() },
                )
            }
            @OptIn(ExperimentalComposeUiApi::class)
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Press) { e ->
                            dragStartIdx =
                                itemIndexAt(
                                    e.changes
                                        .first()
                                        .position.y,
                                    listState,
                                )
                            isDragging = false
                        }.onPointerEvent(PointerEventType.Move) { e ->
                            val start = dragStartIdx ?: return@onPointerEvent
                            if (!e.changes.first().pressed) {
                                dragStartIdx = null
                                return@onPointerEvent
                            }
                            val cur =
                                itemIndexAt(
                                    e.changes
                                        .first()
                                        .position.y,
                                    listState,
                                ) ?: return@onPointerEvent
                            if (cur != start || isDragging) {
                                isDragging = true
                                val lo = minOf(start, cur)
                                val hi = maxOf(start, cur)
                                selectedIds = sorted.subList(lo, minOf(hi + 1, sorted.size)).map { it.typeId }.toSet()
                            }
                        }.onPointerEvent(PointerEventType.Release) { _ ->
                            if (!isDragging) {
                                val idx = dragStartIdx
                                if (idx != null) {
                                    val id = sorted.getOrNull(idx)?.typeId
                                    if (id != null) selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                                }
                            }
                            dragStartIdx = null
                            isDragging = false
                        },
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(sorted, key = { _, item -> item.typeId }) { idx, opp ->
                        RegionRow(
                            opp,
                            idx,
                            tradeType,
                            opp.typeId in selectedIds,
                            volCapEnabled,
                            volCapPctVal,
                            opp.typeId == activeTypeId,
                            maxCargoM3Val,
                        )
                    }
                }
            }
        }
    }
}
