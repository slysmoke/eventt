package org.eventt.features.market

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import org.eventt.ui.theme.negativeColor
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
    // Alternative to the m3-based shipping estimate above: price the haul as a percentage of the
    // cargo's value instead — a courier/freight-contract convention, and more realistic for
    // expensive, low-volume items (ships, blueprints) where m3-based shipping badly understates
    // the real cost/risk.
    var shippingByCostEnabled by remember { mutableStateOf(false) }
    var shippingCostPct by remember { mutableStateOf("2") }
    // Excludes an item entirely if a single unit alone exceeds this (too bulky to haul at all) —
    // not a total-cargo-hold cap on suggested quantity, see regionFinalVol's own doc comment.
    var maxCargoM3 by remember { mutableStateOf("50000") }
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
    var spikeFilter by remember { mutableStateOf(SpikeFilter.ANY) }
    var spikePriceMultiplier by remember { mutableStateOf("1.5") }
    var spikeVolumeMultiplier by remember { mutableStateOf("5") }
    var spikeWindowDays by remember { mutableStateOf("30") }
    var histSourceIsEsi by remember { mutableStateOf(false) }
    var routePresets by remember { mutableStateOf<List<RoutePreset>>(emptyList()) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var detailTypeId by remember { mutableStateOf<Int?>(null) }
    // The order books behind the last completed Analyze run, kept around so a pure-arithmetic
    // filter change (margin/ISK per m3/etc.) can recompute results locally instead of re-fetching.
    var cachedAnalysis by remember { mutableStateOf<CachedAnalysisInput?>(null) }

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
            S.get(S.IR_SHIP_BY_COST_ENABLED)?.let { shippingByCostEnabled = it == "true" }
            S.get(S.IR_SHIP_COST_PCT)?.let { shippingCostPct = it }
            S.get(S.IR_MAX_CARGO)?.let { maxCargoM3 = it }
            S.get(S.IR_MIN_PROFIT)?.let { minNetProfit = it }
            S.get(S.IR_VOL_CAP_ENABLED)?.let { volCapEnabled = it == "true" }
            S.get(S.IR_VOL_CAP_PCT)?.let { volCapPct = it }
            S.get(S.IR_COPY_VOLUME)?.let { copyVolumeEnabled = it == "true" }
            S.get(S.IR_SKIP_EXISTING)?.let { skipExistingOrders = it == "true" }
            S.get(S.IR_SPIKE_FILTER)?.let { name -> SpikeFilter.entries.find { it.name == name }?.let { spikeFilter = it } }
            S.get(S.IR_SPIKE_PRICE_MULTIPLIER)?.let { spikePriceMultiplier = it }
            S.get(S.IR_SPIKE_VOLUME_MULTIPLIER)?.let { spikeVolumeMultiplier = it }
            S.get(S.IR_SPIKE_WINDOW_DAYS)?.let { spikeWindowDays = it }
            routePresets = decodeRoutePresets(S.get(S.IR_PRESETS))
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

    fun persistRoute(
        buyRegion: Int,
        buyStation: Long?,
        sellRegion: Int,
        sellStation: Long?,
    ) {
        scope.launch {
            withContext(Dispatchers.IO) {
                S.set(S.IR_BUY_REGION, buyRegion.toString())
                S.set(S.IR_BUY_STATION, buyStation?.toString() ?: "")
                S.set(S.IR_SELL_REGION, sellRegion.toString())
                S.set(S.IR_SELL_STATION, sellStation?.toString() ?: "")
            }
        }
    }

    fun swapRoute() {
        val oldBuyRegion = buyRegionId
        val oldBuyStation = buyStationId
        buyRegionId = sellRegionId
        buyStationId = sellStationId
        sellRegionId = oldBuyRegion
        sellStationId = oldBuyStation
        persistRoute(buyRegionId, buyStationId, sellRegionId, sellStationId)
    }

    fun applyPreset(preset: RoutePreset) {
        buyRegionId = preset.buyRegionId
        buyStationId = preset.buyStationId
        sellRegionId = preset.sellRegionId
        sellStationId = preset.sellStationId
        persistRoute(preset.buyRegionId, preset.buyStationId, preset.sellRegionId, preset.sellStationId)
    }

    fun saveCurrentAsPreset(name: String) {
        val updated = routePresets.filter { it.name != name } + RoutePreset(name, buyRegionId, buyStationId, sellRegionId, sellStationId)
        routePresets = updated
        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_PRESETS, encodeRoutePresets(updated)) } }
    }

    fun deletePreset(name: String) {
        val updated = routePresets.filter { it.name != name }
        routePresets = updated
        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_PRESETS, encodeRoutePresets(updated)) } }
    }

    fun recomputeFromCache() {
        val cache = cachedAnalysis ?: return
        val maxCargoM3D = maxCargoM3.toDoubleOrNull() ?: Double.MAX_VALUE
        val minMarginD = minMargin.toDoubleOrNull() ?: 0.0
        val minNetD = minNetProfit.toDoubleOrNull() ?: 0.0
        val marginLimitD = marginLimitPct.toDoubleOrNull() ?: 0.0
        val iskPerM3D = iskPerM3.toDoubleOrNull() ?: 1000.0
        val shippingCostPctD = shippingCostPct.toDoubleOrNull() ?: 0.0
        val spikePriceMultiplierD = spikePriceMultiplier.toDoubleOrNull() ?: 1.5
        val spikeVolumeMultiplierD = spikeVolumeMultiplier.toDoubleOrNull() ?: 5.0
        val spikeWindowDaysD = spikeWindowDays.toIntOrNull() ?: 30
        scope.launch(Dispatchers.Default) {
            val locationSystemCache = java.util.concurrent.ConcurrentHashMap<Long, Int?>()
            val recomputed =
                cache.typeIds
                    .mapNotNull { typeId ->
                        computeRegionOpportunityForType(
                            typeId,
                            cache.buyByType[typeId].orEmpty(),
                            cache.sellByType[typeId].orEmpty(),
                            cache.buyRegionId,
                            cache.sellRegionId,
                            cache.buyRegionName,
                            cache.sellRegionName,
                            cache.tradeType,
                            cache.filterGroupIds,
                            iskPerM3D,
                            maxCargoM3D,
                            minMarginD,
                            minNetD,
                            cache.brokerFeePct,
                            cache.salesTaxPct,
                            cache.buyStationId,
                            cache.sellStationId,
                            cache.histSource,
                            marginLimitEnabled = marginLimitEnabled,
                            marginLimitPct = marginLimitD,
                            buyStationSystemId = cache.buyStationSystemId,
                            buyDistanceFromStation = cache.buyDistanceFromStation,
                            sellStationSystemId = cache.sellStationSystemId,
                            sellDistanceFromStation = cache.sellDistanceFromStation,
                            locationSystemCache = locationSystemCache,
                            shippingByCostEnabled = shippingByCostEnabled,
                            shippingCostPct = shippingCostPctD,
                            spikeFilter = spikeFilter,
                            spikePriceMultiplier = spikePriceMultiplierD,
                            spikeVolumeMultiplier = spikeVolumeMultiplierD,
                            spikeWindowDays = spikeWindowDaysD,
                        )
                    }.sortedByDescending { it.netProfit }
            withContext(Dispatchers.Main) {
                results = recomputed
                statusMsg = "${recomputed.size} opportunities found (recalculated)"
            }
        }
    }

    // Re-derives results from the already-fetched order books whenever a purely arithmetic filter
    // changes -- none of these affect which orders exist, only which of the already-cached ones
    // qualify or what profit they'd show, so no new ESI calls are needed. Debounced so typing in a
    // field doesn't recompute on every keystroke.
    LaunchedEffect(
        minMargin,
        minNetProfit,
        marginLimitEnabled,
        marginLimitPct,
        iskPerM3,
        maxCargoM3,
        shippingByCostEnabled,
        shippingCostPct,
        spikeFilter,
        spikePriceMultiplier,
        spikeVolumeMultiplier,
        spikeWindowDays,
    ) {
        if (cachedAnalysis == null || isAnalyzing) return@LaunchedEffect
        delay(400)
        recomputeFromCache()
    }

    if (showSavePresetDialog) {
        SavePresetDialog(
            onDismiss = { showSavePresetDialog = false },
            onSave = { name ->
                saveCurrentAsPreset(name)
                showSavePresetDialog = false
            },
        )
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
                RouteArrow(onSwap = ::swapRoute)
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
                PresetPicker(
                    presets = routePresets,
                    onApply = ::applyPreset,
                    onDelete = ::deletePreset,
                    onSaveCurrent = { showSavePresetDialog = true },
                )
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
                    ParamField("Min Margin %", minMargin, 68.dp) {
                        minMargin = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_MARGIN, it) } }
                    }
                    // Same idea as Tools > Sell Pricing's "Apply margin limit" — caps the assumed
                    // sell price at buyPrice * (1 + this %), so a thin/unreliable destination
                    // market can't inflate the shown profit past what a realistic, cost-based
                    // margin would actually be.
                    CheckboxParamField(
                        label = "Max Margin %",
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
                    ParamField("ISK/m³", iskPerM3, 88.dp, enabled = !shippingByCostEnabled) {
                        iskPerM3 = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_ISK_PER_M3, it) } }
                    }
                    // Alternative to ISK/m³: prices shipping as a % of the cargo's value instead of
                    // its bulk -- a courier/freight-contract convention, more realistic for
                    // expensive/low-volume items where m³-based shipping badly understates cost.
                    CheckboxParamField(
                        label = "Ship % of Cost",
                        checked = shippingByCostEnabled,
                        onCheckedChange = {
                            shippingByCostEnabled = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_SHIP_BY_COST_ENABLED, it.toString()) } }
                        },
                        value = shippingCostPct,
                        onValueChange = { v ->
                            shippingCostPct = v
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_SHIP_COST_PCT, v) } }
                        },
                        fieldEnabled = true,
                    )
                    // Excludes an item entirely once a single unit alone exceeds this -- too bulky
                    // to haul at all, not a total-cargo-hold cap on suggested quantity (that would
                    // silently understate "Qty to Buy" regardless of Dst/Src vol %).
                    ParamField("Max m³/item", maxCargoM3, 88.dp) {
                        maxCargoM3 = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_MAX_CARGO, it) } }
                    }
                    // Filters by total profit potential (net/unit x achievable volume), not the
                    // per-unit figure -- see the totalProfit comment in computeRegionOpportunityForType.
                    ParamField("Min Total Profit", minNetProfit, 108.dp) {
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
                    FilterControl("Skip Owned Items") {
                        Checkbox(
                            checked = skipExistingOrders,
                            onCheckedChange = {
                                skipExistingOrders = it
                                scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_SKIP_EXISTING, it.toString()) } }
                            },
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    SpikeFilterChip(spikeFilter) {
                        spikeFilter = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_SPIKE_FILTER, it.name) } }
                    }
                    ParamField("Price ×", spikePriceMultiplier, 50.dp, enabled = spikeFilter != SpikeFilter.ANY) {
                        spikePriceMultiplier = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_SPIKE_PRICE_MULTIPLIER, it) } }
                    }
                    ParamField("Volume ×", spikeVolumeMultiplier, 50.dp, enabled = spikeFilter != SpikeFilter.ANY) {
                        spikeVolumeMultiplier = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_SPIKE_VOLUME_MULTIPLIER, it) } }
                    }
                    ParamField("Spike Days", spikeWindowDays, 60.dp, enabled = spikeFilter != SpikeFilter.ANY) {
                        spikeWindowDays = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.IR_SPIKE_WINDOW_DAYS, it) } }
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
                                    negativeColor
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
                                    val shippingCostPctD = shippingCostPct.toDoubleOrNull() ?: 0.0
                                    // Empty = no cap (matches Max Buy's convention in Station
                                    // Trading), not a silent revert to a nonzero 50000 m³ cap.
                                    val maxCargoM3D = maxCargoM3.toDoubleOrNull() ?: Double.MAX_VALUE
                                    val minMarginD = minMargin.toDoubleOrNull() ?: 0.0
                                    val minNetD = minNetProfit.toDoubleOrNull() ?: 0.0
                                    val marginLimitD = marginLimitPct.toDoubleOrNull() ?: 0.0
                                    val brokerFeeD = brokerFeePct
                                    val salesTaxD = salesTaxPct
                                    val buyStSnap = buyStationId
                                    val sellStSnap = sellStationId
                                    val spikeFilterSnap = spikeFilter
                                    val spikePriceMultiplierSnap = spikePriceMultiplier.toDoubleOrNull() ?: 1.5
                                    val spikeVolumeMultiplierSnap = spikeVolumeMultiplier.toDoubleOrNull() ?: 5.0
                                    val spikeWindowDaysSnap = spikeWindowDays.toIntOrNull() ?: 30
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
                                        // Order books behind whichever path fetches them below,
                                        // retained after this run completes so a later filter-only
                                        // change (margin/ISK per m3/etc.) can recompute locally
                                        // instead of re-fetching — see recomputeFromCache.
                                        val buyByType = java.util.concurrent.ConcurrentHashMap<Int, List<Map<String, Any?>>>()
                                        val sellByType = java.util.concurrent.ConcurrentHashMap<Int, List<Map<String, Any?>>>()

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
                                                val b = buyAll.byType()
                                                val s = sellAll.byType()
                                                buyByType.putAll(b)
                                                sellByType.putAll(s)
                                                b to s
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
                                                                            val b = bDef.await()
                                                                            val s = sDef.await()
                                                                            buyByType[typeId] = b
                                                                            sellByType[typeId] = s
                                                                            b to s
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
                                                                        shippingByCostEnabled = shippingByCostEnabled,
                                                                        shippingCostPct = shippingCostPctD,
                                                                        spikeFilter = spikeFilterSnap,
                                                                        spikePriceMultiplier = spikePriceMultiplierSnap,
                                                                        spikeVolumeMultiplier = spikeVolumeMultiplierSnap,
                                                                        spikeWindowDays = spikeWindowDaysSnap,
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
                                        cachedAnalysis =
                                            CachedAnalysisInput(
                                                typeIds = typeIds,
                                                buyByType = buyByType.toMap(),
                                                sellByType = sellByType.toMap(),
                                                buyRegionId = buyRegionId,
                                                sellRegionId = sellRegionId,
                                                buyRegionName = buyName,
                                                sellRegionName = sellName,
                                                tradeType = tradeType,
                                                filterGroupIds = filterGroupIds,
                                                brokerFeePct = brokerFeeD,
                                                salesTaxPct = salesTaxD,
                                                buyStationId = buyStSnap,
                                                sellStationId = sellStSnap,
                                                histSource = histSrc,
                                                buyStationSystemId = buySystemId,
                                                buyDistanceFromStation = buyDistances,
                                                sellStationSystemId = sellSystemId,
                                                sellDistanceFromStation = sellDistances,
                                            )
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
            val sorted =
                remember(results, tradeType, sortCol, sortAsc, volCapEnabled, volCapPctVal) {
                    sortRegion(results, tradeType, sortCol, sortAsc, volCapEnabled, volCapPctVal)
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
                                volume = regionFinalVol(opp, tradeType, volCapEnabled, volCapPctVal),
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
                                    "${opp.typeName}\t${regionFinalVol(opp, tradeType, volCapEnabled, volCapPctVal)}"
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
                            onShowDetails = { detailTypeId = it },
                        )
                    }
                }
            }
        }
    }

    detailTypeId?.let { id ->
        val opp = results.find { it.typeId == id }
        ItemDetailDialog(
            typeId = id,
            typeName = opp?.typeName ?: "",
            primaryRegionId = buyRegionId,
            primaryRegionName = opp?.buyRegionName ?: "",
            primaryStationId = buyStationId,
            secondaryRegionId = sellRegionId,
            secondaryRegionName = opp?.sellRegionName,
            secondaryStationId = sellStationId,
            charId = charId,
            onDismiss = { detailTypeId = null },
        )
    }
}

