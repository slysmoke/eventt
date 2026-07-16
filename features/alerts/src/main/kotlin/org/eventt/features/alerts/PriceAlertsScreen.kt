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
    var showOnlyEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
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
                    onClick = {
                        showOnlyEnabled = true
                        scope.launch(Dispatchers.IO) {
                            val loaded = AlertDao.getEnabled()
                            withContext(Dispatchers.Main) { alerts = loaded }
                        }
                    },
                    label = { Text("Active") },
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
                items(alerts) { alert ->
                    AlertCard(
                        alert = alert,
                        onToggle = {
                            scope.launch(Dispatchers.IO) {
                                AlertDao.setEnabled(alert.id, !alert.enabled)
                                val loaded = if (showOnlyEnabled) AlertDao.getEnabled() else AlertDao.getAll()
                                withContext(Dispatchers.Main) { alerts = loaded }
                            }
                        },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                AlertDao.delete(alert.id)
                                val loaded = if (showOnlyEnabled) AlertDao.getEnabled() else AlertDao.getAll()
                                withContext(Dispatchers.Main) { alerts = loaded }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddAlertDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { alert ->
                scope.launch(Dispatchers.IO) {
                    AlertDao.insert(alert)
                    val loaded = if (showOnlyEnabled) AlertDao.getEnabled() else AlertDao.getAll()
                    withContext(Dispatchers.Main) {
                        alerts = loaded
                        showAddDialog = false
                    }
                }
            },
        )
    }
}

@Composable
private fun AlertCard(
    alert: PriceAlertModel,
    onToggle: () -> Unit,
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
            }

            Switch(checked = alert.enabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = Color.Red, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AddAlertDialog(
    onDismiss: () -> Unit,
    onAdd: (PriceAlertModel) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf<org.eventt.core.model.StaticTypeModel?>(null) }
    var searchResults by remember { mutableStateOf<List<org.eventt.core.model.StaticTypeModel>>(emptyList()) }
    var targetPrice by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf("below") }
    var orderType by remember { mutableStateOf("sell") }
    var currentBestSell by remember { mutableStateOf<Double?>(null) }
    var currentBestBuy by remember { mutableStateOf<Double?>(null) }
    var isPriceFetching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                        effectiveRegionId(type.typeId),
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
        title = { Text("New Price Alert") },
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
                            Text("Fetching Jita price…", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
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
                    onAdd(
                        PriceAlertModel(
                            typeId = type.typeId,
                            typeName = type.name,
                            targetPrice = price,
                            condition = condition,
                            orderType = orderType,
                            regionId = effectiveRegionId(type.typeId),
                        ),
                    )
                },
                enabled = selectedType != null && targetPrice.toDoubleOrNull() != null,
            ) {
                Text("Create Alert")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
