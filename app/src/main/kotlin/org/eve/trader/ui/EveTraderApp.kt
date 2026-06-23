package org.eve.trader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eve.trader.AppVersion
import org.eve.trader.core.database.AppState
import org.eve.trader.core.database.CharacterDao
import org.eve.trader.core.model.CharacterModel
import org.eve.trader.core.staticdata.StaticDataImporter
import org.eve.trader.update.UpdateChecker
import org.eve.trader.update.UpdateInfo
import org.eve.trader.update.UpdateProgress
import org.eve.trader.ui.theme.*
import org.eve.trader.ui.common.RequestProgressDialog
import org.eve.trader.features.characters.CharacterManagementScreen
import org.eve.trader.features.market.MarketBrowserScreen
import org.eve.trader.features.assets.AssetViewerScreen
import org.eve.trader.features.wallet.WalletScreen
import org.eve.trader.features.orders.OrdersScreen
import org.eve.trader.features.dashboard.DashboardScreen
import org.eve.trader.features.alerts.AlertMonitor
import org.eve.trader.features.alerts.PriceAlertsScreen
import org.eve.trader.core.model.PriceAlertModel
import org.eve.trader.features.industry.IndustryCalculatorScreen
import org.eve.trader.features.contracts.ContractTrackerScreen
import org.eve.trader.features.watchlist.WatchlistScreen
import org.eve.trader.features.market.MarketAnalysisScreen
import org.eve.trader.features.settings.SettingsScreen
import org.eve.trader.core.everef.EveRefService

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
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
fun EveTraderApp() {
    var darkTheme by remember { mutableStateOf(true) }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val eveColors = if (darkTheme) DarkEveColors else LightEveColors

    val importState by StaticDataImporter.state.collectAsState()
    val everefState by EveRefService.state.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            when {
                StaticDataImporter.isImportNeeded() -> StaticDataImporter.importAll()
                StaticDataImporter.checkVersionChanged() -> StaticDataImporter.importAll()
            }
            AppState.init()
        }
        // EveRef sync runs in parallel — does not block UI or AppState init
        launch(Dispatchers.IO) {
            if (EveRefService.getSelectedSource() == "everef") {
                EveRefService.sync()
            }
        }
    }

    // Update check — runs in background, never blocks startup
    var updateInfo     by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateProgress by remember { mutableStateOf<UpdateProgress>(UpdateProgress.Idle) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            updateInfo = UpdateChecker.checkLatestRelease()
        }
    }

    // Alert monitor — starts polling loop and collects triggered alerts
    val triggeredAlerts by AlertMonitor.triggered.collectAsState()
    LaunchedEffect(Unit) {
        AlertMonitor.start(coroutineScope)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EveTypography
    ) {
        var selectedScreen by remember { mutableStateOf(AppScreen.DASHBOARD) }
        var showProgressDialog by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopBar(
                    darkTheme = darkTheme,
                    currentScreen = selectedScreen,
                    onThemeToggle = { darkTheme = !darkTheme },
                    eveColors = eveColors,
                    onShowProgress = { showProgressDialog = true },
                )
            },
            content = { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Update banner — shown only when a newer release is available
                        updateInfo?.let { info ->
                            UpdateBanner(
                                info = info,
                                progress = updateProgress,
                                onDismiss = { updateInfo = null },
                                onInstall = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        UpdateChecker.downloadAndInstall(info) { p ->
                                            updateProgress = p
                                        }
                                    }
                                },
                            )
                        }

                        // EveRef sync progress banner
                        if (everefState.isRunning) {
                            EveRefSyncBanner(everefState)
                        }

                        // Alert notification banners
                        triggeredAlerts.forEach { alert ->
                            AlertNotificationBanner(
                                alert = alert,
                                onDismiss = { AlertMonitor.dismiss(alert) },
                            )
                        }

                        Row(modifier = Modifier.weight(1f)) {
                            val selectedCharId by AppState.selectedCharId.collectAsState()
                            Sidebar(
                                eveColors = eveColors,
                                selectedScreen = selectedScreen,
                                selectedCharId = selectedCharId,
                                onScreenSelected = { selectedScreen = it },
                            )
                            ScreenContent(selectedScreen, selectedCharId)
                        }
                    }

                    if (showProgressDialog) {
                        RequestProgressDialog(onDismiss = { showProgressDialog = false })
                    }

                    if (importState.isRunning) {
                        SdeImportOverlay(importState)
                    }
                }
            },
        )
    }
}

// ─── Update banner ────────────────────────────────────────────────────────