// ─── Cached order books, for recomputing results without a re-fetch ───────────────────────
// (see cachedAnalysis / recomputeFromCache in InterRegionTab)

private data class CachedAnalysisInput(
    val typeIds: List<Int>,
    val buyByType: Map<Int, List<Map<String, Any?>>>,
    val sellByType: Map<Int, List<Map<String, Any?>>>,
    val buyRegionId: Int,
    val sellRegionId: Int,
    val buyRegionName: String,
    val sellRegionName: String,
    val tradeType: InterRegionTradeType,
    val filterGroupIds: Set<Int>?,
    val brokerFeePct: Double,
    val salesTaxPct: Double,
    val buyStationId: Long?,
    val sellStationId: Long?,
    val histSource: String,
    val buyStationSystemId: Int?,
    val buyDistanceFromStation: Map<Int, Int>,
    val sellStationSystemId: Int?,
    val sellDistanceFromStation: Map<Int, Int>,
)

// ─── Saved routes (buy region/station + sell region/station), for repeat trade lanes ──────

internal data class RoutePreset(
    val name: String,
    val buyRegionId: Int,
    val buyStationId: Long?,
    val sellRegionId: Int,
    val sellStationId: Long?,
)

// One preset per line, fields pipe-separated -- a handful of small numeric ids plus a short
// user-typed name, so a delimited string is plenty; '|'/newline are stripped from the name to
// keep parsing unambiguous rather than pulling in a JSON dependency for five fields.
private fun encodeRoutePresets(presets: List<RoutePreset>): String =
    presets.joinToString("\n") { p ->
        listOf(p.name.replace('|', ' ').replace('\n', ' '), p.buyRegionId, p.buyStationId ?: "", p.sellRegionId, p.sellStationId ?: "")
            .joinToString("|")
    }

