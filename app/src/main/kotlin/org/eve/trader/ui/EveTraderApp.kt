package org.eve.trader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.eve.trader.ui.theme.*
import org.eve.trader.ui.common.RequestProgressDialog

@Composable
fun EveTraderApp() {
    var darkTheme by remember { mutableStateOf(true) }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val eveColors = if (darkTheme) DarkEveColors else LightEveColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EveTypography
    ) {
        var showProgressDialog by remember { mutableStateOf(false) }

        Scaffold(
            topBar = { TopBar(darkTheme, onThemeToggle = { darkTheme = !darkTheme }, eveColors, onShowProgress = { showProgressDialog = true }) },
            content = { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Sidebar(eveColors)
                        MainContent(eveColors)
                    }

                    if (showProgressDialog) {
                        RequestProgressDialog(onDismiss = { showProgressDialog = false })
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    darkTheme: Boolean,
    onThemeToggle: () -> Unit,
    eveColors: EveColors,
    onShowProgress: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = eveColors.accentColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "EVE Trader",
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        },
        actions = {
            IconButton(onClick = onShowProgress) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = "Show request progress",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onThemeToggle) {
                Icon(
                    imageVector = if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Toggle theme",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = eveColors.headerColor
        )
    )
}

@Composable
private fun Sidebar(eveColors: EveColors) {
    NavigationDrawer(
        modifier = Modifier.width(220.dp).fillMaxHeight(),
        items = listOf(
            Icons.Default.Store to "Market",
            Icons.Default.Inventory to "Inventory",
            Icons.AutoMirrored.Filled.ShowChart to "Price History",
            Icons.Default.LocalShipping to "Orders",
            Icons.Default.Settings to "Settings"
        ),
        eveColors = eveColors
    )
}

@Composable
private fun NavigationDrawer(
    modifier: Modifier = Modifier,
    items: List<Pair<androidx.compose.ui.graphics.vector.ImageVector, String>>,
    eveColors: EveColors
) {
    Surface(
        modifier = modifier,
        color = eveColors.headerColor,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            var selectedItem by remember { mutableStateOf("Market") }

            items.forEach { (icon, label) ->
                val isSelected = selectedItem == label
                NavigationItem(
                    icon = icon,
                    label = label,
                    isSelected = isSelected,
                    eveColors = eveColors,
                    onClick = { selectedItem = label }
                )
            }
        }
    }
}

@Composable
private fun NavigationItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    eveColors: EveColors,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) eveColors.accentColor.copy(alpha = 0.15f) else Color.Transparent
    val contentColor = if (isSelected) eveColors.accentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            color = backgroundColor,
            onClick = onClick
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = label,
                    color = contentColor,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun MainContent(eveColors: EveColors) {
    Surface(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        color = MaterialTheme.colorScheme.background
    ) {
        Column {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = eveColors.headerColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Welcome to EVE Trader",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Select a module from the sidebar to get started",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoCard("Market Scanner", "Scan regional markets for opportunities", eveColors)
                InfoCard("Price Alerts", "Get notified on price changes", eveColors)
                InfoCard("Trade Calculator", "Calculate profit margins", eveColors)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = eveColors.headerColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Recent Activity",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No recent activity to display",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    description: String,
    eveColors: EveColors
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = eveColors.headerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