@Composable
private fun UpdateBanner(
    info: UpdateInfo,
    progress: UpdateProgress,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    val isWorking = progress is UpdateProgress.Downloading || progress is UpdateProgress.Restarting

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 4.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Version ${info.version} available  (current: ${AppVersion.NAME})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    if (progress is UpdateProgress.Downloading) {
                        Text(
                            progress.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                        )
                    } else if (progress is UpdateProgress.Restarting) {
                        Text(
                            "Restarting…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                        )
                    } else if (progress is UpdateProgress.Error) {
                        Text(
                            progress.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (!isWorking) {
                    Button(
                        onClick = onInstall,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Update", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Close, null, Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f),
                        )
                    }
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            // Download progress bar
            if (progress is UpdateProgress.Downloading) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.tertiaryContainer,
                )
            }
        }
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
    selectedCharId: Int?,
    onScreenSelected: (AppScreen) -> Unit,
) {
    val characters = remember { try { CharacterDao.getAll() } catch (_: Exception) { emptyList<CharacterModel>() } }
    var charMenuExpanded by remember { mutableStateOf(false) }
    val selectedChar = characters.find { it.id == selectedCharId }

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

            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))

            // Character selector
            if (characters.isNotEmpty()) {
                Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.15f),
                        onClick = { charMenuExpanded = true },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = eveColors.accentColor,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                selectedChar?.name ?: "Select character",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = charMenuExpanded,
                        onDismissRequest = { charMenuExpanded = false },
                    ) {
                        characters.forEach { char ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (char.id == selectedCharId) {
                                            Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = eveColors.accentColor)
                                        } else {
                                            Spacer(Modifier.size(14.dp))
                                        }
                                        Text(char.name, style = MaterialTheme.typography.bodyMedium)
                                    }
                                },
                                onClick = {
                                    AppState.selectCharacter(char.id)
                                    charMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }

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
                    progress = { state.progress },
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
private fun AlertNotificationBanner(
    alert: PriceAlertModel,
    onDismiss: () -> Unit,
) {
    val isAbove = alert.condition == "above"
    Surface(
        color = if (isAbove) Color(0xFF1B4332) else Color(0xFF3B1212),
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (isAbove) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isAbove) Color(0xFF69DB7C) else Color(0xFFFF6B6B),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    alert.typeName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    "${alert.orderType.replaceFirstChar { it.uppercase() }} price " +
                    "${if (isAbove) "rose above" else "dropped below"} " +
                    formatAlertPrice(alert.targetPrice),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, null, Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

private fun formatAlertPrice(v: Double): String = when {
    v >= 1_000_000_000 -> String.format("%.2fB ISK", v / 1_000_000_000)
    v >= 1_000_000     -> String.format("%.2fM ISK", v / 1_000_000)
    v >= 1_000         -> String.format("%.1fK ISK", v / 1_000)
    else               -> String.format("%,.2f ISK", v)
}

@Composable
private fun EveRefSyncBanner(state: EveRefService.SyncState) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Syncing market history (EveRef)…",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (state.status.isNotEmpty()) {
                        Text(
                            buildString {
                                append(state.status)
                                if (state.totalFiles > 0) {
                                    append("  •  ${state.filesDownloaded}/${state.totalFiles} files")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                        )
                    }
                }
                Text(
                    "${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        }
    }
}

@Composable
private fun ScreenContent(screen: AppScreen, selectedCharId: Int?) {
    // Track which screens have been visited so we only mount them on first visit,
    // but keep them in the composition afterwards to preserve their state.
    var visited by remember { mutableStateOf(setOf(screen)) }
    LaunchedEffect(screen) { visited = visited + screen }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AppScreen.entries.forEach { s ->
                if (s !in visited) return@forEach
                key(s) {
                    val active = s == screen
                    Box(
                        modifier = Modifier
                            .then(if (active) Modifier.fillMaxSize() else Modifier.requiredSize(0.dp))
                            .clipToBounds()
                    ) {
                        when (s) {
                            AppScreen.DASHBOARD   -> DashboardScreen(charId = selectedCharId)
                            AppScreen.CHARACTERS  -> CharacterManagementScreen()
                            AppScreen.MARKET      -> MarketBrowserScreen()
                            AppScreen.ANALYSIS    -> MarketAnalysisScreen()
                            AppScreen.ASSETS      -> AssetViewerScreen(charId = selectedCharId)
                            AppScreen.WALLET      -> WalletScreen(charId = selectedCharId)
                            AppScreen.ORDERS      -> OrdersScreen(charId = selectedCharId)
                            AppScreen.WATCHLIST   -> WatchlistScreen()
                            AppScreen.ALERTS      -> PriceAlertsScreen()
                            AppScreen.CONTRACTS   -> ContractTrackerScreen(charId = selectedCharId)
                            AppScreen.INDUSTRY    -> IndustryCalculatorScreen()
                            AppScreen.SETTINGS    -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}
