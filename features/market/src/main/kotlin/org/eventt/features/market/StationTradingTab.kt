package org.eventt.features.market

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
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
import org.eventt.core.model.PLEX_MARKET_REGION_ID
import org.eventt.core.model.PLEX_TYPE_ID
import org.eventt.core.model.StaticMarketGroupModel
import org.eventt.core.model.StaticRegionModel
import org.eventt.core.model.StaticStationModel
import org.eventt.core.staticdata.JumpGraphService
import org.eventt.ui.common.ensureVisible
import org.eventt.ui.theme.negativeColor
import java.util.Locale

// ─── Station Trading ──────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StationTradingTab(
    allRegions: List<StaticRegionModel>,
    topGroups: List<StaticMarketGroupModel>,
    charId: Int?,
) {
    val scope = rememberCoroutineScope()

    var regionId by remember { mutableStateOf(10000002) }
    var stationId by remember { mutableStateOf<Long?>(null) }
    var stations by remember { mutableStateOf<List<StaticStationModel>>(emptyList()) }
    var selectedTopGroup by remember { mutableStateOf<StaticMarketGroupModel?>(null) }
    var selectedSubGroup by remember { mutableStateOf<StaticMarketGroupModel?>(null) }
    var subGroups by remember { mutableStateOf<List<StaticMarketGroupModel>>(emptyList()) }
    var minMargin by remember { mutableStateOf("5") }
    var minDailyVol by remember { mutableStateOf("10") }
    var maxBuyPrice by remember { mutableStateOf("500000000") }
    var minNetProfit by remember { mutableStateOf("100000") }
    var isAnalyzing by remember { mutableStateOf(false) }
    var analyzeJob by remember { mutableStateOf<Job?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StationOpportunity>>(emptyList()) }
    var sortCol by remember { mutableStateOf(StationSortCol.NET_PROFIT) }
    var sortAsc by remember { mutableStateOf(false) }
    var brokerFeePct by remember { mutableStateOf(3.0) }
    var salesTaxPct by remember { mutableStateOf(8.0) }
    var volCapEnabled by remember { mutableStateOf(false) }
    var volCapPct by remember { mutableStateOf("10") }
    var copyVolumeEnabled by remember { mutableStateOf(true) }
    var skipExistingOrders by remember { mutableStateOf(false) }
    var spikeFilter by remember { mutableStateOf(SpikeFilter.ANY) }
    var histSourceIsEsi by remember { mutableStateOf(false) }
    var detailTypeId by remember { mutableStateOf<Int?>(null) }

    // Load persisted settings + character tax values
    LaunchedEffect(charId) {
        withContext(Dispatchers.IO) {
            histSourceIsEsi = EveRefService.getSelectedSource() == "esi"
            S.get(S.ST_REGION)?.toIntOrNull()?.let { regionId = it }
            S.get(S.ST_STATION)?.toLongOrNull()?.let { stationId = it }
            S.get(S.ST_MARGIN)?.let { minMargin = it }
            S.get(S.ST_MIN_VOL)?.let { minDailyVol = it }
            S.get(S.ST_MAX_PRICE)?.let { maxBuyPrice = it }
            S.get(S.ST_MIN_PROFIT)?.let { minNetProfit = it }
            S.get(S.ST_VOL_CAP_ENABLED)?.let { volCapEnabled = it == "true" }
            S.get(S.ST_VOL_CAP_PCT)?.let { volCapPct = it }
            S.get(S.ST_COPY_VOLUME)?.let { copyVolumeEnabled = it == "true" }
            S.get(S.ST_SKIP_EXISTING)?.let { skipExistingOrders = it == "true" }
            S.get(S.ST_SPIKE_FILTER)?.let { name -> SpikeFilter.entries.find { it.name == name }?.let { spikeFilter = it } }
            if (charId != null) {
                brokerFeePct = StaticDataDao.getCharBrokersFee(charId)
                salesTaxPct = StaticDataDao.getCharSalesTax(charId)
            }
        }
    }

    // Reload stations when region changes
    LaunchedEffect(regionId) {
        val loaded = withContext(Dispatchers.IO) { StaticDataDao.getStationsByRegion(regionId) }
        stations = loaded
        // If saved station is in the new region keep it, otherwise clear
        if (stationId != null && loaded.none { it.stationId == stationId }) {
            stationId = null
            scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_STATION, "") } }
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
            // Row 1: location + category on the left, read-only fees info pinned right.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                RegionPicker(allRegions, regionId, width = 180.dp, accentColor = MaterialTheme.colorScheme.primary) {
                    regionId = it
                    scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_REGION, it.toString()) } }
                }
                StationPicker(stations, stationId, width = 200.dp) {
                    stationId = it
                    scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_STATION, it?.toString() ?: "") } }
                }
                FilterDivider()
                GroupDropdown("Category", topGroups, selectedTopGroup, "All categories", 150.dp) { g ->
                    selectedTopGroup = g
                    selectedSubGroup = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            S.set(S.ST_CAT_TOP, g?.marketGroupId?.toString() ?: "")
                            S.set(S.ST_CAT_SUB, "")
                        }
                    }
                }
                if (subGroups.isNotEmpty()) {
                    GroupDropdown("Subcategory", subGroups, selectedSubGroup, "All", 140.dp) { g ->
                        selectedSubGroup = g
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_CAT_SUB, g?.marketGroupId?.toString() ?: "") } }
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
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_MARGIN, it) } }
                    }
                    ParamField("Min Vol", minDailyVol, 72.dp) {
                        minDailyVol = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_MIN_VOL, it) } }
                    }
                    ParamField("Max Buy", maxBuyPrice, 105.dp) {
                        maxBuyPrice = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_MAX_PRICE, it) } }
                    }
                    ParamField("Min Net", minNetProfit, 100.dp) {
                        minNetProfit = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_MIN_PROFIT, it) } }
                    }
                    FilterDivider()
                    // Volume modifier — scales the suggested/displayed daily volume by this percentage
                    // (e.g. entering 50 shows/copies 50% of the computed daily volume).
                    CheckboxParamField(
                        label = "Vol %",
                        checked = volCapEnabled,
                        onCheckedChange = {
                            volCapEnabled = it
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_VOL_CAP_ENABLED, it.toString()) } }
                        },
                        value = volCapPct,
                        onValueChange = { v ->
                            volCapPct = v
                            scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_VOL_CAP_PCT, v) } }
                        },
                    )
                    FilterControl("Skip Owned Items") {
                        Checkbox(
                            checked = skipExistingOrders,
                            onCheckedChange = {
                                skipExistingOrders = it
                                scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_SKIP_EXISTING, it.toString()) } }
                            },
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    SpikeFilterChip(spikeFilter) {
                        spikeFilter = it
                        scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_SPIKE_FILTER, it.name) } }
                    }
                    FilterDivider()
                    // Toggles whether the hotkey's second press copies the suggested volume, or just
                    // advances straight to the next item after copying the price.
                    FilterControl("Copy Vol") {
                        Switch(
                            checked = copyVolumeEnabled,
                            onCheckedChange = {
                                copyVolumeEnabled = it
                                scope.launch { withContext(Dispatchers.IO) { S.set(S.ST_COPY_VOLUME, it.toString()) } }
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
                                    results = emptyList()
                                    try {
                                        val filterGroupId = selectedSubGroup?.marketGroupId ?: selectedTopGroup?.marketGroupId
                                        val filterGroupIds = filterGroupId?.let { withContext(Dispatchers.IO) { buildGroupSubtree(it) } }

                                        val allTypeIds =
                                            withContext(Dispatchers.IO) {
                                                if (filterGroupIds != null) {
                                                    StaticDataDao.getTypeIdsByMarketGroups(filterGroupIds)
                                                } else {
                                                    StaticDataDao.getAllMarketTypeIds()
                                                }
                                            }
                                        // Region-scoped, not character-scoped: excludes a type if
                                        // ANY locally-known character/corp already has an active
                                        // order for it in this region — saves the ESI calls too.
                                        val typeIds =
                                            if (skipExistingOrders) {
                                                val excluded =
                                                    withContext(Dispatchers.IO) {
                                                        ActiveOrderDao
                                                            .getAll()
                                                            .filter { it.regionId == regionId }
                                                            .map { it.typeId }
                                                            .toSet()
                                                    }
                                                allTypeIds.filter { it !in excluded }
                                            } else {
                                                allTypeIds
                                            }

                                        // Empty = no minimum required (matches Min Vol/Min Net's
                                        // convention below), not a silent revert to a nonzero default.
                                        val minMarginD = minMargin.toDoubleOrNull() ?: 0.0
                                        val minDailyVolL = minDailyVol.toLongOrNull() ?: 0L
                                        val maxBuyPriceD = maxBuyPrice.toDoubleOrNull() ?: Double.MAX_VALUE
                                        val minNetProfitD = minNetProfit.toDoubleOrNull() ?: 0.0
                                        val brokerFeePctD = brokerFeePct
                                        val salesTaxPctD = salesTaxPct
                                        val stationIdSnap = stationId
                                        val spikeFilterSnap = spikeFilter
                                        val histSrc = withContext(Dispatchers.IO) { EveRefService.getSelectedSource() }

                                        // Buy orders sitting at a different station/citadel — even in a
                                        // neighboring system — still compete if their order range reaches
                                        // the station being analyzed. Build the region's jump graph once
                                        // up front (cached permanently after the first run) so every
                                        // type's range check below is just a map lookup, not a network call.
                                        val stationSystemId =
                                            stationIdSnap?.let {
                                                withContext(Dispatchers.IO) { StaticDataDao.getStationById(it)?.systemId }
                                            }
                                        val distanceFromStation: Map<Int, Int> =
                                            if (stationSystemId != null) {
                                                withContext(Dispatchers.IO) {
                                                    JumpGraphService.ensureRegionGraph(regionId) { progress ->
                                                        statusMsg = "Building jump graph: ${progress.fetched}/${progress.total} systems…"
                                                    }
                                                    JumpGraphService.bfsDistances(stationSystemId, regionId)
                                                }
                                            } else {
                                                emptyMap()
                                            }
                                        val locationSystemCache = java.util.concurrent.ConcurrentHashMap<Long, Int?>()

                                        // Above ~1000 types (or with no category filter at all), one
                                        // paginated all-orders fetch (~a few hundred pages) beats
                                        // thousands of per-type requests. PLEX still needs its own
                                        // call either way — it trades in its own special region.
                                        val bulkByType: Map<Int, List<Map<String, Any?>>>? =
                                            if (filterGroupIds == null || typeIds.size > BULK_ORDER_FETCH_THRESHOLD) {
                                                statusMsg = "Fetching all region orders…"
                                                withContext(Dispatchers.IO) { EsiClient.getMarketRegionOrders(regionId) }
                                                    .groupBy { (it["type_id"] as? Number)?.toInt() ?: 0 }
                                            } else {
                                                null
                                            }

                                        statusMsg = "0/${typeIds.size} types checked…"

                                        val semaphore = Semaphore(10)
                                        val mutex = Mutex()
                                        val found = mutableListOf<StationOpportunity>()
                                        var checked = 0

                                        coroutineScope {
                                            typeIds
                                                .map { typeId ->
                                                    async(Dispatchers.IO) {
                                                        semaphore.withPermit {
                                                            runCatching {
                                                                val effRegion =
                                                                    if (typeId ==
                                                                        PLEX_TYPE_ID
                                                                    ) {
                                                                        PLEX_MARKET_REGION_ID
                                                                    } else {
                                                                        regionId
                                                                    }
                                                                val orders =
                                                                    if (bulkByType != null && effRegion == regionId) {
                                                                        bulkByType[typeId].orEmpty()
                                                                    } else {
                                                                        EsiClient.getMarketRegionOrders(effRegion, typeId = typeId)
                                                                    }
                                                                val opp =
                                                                    computeOpportunityForType(
                                                                        typeId,
                                                                        orders,
                                                                        effRegion,
                                                                        minMarginD,
                                                                        minDailyVolL,
                                                                        maxBuyPriceD,
                                                                        minNetProfitD,
                                                                        brokerFeePctD,
                                                                        salesTaxPctD,
                                                                        stationIdSnap,
                                                                        histSrc,
                                                                        stationSystemId =
                                                                            if (effRegion ==
                                                                                regionId
                                                                            ) {
                                                                                stationSystemId
                                                                            } else {
                                                                                null
                                                                            },
                                                                        distanceFromStation =
                                                                            if (effRegion ==
                                                                                regionId
                                                                            ) {
                                                                                distanceFromStation
                                                                            } else {
                                                                                emptyMap()
                                                                            },
                                                                        locationSystemCache = locationSystemCache,
                                                                        spikeFilter = spikeFilterSnap,
                                                                    )
                                                                // Protect shared list mutation on IO, then update Compose state on Main
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
                            Icon(Icons.Default.Search, null, Modifier.size(14.dp))
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
                icon = Icons.Default.Store,
                primary = "Configure filters and click Analyze",
                secondary = "Finds items with profitable spread between buy and sell orders at the same station",
            )
        } else {
            // Not capped at 100 — this scales volume in either direction (50 halves it, 200 doubles it).
            val volCapPctVal = volCapPct.toDoubleOrNull()?.coerceIn(0.01, 1000.0) ?: 10.0
            val sorted =
                remember(results, sortCol, sortAsc, volCapEnabled, volCapPctVal) {
                    sortStation(results, sortCol, sortAsc, volCapEnabled, volCapPctVal)
                }
            var selectedIds by remember(results) { mutableStateOf(setOf<Int>()) }
            val listState = rememberLazyListState()
            var dragStartIdx by remember { mutableStateOf<Int?>(null) }
            var isDragging by remember { mutableStateOf(false) }
            val activeTypeId by StationTradingQueue.currentTypeId.collectAsState()

            // Keep the hotkey queue in sync with what's on screen — the selected subset if the
            // user has picked specific items, otherwise every currently sorted/filtered opportunity.
            LaunchedEffect(charId, sorted, selectedIds, volCapEnabled, volCapPctVal, copyVolumeEnabled) {
                StationTradingQueue.copyVolume = copyVolumeEnabled
                val cid = charId
                if (cid == null) {
                    StationTradingQueue.clear()
                } else {
                    val source = if (selectedIds.isNotEmpty()) sorted.filter { it.typeId in selectedIds } else sorted
                    StationTradingQueue.update(
                        source.map { opp ->
                            PendingStationItem(
                                charId = cid,
                                typeId = opp.typeId,
                                typeName = opp.typeName,
                                bestBuy = opp.bestBuy,
                                volume = stationEffVol(opp, volCapEnabled, volCapPctVal),
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

            StationHeader(sortCol, sortAsc) { col ->
                if (sortCol == col) {
                    sortAsc = !sortAsc
                } else {
                    sortCol = col
                    sortAsc = false
                }
            }
            if (charId != null && StationTradingQueue.size > 0) {
                val hotkeyLabel by HotkeyBindings.queueLabel.collectAsState()
                Text(
                    "$hotkeyLabel cycles ${StationTradingQueue.size} item(s) — position " +
                        "${StationTradingQueue.currentPosition}/${StationTradingQueue.size}",
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
                                .joinToString("\n") { "${it.typeName}\t${stationEffVol(it, volCapEnabled, volCapPctVal)}" }
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
                        StationRow(
                            opp,
                            idx,
                            opp.typeId in selectedIds,
                            opp.typeId == activeTypeId,
                            volCapEnabled,
                            volCapPctVal,
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
            primaryRegionId = regionId,
            primaryRegionName = allRegions.find { it.regionId == regionId }?.name ?: "",
            primaryStationId = stationId,
            charId = charId,
            onDismiss = { detailTypeId = null },
        )
    }
}
