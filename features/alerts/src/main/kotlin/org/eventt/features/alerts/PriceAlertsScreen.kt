package org.eventt.features.alerts

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.AlertDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.PLEX_MARKET_REGION_ID
import org.eventt.core.model.PLEX_TYPE_ID
import org.eventt.core.model.PriceAlertModel
import org.eventt.ui.common.*
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import java.util.Locale

private const val JITA_REGION_ID = 10000002

private fun effectiveRegionId(typeId: Int) = if (typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else JITA_REGION_ID

@Composable
fun PriceAlertsScreen() {
    val scope = rememberCoroutineScope()
    var alerts by remember { mutableStateOf<List<PriceAlertModel>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PriceAlertModel?>(null) }
    var showOnlyEnabled by remember { mutableStateOf(true) }
    val revision by AlertDao.revision.collectAsState()

    // Keyed on AlertDao.revision: this screen stays mounted after its first visit, so alerts
    // created elsewhere (Market Analysis) or fired by AlertMonitor must push a reload themselves.
    LaunchedEffect(revision, showOnlyEnabled) {
        alerts =
            withContext(Dispatchers.IO) {
                if (showOnlyEnabled) AlertDao.getEnabled() else AlertDao.getAll()
            }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Price Alerts", style = MaterialTheme.typography.headlineMedium)
            Row {
                FilterChip(
                    selected = showOnlyEnabled,
                    onClick = { showOnlyEnabled = !showOnlyEnabled },
                    label = { Text("Active only") },
                )
                Spacer(modifier = Modifier.width(4.dp))
                Button(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Text("Add Alert")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (alerts.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Notifications,
                title = "No Alerts",
                description = "Create a price alert to get notified when prices change.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(alerts, key = { it.id }) { alert ->
                    AlertCard(
                        alert = alert,
                        onToggle = { scope.launch(Dispatchers.IO) { AlertDao.setEnabled(alert.id, !alert.enabled) } },
                        onEdit = { editing = alert },
                        onDelete = { scope.launch(Dispatchers.IO) { AlertDao.delete(alert.id) } },
                    )
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
}

@Composable
private fun AlertCard(
    alert: PriceAlertModel,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = if (!alert.enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (alert.condition == "above") Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = if (alert.condition == "above") positiveColor else negativeColor,
                modifier = Modifier.size(24.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(alert.typeName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "Alert when ${alert.orderType} price is ${alert.condition} ${formatIsk(alert.targetPrice)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                if (alert.triggered) {
                    Text(
                        "Triggered${alert.triggeredAt?.let { " " + formatDateTime(it) } ?: ""} — edit to re-arm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Switch(checked = alert.enabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, "Edit alert", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = Color.Red, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun formatDateTime(millis: Long): String =
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
