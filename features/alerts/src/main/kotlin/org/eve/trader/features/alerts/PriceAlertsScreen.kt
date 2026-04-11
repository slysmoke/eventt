package org.eve.trader.features.alerts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
import org.eve.trader.core.database.AlertDao
import org.eve.trader.core.database.DatabaseManager
import org.eve.trader.core.database.StaticDataDao
import org.eve.trader.core.model.PriceAlertModel
import org.eve.trader.ui.common.*

@Composable
fun PriceAlertsScreen() {
    val scope = rememberCoroutineScope()
    var alerts by remember { mutableStateOf<List<PriceAlertModel>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showOnlyEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO) { DatabaseManager.initialize() } } catch (e: Exception) {}
        loadAlerts(showOnlyEnabled) { alerts = it }
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
                        loadAlerts(true) { alerts = it }
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
                                withContext(Dispatchers.Main) { loadAlerts(showOnlyEnabled) { alerts = it } }
                            }
                        },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                AlertDao.delete(alert.id)
                                withContext(Dispatchers.Main) { loadAlerts(showOnlyEnabled) { alerts = it } }
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
                    withContext(Dispatchers.Main) {
                        loadAlerts(showOnlyEnabled) { alerts = it }
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
        colors = CardDefaults.cardColors(
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
                tint = if (alert.condition == "above") Color(0xFF69DB7C) else Color(0xFFFF6B6B),
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
    var selectedType by remember { mutableStateOf<org.eve.trader.core.model.StaticTypeModel?>(null) }
    var searchResults by remember { mutableStateOf<List<org.eve.trader.core.model.StaticTypeModel>>(emptyList()) }
    var targetPrice by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf("below") }
    val scope = rememberCoroutineScope()

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
                        }
                    },
                    placeholder = "Search item...",
                )

                if (searchResults.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                        items(searchResults) { type ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedType = type
                                    searchQuery = type.name
                                    searchResults = emptyList()
                                }.padding(8.dp),
                            ) {
                                Text(type.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

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
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
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
                        )
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

private fun loadAlerts(enabledOnly: Boolean, callback: (List<PriceAlertModel>) -> Unit) {
    try {
        callback(if (enabledOnly) AlertDao.getEnabled() else AlertDao.getAll())
    } catch (e: Exception) {
        println("Error loading alerts: ${e.message}")
    }
}

private fun formatIsk(value: Double): String {
    return when {
        value >= 1_000_000_000_000 -> String.format("%.2fT", value / 1_000_000_000_000)
        value >= 1_000_000_000 -> String.format("%.2fB", value / 1_000_000_000)
        value >= 1_000_000 -> String.format("%.2fM", value / 1_000_000)
        else -> String.format("%,.2f", value)
    }
}
