package org.eve.trader.features.industry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import org.eve.trader.core.database.StaticDataDao
import org.eve.trader.ui.common.*

@Composable
fun IndustryCalculatorScreen() {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<org.eve.trader.core.model.StaticTypeModel>>(emptyList()) }
    var selectedType by remember { mutableStateOf<org.eve.trader.core.model.StaticTypeModel?>(null) }
    var meLevel by remember { mutableIntStateOf(0) }
    var quantity by remember { mutableIntStateOf(1) }
    var jobCost by remember { mutableDoubleStateOf(0.0) }
    var showBom by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Industry Calculator", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(8.dp))

        // Item search
        SearchField(
            query = searchQuery,
            onQueryChange = { q ->
                searchQuery = q
                if (q.length >= 2) {
                    scope.launch(Dispatchers.IO) {
                        searchResults = StaticDataDao.searchTypes(q, limit = 15)
                    }
                } else {
                    searchResults = emptyList()
                }
            },
            placeholder = "Search blueprint/product...",
            modifier = Modifier.fillMaxWidth(0.6f),
        )

        if (searchResults.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(0.6f).heightIn(max = 200.dp)) {
                LazyColumn {
                    items(searchResults) { type ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedType = type
                                searchQuery = type.name
                                searchResults = emptyList()
                            }.padding(12.dp),
                        ) {
                            Icon(Icons.Default.Extension, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(type.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        if (selectedType != null) {
            Spacer(modifier = Modifier.height(16.dp))

            // Configuration
            ContentCard("Configuration") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ME Level
                    Column {
                        Text("Material Efficiency", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(listOf(0, 1, 2, 3, 4, 5, 10)) { level ->
                                FilterChip(
                                    selected = meLevel == level,
                                    onClick = { meLevel = level },
                                    label = { Text("$level") },
                                )
                            }
                        }
                    }

                    // Quantity
                    Column {
                        Text("Run Quantity", style = MaterialTheme.typography.labelMedium)
                        Row {
                            IconButton(onClick = { if (quantity > 1) quantity-- }) {
                                Icon(Icons.Default.Remove, null)
                            }
                            Text("$quantity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { quantity++ }) {
                                Icon(Icons.Default.Add, null)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // BOM toggle
            Button(onClick = { showBom = !showBom }) {
                Icon(Icons.Default.List, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (showBom) "Hide Materials" else "Show Bill of Materials")
            }

            if (showBom) {
                Spacer(modifier = Modifier.height(8.dp))

                // Mock BOM (would fetch from SDE)
                ContentCard("Bill of Materials (Estimated)") {
                    Text(
                        "Note: Bill of Materials data requires SDE import. Showing placeholder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Example mock materials
                    val mockMaterials = listOf(
                        "Tritanium" to 50000.0 * quantity,
                        "Pyerite" to 15000.0 * quantity,
                        "Mexallon" to 5000.0 * quantity,
                        "Isogen" to 2000.0 * quantity,
                        "Nocxium" to 500.0 * quantity,
                        "Zydrine" to 100.0 * quantity,
                        "Megacyte" to 50.0 * quantity,
                        "Morphite" to 25.0 * quantity,
                    )

                    mockMaterials.forEach { (name, qty) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            Text(formatNumber(qty), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        HorizontalDivider()
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "ME ${meLevel}% reduction applied. Fetch real prices from market to calculate total cost.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Cost estimation placeholder
            ContentCard("Cost Estimation") {
                Column {
                    Text("To calculate manufacturing costs:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    listOf(
                        "1. Import market prices for raw materials at your station",
                        "2. SDE data provides exact material quantities per blueprint",
                        "3. Apply ME reduction to material requirements",
                        "4. Calculate total material cost + facility fees",
                        "5. Compare against current market sell price for profit",
                    ).forEach { step ->
                        Text("• $step", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(16.dp))
            EmptyState(
                icon = Icons.Default.Factory,
                title = "Select an Item",
                description = "Search for a product to calculate manufacturing costs.",
            )
        }
    }
}

private fun formatNumber(value: Double): String {
    return when {
        value >= 1_000_000_000 -> String.format("%.1fB", value / 1_000_000_000)
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000)
        value >= 1_000 -> String.format("%.1fK", value / 1_000)
        value % 1 == 0.0 -> value.toLong().toString()
        else -> String.format("%.2f", value)
    }
}