private fun decodeRoutePresets(raw: String?): List<RoutePreset> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.lines().mapNotNull { line ->
        val parts = line.split("|")
        if (parts.size != 5) return@mapNotNull null
        RoutePreset(
            name = parts[0],
            buyRegionId = parts[1].toIntOrNull() ?: return@mapNotNull null,
            buyStationId = parts[2].toLongOrNull(),
            sellRegionId = parts[3].toIntOrNull() ?: return@mapNotNull null,
            sellStationId = parts[4].toLongOrNull(),
        )
    }
}

@Composable
private fun PresetPicker(
    presets: List<RoutePreset>,
    onApply: (RoutePreset) -> Unit,
    onDelete: (String) -> Unit,
    onSaveCurrent: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    FilterControl("Presets") {
        Box {
            ChipSurface(onClick = { expanded = true }, width = 130.dp) {
                Icon(Icons.Default.Bookmark, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (presets.isEmpty()) "None saved" else "${presets.size} saved",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (presets.isEmpty()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else Color.Unspecified,
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
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.width(240.dp)) {
                DropdownMenuItem(
                    text = { Text("Save current route…", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(14.dp)) },
                    onClick = {
                        expanded = false
                        onSaveCurrent()
                    },
                )
                if (presets.isNotEmpty()) {
                    HorizontalDivider()
                    presets.forEach { preset ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    preset.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                onApply(preset)
                                expanded = false
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Delete preset",
                                    modifier = Modifier.size(14.dp).clickable { onDelete(preset.name) },
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavePresetDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Route Preset") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("e.g. Jita → Amarr") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
