package org.eventt.features.contracts

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.contracts.generated.resources.*
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.ContractDao
import org.eventt.core.database.CorporationDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.ViewContext
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.ContractItemModel
import org.eventt.core.model.ContractModel
import org.eventt.core.model.CorpFeature
import org.eventt.core.model.stringBlocking
import org.eventt.ui.common.*
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.stringResource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val SHOW_ALL_CONTRACTS_SETTING = "contracts.show_all"

// Character/corp names don't change; resolved once per app run instead of on every reload.
private val nameCache = ConcurrentHashMap<Int, String>()

@Composable
fun ContractTrackerScreen(context: ViewContext?) {
    val scope = rememberCoroutineScope()
    val charId = (context as? ViewContext.Character)?.charId
    val corpId = (context as? ViewContext.Corporation)?.corporationId
    val actingCharId = context?.actingCharId

    var lines by remember { mutableStateOf<List<ContractLine>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    // Ignores the character/corp scope below and shows every locally-stored contract, across
    // every character and corporation — mirrors Dashboard's "Combine all" switch. Persisted.
    var showAll by remember { mutableStateOf(false) }
    // Persisted; also gates ContractWatchService's background sweep (see there for why it's
    // opt-in rather than always-on).
    var autoRefresh by remember { mutableStateOf(false) }
    var refreshAvailableAt by remember { mutableStateOf<Long?>(null) }
    var deniedFeatures by remember { mutableStateOf<Set<CorpFeature>>(emptySet()) }
    var reloadNonce by remember { mutableStateOf(0) }

    fun contractsEndpoint(): String? =
        when {
            corpId != null -> "/corporations/$corpId/contracts/"
            charId != null -> "/characters/$charId/contracts/"
            else -> null
        }

    fun refresh(clearDenied: Boolean = false) {
        val acting = actingCharId ?: return
        isLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                if (clearDenied) {
                    CorpFeature.entries.forEach { CharacterDao.setCorpFeatureDenied(acting, it, denied = false) }
                }
                ContractSyncService.refresh(characterId = charId, corporationId = corpId, actingCharId = acting)
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    reloadNonce++
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            showAll = StaticDataDao.getSetting(SHOW_ALL_CONTRACTS_SETTING) == "true"
            autoRefresh = StaticDataDao.getSetting(CONTRACTS_AUTO_REFRESH_SETTING) == "true"
        }
    }

    LaunchedEffect(context, showAll, reloadNonce) {
        lines = withContext(Dispatchers.IO) { loadLines(if (showAll) null else charId, if (showAll) null else corpId, showAll) }
        refreshAvailableAt =
            if (showAll) null else withContext(Dispatchers.IO) { contractsEndpoint()?.let { EsiClient.getEndpointExpiry(it) } }
        deniedFeatures =
            if (corpId != null && actingCharId != null) {
                withContext(Dispatchers.IO) { CharacterDao.getDeniedCorpFeatures(actingCharId) }
            } else {
                emptySet()
            }
    }

    // Keeps refreshing this context's contracts on an interval while the toggle is on and this
    // screen is open — ContractWatchService's own sweep covers the "screen not open" case.
    LaunchedEffect(autoRefresh, context, showAll) {
        if (!autoRefresh || showAll || actingCharId == null) return@LaunchedEffect
        while (true) {
            delay(CONTRACTS_REFRESH_INTERVAL_MILLIS)
            refresh()
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.contract_tracker), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Segment(stringResource(Res.string.show_all), showAll) {
                showAll = !showAll
                scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(SHOW_ALL_CONTRACTS_SETTING, showAll.toString()) }
            }
            Segment(stringResource(Res.string.auto_refresh), autoRefresh) {
                autoRefresh = !autoRefresh
                scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(CONTRACTS_AUTO_REFRESH_SETTING, autoRefresh.toString()) }
            }
            EsiRefreshButton(
                isLoading = isLoading,
                expiresAtMs = refreshAvailableAt,
                enabled = actingCharId != null && !showAll,
                onClick = { refresh() },
            )
        }

        if (corpId != null && !showAll && deniedFeatures.isNotEmpty()) {
            CorpAccessNotice(deniedFeatures, onRetry = { refresh(clearDenied = true) })
        }

        val all = lines
        when {
            all == null -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }

            all.isEmpty() -> {
                EmptyState(
                    icon = Icons.Default.Folder,
                    title = stringResource(Res.string.no_contracts),
                    description = stringResource(Res.string.select_char_contracts),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            else -> {
                ContractsBody(all, showOwner = showAll, actingCharId = actingCharId, modifier = Modifier.weight(1f))
            }
        }
    }

    LoadingOverlay(isLoading = isLoading, message = stringResource(Res.string.fetching_contracts))
}

