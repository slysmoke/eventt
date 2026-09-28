package org.eventt.features.market

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.eventt.core.database.ActiveOrderDao
import org.eventt.core.database.AlertDao
import org.eventt.core.database.AssetDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.WalletDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.everef.EveRefService
import org.eventt.core.model.ALERT_CATEGORY_INVESTMENT
import org.eventt.core.model.HotkeyBindings
import org.eventt.core.model.PLEX_MARKET_REGION_ID
import org.eventt.core.model.PLEX_TYPE_ID
import org.eventt.core.model.PriceAlertModel
import org.eventt.core.model.StaticMarketGroupModel
import org.eventt.core.model.StaticRegionModel
import org.eventt.core.model.eveSigFigStep
import org.eventt.core.model.stringBlocking
import org.eventt.market.generated.resources.*
import org.eventt.ui.common.Tip
import org.eventt.ui.common.formatPriceAbbr
import org.eventt.ui.common.formatVolume
import org.eventt.ui.common.onRightClick
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

/**
 * Long-term dip-buying / DCA analysis for a market group -- defaults to Manufacture & Research >
 * Materials (minerals, ice products, moon/PI materials). See MaterialsInvestmentCompute.kt for
 * the rationale on why that group specifically suits this strategy. Unlike the other two tabs
 * this doesn't find something to flip today -- it ranks items by how far below their own recent
 * average price they currently sit, then splits a stated ISK budget across the most-discounted
 * ones with a per-item buy ladder (small tranche now, bigger tranches at lower trigger prices) so
 * a continued slide gets bought into rather than chased.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MaterialsInvestmentTab(
    allRegions: List<StaticRegionModel>,
    topGroups: List<StaticMarketGroupModel>,
    charId: Int?,
) {
    val scope = rememberCoroutineScope()

    var regionId by remember { mutableStateOf(10000002) }
    var selectedTopGroup by remember { mutableStateOf<StaticMarketGroupModel?>(null) }
    var selectedSubGroup by remember { mutableStateOf<StaticMarketGroupModel?>(null) }
    var subGroups by remember { mutableStateOf<List<StaticMarketGroupModel>>(emptyList()) }
    var lookbackDays by remember { mutableStateOf("90") }
    var minDailyVol by remember { mutableStateOf("50") }
    var minDiscountPct by remember { mutableStateOf("5") }
    var maxVolatilityPct by remember { mutableStateOf("0") }
    var minVolatilityPct by remember { mutableStateOf("0") }
    var spikeFilter by remember { mutableStateOf(SpikeFilter.ANY) }
    var spikePriceMultiplier by remember { mutableStateOf("1.8") }
    var spikeVolumeMultiplier by remember { mutableStateOf("5") }
    var totalBudget by remember { mutableStateOf("100000000") }
    var maxItems by remember { mutableStateOf("10") }
    var maxPerItemPct by remember { mutableStateOf("25") }
    var liquidityDays by remember { mutableStateOf("3") }
    var ladderLevels by remember { mutableStateOf("4") }
    var ladderStepPct by remember { mutableStateOf("5") }
    var excludeStructuralBreak by remember { mutableStateOf(true) }
    var copyVolumeEnabled by remember { mutableStateOf(true) }
    var selectedTypeIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var analyzeJob by remember { mutableStateOf<Job?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<MaterialCandidate>>(emptyList()) }
    // Selected character's sales tax / broker fee (defaults without one), read at Analyze time.
    var fees by remember { mutableStateOf(MaterialFees()) }
    var sortCol by remember { mutableStateOf(MaterialSortCol.ALLOCATED) }
    var sortAsc by remember { mutableStateOf(false) }
    var detailTypeId by remember { mutableStateOf<Int?>(null) }
    var settingsLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            S.get(S.MI_REGION)?.toIntOrNull()?.let { regionId = it }
            S.get(S.MI_LOOKBACK_DAYS)?.let { lookbackDays = it }
            S.get(S.MI_MIN_VOL)?.let { minDailyVol = it }
            S.get(S.MI_MIN_DISCOUNT)?.let { minDiscountPct = it }
            S.get(S.MI_MAX_VOLATILITY)?.let { maxVolatilityPct = it }
            S.get(S.MI_MIN_VOLATILITY)?.let { minVolatilityPct = it }
            S.get(S.MI_SPIKE_FILTER)?.let { name -> SpikeFilter.entries.find { it.name == name }?.let { spikeFilter = it } }
            S.get(S.MI_SPIKE_PRICE_MULTIPLIER)?.let { spikePriceMultiplier = it }
            S.get(S.MI_SPIKE_VOLUME_MULTIPLIER)?.let { spikeVolumeMultiplier = it }
            S.get(S.MI_TOTAL_BUDGET)?.let { totalBudget = it }
            S.get(S.MI_MAX_ITEMS)?.let { maxItems = it }
            S.get(S.MI_MAX_PER_ITEM_PCT)?.let { maxPerItemPct = it }
            S.get(S.MI_LIQUIDITY_DAYS)?.let { liquidityDays = it }
            S.get(S.MI_LADDER_LEVELS)?.let { ladderLevels = it }
            S.get(S.MI_LADDER_STEP_PCT)?.let { ladderStepPct = it }
            S.get(S.MI_EXCLUDE_STRUCTURAL_BREAK)?.let { excludeStructuralBreak = it == "true" }
            S.get(S.MI_COPY_VOLUME)?.let { copyVolumeEnabled = it == "true" }
            settingsLoaded = true
        }
    }

    // Restore category selection after groups load -- on a first-ever run (nothing persisted yet)
    // default to Manufacture & Research > Materials, since that's what this tab is built around.
    LaunchedEffect(topGroups, settingsLoaded) {
        if (topGroups.isEmpty() || !settingsLoaded) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val savedTopId = S.get(S.MI_CAT_TOP)?.toIntOrNull()
            val top =
                if (savedTopId != null) {
                    topGroups.find { it.marketGroupId == savedTopId }
                } else {
                    topGroups.find { it.name.equals("Manufacture & Research", ignoreCase = true) }
                }
            selectedTopGroup = top ?: return@withContext
            val subs = StaticDataDao.getChildMarketGroups(top.marketGroupId)
            subGroups = subs
            val savedSubId = S.get(S.MI_CAT_SUB)?.toIntOrNull()
            selectedSubGroup =
                if (savedSubId != null || savedTopId != null) {
                    subs.find { it.marketGroupId == savedSubId }
                } else {
                    subs.find { it.name.equals("Materials", ignoreCase = true) }
                }
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

    val allocated =
        remember(candidates, totalBudget, maxItems, maxPerItemPct, liquidityDays, ladderLevels, ladderStepPct, fees) {
            allocateBudget(
                candidates = candidates,
                totalBudget = totalBudget.toDoubleOrNull() ?: 0.0,
                maxItems = maxItems.toIntOrNull() ?: 10,
                maxPerItemPct = maxPerItemPct.toDoubleOrNull() ?: 25.0,
                liquidityDays = liquidityDays.toDoubleOrNull() ?: 3.0,
                ladderLevels = ladderLevels.toIntOrNull() ?: 4,
                ladderStepPct = ladderStepPct.toDoubleOrNull() ?: 5.0,
                fees = fees,
            )
        }
    val sorted = remember(allocated, sortCol, sortAsc) { sortMaterials(allocated, sortCol, sortAsc) }

    // Drop stale picks after a re-scan or budget/ladder change drops an item out of `allocated`
    // entirely -- otherwise a checked-but-vanished row would silently keep feeding stale rungs
    // into the hotkey queue below.
    LaunchedEffect(allocated) {
        val stillPresent = allocated.map { it.candidate.typeId }.toSet()
        if (selectedTypeIds.any { it !in stillPresent }) selectedTypeIds = selectedTypeIds intersect stillPresent
    }

    // The hotkey queue: every buy-ladder rung of every checked row, snapped to EVE's price grid --
    // see MaterialsInvestmentQueue. Rebuilt live so toggling a checkbox or Copy Vol takes effect on
    // the very next hotkey press, the same way StationTradingQueue/InterRegionQueue stay in sync.
    val queueItems =
        remember(allocated, selectedTypeIds, charId) {
            allocated
                .filter { it.candidate.typeId in selectedTypeIds }
                .flatMap { alloc ->
                    alloc.ladder.map { level ->
                        val step = eveSigFigStep(level.triggerPrice)
                        val snappedPrice = kotlin.math.round(level.triggerPrice / step) * step
                        PendingMaterialItem(charId, alloc.candidate.typeId, alloc.candidate.typeName, snappedPrice, level.qty)
                    }
                }
        }
    LaunchedEffect(queueItems, copyVolumeEnabled) {
        MaterialsInvestmentQueue.copyVolume = copyVolumeEnabled
        MaterialsInvestmentQueue.update(queueItems)
    }
    val activeQueueTypeId by MaterialsInvestmentQueue.currentTypeId.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        FilterBar {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                RegionPicker(allRegions, regionId, width = 180.dp, accentColor = MaterialTheme.colorScheme.primary) {
                    regionId = it
                    scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_REGION, it.toString()) } }
                }
                FilterDivider()
                GroupDropdown(
                    stringResource(Res.string.category),
                    topGroups,
                    selectedTopGroup,
                    stringResource(Res.string.all_categories),
                    170.dp,
                ) { g ->
                    selectedTopGroup = g
                    selectedSubGroup = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            S.set(S.MI_CAT_TOP, g?.marketGroupId?.toString() ?: "")
                            S.set(S.MI_CAT_SUB, "")
                        }
                    }
                }
                if (subGroups.isNotEmpty()) {
                    GroupDropdown(
                        stringResource(Res.string.subcategory),
                        subGroups,
                        selectedSubGroup,
                        stringResource(Res.string.all_short),
                        140.dp,
                    ) { g ->
                        selectedSubGroup = g
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_CAT_SUB, g?.marketGroupId?.toString() ?: "") } }
                    }
                }
                FilterDivider()
                Tip(
                    stringResource(Res.string.mi_tip_lookback),
                ) {
                    ParamField(stringResource(Res.string.lookback_d), lookbackDays, 65.dp) {
                        lookbackDays = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_LOOKBACK_DAYS, it) } }
                    }
                }
                Tip(stringResource(Res.string.mi_tip_min_vol)) {
                    ParamField(stringResource(Res.string.min_vol), minDailyVol, 65.dp) {
                        minDailyVol = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_MIN_VOL, it) } }
                    }
                }
                Tip(
                    stringResource(Res.string.mi_tip_min_discount),
                ) {
                    ParamField(stringResource(Res.string.min_discount_pct), minDiscountPct, 90.dp) {
                        minDiscountPct = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_MIN_DISCOUNT, it) } }
                    }
                }
                Tip(
                    stringResource(Res.string.mi_tip_min_volatility),
                ) {
                    ParamField(stringResource(Res.string.min_volatility_pct), minVolatilityPct, 95.dp) {
                        minVolatilityPct = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_MIN_VOLATILITY, it) } }
                    }
                }
                Tip(stringResource(Res.string.mi_tip_max_volatility)) {
                    ParamField(stringResource(Res.string.max_volatility_pct), maxVolatilityPct, 95.dp) {
                        maxVolatilityPct = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_MAX_VOLATILITY, it) } }
                    }
                }
                Tip(
                    stringResource(Res.string.mi_tip_1y_lows),
                ) {
                    FilterControl(stringResource(Res.string.exclude_1y_lows)) {
                        Checkbox(
                            checked = excludeStructuralBreak,
                            onCheckedChange = {
                                excludeStructuralBreak = it
                                scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_EXCLUDE_STRUCTURAL_BREAK, it.toString()) } }
                            },
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Tip(stringResource(Res.string.mi_tip_spike)) {
                    SpikeFilterChip(spikeFilter) {
                        spikeFilter = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_SPIKE_FILTER, it.name) } }
                    }
                }
                Tip(stringResource(Res.string.mi_tip_price_mult)) {
                    ParamField(
                        stringResource(Res.string.price_mult),
                        spikePriceMultiplier,
                        50.dp,
                        enabled = spikeFilter != SpikeFilter.ANY,
                    ) {
                        spikePriceMultiplier = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_SPIKE_PRICE_MULTIPLIER, it) } }
                    }
                }
                Tip(stringResource(Res.string.mi_tip_vol_mult)) {
                    ParamField(
                        stringResource(Res.string.volume_mult),
                        spikeVolumeMultiplier,
                        50.dp,
                        enabled =
                            spikeFilter != SpikeFilter.ANY,
                    ) {
                        spikeVolumeMultiplier = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_SPIKE_VOLUME_MULTIPLIER, it) } }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Tip(
                        stringResource(Res.string.mi_tip_budget),
                    ) {
                        ParamField(stringResource(Res.string.budget_isk), totalBudget, 120.dp) {
                            totalBudget = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_TOTAL_BUDGET, it) } }
                        }
                    }
                    Tip(
                        stringResource(Res.string.mi_tip_max_items),
                    ) {
                        ParamField(stringResource(Res.string.max_items), maxItems, 65.dp) {
                            maxItems = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_MAX_ITEMS, it) } }
                        }
                    }
                    Tip(stringResource(Res.string.mi_tip_max_per_item)) {
                        ParamField(stringResource(Res.string.max_pct_item), maxPerItemPct, 75.dp) {
                            maxPerItemPct = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_MAX_PER_ITEM_PCT, it) } }
                        }
                    }
                    Tip(
                        stringResource(Res.string.mi_tip_liquidity),
                    ) {
                        ParamField(stringResource(Res.string.liquidity_d), liquidityDays, 75.dp) {
                            liquidityDays = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_LIQUIDITY_DAYS, it) } }
                        }
                    }
                    FilterDivider()
                    Tip(
                        stringResource(Res.string.mi_tip_ladder_levels),
                    ) {
                        ParamField(stringResource(Res.string.ladder_levels), ladderLevels, 80.dp) {
                            ladderLevels = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_LADDER_LEVELS, it) } }
                        }
                    }
                    Tip(
                        stringResource(Res.string.mi_tip_ladder_step),
                    ) {
                        ParamField(stringResource(Res.string.ladder_step_pct), ladderStepPct, 85.dp) {
                            ladderStepPct = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_LADDER_STEP_PCT, it) } }
                        }
                    }
                    FilterDivider()
                    val hotkeyLabel by HotkeyBindings.queueLabel.collectAsState()
                    Tip(
                        stringResource(Res.string.mi_tip_hotkey, hotkeyLabel),
                    ) {
                        FilterControl(stringResource(Res.string.hotkey_queue, hotkeyLabel)) {
                            Text(
                                if (queueItems.isEmpty()) {
                                    if (selectedTypeIds.isEmpty()) {
                                        stringResource(
                                            Res.string.check_rows,
                                        )
                                    } else {
                                        stringResource(Res.string.no_rungs)
                                    }
                                } else {
                                    "${MaterialsInvestmentQueue.currentPosition}/${queueItems.size}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                    Tip(stringResource(Res.string.mi_tip_copy_vol)) {
                        FilterControl(stringResource(Res.string.copy_vol)) {
                            Switch(
                                checked = copyVolumeEnabled,
                                onCheckedChange = {
                                    copyVolumeEnabled = it
                                    scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_COPY_VOLUME, it.toString()) } }
                                },
                                modifier = Modifier.height(FilterFieldHeight),
                            )
                        }
                    }
                    Tip(
                        stringResource(Res.string.mi_tip_alerts),
                    ) {
                        FilterControl(stringResource(Res.string.alerts)) {
                            val targets = sorted.filter { selectedTypeIds.isEmpty() || it.candidate.typeId in selectedTypeIds }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val n = withContext(Dispatchers.IO) { createAlerts(targets, regionId, charId) }
                                        statusMsg = getString(Res.string.alerts_set_items, n, targets.size)
                                    }
                                },
                                enabled = targets.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(FilterFieldHeight),
                            ) {
                                Icon(Icons.Default.NotificationsActive, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (selectedTypeIds.isEmpty()) {
                                        stringResource(
                                            Res.string.all_n,
                                            targets.size,
                                        )
                                    } else {
                                        stringResource(Res.string.checked_n, targets.size)
                                    },
                                )
                            }
                        }
                    }
                }
                if (statusMsg.isNotEmpty()) {
                    val errorPrefix = stringResource(Res.string.status_error, "")
                    FilterActionSlot {
                        Text(
                            statusMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (statusMsg.startsWith(
                                        errorPrefix,
                                    )
                                ) {
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
                            Text(stringResource(Res.string.stop))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                FilterActionSlot {
                    Button(
                        onClick = {
                            val job =
                                scope.launch {
                                    isAnalyzing = true
                                    candidates = emptyList()
                                    try {
                                        val groupId = selectedSubGroup?.marketGroupId ?: selectedTopGroup?.marketGroupId
                                        if (groupId == null) {
                                            statusMsg = stringBlocking(Res.string.err_pick_category)
                                            return@launch
                                        }
                                        val groupIds = withContext(Dispatchers.IO) { buildGroupSubtree(groupId) }
                                        val typeIds = withContext(Dispatchers.IO) { StaticDataDao.getTypeIdsByMarketGroups(groupIds) }

                                        val lookbackDaysSnap = lookbackDays.toIntOrNull() ?: 90
                                        val minDailyVolSnap = minDailyVol.toLongOrNull() ?: 0L
                                        val minDiscountSnap = minDiscountPct.toDoubleOrNull() ?: 0.0
                                        val maxVolatilitySnap = maxVolatilityPct.toDoubleOrNull() ?: 0.0
                                        val minVolatilitySnap = minVolatilityPct.toDoubleOrNull() ?: 0.0
                                        val spikeFilterSnap = spikeFilter
                                        val spikePriceMultiplierSnap = spikePriceMultiplier.toDoubleOrNull() ?: 1.8
                                        val spikeVolumeMultiplierSnap = spikeVolumeMultiplier.toDoubleOrNull() ?: 5.0
                                        val excludeStructuralBreakSnap = excludeStructuralBreak
                                        val histSrc = withContext(Dispatchers.IO) { EveRefService.getSelectedSource() }

                                        statusMsg = stringBlocking(Res.string.mi_fetching_region_orders)
                                        val ordersByType =
                                            withContext(Dispatchers.IO) { EsiClient.getMarketRegionOrders(regionId) }
                                                .groupBy { (it["type_id"] as? Number)?.toInt() ?: 0 }

                                        // One wallet-transactions read for the whole scan (not per item) -- see
                                        // computeMaterialPosition/computeMaterialCandidate in MaterialsInvestmentCompute.kt.
                                        val myTransactionsByType =
                                            charId?.let { id ->
                                                withContext(Dispatchers.IO) { WalletDao.getAllTransactions(characterId = id) }
                                                    .groupBy { it.typeId }
                                            }
                                        // Real physical stock, not net(buys - sells) -- materials routinely arrive via mining
                                        // + reprocessing without ever creating a wallet transaction, so Held needs this to
                                        // reflect what's actually sitting in the hangar, not just what was market-bought.
                                        val myAssetQtyByType =
                                            charId?.let { id ->
                                                withContext(Dispatchers.IO) { AssetDao.getByCharacter(id) }
                                                    .groupBy { it.typeId }
                                                    .mapValues { (_, assets) -> assets.sumOf { it.quantity }.toLong() }
                                            }

                                        val myOrdersByType =
                                            charId?.let { id ->
                                                withContext(Dispatchers.IO) { ActiveOrderDao.getAll(characterId = id) }
                                                    .filter { it.state == "active" }
                                                    .groupBy { it.typeId }
                                            }
                                        fees =
                                            charId?.let { id ->
                                                withContext(Dispatchers.IO) {
                                                    MaterialFees(StaticDataDao.getCharSalesTax(id), StaticDataDao.getCharBrokersFee(id))
                                                }
                                            } ?: MaterialFees()
                                        val feesSnap = fees
                                        val takeProfitSnap = ladderStepPct.toDoubleOrNull() ?: 5.0

                                        statusMsg = stringBlocking(Res.string.types_checked_zero, typeIds.size)
                                        val semaphore = Semaphore(10)
                                        val found = java.util.Collections.synchronizedList(mutableListOf<MaterialCandidate>())
                                        var checked = 0
                                        val mutex = Mutex()

                                        coroutineScope {
                                            typeIds
                                                .map { typeId ->
                                                    async(Dispatchers.IO) {
                                                        semaphore.withPermit {
                                                            runCatching {
                                                                // PLEX only ever trades in its own dedicated region, never in
                                                                // whichever region is selected here -- the bulk fetch above
                                                                // (scoped to `regionId`) always comes back empty for it, so it
                                                                // needs its own per-type call against PLEX_MARKET_REGION_ID
                                                                // instead, same as Station Trading/Inter-Region already do.
                                                                val effRegion =
                                                                    if (typeId ==
                                                                        PLEX_TYPE_ID
                                                                    ) {
                                                                        PLEX_MARKET_REGION_ID
                                                                    } else {
                                                                        regionId
                                                                    }
                                                                val orders =
                                                                    if (effRegion == regionId) {
                                                                        ordersByType[typeId].orEmpty()
                                                                    } else {
                                                                        EsiClient.getMarketRegionOrders(effRegion, typeId = typeId)
                                                                    }
                                                                computeMaterialCandidate(
                                                                    typeId = typeId,
                                                                    orders = orders,
                                                                    regionId = effRegion,
                                                                    lookbackDays = lookbackDaysSnap,
                                                                    minDailyVol = minDailyVolSnap,
                                                                    minDiscountPct = minDiscountSnap,
                                                                    maxVolatilityPct = maxVolatilitySnap,
                                                                    minVolatilityPct = minVolatilitySnap,
                                                                    historySource = histSrc,
                                                                    spikeFilter = spikeFilterSnap,
                                                                    spikePriceMultiplier = spikePriceMultiplierSnap,
                                                                    spikeVolumeMultiplier = spikeVolumeMultiplierSnap,
                                                                    excludeStructuralBreak = excludeStructuralBreakSnap,
                                                                    myTransactionsByType = myTransactionsByType,
                                                                    myAssetQtyByType = myAssetQtyByType,
                                                                    myOrdersByType = myOrdersByType,
                                                                    fees = feesSnap,
                                                                    takeProfitPct = takeProfitSnap,
                                                                )
                                                            }.getOrNull()?.let { found.add(it) }
                                                            mutex.withLock {
                                                                checked++
                                                                if (checked % 20 == 0 || checked == typeIds.size) {
                                                                    statusMsg =
                                                                        stringBlocking(Res.string.types_checked, checked, typeIds.size)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }.forEach { it.await() }
                                        }
                                        candidates = found.toList()
                                        statusMsg = stringBlocking(Res.string.dip_candidates, candidates.size)
                                    } catch (e: CancellationException) {
                                        statusMsg = stringBlocking(Res.string.cancelled)
                                        throw e
                                    } catch (e: Exception) {
                                        statusMsg = stringBlocking(Res.string.status_error, e.message.orEmpty())
                                    } finally {
                                        isAnalyzing = false
                                    }
                                }
                            analyzeJob = job
                        },
                        enabled = !isAnalyzing,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
                        modifier = Modifier.height(FilterFieldHeight),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.TrendingDown, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isAnalyzing) stringResource(Res.string.analyzing) else stringResource(Res.string.analyze))
                    }
                }
            }
        }

        if (sorted.isEmpty()) {
            AnalysisEmptyState(
                icon = Icons.AutoMirrored.Filled.TrendingDown,
                primary = if (isAnalyzing) stringResource(Res.string.scanning_history) else stringResource(Res.string.no_dip_candidates),
                secondary =
                    if (isAnalyzing) {
                        statusMsg
                    } else {
                        stringResource(Res.string.mi_empty_hint)
                    },
            )
        } else {
            MaterialsHeader(
                sort = sortCol,
                asc = sortAsc,
                allChecked = sorted.isNotEmpty() && sorted.all { it.candidate.typeId in selectedTypeIds },
                onCheckAll = { checked ->
                    selectedTypeIds = if (checked) sorted.map { it.candidate.typeId }.toSet() else emptySet()
                },
                onSort = { col ->
                    if (sortCol == col) {
                        sortAsc = !sortAsc
                    } else {
                        sortCol = col
                        sortAsc = true
                    }
                },
            )
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(sorted, key = { it.candidate.typeId }) { alloc ->
                    MaterialRow(
                        alloc,
                        checked = alloc.candidate.typeId in selectedTypeIds,
                        onCheckedChange = { checked ->
                            selectedTypeIds =
                                if (checked) selectedTypeIds + alloc.candidate.typeId else selectedTypeIds - alloc.candidate.typeId
                        },
                        isActiveInQueue = alloc.candidate.typeId == activeQueueTypeId,
                        onShowDetails = { detailTypeId = it },
                        onCreateAlerts = {
                            scope.launch {
                                val n = withContext(Dispatchers.IO) { createAlerts(listOf(alloc), regionId, charId) }
                                statusMsg = getString(Res.string.alerts_set_one, n, alloc.candidate.typeName)
                            }
                        },
                    )
                }
            }
        }
    }

    detailTypeId?.let { id ->
        val opp = sorted.find { it.candidate.typeId == id }?.candidate
        ItemDetailDialog(
            typeId = id,
            typeName = opp?.typeName ?: "",
            primaryRegionId = regionId,
            primaryRegionName = allRegions.find { it.regionId == regionId }?.name ?: "",
            primaryStationId = null,
            charId = charId,
            onDismiss = { detailTypeId = null },
        )
    }
}

// ─── Table ──────────────────────────────────────────────────────────────

@Composable
private fun MaterialsHeader(
    sort: MaterialSortCol,
    asc: Boolean,
    allChecked: Boolean,
    onCheckAll: (Boolean) -> Unit,
    onSort: (MaterialSortCol) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Tip(stringResource(Res.string.mi_tip_select_all)) {
                Checkbox(checked = allChecked, onCheckedChange = onCheckAll, modifier = Modifier.size(28.dp))
            }
            MCol(stringResource(Res.string.m_item), MaterialSortCol.NAME, sort, asc, onSort, Modifier.weight(1f))
            Tip(
                stringResource(Res.string.mi_tip_current),
            ) {
                MCol(stringResource(Res.string.m_current), MaterialSortCol.CURRENT, sort, asc, onSort, Modifier.width(80.dp))
            }
            Tip(stringResource(Res.string.mi_tip_avg)) {
                MCol(stringResource(Res.string.m_avg), MaterialSortCol.AVG, sort, asc, onSort, Modifier.width(80.dp))
            }
            Tip(
                stringResource(Res.string.mi_tip_held),
            ) {
                MCol(stringResource(Res.string.m_held), MaterialSortCol.HELD, sort, asc, onSort, Modifier.width(140.dp))
            }
            Tip(stringResource(Res.string.mi_tip_drawdown)) {
                MCol(stringResource(Res.string.m_drawdown), MaterialSortCol.DRAWDOWN, sort, asc, onSort, Modifier.width(75.dp))
            }
            Tip(
                stringResource(Res.string.mi_tip_vs_avg),
            ) {
                MCol(stringResource(Res.string.m_vs_avg), MaterialSortCol.VS_AVG, sort, asc, onSort, Modifier.width(65.dp))
            }
            Tip(stringResource(Res.string.mi_tip_7d)) {
                MCol(stringResource(Res.string.m_7d), MaterialSortCol.TREND, sort, asc, onSort, Modifier.width(55.dp))
            }
            Tip(
                stringResource(Res.string.mi_tip_backtest),
            ) {
                MCol(stringResource(Res.string.m_backtest), MaterialSortCol.BACKTEST, sort, asc, onSort, Modifier.width(105.dp))
            }
            Tip(stringResource(Res.string.mi_tip_volatility)) {
                MCol(stringResource(Res.string.m_volatility), MaterialSortCol.VOLATILITY, sort, asc, onSort, Modifier.width(70.dp))
            }
            Tip(stringResource(Res.string.mi_tip_vol_day)) {
                MCol(stringResource(Res.string.m_vol_day), MaterialSortCol.VOLUME, sort, asc, onSort, Modifier.width(65.dp))
            }
            Tip(
                stringResource(Res.string.mi_tip_total_profit),
            ) {
                MCol(stringResource(Res.string.m_total_profit), MaterialSortCol.PROFIT, sort, asc, onSort, Modifier.width(85.dp))
            }
            Tip(stringResource(Res.string.mi_tip_margin)) {
                MCol(stringResource(Res.string.m_margin), MaterialSortCol.MARGIN, sort, asc, onSort, Modifier.width(60.dp))
            }
            Tip(
                stringResource(Res.string.mi_tip_to_buy),
            ) {
                MCol(stringResource(Res.string.m_to_buy), MaterialSortCol.ALLOCATED, sort, asc, onSort, Modifier.width(80.dp))
            }
            Spacer(Modifier.width(28.dp))
        }
    }
}

@Composable
private fun <T> MCol(
    label: String,
    col: T,
    current: T,
    asc: Boolean,
    onSort: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
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
        if (active) {
            Icon(
                if (asc) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                null,
                Modifier.size(10.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun MaterialRow(
    alloc: AllocatedMaterial,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isActiveInQueue: Boolean,
    onShowDetails: (Int) -> Unit,
    onCreateAlerts: () -> Unit,
) {
    val c = alloc.candidate
    val actionColor =
        when (alloc.action) {
            MaterialAction.BUY -> positiveColor
            MaterialAction.SELL -> warningColor
            MaterialAction.BUYING -> positiveColor.copy(alpha = 0.6f)
            MaterialAction.ON_SALE -> warningColor.copy(alpha = 0.6f)
            MaterialAction.WAIT -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            null -> MaterialTheme.colorScheme.onSurface
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .onRightClick { onShowDetails(c.typeId) }
                .then(if (isActiveInQueue) Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) else Modifier)
                .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.size(28.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                alloc.action?.let { action ->
                    Tip(
                        when (action) {
                            MaterialAction.BUY -> {
                                stringResource(Res.string.act_buy)
                            }

                            MaterialAction.WAIT -> {
                                stringResource(Res.string.act_wait)
                            }

                            MaterialAction.SELL -> {
                                stringResource(Res.string.act_sell)
                            }

                            MaterialAction.BUYING -> {
                                stringResource(Res.string.act_buying)
                            }

                            MaterialAction.ON_SALE -> {
                                stringResource(Res.string.act_on_sale)
                            }
                        },
                    ) {
                        Icon(
                            when (action) {
                                MaterialAction.BUY -> Icons.Default.ShoppingCart
                                MaterialAction.WAIT -> Icons.Default.HourglassEmpty
                                MaterialAction.SELL -> Icons.Default.Sell
                                MaterialAction.BUYING -> Icons.Default.Downloading
                                MaterialAction.ON_SALE -> Icons.Default.Storefront
                            },
                            contentDescription = action.name,
                            tint = actionColor,
                            modifier = Modifier.size(14.dp).padding(end = 2.dp),
                        )
                    }
                }
                Text(
                    c.typeName,
                    style = MaterialTheme.typography.bodySmall,
                    color = actionColor,
                    fontWeight = if (alloc.action == MaterialAction.SELL) FontWeight.SemiBold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (c.spikeDetected) {
                    Tip(
                        stringResource(Res.string.mi_tip_spike_row),
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = stringResource(Res.string.spike_detected),
                            modifier = Modifier.size(13.dp),
                            tint = warningColor,
                        )
                    }
                }
                IconButton(onClick = { onShowDetails(c.typeId) }, modifier = Modifier.size(20.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ShowChart,
                        contentDescription = stringResource(Res.string.open_chart),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(formatPriceAbbr(c.currentPrice), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(80.dp))
            Text(
                formatPriceAbbr(c.avgPrice),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.width(80.dp),
            )
            Text(
                c.position?.takeIf { it.qtyHeld != 0L }?.let { p ->
                    "${formatVolume(p.qtyHeld)}${p.avgBuyPrice?.let { " @ ${formatPriceAbbr(it)}" } ?: ""}"
                } ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(140.dp),
            )
            Text(
                "${String.format(Locale.US, "%.1f", c.drawdownFromHighPct)}%",
                style = MaterialTheme.typography.bodySmall,
                color = negativeColor,
                modifier = Modifier.width(75.dp),
            )
            Text(
                "${String.format(Locale.US, "%.1f", c.vsAvgPct)}%",
                style = MaterialTheme.typography.bodySmall,
                color = positiveColor,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(65.dp),
            )
            val trendColor =
                when {
                    c.trendPct.isNaN() -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    c.trendPct >= 0 -> positiveColor
                    else -> negativeColor
                }
            Text(
                if (c.trendPct.isNaN()) "—" else "${String.format(Locale.US, "%.1f", c.trendPct)}%",
                style = MaterialTheme.typography.bodySmall,
                color = trendColor,
                modifier = Modifier.width(55.dp),
            )
            Text(
                c.backtest?.let { bt ->
                    "${signedPct(bt.pnlPct)} (${signedPct(bt.worstDrawdownPct)})"
                } ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = c.backtest?.let { if (it.pnlPct >= 0) positiveColor else negativeColor } ?: Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(105.dp),
            )
            Text(
                "${String.format(Locale.US, "%.1f", c.volatilityPct)}%",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.width(70.dp),
            )
            Text(
                formatVolume(c.dailyVolume),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.width(65.dp),
            )
            val profit = alloc.sellTarget?.profitNow
            val profitColor = profit?.let { if (it >= 0) positiveColor else negativeColor } ?: Color.Gray
            Text(
                profit?.let { (if (it >= 0) "+" else "") + formatPriceAbbr(it) } ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = profitColor,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(85.dp),
            )
            Text(
                alloc.sellTarget?.profitNowPct?.let { signedPct(it) } ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = profitColor,
                modifier = Modifier.width(60.dp),
            )
            Text(
                if (alloc.toBuyIsk > 1.0) formatPriceAbbr(alloc.toBuyIsk) else "—",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(80.dp),
            )
            Tip(
                stringResource(Res.string.mi_tip_row_alerts),
            ) {
                IconButton(onClick = onCreateAlerts, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.NotificationsActive,
                        contentDescription = stringResource(Res.string.create_alerts_row),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
        if (alloc.ladder.isNotEmpty()) {
            Tip(
                stringResource(Res.string.mi_tip_ladder),
            ) {
                Text(
                    stringResource(Res.string.ladder_buy_prefix) + " " +
                        alloc.ladder.joinToString("  ·  ") {
                            "${formatPriceAbbr(it.triggerPrice)}→${formatPriceAbbr(it.iskAmount)}" + if (it.alreadyTriggered) "*" else ""
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 28.dp, top = 1.dp),
                )
            }
        }
        c.position?.takeIf { it.hasOrders }?.let { p ->
            Tip(stringResource(Res.string.mi_tip_orders)) {
                Row(modifier = Modifier.padding(start = 28.dp, top = 1.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (p.buyOrderQty > 0) {
                        Text(
                            stringResource(
                                Res.string.buying_line,
                                formatVolume(p.buyOrderQty),
                                formatPriceAbbr(p.buyOrderPrice ?: 0.0),
                                formatPriceAbbr(p.buyOrderIsk),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = positiveColor.copy(alpha = 0.8f),
                        )
                    }
                    if (p.listedQty > 0) {
                        Text(
                            stringResource(Res.string.on_sale_line, formatVolume(p.listedQty), formatPriceAbbr(p.listedPrice ?: 0.0)),
                            style = MaterialTheme.typography.labelSmall,
                            color = warningColor.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
        alloc.sellTarget?.let { st ->
            Tip(
                stringResource(Res.string.mi_tip_sell_target),
            ) {
                Row(modifier = Modifier.padding(start = 28.dp, top = 1.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(Res.string.sell_line, formatVolume(st.qty), formatPriceAbbr(st.targetPrice)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    if (st.profitNow != null && c.bestAsk != null) {
                        Text(
                            stringResource(
                                Res.string.now_line,
                                formatPriceAbbr(c.bestAsk),
                                (if (st.profitNow >= 0) "+" else "") + formatPriceAbbr(st.profitNow),
                                signedPct(st.profitNowPct ?: 0.0),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (st.profitNow >= 0) positiveColor else negativeColor,
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
}

private fun signedPct(pct: Double): String = "${if (pct >= 0) "+" else ""}${String.format(Locale.US, "%.0f", pct)}%"

// Hover-to-explain wrapper for a filter field or column header -- this tab has several numbers
// (Lookback d, the Backtest column, the ladder anchor logic) that read as self-explanatory but
// aren't, so every non-obvious control gets one rather than relying on remembering an explanation
// from outside the app.
@OptIn(ExperimentalFoundationApi::class)
// Alerts for one item: a "top buy order fell to this rung" alert per buy-ladder level not already
// crossed, plus a sell-target alert for a held position -- skipping any that already have a live,
// un-triggered alert at essentially the same price, so re-clicking after a re-scan doesn't pile up
// duplicates.
private fun ladderAlerts(
    alloc: AllocatedMaterial,
    regionId: Int,
    charId: Int?,
    existing: List<PriceAlertModel>,
): List<PriceAlertModel> {
    // AlertMonitor trusts whatever region is stored on the alert rather than re-deriving it per
    // typeId (see effectiveRegion in AlertMonitor.kt) -- PLEX only trades in its own global region,
    // so an alert stored with the UI's selected region (e.g. The Forge) would silently watch the
    // wrong market forever and never reflect PLEX's real price.
    val effRegion = if (alloc.candidate.typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else regionId
    val toInsert = mutableListOf<PriceAlertModel>()
    alloc.ladder.filterNot { it.alreadyTriggered }.forEach { level ->
        val duplicate =
            existing.any {
                !it.triggered && it.typeId == alloc.candidate.typeId && it.regionId == effRegion &&
                    it.condition == "below" && it.orderType == "buy" &&
                    kotlin.math.abs(it.targetPrice - level.triggerPrice) < 0.01
            }
        if (!duplicate) {
            toInsert +=
                PriceAlertModel(
                    typeId = alloc.candidate.typeId,
                    typeName = alloc.candidate.typeName,
                    targetPrice = level.triggerPrice,
                    condition = "below",
                    regionId = effRegion,
                    // Tracks the top buy order price, matching currentPrice throughout this tab --
                    // fires once the market's own best bid has fallen to this rung, telling you
                    // it's a realistic level to place (or that your own standing order there is now
                    // competitive), not that you could instant-sell into a buyer at that price.
                    orderType = "buy",
                    characterId = charId,
                    category = ALERT_CATEGORY_INVESTMENT,
                )
        }
    }
    // Held position: one "lowest ask rose to my sell target" alert -- the signal to go sell.
    alloc.sellTarget?.takeIf { alloc.action != MaterialAction.SELL }?.let { st ->
        val duplicate =
            existing.any {
                !it.triggered && it.typeId == alloc.candidate.typeId && it.regionId == effRegion &&
                    it.condition == "above" && it.orderType == "sell" &&
                    kotlin.math.abs(it.targetPrice - st.targetPrice) < 0.01
            }
        if (!duplicate) {
            toInsert +=
                PriceAlertModel(
                    typeId = alloc.candidate.typeId,
                    typeName = alloc.candidate.typeName,
                    targetPrice = st.targetPrice,
                    condition = "above",
                    regionId = effRegion,
                    orderType = "sell",
                    characterId = charId,
                    category = ALERT_CATEGORY_INVESTMENT,
                )
        }
    }
    return toInsert
}

// One DB read for the duplicate check and one bulk insert, however many items are passed.
private fun createAlerts(
    allocs: List<AllocatedMaterial>,
    regionId: Int,
    charId: Int?,
): Int {
    val existing = runCatching { AlertDao.getAll() }.getOrDefault(emptyList())
    val alerts = allocs.flatMap { ladderAlerts(it, regionId, charId, existing) }
    AlertDao.insertAll(alerts)
    return alerts.size
}
