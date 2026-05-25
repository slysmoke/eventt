package org.eve.trader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eve.trader.core.staticdata.StaticDataImporter
import org.eve.trader.ui.theme.*
import org.eve.trader.ui.common.RequestProgressDialog
import org.eve.trader.features.characters.CharacterManagementScreen
import org.eve.trader.features.market.MarketBrowserScreen
import org.eve.trader.features.assets.AssetViewerScreen
import org.eve.trader.features.wallet.WalletScreen
import org.eve.trader.features.orders.OrdersScreen
import org.eve.trader.features.dashboard.DashboardScreen
import org.eve.trader.features.alerts.PriceAlertsScreen
import org.eve.trader.features.industry.IndustryCalculatorScreen
import org.eve.trader.features.contracts.ContractTrackerScreen
import org.eve.trader.features.watchlist.WatchlistScreen
import org.eve.trader.features.market.MarketAnalysisScreen

enum class AppScreen(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Dashboard),
    CHARACTERS("Characters", Icons.Default.Person),
    MARKET("Market", Icons.Default.Store),
    ANALYSIS("Analysis", Icons.Default.Analytics),
    ASSETS("Assets", Icons.Default.Inventory),
    WALLET("Wallet", Icons.Default.AccountBalance),
    ORDERS("Orders", Icons.Default.ShoppingCart),
    WATCHLIST("Watchlist", Icons.Default.Visibility),
    ALERTS("Alerts", Icons.Default.Notifications),
    CONTRACTS("Contracts", Icons.Default.Description),
    INDUSTRY("Industry", Icons.Default.Factory),
}

@Composable
fun EveTraderApp() {
    var darkTheme by remember { mutableStateOf(true) }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val eveColors = if (darkTheme) DarkEveColors else LightEveColors

    // Trigger SDE import on first run; check for updates on subsequent runs
    val importState by StaticDataImporter.state.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            when {
                StaticDataImporter.isImportNeeded() -> StaticDataImporter.importAll()
                StaticDataImporter.checkVersionChanged() -> {
                    // New EVE patch — re-import to pick up renamed items, new types, etc.
                    StaticDataImporter.importAll()
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EveTypography
    ) {
        var selectedScreen by remember { mutableStateOf(AppScreen.DASHBOARD) }
        var showProgressDialog by remember { mutableStateOf(false) }

        val coroutineScope = rememberCoroutineScope()
        Scaffold(
            topBar = {
                TopBar(
                    darkTheme = darkTheme,
                    currentScreen = selectedScreen,
                    onThemeToggle = { darkTheme = !darkTheme },
                    eveColors = eveColors,
                    onShowProgress = { showProgressDialog = true },
                    onNavDrawerToggle = { /* navigation handled by sidebar */ },
                    onUpdateSde = {
                        coroutineScope.launch(Dispatchers.IO) {
                            StaticDataImporter.importAll()
                        }
                    },
                )
            },
            content = { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Sidebar(
                            eveColors = eveColors,
                            selectedScreen = selectedScreen,
                            onScreenSelected = { selectedScreen = it },
                        )
                        ScreenContent(selectedScreen)
                    }

                    if (showProgressDialog) {
                        RequestProgressDialog(onDismiss = { showProgressDialog = false })
                    }

                    // SDE import overlay — shown on first run and after EVE patches
                    if (importState.isRunning) {
                        SdeImportOverlay(importState)
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    darkTheme: Boolean,
    currentScreen: AppScreen,
    onThemeToggle: () -> Unit,
    eveColors: EveColors,
    onShowProgress: () -> Unit,
    onNavDrawerToggle: () -> Unit,
    onUpdateSde: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = currentScreen.icon,
                    contentDescription = null,
                    tint = eveColors.accentColor,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = currentScreen.label,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        },
        actions = {
            IconButton(onClick = onUpdateSde) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Update static data (SDE)",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onShowProgress) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = "Show request progress",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onThemeToggle) {
                Icon(
                    imageVector = if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Toggle theme",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = eveColors.headerColor,
        ),
    )
}

@Composable
private fun Sidebar(
    eveColors: EveColors,
    selectedScreen: AppScreen,
    onScreenSelected: (AppScreen) -> Unit,
) {
    Surface(
        modifier = Modifier.width(200.dp).fillMaxHeight(),
        color = eveColors.headerColor,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp).fillMaxHeight()) {
            // EVE Trader branding
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = eveColors.accentColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("EVE Trader", style = MaterialTheme.typography.titleMedium, color = eveColors.accentColor)
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))

            // Navigation items
            AppScreen.entries.forEach { screen ->
                val isSelected = selectedScreen == screen
                val backgroundColor = if (isSelected) eveColors.accentColor.copy(alpha = 0.15f) else Color.Transparent
                val contentColor = if (isSelected) eveColors.accentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    color = backgroundColor,
                    shape = MaterialTheme.shapes.small,
                    onClick = { onScreenSelected(screen) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = screen.icon,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = screen.label,
                            color = contentColor,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SdeImportOverlay(state: StaticDataImporter.ImportState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(modifier = Modifier.width(480.dp)) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("Loading Game Data", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Downloading static data from EVE Online ESI.\nThis takes a few minutes on first run.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = state.progress,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.error?.let { error ->
                    Text(
                        "Error: $error",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenContent(screen: AppScreen) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (screen) {
            AppScreen.DASHBOARD -> DashboardScreen()
            AppScreen.CHARACTERS -> CharacterManagementScreen()
            AppScreen.MARKET -> MarketBrowserScreen()
            AppScreen.ANALYSIS -> MarketAnalysisScreen()
            AppScreen.ASSETS -> AssetViewerScreen()
            AppScreen.WALLET -> WalletScreen()
            AppScreen.ORDERS -> OrdersScreen()
            AppScreen.WATCHLIST -> WatchlistScreen()
            AppScreen.ALERTS -> PriceAlertsScreen()
            AppScreen.CONTRACTS -> ContractTrackerScreen()
            AppScreen.INDUSTRY -> IndustryCalculatorScreen()
        }
    }
}
