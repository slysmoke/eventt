package org.eventt.features.market

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
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
import org.eventt.core.database.AlertDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.WalletDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.everef.EveRefService
import org.eventt.core.model.PriceAlertModel
import org.eventt.core.model.StaticMarketGroupModel
import org.eventt.core.model.StaticRegionModel
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
    var isAnalyzing by remember { mutableStateOf(false) }
    var analyzeJob by remember { mutableStateOf<Job?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<MaterialCandidate>>(emptyList()) }
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
        remember(candidates, totalBudget, maxItems, maxPerItemPct, liquidityDays, ladderLevels, ladderStepPct) {
            allocateBudget(
                candidates = candidates,
                totalBudget = totalBudget.toDoubleOrNull() ?: 0.0,
                maxItems = maxItems.toIntOrNull() ?: 10,
                maxPerItemPct = maxPerItemPct.toDoubleOrNull() ?: 25.0,
                liquidityDays = liquidityDays.toDoubleOrNull() ?: 3.0,
                ladderLevels = ladderLevels.toIntOrNull() ?: 4,
                ladderStepPct = ladderStepPct.toDoubleOrNull() ?: 5.0,
            )
        }
    val sorted = remember(allocated, sortCol, sortAsc) { sortMaterials(allocated, sortCol, sortAsc) }

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
                            "item's allocation -- buy more the further it falls.",
                    ) {
                        ParamField("Ladder Step %", ladderStepPct, 85.dp) {
                            ladderStepPct = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.MI_LADDER_STEP_PCT, it) } }
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
                                                                computeMaterialCandidate(
                                                                    typeId = typeId,
                                                                    orders = ordersByType[typeId].orEmpty(),
                                                                    regionId = regionId,
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
            MaterialsHeader(sortCol, sortAsc) { col ->
                if (sortCol == col) {
                    sortAsc = !sortAsc
                } else {
                    sortCol = col
                    sortAsc = true
                }
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(sorted, key = { it.candidate.typeId }) { alloc ->
                    MaterialRow(
                        alloc,
                        onShowDetails = { detailTypeId = it },
                        onCreateAlerts = {
                            scope.launch {
                                withContext(Dispatchers.IO) { createLadderAlerts(alloc, regionId, charId) }
                                statusMsg = "Alerts set for ${alloc.candidate.typeName}"
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
    onSort: (MaterialSortCol) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.width(28.dp),
            )
            MCol("Item", MaterialSortCol.NAME, sort, asc, onSort, Modifier.weight(1f))
            Tip("Live best sell price -- what you'd actually pay to buy in right now.") {
                MCol("Current", MaterialSortCol.CURRENT, sort, asc, onSort, Modifier.width(80.dp))
            }
            Tip("Average price over the Lookback-day window.") {
                MCol("Avg", MaterialSortCol.AVG, sort, asc, onSort, Modifier.width(80.dp))
            }
            Tip(
                "Your current holding: quantity @ average buy price, from your wallet transaction history " +
                    "(average-cost, not FIFO). Used to anchor the buy ladder to your real entry instead of the live price.",
            ) {
                MCol("Held", MaterialSortCol.HELD, sort, asc, onSort, Modifier.width(95.dp))
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
                    "a year of this item's own history. Format: final P&L% (worst paper drawdown% along the way). A negative " +
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
            Tip("ISK allocated to this item from your total budget, after ranking, the per-item cap, and the liquidity cap.") {
                MCol("Allocated", MaterialSortCol.ALLOCATED, sort, asc, onSort, Modifier.width(80.dp))
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
    onShowDetails: (Int) -> Unit,
    onCreateAlerts: () -> Unit,
) {
    val c = alloc.candidate
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(28.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.typeName,
                    style = MaterialTheme.typography.bodySmall,
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
                modifier = Modifier.width(95.dp),
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
            Text(
                formatPriceAbbr(alloc.allocatedIsk),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(80.dp),
            )
            Tip("Create price alerts (Alerts tab) for this item's remaining buy-ladder trigger prices -- skips levels already crossed.") {
                IconButton(onClick = onCreateAlerts, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.NotificationsActive,
                        contentDescription = "Create alerts for this item's buy ladder",
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
        val ladderLine =
            buildString {
                if (alloc.ladder.isNotEmpty()) {
                    append("Buy: ")
                    append(
                        alloc.ladder.joinToString("  ·  ") {
                            "${formatPriceAbbr(it.triggerPrice)}→${formatPriceAbbr(it.iskAmount)}" + if (it.alreadyTriggered) "*" else ""
                        },
                    )
                }
                if (alloc.sellLadder.isNotEmpty()) {
                    if (isNotEmpty()) append("   ")
                    append("Sell: ")
                    append(alloc.sellLadder.joinToString("  ·  ") { "${formatPriceAbbr(it.targetPrice)}→${formatVolume(it.qty)}u" })
                }
            }
        if (ladderLine.isNotEmpty()) {
            Tip(
                "Buy: trigger price → ISK to spend at that level (* = already at/below the live price). " +
                    "Sell: your take-profit target price → quantity to sell, only shown while you hold a position.",
            ) {
                Text(
                    ladderLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 28.dp, top = 1.dp),
                )
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

// Creates a below-current-price alert (AlertMonitor compares against best sell, matching what
// `currentPrice` means throughout this tab) for each buy-ladder level not already crossed, skipping
// any that already have a live, un-triggered alert at essentially the same price -- so clicking the
// bell again after a re-scan doesn't pile up duplicate alerts for the same trigger.
private fun createLadderAlerts(
    alloc: AllocatedMaterial,
    regionId: Int,
    charId: Int?,
) {
    val existing = runCatching { AlertDao.getAll() }.getOrDefault(emptyList())
    alloc.ladder.filterNot { it.alreadyTriggered }.forEach { level ->
        val duplicate =
            existing.any {
                !it.triggered && it.typeId == alloc.candidate.typeId && it.regionId == regionId && it.condition == "below" &&
                    kotlin.math.abs(it.targetPrice - level.triggerPrice) < 0.01
            }
        if (!duplicate) {
            AlertDao.insert(
                PriceAlertModel(
                    typeId = alloc.candidate.typeId,
                    typeName = alloc.candidate.typeName,
                    targetPrice = level.triggerPrice,
                    condition = "below",
                    regionId = regionId,
                    orderType = "sell",
                    characterId = charId,
                ),
            )
        }
    }
}
