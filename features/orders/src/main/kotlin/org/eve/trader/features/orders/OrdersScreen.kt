package org.eve.trader.features.orders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
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
import org.eve.trader.core.database.DatabaseManager
import org.eve.trader.core.database.TrackedOrderDao
import org.eve.trader.core.database.CharacterDao
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.core.model.TrackedOrderModel
import org.eve.trader.ui.common.*

@Composable
fun OrdersScreen() {
    val scope = rememberCoroutineScope()
    var orders by remember { mutableStateOf<List<TrackedOrderModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(0) } // 0 = tracked, 1 = active ESI orders

    LaunchedEffect(Unit) {
        loadOrders { list -> orders = list }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Orders", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Track Order")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tabs
        TabRow(selectedTabIndex = activeTab) {
            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) { Text("Tracked", modifier = Modifier.padding(8.dp)) }
            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) { Text("Active (ESI)", modifier = Modifier.padding(8.dp)) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (activeTab) {
            0 -> TrackedOrdersView(orders, onRefresh = { loadOrders { list -> orders = list } })
            1 -> ActiveOrdersView()
        }
    }

    if (showAddDialog) {
        AddTrackedOrderDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { order ->
                scope.launch(Dispatchers.IO) {
                    TrackedOrderDao.insert(order)
                    withContext(Dispatchers.Main) {
                        loadOrders { list -> orders = list }
                        showAddDialog = false
                    }
                }
            },
        )
    }

    LoadingOverlay(isLoading = isLoading, message = "Loading orders...")
}

@Composable
private fun TrackedOrdersView(orders: List<TrackedOrderModel>, onRefresh: () -> Unit) {
    if (orders.isEmpty()) {
        EmptyState(
            icon = Icons.Default.TrendingUp,
            title = "No Tracked Orders",
            description = "Add a tracked order to track your buy prices and margins.",
        )
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(orders) { order ->
            TrackedOrderCard(order)
        }
    }
}

@Composable
private fun TrackedOrderCard(order: TrackedOrderModel) {
    val marginColor = when {
        order.marginPercent > 0 -> Color(0xFF69DB7C)
        order.marginPercent < 0 -> Color(0xFFFF6B6B)
        else -> Color.Gray
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(order.typeName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(
                    color = marginColor.copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "${if (order.marginPercent >= 0) "+" else ""}${String.format("%.1f", order.marginPercent)}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = marginColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Buy Price", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(formatIsk(order.buyPrice), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Quantity", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(order.quantity.toString(), style = MaterialTheme.typography.bodyMedium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Total Cost", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(formatIsk(order.totalCost), style = MaterialTheme.typography.bodyMedium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Current Value", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(formatIsk(order.totalValue), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (order.notes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(order.notes, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun ActiveOrdersView() {
    val characters = remember {
        try { CharacterDao.getAll() } catch (e: Exception) { emptyList() }
    }
    var ordersData by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(characters) { char ->
                Button(
                    onClick = {
                        isLoading = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val orders = EsiClient.getCharacterOrders(char.id)
                                withContext(Dispatchers.Main) {
                                    ordersData = orders
                                    isLoading = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { isLoading = false }
                            }
                        }
                    },
                    enabled = !isLoading,
                ) {
                    Text(char.name)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (ordersData.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Receipt,
                title = "Select a Character",
                description = "Choose a character to view their active ESI orders.",
            )
        } else {
            LazyColumn {
                items(ordersData.take(50)) { order ->
                    val isBuy = (order["is_buy_order"] as? Boolean) ?: false
                    val price = (order["price"] as? Number)?.toDouble() ?: 0.0
                    val volume = (order["volume_remain"] as? Number)?.toInt() ?: 0
                    val typeId = (order["type_id"] as? Number)?.toInt() ?: 0

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isBuy) Icons.Default.ShoppingCart else Icons.Default.Store,
                                contentDescription = null,
                                tint = if (isBuy) Color(0xFF69DB7C) else Color(0xFFFF6B6B),
                                modifier = Modifier.size(18.dp),
                            )
                            Column {
                                Text("Type ID: $typeId", style = MaterialTheme.typography.bodyMedium)
                                Text("Issued: ${(order["issued"] as? String)?.take(10) ?: ""}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatIsk(price), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("Vol: $volume", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Fetching orders from ESI...")
}

@Composable
private fun AddTrackedOrderDialog(
    onDismiss: () -> Unit,
    onAdd: (TrackedOrderModel) -> Unit,
) {
    var typeName by remember { mutableStateOf("") }
    var buyPrice by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var typeId by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<org.eve.trader.core.model.StaticTypeModel>>(emptyList()) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track New Order") },
        text = {
            Column {
                SearchField(
                    query = searchQuery,
                    onQueryChange = { q ->
                        searchQuery = q
                        if (q.length >= 2) {
                            scope.launch(Dispatchers.IO) {
                                searchResults = org.eve.trader.core.database.StaticDataDao.searchTypes(q, limit = 10)
                            }
                        }
                    },
                    placeholder = "Search item...",
                )

                if (searchResults.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                        items(searchResults) { type ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    typeId = type.typeId
                                    typeName = type.name
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

                OutlinedTextField(
                    value = buyPrice,
                    onValueChange = { buyPrice = it },
                    label = { Text("Buy Price (ISK)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it },
                    label = { Text("Quantity") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val price = buyPrice.toDoubleOrNull() ?: 0.0
                    val qty = quantity.toIntOrNull() ?: 0
                    if (typeId > 0 && price > 0 && qty > 0) {
                        onAdd(
                            TrackedOrderModel(
                                typeId = typeId,
                                typeName = typeName,
                                buyPrice = price,
                                quantity = qty,
                                notes = notes,
                            )
                        )
                    }
                },
                enabled = typeId > 0 && buyPrice.toDoubleOrNull() != null && quantity.toIntOrNull() != null,
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun loadOrders(callback: (List<TrackedOrderModel>) -> Unit) {
    try {
        callback(TrackedOrderDao.getAll())
    } catch (e: Exception) {
        println("Error loading orders: ${e.message}")
    }
}

private fun formatIsk(value: Double): String {
    return when {
        kotlin.math.abs(value) >= 1_000_000_000_000 -> String.format("%.2fT", value / 1_000_000_000_000)
        kotlin.math.abs(value) >= 1_000_000_000 -> String.format("%.2fB", value / 1_000_000_000)
        kotlin.math.abs(value) >= 1_000_000 -> String.format("%.2fM", value / 1_000_000)
        kotlin.math.abs(value) >= 1_000 -> String.format("%.2fK", value / 1_000)
        else -> String.format("%,.2f", value)
    }
}
