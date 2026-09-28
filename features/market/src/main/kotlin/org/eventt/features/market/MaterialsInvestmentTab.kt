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
import org.eventt.core.model.HotkeyBindings
import org.eventt.core.model.PLEX_MARKET_REGION_ID
import org.eventt.core.model.PLEX_TYPE_ID
import org.eventt.core.model.PriceAlertModel
import org.eventt.core.model.StaticMarketGroupModel
import org.eventt.core.model.StaticRegionModel
import org.eventt.core.model.eveSigFigStep
import org.eventt.ui.common.formatPriceAbbr
import org.eventt.ui.common.formatVolume
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
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
                GroupDropdown("Category", topGroups, selectedTopGroup, "All categories", 170.dp) { g ->
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
                    GroupDropdown("Subcategory", subGroups, selectedSubGroup, "All", 140.dp) { g ->
                        selectedSubGroup = g
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_CAT_SUB, g?.marketGroupId?.toString() ?: "") } }
                    }
                }
                FilterDivider()
                Tip(
                    "How many days of price history define \"normal\" for this item -- Avg/High/Low/Volatility and the vs Avg " +
                        "discount are all computed over this window. It's not a wait time: ESI already has up to ~13 months of " +
                        "history, so results appear immediately. It's also the window the Backtest column re-checks on every " +
                        "single simulated day across all available history, not just once.",
                ) {
                    ParamField("Lookback d", lookbackDays, 65.dp) {
                        lookbackDays = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_LOOKBACK_DAYS, it) } }
                    }
                }
                Tip("Skip items whose median daily trade volume (over the lookback window) is below this -- too thin to reliably trade.") {
                    ParamField("Min Vol", minDailyVol, 65.dp) {
                        minDailyVol = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_MIN_VOL, it) } }
                    }
                }
                Tip(
                    "Only include items currently at least this % below their own Lookback-day average price -- the core \"is it cheap\" filter.",
                ) {
                    ParamField("Min Discount %", minDiscountPct, 90.dp) {
                        minDiscountPct = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_MIN_DISCOUNT, it) } }
                    }
                }
                Tip("Skip items whose price swings more than this % (std-dev ÷ average) over the lookback window. 0 = no limit.") {
                    ParamField("Max Volatility %", maxVolatilityPct, 95.dp) {
                        maxVolatilityPct = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_MAX_VOLATILITY, it) } }
                    }
                }
                Tip(
                    "Skip items whose current price is at or below their own lowest recorded price in roughly the last year. " +
                        "A break to a new multi-month low looks like structural decline (e.g. made obsolete by a balance patch), " +
                        "not a routine dip worth feeding the buy ladder.",
                ) {
                    FilterControl("Exclude 1y Lows") {
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
                Tip("Filters out items whose recent price/volume history shows a sharp one-off spike (e.g. a buyout or wash trading).") {
                    SpikeFilterChip(spikeFilter) {
                        spikeFilter = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_SPIKE_FILTER, it.name) } }
                    }
                }
                Tip("A day's price counts as a spike if it's at least this many times the surrounding baseline.") {
                    ParamField("Price ×", spikePriceMultiplier, 50.dp, enabled = spikeFilter != SpikeFilter.ANY) {
                        spikePriceMultiplier = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_SPIKE_PRICE_MULTIPLIER, it) } }
                    }
                }
                Tip("A day's trade volume counts as a spike if it's at least this many times the surrounding baseline.") {
                    ParamField("Volume ×", spikeVolumeMultiplier, 50.dp, enabled = spikeFilter != SpikeFilter.ANY) {
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
                        "Total ISK you're willing to invest. Split across the picked candidates below, weighted by discount depth × daily traded ISK value.",
                    ) {
                        ParamField("Budget (ISK)", totalBudget, 120.dp) {
                            totalBudget = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_TOTAL_BUDGET, it) } }
                        }
                    }
                    Tip(
                        "How many of the top-ranked candidates get a share of the budget -- ranked by real ISK opportunity " +
                            "(discount % × daily traded value), not by raw discount % alone, so a deep discount on a thin/illiquid " +
                            "item doesn't crowd out a shallower discount on a liquid, high-value one.",
                    ) {
                        ParamField("Max Items", maxItems, 65.dp) {
                            maxItems = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_MAX_ITEMS, it) } }
                        }
                    }
                    Tip("Caps any single item at this % of the total budget, regardless of how deep its discount is.") {
                        ParamField("Max %/Item", maxPerItemPct, 75.dp) {
                            maxPerItemPct = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_MAX_PER_ITEM_PCT, it) } }
                        }
                    }
                    Tip(
                        "Second cap: never allocate more than this many days' worth of the item's own median daily trading " +
                            "value -- avoids parking more ISK in one material than the market could plausibly absorb.",
                    ) {
                        ParamField("Liquidity d", liquidityDays, 75.dp) {
                            liquidityDays = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_LIQUIDITY_DAYS, it) } }
                        }
                    }
                    FilterDivider()
                    Tip(
                        "How many price rungs to split each item's allocation into. Anchored to your real average cost when " +
                            "you're already holding and underwater, otherwise to the current live price.",
                    ) {
                        ParamField("Ladder Levels", ladderLevels, 80.dp) {
                            ladderLevels = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_LADDER_LEVELS, it) } }
                        }
                    }
                    Tip(
                        "Price gap between each ladder rung. Later rungs (further price drops) get a bigger share of the " +
                            "item's allocation -- buy more the further it falls. Also the take-profit % for held items: " +
                            "the sell target is your average cost + this %, after sales tax and broker fee.",
                    ) {
                        ParamField("Ladder Step %", ladderStepPct, 85.dp) {
                            ladderStepPct = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_LADDER_STEP_PCT, it) } }
                        }
                    }
                    FilterDivider()
                    val hotkeyLabel by HotkeyBindings.queueLabel.collectAsState()
                    Tip(
                        "Check rows below, then press $hotkeyLabel to step through every checked item's buy-ladder rungs: " +
                            "opens the item's market window and copies the target price, then on the next press copies the " +
                            "quantity -- paste each into EVE's buy-order dialog.",
                    ) {
                        FilterControl("Hotkey Queue ($hotkeyLabel)") {
                            Text(
                                if (queueItems.isEmpty()) {
                                    if (selectedTypeIds.isEmpty()) "check rows →" else "no rungs"
                                } else {
                                    "${MaterialsInvestmentQueue.currentPosition}/${queueItems.size}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                    Tip("Whether the second hotkey press (per rung) also copies the suggested quantity, or just advances.") {
                        FilterControl("Copy Vol") {
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
                        "Create alerts for every checked row at once (all rows if none are checked): buy-ladder rungs " +
                            "plus sell targets for held positions. Existing identical alerts are skipped. AlertMonitor " +
                            "fetches each item's order book once, however many alerts it has.",
                    ) {
                        FilterControl("Alerts") {
                            val targets = sorted.filter { selectedTypeIds.isEmpty() || it.candidate.typeId in selectedTypeIds }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val n = withContext(Dispatchers.IO) { createAlerts(targets, regionId, charId) }
                                        statusMsg = "$n alert(s) set for ${targets.size} item(s)"
                                    }
                                },
                                enabled = targets.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(FilterFieldHeight),
                            ) {
                                Icon(Icons.Default.NotificationsActive, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (selectedTypeIds.isEmpty()) "All (${targets.size})" else "Checked (${targets.size})")
                            }
                        }
                    }
                }
                if (statusMsg.isNotEmpty()) {
                    FilterActionSlot {
                        Text(
                            statusMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color = if ("Error" in statusMsg) negativeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
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
                            val job =
                                scope.launch {
                                    isAnalyzing = true
                                    candidates = emptyList()
                                    try {
                                        val groupId = selectedSubGroup?.marketGroupId ?: selectedTopGroup?.marketGroupId
                                        if (groupId == null) {
                                            statusMsg = "Error: pick a category first"
                                            return@launch
                                        }
                                        val groupIds = withContext(Dispatchers.IO) { buildGroupSubtree(groupId) }
                                        val typeIds = withContext(Dispatchers.IO) { StaticDataDao.getTypeIdsByMarketGroups(groupIds) }

                                        val lookbackDaysSnap = lookbackDays.toIntOrNull() ?: 90
                                        val minDailyVolSnap = minDailyVol.toLongOrNull() ?: 0L
                                        val minDiscountSnap = minDiscountPct.toDoubleOrNull() ?: 0.0
                                        val maxVolatilitySnap = maxVolatilityPct.toDoubleOrNull() ?: 0.0
                                        val spikeFilterSnap = spikeFilter
                                        val spikePriceMultiplierSnap = spikePriceMultiplier.toDoubleOrNull() ?: 1.8
                                        val spikeVolumeMultiplierSnap = spikeVolumeMultiplier.toDoubleOrNull() ?: 5.0
                                        val excludeStructuralBreakSnap = excludeStructuralBreak
                                        val histSrc = withContext(Dispatchers.IO) { EveRefService.getSelectedSource() }

                                        statusMsg = "Fetching region orders…"
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

                                        statusMsg = "0/${typeIds.size} types checked…"
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
                                                                    historySource = histSrc,
                                                                    spikeFilter = spikeFilterSnap,
                                                                    spikePriceMultiplier = spikePriceMultiplierSnap,
                                                                    spikeVolumeMultiplier = spikeVolumeMultiplierSnap,
                                                                    excludeStructuralBreak = excludeStructuralBreakSnap,
                                                                    myTransactionsByType = myTransactionsByType,
                                                                    myAssetQtyByType = myAssetQtyByType,
                                                                    myOrdersByType = myOrdersByType,
                                                                    fees = feesSnap,
                                                                )
                                                            }.getOrNull()?.let { found.add(it) }
                                                            mutex.withLock {
                                                                checked++
                                                                if (checked % 20 == 0 || checked == typeIds.size) {
                                                                    statusMsg = "$checked/${typeIds.size} types checked…"
                                                                }
                                                            }
                                                        }
                                                    }
                                                }.forEach { it.await() }
                                        }
                                        candidates = found.toList()
                                        statusMsg = "${candidates.size} dip candidates found"
                                    } catch (e: CancellationException) {
                                        statusMsg = "Cancelled"
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
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
                        modifier = Modifier.height(FilterFieldHeight),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.TrendingDown, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isAnalyzing) "Analyzing…" else "Analyze")
                    }
                }
            }
        }

        if (sorted.isEmpty()) {
            AnalysisEmptyState(
                icon = Icons.AutoMirrored.Filled.TrendingDown,
                primary = if (isAnalyzing) "Scanning price history…" else "No dip candidates yet",
                secondary =
                    if (isAnalyzing) {
                        statusMsg
                    } else {
                        "Pick a category (defaults to Manufacture & Research > Materials) and click Analyze"
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
                                statusMsg = "$n alert(s) set for ${alloc.candidate.typeName}"
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
            Tip("Select all / none -- checked rows feed the hotkey queue.") {
                Checkbox(checked = allChecked, onCheckedChange = onCheckAll, modifier = Modifier.size(28.dp))
            }
            MCol("Item", MaterialSortCol.NAME, sort, asc, onSort, Modifier.weight(1f))
            Tip(
                "Live top buy order price -- the current best bid. This tab is built around placing your own buy orders, not instant-buying, so ladder prices are targets for standing orders, not asks to pay.",
            ) {
                MCol("Current", MaterialSortCol.CURRENT, sort, asc, onSort, Modifier.width(80.dp))
            }
            Tip("Average price over the Lookback-day window.") {
                MCol("Avg", MaterialSortCol.AVG, sort, asc, onSort, Modifier.width(80.dp))
            }
            Tip(
                "Your current holding: real quantity from your assets (all locations) plus stock listed in your sell orders, " +
                    "@ average cost of your tracked market " +
                    "buys (average-cost, not FIFO -- and won't cover stock that arrived via mining/reprocessing/manufacturing " +
                    "rather than a market buy). Used to anchor the buy ladder to your real entry instead of the live price.",
            ) {
                MCol("Held", MaterialSortCol.HELD, sort, asc, onSort, Modifier.width(140.dp))
            }
            Tip("How far below the Lookback-window's highest price the current price sits.") {
                MCol("Drawdown", MaterialSortCol.DRAWDOWN, sort, asc, onSort, Modifier.width(75.dp))
            }
            Tip(
                "How far below the Lookback-window average the current price sits -- the core \"is it cheap\" number this " +
                    "tab ranks candidates by.",
            ) {
                MCol("vs Avg", MaterialSortCol.VS_AVG, sort, asc, onSort, Modifier.width(65.dp))
            }
            Tip("Price change over the last 7 days.") {
                MCol("7d", MaterialSortCol.TREND, sort, asc, onSort, Modifier.width(55.dp))
            }
            Tip(
                "Replays this same \"buy when below the trailing Lookback-day average by Min Discount %\" rule over up to " +
                    "a year of this item's own history, paying broker fee on each buy and netting sales tax + broker fee on the " +
                    "exit. Format: final P&L% (worst paper drawdown% along the way). A negative " +
                    "first number is a warning sign -- the dip-buying pattern hasn't historically paid off for this item.",
            ) {
                MCol("Backtest", MaterialSortCol.BACKTEST, sort, asc, onSort, Modifier.width(105.dp))
            }
            Tip("Price swinginess over the lookback window (std-dev ÷ average). Higher = choppier.") {
                MCol("Volatility", MaterialSortCol.VOLATILITY, sort, asc, onSort, Modifier.width(70.dp))
            }
            Tip("Median daily trade volume over the lookback window.") {
                MCol("Vol/day", MaterialSortCol.VOLUME, sort, asc, onSort, Modifier.width(65.dp))
            }
            Tip(
                "Held positions only: net profit selling the whole stack at the current lowest ask, after sales tax " +
                    "and broker fee, against your average cost including the broker fee you paid to buy.",
            ) {
                MCol("Total Profit", MaterialSortCol.PROFIT, sort, asc, onSort, Modifier.width(85.dp))
            }
            Tip("That profit as % of what the held stack cost you.") {
                MCol("Margin", MaterialSortCol.MARGIN, sort, asc, onSort, Modifier.width(60.dp))
            }
            Tip(
                "ISK left to buy: this item's share of the budget (after ranking, the per-item cap and the liquidity cap) " +
                    "minus what you already hold at cost (incl. buy broker fee) and ISK in your open buy orders. Ladder " +
                    "quantities leave room for the broker fee. 0 once the position is full.",
            ) {
                MCol("To Buy", MaterialSortCol.ALLOCATED, sort, asc, onSort, Modifier.width(80.dp))
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
                .then(if (isActiveInQueue) Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) else Modifier)
                .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.size(28.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                alloc.action?.let { action ->
                    Tip(
                        when (action) {
                            MaterialAction.BUY -> "Buy: still below its share of the budget -- place the buy ladder below."
                            MaterialAction.WAIT -> "Wait: position is full, sell target not reached yet."
                            MaterialAction.SELL -> "Sell: the lowest ask already clears your cost + take-profit after fees."
                            MaterialAction.BUYING -> "Buying: your buy orders already cover the rest of this item's allocation."
                            MaterialAction.ON_SALE -> "On sale: your stock is listed in sell orders -- waiting for fills."
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
                        "Recent price or volume spike detected -- a sharp one-off jump (e.g. a single large buyout, or wash " +
                            "trading) rather than an organic move. Doesn't exclude the item by itself (Spike Filter is set to " +
                            "\"Any\"); check the price chart (ⓘ) before trusting the ladder on this one.",
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Recent price spike detected",
                            modifier = Modifier.size(13.dp),
                            tint = warningColor,
                        )
                    }
                }
                IconButton(onClick = { onShowDetails(c.typeId) }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Info, contentDescription = "Item details", modifier = Modifier.size(14.dp))
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
                    "${signedPct(bt.unrealizedPnlPct)} (${signedPct(bt.worstDrawdownPct)})"
                } ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = c.backtest?.let { if (it.unrealizedPnlPct >= 0) positiveColor else negativeColor } ?: Color.Gray,
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
                "Create price alerts (Alerts tab) for this item: remaining buy-ladder rungs (top buy order falls to the " +
                    "rung) and, for a held position, the sell target (lowest ask rises to it). Skips levels already crossed " +
                    "and alerts that already exist.",
            ) {
                IconButton(onClick = onCreateAlerts, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.NotificationsActive,
                        contentDescription = "Create alerts for this item's buy ladder",
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
        if (alloc.ladder.isNotEmpty()) {
            Tip(
                "Target price for a standing buy order → ISK to spend at that rung (* = already at/above the current top " +
                    "bid, so it's actionable right now rather than a future target).",
            ) {
                Text(
                    "Buy: " +
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
            Tip("Your active market orders on this item (from the last Orders sync).") {
                Row(modifier = Modifier.padding(start = 28.dp, top = 1.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (p.buyOrderQty > 0) {
                        Text(
                            "▲ buying ${formatVolume(p.buyOrderQty)}u @ ${formatPriceAbbr(p.buyOrderPrice ?: 0.0)} " +
                                "(${formatPriceAbbr(p.buyOrderIsk)} in orders)",
                            style = MaterialTheme.typography.labelSmall,
                            color = positiveColor.copy(alpha = 0.8f),
                        )
                    }
                    if (p.listedQty > 0) {
                        Text(
                            "▼ on sale ${formatVolume(p.listedQty)}u @ ${formatPriceAbbr(p.listedPrice ?: 0.0)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = warningColor.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
        alloc.sellTarget?.let { st ->
            Tip(
                "Sell the whole stack in one order at or above the target (average cost + take-profit %, after sales tax " +
                    "and broker fee). \"now\" is the net profit selling everything at the current lowest ask.",
            ) {
                Row(modifier = Modifier.padding(start = 28.dp, top = 1.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Sell: ${formatVolume(st.qty)}u @ ≥${formatPriceAbbr(st.targetPrice)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    if (st.profitNow != null && c.bestAsk != null) {
                        Text(
                            "now ${formatPriceAbbr(c.bestAsk)} → ${if (st.profitNow >= 0) "+" else ""}${formatPriceAbbr(st.profitNow)} " +
                                "(${signedPct(st.profitNowPct ?: 0.0)})",
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
@Composable
private fun Tip(
    text: String,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 4.dp) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(8.dp).widthIn(max = 280.dp),
                )
            }
        },
        content = content,
    )
}

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