// Reads the scope's contracts (null ids = everyone's) and resolves stations and parties for display.
internal fun loadLines(
    characterId: Int?,
    corporationId: Int?,
    showOwner: Boolean,
): List<ContractLine> {
    val contracts = ContractDao.getAll(characterId = characterId, corporationId = corporationId)
    val chars = CharacterDao.getAll().associate { it.id to it.name }
    val corps = if (showOwner) CorporationDao.getAll().associate { (it["id"] as Int) to (it["name"] as? String ?: "") } else emptyMap()
    val missing =
        contracts
            .flatMap { listOf(it.issuerId, it.assigneeId, it.acceptorId) }
            .filter { it > 0 && it !in chars && !nameCache.containsKey(it) }
            .distinct()
    if (missing.isNotEmpty()) runCatching { nameCache.putAll(EsiClient.resolveNames(missing)) }

    fun name(id: Int) = if (id <= 0) null else chars[id] ?: nameCache[id] ?: "#$id"
    val stations =
        contracts.flatMap { listOf(it.startStationId, it.endStationId) }.distinct().associateWith { id ->
            StaticDataDao.getStationById(id)?.let { it.systemName.ifEmpty { it.name.substringBefore(" - ") } } ?: "#$id"
        }
    return contracts.map { c ->
        val ours = c.issuerId in chars
        val party = if (ours) name(c.acceptorId) ?: name(c.assigneeId) else name(c.issuerId)
        val owner = if (showOwner) c.corporationId?.let { corps[it] ?: "#$it" } ?: c.characterId?.let { chars[it] ?: "#$it" } else null
        ContractLine(c, stations[c.startStationId] ?: "", stations[c.endStationId] ?: "", party, owner)
    }
}

/** Everything under the header for already-built lines — no ESI here except a selected contract's items. */
@Composable
internal fun ContractsBody(
    all: List<ContractLine>,
    showOwner: Boolean,
    actingCharId: Int?,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    // Opens on what needs watching, or on everything when nothing does.
    var tab by remember { mutableStateOf(if (all.any { it.group == StatusGroup.ACTIVE }) StatusGroup.ACTIVE else StatusGroup.ALL) }
    var type by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    val inTab = remember(all, tab) { if (tab == StatusGroup.ALL) all else all.filter { it.group == tab } }
    val shown =
        remember(inTab, type, query) {
            inTab.filter { l ->
                (type == null || l.c.type == type) &&
                    (query.isBlank() || l.search.contains(query.trim(), ignoreCase = true))
            }
        }
    val selected = all.find { it.c.contractId == selectedId }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TabBar(
            listOf(
                TabItem(stringResource(Res.string.tab_active), all.count { it.group == StatusGroup.ACTIVE }, OUTSTANDING_COLOR),
                TabItem(stringResource(Res.string.tab_finished), all.count { it.group == StatusGroup.FINISHED }, positiveColor),
                TabItem(stringResource(Res.string.tab_closed), all.count { it.group == StatusGroup.CLOSED }, negativeColor),
                TabItem(stringResource(Res.string.status_all), all.size),
            ),
            tab.ordinal,
            { tab = StatusGroup.entries[it] },
        )
        TotalsStrip(remember(all) { totals(all, now) })
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Panel(
                null,
                Modifier.weight(0.7f).fillMaxHeight(),
                header = {
                    Segment(stringResource(Res.string.type_all), type == null) { type = null }
                    inTab.map { it.c.type }.distinct().sorted().forEach { t ->
                        Segment(contractTypeLabel(t), type == t) { type = if (type == t) null else t }
                    }
                    Spacer(Modifier.weight(1f))
                },
            ) {
                TerminalSearch(query, { query = it }, stringResource(Res.string.search_contracts), Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                ContractsTable(shown, showOwner, selectedId, now) {
                    selectedId =
                        if (selectedId == it.c.contractId) null else it.c.contractId
                }
            }
            Column(Modifier.weight(0.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selected != null) {
                    Panel(
                        stringResource(Res.string.panel_contract),
                        Modifier.fillMaxSize(),
                        header = {
                            Spacer(Modifier.weight(1f))
                            Segment("✕", false) { selectedId = null }
                        },
                    ) { ContractDetails(selected, actingCharId, now) }
                } else {
                    Panel(stringResource(Res.string.panel_routes), Modifier.fillMaxWidth().weight(1f)) {
                        StatTable(
                            remember(all) { lanes(all, now) },
                            stringResource(Res.string.h_route),
                            stringResource(Res.string.no_couriers),
                        )
                    }
                    Panel(stringResource(Res.string.panel_parties), Modifier.fillMaxWidth().weight(1f)) {
                        StatTable(
                            remember(all) { parties(all, now) },
                            stringResource(Res.string.h_party),
                            stringResource(Res.string.no_parties),
                        )
                    }
                }
            }
        }
    }
}

