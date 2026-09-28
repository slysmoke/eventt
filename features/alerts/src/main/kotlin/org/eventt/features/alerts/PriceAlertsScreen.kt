package org.eventt.features.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.AlertDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.ALERT_CATEGORY_INVESTMENT
import org.eventt.core.model.PLEX_MARKET_REGION_ID
import org.eventt.core.model.PLEX_TYPE_ID
import org.eventt.core.model.PriceAlertModel
import org.eventt.ui.common.*
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import java.util.Locale

private const val JITA_REGION_ID = 10000002

private fun effectiveRegionId(typeId: Int) = if (typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else JITA_REGION_ID

private val MUTED = Color(0xFF8A8A8A)

internal enum class AlertStatusFilter(
    val label: String,
) {
    ALL("All"),
    ACTIVE("Active"),
    TRIGGERED("Triggered"),
    DISABLED("Disabled"),
}

internal fun PriceAlertModel.matches(f: AlertStatusFilter) =
    when (f) {
        AlertStatusFilter.ALL -> true
        AlertStatusFilter.ACTIVE -> enabled && !triggered
        AlertStatusFilter.TRIGGERED -> triggered
        AlertStatusFilter.DISABLED -> !enabled
    }

// Signed % the watched price still has to move to fire: negative = it must fall (a "below"
// alert), positive = rise. 0 or past the target -> already there.
internal fun distancePct(
    alert: PriceAlertModel,
    quote: AlertMonitor.Quote?,
): Double? {
    val current = (if (alert.orderType == "buy") quote?.bid else quote?.ask)?.takeIf { it > 0 } ?: return null
    return (alert.targetPrice - current) / current * 100.0
}

/** One item's alerts within a section, the nearest-to-firing active one first. */
internal data class AlertGroup(
    val typeId: Int,
    val regionId: Int,
    val typeName: String,
    val alerts: List<PriceAlertModel>,
)

internal fun groupAlerts(
    alerts: List<PriceAlertModel>,
    quotes: Map<Pair<Int, Int>, AlertMonitor.Quote>,
): List<AlertGroup> {
    fun near(a: PriceAlertModel) =
        if (a.enabled && !a.triggered) {
            distancePct(a, quotes[a.typeId to AlertMonitor.effectiveRegionOf(a)])?.let { kotlin.math.abs(it) } ?: Double.MAX_VALUE
        } else {
            Double.MAX_VALUE
        }
    return alerts
        .groupBy { it.typeId to AlertMonitor.effectiveRegionOf(it) }
        .map { (key, list) -> AlertGroup(key.first, key.second, list.first().typeName, list.sortedBy { near(it) }) }
        .sortedWith(compareBy<AlertGroup> { near(it.alerts.first()) }.thenBy { it.typeName })
}

@Composable
fun PriceAlertsScreen() {
    val scope = rememberCoroutineScope()
    var alerts by remember { mutableStateOf<List<PriceAlertModel>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PriceAlertModel?>(null) }
    var statusFilter by remember { mutableStateOf(AlertStatusFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<Set<Pair<Int, Int>>>(emptySet()) }
    var collapsedSections by remember { mutableStateOf<Set<String>>(emptySet()) }
    var chartFor by remember { mutableStateOf<AlertGroup?>(null) }
    var confirmDelete by remember { mutableStateOf<Pair<String, List<Int>>?>(null) }
    var checking by remember { mutableStateOf(false) }
    val revision by AlertDao.revision.collectAsState()
    val quotes by AlertMonitor.quotes.collectAsState()

    // Keyed on AlertDao.revision: this screen stays mounted after its first visit, so alerts
    // created elsewhere (Market Analysis) or fired by AlertMonitor must push a reload themselves.
    LaunchedEffect(revision) {
        alerts = withContext(Dispatchers.IO) { AlertDao.getAll() }
    }
    // Live prices for "distance to target" without waiting for the 5-minute poll.
    LaunchedEffect(Unit) { runCatching { AlertMonitor.checkNow() } }

    val visible = alerts.filter { it.matches(statusFilter) && (query.isBlank() || it.typeName.contains(query, ignoreCase = true)) }
    val sections =
        listOf(
            Triple("investment", "Long-Term Investment", visible.filter { it.category == ALERT_CATEGORY_INVESTMENT }),
            Triple("general", "General", visible.filter { it.category != ALERT_CATEGORY_INVESTMENT }),
        )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // ── Toolbar
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Price Alerts", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(16.dp))
            Pill("${alerts.count { it.enabled && !it.triggered }} active", positiveColor)
            Spacer(Modifier.width(6.dp))
            Pill("${alerts.count { it.triggered }} triggered", warningColor)
            Spacer(Modifier.width(6.dp))
            Pill("${alerts.count { !it.enabled }} disabled", MUTED)
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Filter items…", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(220.dp).height(48.dp),
            )
            Spacer(Modifier.width(8.dp))
            AlertStatusFilter.entries.forEach { f ->
                FilterChip(selected = statusFilter == f, onClick = { statusFilter = f }, label = { Text(f.label) })
                Spacer(Modifier.width(4.dp))
            }
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = {
                    scope.launch {
                        checking = true
                        runCatching { AlertMonitor.checkNow() }
                        checking = false
                    }
                },
                enabled = !checking,
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, "Check alerts now")
                }
            }
            Button(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Text("Add Alert")
            }
        }
        Spacer(Modifier.height(10.dp))

        if (alerts.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Notifications,
                title = "No Alerts",
                description = "Create a price alert here, from Market Browser, or in bulk from Long-Term Investment.",
            )
            return@Column
        }

        AlertTableHeader()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            sections.forEach { (key, title, list) ->
                val groups = groupAlerts(list, quotes)
                val collapsed = key in collapsedSections
                item(key = "section-$key") {
                    SectionHeader(
                        title = title,
                        itemCount = groups.size,
                        alertCount = list.size,
                        collapsed = collapsed,
                        onToggle = { collapsedSections = if (collapsed) collapsedSections - key else collapsedSections + key },
                        onDeleteTriggered = {
                            list.filter { it.triggered }.map { it.id }.takeIf { it.isNotEmpty() }?.let {
                                confirmDelete = "Delete ${it.size} triggered alert(s) in $title?" to it
                            }
                        },
                        onDeleteAll = {
                            list.map { it.id }.takeIf { it.isNotEmpty() }?.let {
                                confirmDelete = "Delete all ${it.size} alert(s) shown in $title?" to it
                            }
                        },
                    )
                }
                if (!collapsed) {
                    if (groups.isEmpty()) {
                        item(key = "empty-$key") {
                            Text(
                                "Nothing here for the current filter.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MUTED,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    groups.forEach { g ->
                        val gKey = g.typeId to g.regionId
                        val isOpen = gKey in expanded
                        val quote = quotes[gKey]
                        item(key = "g-$key-${g.typeId}-${g.regionId}") {
                            GroupRow(
                                group = g,
                                quote = quote,
                                expanded = isOpen,
                                onToggle = { expanded = if (isOpen) expanded - gKey else expanded + gKey },
                                onChart = { chartFor = g },
                                single = g.alerts.singleOrNull(),
                                onToggleAlert = { a -> scope.launch(Dispatchers.IO) { AlertDao.setEnabled(a.id, !a.enabled) } },
                                onEdit = { editing = it },
                                onDelete = { a -> scope.launch(Dispatchers.IO) { AlertDao.delete(a.id) } },
                            )
                        }
                        if (isOpen && g.alerts.size > 1) {
                            items(g.alerts, key = { "a-${it.id}" }) { a ->
                                AlertRow(
                                    alert = a,
                                    quote = quote,
                                    onToggle = { scope.launch(Dispatchers.IO) { AlertDao.setEnabled(a.id, !a.enabled) } },
                                    onEdit = { editing = a },
                                    onDelete = { scope.launch(Dispatchers.IO) { AlertDao.delete(a.id) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertEditDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { alert ->
                scope.launch(Dispatchers.IO) { AlertDao.insert(alert) }
                showAddDialog = false
            },
        )
    }

    editing?.let { original ->
        AlertEditDialog(
            initial = original,
            onDismiss = { editing = null },
            onSave = { edited ->
                // Saving re-arms the alert — a changed target is a new question, so a previous
                // trigger shouldn't keep it silenced.
                scope.launch(Dispatchers.IO) {
                    AlertDao.update(edited.copy(id = original.id, enabled = true, triggered = false, triggeredAt = null))
                }
                editing = null
            },
        )
    }

    confirmDelete?.let { (text, ids) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete alerts") },
            text = { Text(text) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) { AlertDao.deleteAll(ids) }
                        confirmDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    chartFor?.let { g -> AlertChartDialog(g.typeId, g.typeName, g.regionId, onDismiss = { chartFor = null }) }
}

// ── Table pieces ────────────────────────────────────────────────────────────────────────────

private val COL_ALERTS = 70.dp
private val COL_NEXT = 170.dp
private val COL_QUOTE = 190.dp
private val COL_DIST = 80.dp
private val COL_STATUS = 130.dp
private val COL_ACTIONS = 150.dp

@Composable
private fun AlertTableHeader() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderCell("Item", Modifier.weight(1f).padding(start = 28.dp))
            HeaderCell("Alerts", Modifier.width(COL_ALERTS))
            HeaderCell("Next trigger", Modifier.width(COL_NEXT))
            HeaderCell("Bid / Ask now", Modifier.width(COL_QUOTE))
            HeaderCell("To go", Modifier.width(COL_DIST))
            HeaderCell("Status", Modifier.width(COL_STATUS))
            Spacer(Modifier.width(COL_ACTIONS))
        }
    }
}

@Composable
private fun HeaderCell(
    text: String,
    modifier: Modifier,
) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MUTED, modifier = modifier, maxLines = 1)
}

@Composable
private fun SectionHeader(
    title: String,
    itemCount: Int,
    alertCount: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onDeleteTriggered: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Text("$itemCount items · $alertCount alerts", style = MaterialTheme.typography.labelSmall, color = MUTED)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDeleteTriggered) { Text("Delete triggered", style = MaterialTheme.typography.labelSmall) }
        TextButton(onClick = onDeleteAll) {
            Text("Delete all", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
    HorizontalDivider(thickness = 0.5.dp)
}

private fun conditionText(a: PriceAlertModel) =
    "${if (a.orderType == "buy") "Bid" else "Ask"} ${if (a.condition == "above") "≥" else "≤"} ${formatPriceAbbr(a.targetPrice)}"

@Composable
private fun DistanceText(
    pct: Double?,
    modifier: Modifier,
) {
    val color =
        when {
            pct == null -> MUTED
            kotlin.math.abs(pct) < 2 -> warningColor
            else -> MaterialTheme.colorScheme.onSurface
        }
    Text(
        pct?.let { (if (it >= 0) "+" else "") + String.format(Locale.US, "%.1f%%", it) } ?: "—",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun StatusChip(a: PriceAlertModel) {
    val (text, color) =
        when {
            a.triggered -> "Triggered${a.triggeredAt?.let { " " + formatDateTime(it).drop(5) } ?: ""}" to warningColor
            !a.enabled -> "Disabled" to MUTED
            else -> "Active" to positiveColor
        }
    Pill(text, color)
}

@Composable
private fun Pill(
    text: String,
    color: Color,
) {
    Surface(color = color.copy(alpha = 0.14f), shape = MaterialTheme.shapes.extraSmall) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun QuoteText(
    quote: AlertMonitor.Quote?,
    modifier: Modifier,
) {
    Row(modifier = modifier) {
        Text(quote?.bid?.let { formatPriceAbbr(it) } ?: "—", style = MaterialTheme.typography.bodySmall, color = positiveColor)
        Text(" / ", style = MaterialTheme.typography.bodySmall, color = MUTED)
        Text(quote?.ask?.let { formatPriceAbbr(it) } ?: "—", style = MaterialTheme.typography.bodySmall, color = negativeColor)
    }
}

@Composable
private fun AlertActions(
    alert: PriceAlertModel,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Switch(checked = alert.enabled, onCheckedChange = { onToggle() }, modifier = Modifier.height(24.dp))
    IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Edit, "Edit alert", Modifier.size(16.dp)) }
    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
        Icon(Icons.Default.Delete, "Delete alert", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun GroupRow(
    group: AlertGroup,
    quote: AlertMonitor.Quote?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChart: () -> Unit,
    // Set when the item has just one alert: its controls go straight on this row, nothing to expand.
    single: PriceAlertModel?,
    onToggleAlert: (PriceAlertModel) -> Unit,
    onEdit: (PriceAlertModel) -> Unit,
    onDelete: (PriceAlertModel) -> Unit,
) {
    val lead = group.alerts.first()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .onRightClick(onChart)
                .then(if (single == null) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(22.dp)) {
            if (single == null) Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(16.dp))
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            TypeIcon(group.typeId, size = 24.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                group.typeName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clickable(onClick = onChart),
            )
            IconButton(onClick = onChart, modifier = Modifier.size(26.dp)) {
                Icon(Icons.AutoMirrored.Filled.ShowChart, "Open chart", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text("${group.alerts.size}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(COL_ALERTS))
        Text(
            conditionText(lead),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(COL_NEXT),
        )
        QuoteText(quote, Modifier.width(COL_QUOTE))
        DistanceText(distancePct(lead, quote).takeIf { lead.enabled && !lead.triggered }, Modifier.width(COL_DIST))
        Box(Modifier.width(COL_STATUS)) {
            if (single != null) {
                StatusChip(single)
            } else {
                val active = group.alerts.count { it.enabled && !it.triggered }
                val fired = group.alerts.count { it.triggered }
                Text(
                    listOfNotNull(
                        "$active active".takeIf { active > 0 },
                        "$fired triggered".takeIf { fired > 0 },
                    ).joinToString(" · ").ifEmpty { "disabled" },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (fired > 0) warningColor else MUTED,
                )
            }
        }
        Row(Modifier.width(COL_ACTIONS), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            if (single != null) AlertActions(single, { onToggleAlert(single) }, { onEdit(single) }, { onDelete(single) })
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
}

@Composable
private fun AlertRow(
    alert: PriceAlertModel,
    quote: AlertMonitor.Quote?,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f))
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(22.dp))
        Row(Modifier.weight(1f).padding(start = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (alert.condition == "above") Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                null,
                Modifier.size(13.dp),
                tint = if (alert.condition == "above") positiveColor else negativeColor,
            )
            Spacer(Modifier.width(6.dp))
            Text("created ${formatDateTime(alert.createdAt)}", style = MaterialTheme.typography.labelSmall, color = MUTED)
        }
        Spacer(Modifier.width(COL_ALERTS))
        Text(
            conditionText(alert),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(COL_NEXT),
        )
        Spacer(Modifier.width(COL_QUOTE))
        DistanceText(distancePct(alert, quote).takeIf { alert.enabled && !alert.triggered }, Modifier.width(COL_DIST))
        Box(Modifier.width(COL_STATUS)) { StatusChip(alert) }
        Row(Modifier.width(COL_ACTIONS), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            AlertActions(alert, onToggle, onEdit, onDelete)
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
}

internal fun formatDateTime(millis: Long): String =
    java.time.Instant
        .ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(
            java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm"),
        )

// Create (initial == null) or edit an alert. Editing keeps the item's region as stored, since
// alerts from Market Analysis may watch a region other than the Jita default used for new ones.
@Composable
private fun AlertEditDialog(
    initial: PriceAlertModel?,
    onDismiss: () -> Unit,
    onSave: (PriceAlertModel) -> Unit,
) {
    var searchQuery by remember { mutableStateOf(initial?.typeName ?: "") }
    var selectedType by remember { mutableStateOf<org.eventt.core.model.StaticTypeModel?>(null) }
    var searchResults by remember { mutableStateOf<List<org.eventt.core.model.StaticTypeModel>>(emptyList()) }
    var targetPrice by remember { mutableStateOf(initial?.let { String.format(Locale.US, "%.2f", it.targetPrice) } ?: "") }
    var condition by remember { mutableStateOf(initial?.condition ?: "below") }
    var orderType by remember { mutableStateOf(initial?.orderType ?: "sell") }
    var currentBestSell by remember { mutableStateOf<Double?>(null) }
    var currentBestBuy by remember { mutableStateOf<Double?>(null) }
    var isPriceFetching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(initial) {
        val init = initial ?: return@LaunchedEffect
        selectedType = withContext(Dispatchers.IO) { StaticDataDao.getTypeById(init.typeId) }
    }

    fun regionFor(typeId: Int) = initial?.takeIf { it.typeId == typeId && it.regionId > 0 }?.regionId ?: effectiveRegionId(typeId)

    // Fetch current price whenever type or orderType changes
    LaunchedEffect(selectedType, orderType) {
        val type = selectedType ?: return@LaunchedEffect
        isPriceFetching = true
        currentBestSell = null
        currentBestBuy = null
        val orders =
            withContext(Dispatchers.IO) {
                runCatching {
                    EsiClient.getMarketRegionOrders(
                        regionFor(type.typeId),
                        typeId = type.typeId,
                    )
                }.getOrDefault(emptyList())
            }
        currentBestSell =
            orders
                .filter { (it["is_buy_order"] as? Boolean) == false }
                .minOfOrNull { (it["price"] as? Number)?.toDouble() ?: Double.MAX_VALUE }
        currentBestBuy =
            orders
                .filter { (it["is_buy_order"] as? Boolean) == true }
                .maxOfOrNull { (it["price"] as? Number)?.toDouble() ?: 0.0 }
        // Pre-fill target price with current price if not yet set
        if (targetPrice.isEmpty()) {
            val current = if (orderType == "sell") currentBestSell else currentBestBuy
            current?.let { targetPrice = String.format(Locale.US, "%.2f", it) }
        }
        isPriceFetching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New Price Alert" else "Edit Price Alert") },
        text = {
            Column {
                SearchField(
                    query = searchQuery,
                    onQueryChange = { q ->
                        searchQuery = q
                        if (q.length >= 2) {
                            scope.launch(Dispatchers.IO) {
                                searchResults = StaticDataDao.searchTypes(q, limit = 10)
                            }
                        } else {
                            searchResults = emptyList()
                        }
                    },
                    placeholder = "Search item...",
                )

                if (searchResults.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                        items(searchResults) { type ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedType = type
                                            searchQuery = type.name
                                            searchResults = emptyList()
                                            targetPrice = ""
                                        }.padding(8.dp),
                            ) {
                                Text(type.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Order type selector
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Price type:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = orderType == "sell",
                        onClick = {
                            orderType = "sell"
                            targetPrice = ""
                        },
                        label = { Text("Sell") },
                    )
                    Spacer(Modifier.width(4.dp))
                    FilterChip(
                        selected = orderType == "buy",
                        onClick = {
                            orderType = "buy"
                            targetPrice = ""
                        },
                        label = { Text("Buy") },
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Current price hint
                if (selectedType != null) {
                    if (isPriceFetching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Fetching price…", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    } else {
                        val bestSell = currentBestSell
                        val bestBuy = currentBestBuy
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (bestSell != null) {
                                Text(
                                    "Sell: ${formatIsk(bestSell)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (orderType == "sell") MaterialTheme.colorScheme.primary else Color.Gray,
                                )
                            }
                            if (bestBuy != null) {
                                Text(
                                    "Buy: ${formatIsk(bestBuy)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (orderType == "buy") MaterialTheme.colorScheme.primary else Color.Gray,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Condition
                Row {
                    FilterChip(selected = condition == "below", onClick = { condition = "below" }, label = { Text("Below") })
                    Spacer(modifier = Modifier.width(4.dp))
                    FilterChip(selected = condition == "above", onClick = { condition = "above" }, label = { Text("Above") })
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = targetPrice,
                    onValueChange = { targetPrice = it },
                    label = { Text("Target Price (ISK)") },
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val type = selectedType ?: return@Button
                    val price = targetPrice.toDoubleOrNull() ?: return@Button
                    val base =
                        initial ?: PriceAlertModel(typeId = type.typeId, targetPrice = price, condition = condition)
                    onSave(
                        base.copy(
                            typeId = type.typeId,
                            typeName = type.name,
                            targetPrice = price,
                            condition = condition,
                            orderType = orderType,
                            regionId = regionFor(type.typeId),
                        ),
                    )
                },
                enabled = selectedType != null && targetPrice.toDoubleOrNull() != null,
            ) {
                Text(if (initial == null) "Create Alert" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