private val OUTSTANDING_COLOR = Color(0xFFB197FC)

@Composable
private fun statusColor(status: String): Color =
    when {
        status == "outstanding" -> OUTSTANDING_COLOR
        status == "in_progress" -> warningColor
        status.startsWith("finished") -> positiveColor
        status == "cancelled" || status == "deleted" -> AXIS_COLOR
        else -> negativeColor
    }

@Composable
private fun TotalsStrip(t: ContractTotals) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.dp, outline(), MaterialTheme.shapes.extraSmall)) {
        Kpi(
            stringResource(Res.string.kpi_active),
            t.active.toString(),
            stringResource(Res.string.kpi_in_transit, t.inTransit.toString()),
            null,
            OUTSTANDING_COLOR,
        )
        Kpi(
            stringResource(Res.string.kpi_collateral),
            formatIsk(t.collateralInTransit),
            stringResource(Res.string.kpi_outstanding, formatIsk(t.collateralOutstanding)),
            null,
            null,
            Res.string.tip_collateral,
        )
        Kpi(
            stringResource(Res.string.kpi_rewards),
            formatIsk(t.rewards),
            stringResource(Res.string.kpi_n_30d, t.couriers.toString(), formatIsk(t.rewards30d)),
            null,
            null,
            Res.string.tip_rewards,
        )
        Kpi(
            stringResource(Res.string.kpi_reward_rate),
            t.rewardToCollateralPct?.let { String.format(Locale.US, "%.2f%%", it) } ?: "—",
            null,
            null,
            null,
            Res.string.tip_reward_rate,
        )
        Kpi(
            stringResource(Res.string.kpi_delivery),
            t.avgDelivery?.let(::formatSpan) ?: "—",
            t.avgAccept?.let { stringResource(Res.string.kpi_accept, formatSpan(it)) },
            null,
            null,
            Res.string.tip_delivery,
        )
        Kpi(
            stringResource(Res.string.kpi_exchange),
            formatIsk(t.exchange),
            stringResource(Res.string.kpi_n_30d, t.exchanges.toString(), formatIsk(t.exchange30d)),
            null,
            null,
        )
        Kpi(
            stringResource(Res.string.kpi_expiring),
            t.expiring24h.toString(),
            null,
            null,
            null,
            valueColor = warningColor.takeIf { t.expiring24h > 0 },
            last = true,
        )
    }
}

private val DATE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
private val FULL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun ContractsTable(
    lines: List<ContractLine>,
    showOwner: Boolean,
    selectedId: Int?,
    now: Instant,
    onClick: (ContractLine) -> Unit,
) {
    val cols =
        buildList<Col<ContractLine>> {
            add(Col(stringResource(Res.string.h_type), 0.9f, end = false) { it.c.type })
            add(Col(stringResource(Res.string.h_status), 0.9f, end = false) { it.c.status })
            add(Col(stringResource(Res.string.h_route_title), 2.2f, end = false) { it.search.lowercase() })
            add(Col(stringResource(Res.string.h_party), 1.5f, end = false) { it.party?.lowercase() })
            if (showOwner) add(Col(stringResource(Res.string.h_owner), 1.2f, end = false) { it.owner?.lowercase() })
            add(Col(stringResource(Res.string.h_value), 0.9f) { it.value })
            add(Col(stringResource(Res.string.info_collateral), 0.9f) { it.c.collateral })
            add(
                Col(
                    stringResource(Res.string.h_rate),
                    0.6f,
                ) { l ->
                    l.c.collateral
                        .takeIf { l.isCourier && it > 0 }
                        ?.let { l.c.reward / it }
                },
            )
            add(Col(stringResource(Res.string.sort_issued), 0.9f) { it.issued })
            add(Col(stringResource(Res.string.h_time), 0.8f) { it.age(now) })
        }
    Table(
        cols = cols,
        rows = lines,
        empty = stringResource(Res.string.contracts_no_match),
        defaultSort = cols.indexOfFirst { it.title == stringResource(Res.string.sort_issued) },
        key = { it.c.contractId },
        onClick = onClick,
        highlight = { it.c.contractId == selectedId },
    ) { l ->
        val expiring = l.c.status == "outstanding" && l.expires?.isBefore(now.plus(Duration.ofDays(1))) == true
        buildList {
            add(Cell(contractTypeLabel(l.c.type), AXIS_COLOR))
            add(Cell(contractStatusLabel(l.c.status), statusColor(l.c.status)))
            add(Cell(if (l.isCourier) "${l.from} → ${l.to}" else l.c.title.ifEmpty { l.from }))
            add(Cell(l.party ?: stringResource(Res.string.party_public), AXIS_COLOR.takeIf { l.party == null }))
            if (showOwner) add(Cell(l.owner.orEmpty(), AXIS_COLOR))
            add(Cell(if (l.value > 0) formatIsk(l.value) else "—"))
            add(Cell(if (l.c.collateral > 0) formatIsk(l.c.collateral) else "—", AXIS_COLOR))
            add(
                Cell(
                    if (l.isCourier && l.c.collateral > 0) String.format(Locale.US, "%.2f%%", l.c.reward / l.c.collateral * 100) else "",
                    AXIS_COLOR,
                ),
            )
            add(Cell(l.issued?.let(DATE_TIME::format) ?: "", AXIS_COLOR))
            add(Cell(l.age(now)?.let(::formatSpan) ?: "—", warningColor.takeIf { expiring }))
        }
    }
}

// Routes / counterparties: count, total value, average issued → done time.
@Composable
private fun StatTable(
    rows: List<LaneStat>,
    title: String,
    empty: String,
) {
    Table(
        cols =
            listOf(
                Col(title, 2f, end = false) { it.label.lowercase() },
                Col("#", 0.5f) { it.count },
                Col(stringResource(Res.string.h_value), 1f) { it.value },
                Col(stringResource(Res.string.h_avg_time), 0.9f) { it.avgTime },
            ),
        rows = rows,
        empty = empty,
        defaultSort = 1,
        key = { it.label },
    ) { s ->
        listOf(
            Cell(s.label),
            Cell(s.count.toString()),
            Cell(formatIsk(s.value)),
            Cell(s.avgTime?.let(::formatSpan) ?: "—", AXIS_COLOR),
        )
    }
}

@Composable
private fun ContractDetails(
    l: ContractLine,
    actingCharId: Int?,
    now: Instant,
) {
    val contract = l.c
    var items by remember(contract.contractId) { mutableStateOf<List<ContractItemModel>?>(null) }
    var places by remember(contract.contractId) { mutableStateOf(l.from to l.to) }

    // Items are fetched on first open (cached locally afterward) rather than for every row up
    // front — they're only useful once you're looking at one contract.
    LaunchedEffect(contract.contractId) {
        withContext(Dispatchers.IO) {
            places =
                (StaticDataDao.getStationById(contract.startStationId)?.name ?: l.from) to
                (StaticDataDao.getStationById(contract.endStationId)?.name ?: l.to)
            items = loadItems(contract, actingCharId)
        }
    }

    fun time(s: String?) = parseEsiTime(s)?.let(FULL_DATE_TIME::format)

    LazyColumn {
        item {
            Text(
                "${contractTypeLabel(contract.type)} · ${contractStatusLabel(contract.status)}",
                style = MaterialTheme.typography.labelMedium,
                color = statusColor(contract.status),
                fontWeight = FontWeight.Bold,
            )
            if (contract.title.isNotEmpty()) Text(contract.title, style = MaterialTheme.typography.titleSmall)
            if (contract.description.isNotBlank()) {
                Text(contract.description, style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR)
            }
            Spacer(Modifier.height(6.dp))
            if (contract.price > 0) KV(stringResource(Res.string.info_price), formatIsk(contract.price))
            if (contract.reward > 0) KV(stringResource(Res.string.info_reward), formatIsk(contract.reward))
            if (contract.collateral > 0) KV(stringResource(Res.string.info_collateral), formatIsk(contract.collateral))
            if (contract.buyout > 0) KV(stringResource(Res.string.info_buyout), formatIsk(contract.buyout))
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = outline())
            KV(stringResource(Res.string.info_from), places.first)
            if (contract.endStationId != contract.startStationId) KV(stringResource(Res.string.info_to), places.second)
            l.party?.let { KV(stringResource(Res.string.h_party), it) }
            if (contract.forCorp) KV(stringResource(Res.string.info_for_corp), stringResource(Res.string.yes))
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = outline())
            time(contract.dateIssued)?.let { KV(stringResource(Res.string.sort_issued), it) }
            time(contract.dateAccepted)?.let { KV(stringResource(Res.string.info_accepted), it) }
            time(contract.dateCompleted)?.let { KV(stringResource(Res.string.sort_completed), it) }
            if (l.group == StatusGroup.ACTIVE) time(contract.dateExpired)?.let { KV(stringResource(Res.string.info_expires), it) }
            if (contract.numDays > 0) KV(stringResource(Res.string.info_duration), stringResource(Res.string.days_short, contract.numDays))
            l.age(now)?.let { KV(stringResource(Res.string.h_time), formatSpan(it)) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = outline())
        }
        val current = items
        when {
            current == null -> {
                item { Hint(stringResource(Res.string.loading_items)) }
            }

            current.isEmpty() -> {
                item { Hint(stringResource(Res.string.no_items_contract)) }
            }

            else -> {
                val (included, requested) = current.partition { it.isIncluded }
                if (included.isNotEmpty()) {
                    item { ItemsHeader(stringResource(Res.string.items)) }
                    items(included, key = { "i${it.recordId}" }) { ContractItemRow(it) }
                }
                if (requested.isNotEmpty()) {
                    item { ItemsHeader(stringResource(Res.string.requested_in_exchange)) }
                    items(requested, key = { "r${it.recordId}" }) { ContractItemRow(it) }
                }
            }
        }
    }
}

private fun loadItems(
    contract: ContractModel,
    actingCharId: Int?,
): List<ContractItemModel> {
    val cached = ContractDao.getItemsForContract(contract.contractId)
    if (cached.isNotEmpty()) return cached
    // ESI's items endpoint is always character-or-corporation scoped, never bare by contract id —
    // a personal contract uses its own owning character, a corp contract (no per-row owner) falls
    // back to whichever character is currently acting for it.
    val effectiveCharId = contract.characterId ?: actingCharId ?: return emptyList()
    return runCatching {
        EsiClient.getContractItems(contract.contractId, characterId = effectiveCharId, corporationId = contract.corporationId)
    }.getOrDefault(emptyList())
        .mapNotNull { it.toContractItemModel(contract.contractId) }
        .also { parsed -> parsed.forEach { ContractDao.insertContractItem(it) } }
}

@Composable
private fun ItemsHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = AXIS_COLOR,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun ContractItemRow(item: ContractItemModel) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TypeIcon(item.typeId, size = 18.dp)
        Text(
            item.typeName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(formatVolume(item.quantity.toLong()), style = MaterialTheme.typography.bodySmall, fontFamily = Mono, color = AXIS_COLOR)
    }
}

private fun Map<String, Any?>.toContractItemModel(contractId: Int): ContractItemModel? {
    val typeId = (this["type_id"] as? Number)?.toInt() ?: return null
    return ContractItemModel(
        contractId = contractId,
        recordId = (this["record_id"] as? Number)?.toInt() ?: 0,
        typeId = typeId,
        typeName = StaticDataDao.getTypeById(typeId)?.name ?: stringBlocking(Res.string.type_n, typeId),
        quantity = (this["quantity"] as? Number)?.toInt() ?: 0,
        rawQuantity = (this["raw_quantity"] as? Number)?.toInt() ?: 0,
        isIncluded = (this["is_included"] as? Boolean) ?: true,
        isSingleton = (this["is_singleton"] as? Boolean) ?: false,
        estimatedPrice = 0.0,
    )
}

private fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

@Composable
private fun contractTypeLabel(type: String): String =
    when (type) {
        "item_exchange" -> stringResource(Res.string.type_item_exchange)
        "courier" -> stringResource(Res.string.type_courier)
        "auction" -> stringResource(Res.string.type_auction)
        "loan" -> stringResource(Res.string.type_loan)
        else -> type.replace("_", " ").capitalize()
    }

@Composable
private fun contractStatusLabel(status: String): String =
    when (status) {
        "outstanding" -> stringResource(Res.string.cst_outstanding)
        "in_progress" -> stringResource(Res.string.cst_in_progress)
        "finished" -> stringResource(Res.string.cst_finished)
        "finished_issuer" -> stringResource(Res.string.status_finished_issuer)
        "finished_contractor" -> stringResource(Res.string.status_finished_contractor)
        "cancelled" -> stringResource(Res.string.cst_cancelled)
        "rejected" -> stringResource(Res.string.status_rejected)
        "failed" -> stringResource(Res.string.status_failed)
        "deleted" -> stringResource(Res.string.status_deleted)
        "reversed" -> stringResource(Res.string.status_reversed)
        else -> status.replace("_", " ").capitalize()
    }
