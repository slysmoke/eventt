package org.eventt.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.AppVersion
import org.eventt.GlobalHotkeyService
import org.eventt.core.cache.EsiCacheManager
import org.eventt.core.database.AppState
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.CorporationDao
import org.eventt.core.database.DatabaseManager
import org.eventt.core.database.NostrOrderDao
import org.eventt.core.database.NostrReservationModel
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.ViewContext
import org.eventt.core.everef.EveRefService
import org.eventt.core.model.CharacterModel
import org.eventt.core.model.PriceAlertModel
import org.eventt.core.model.RequestStatus
import org.eventt.core.nostr.NostrRelayEvent
import org.eventt.core.nostr.NostrRelayManager
import org.eventt.core.queue.RequestQueueManager
import org.eventt.core.staticdata.CitadelService
import org.eventt.core.staticdata.StaticDataImporter
import org.eventt.features.alerts.AlertMonitor
import org.eventt.features.alerts.PriceAlertsScreen
import org.eventt.features.assets.AssetViewerScreen
import org.eventt.features.characters.CharacterManagementScreen
import org.eventt.features.contracts.ContractTrackerScreen
import org.eventt.features.dashboard.DashboardScreen
import org.eventt.features.market.MarketAnalysisScreen
import org.eventt.features.market.MarketBrowserScreen
import org.eventt.features.orders.OrdersScreen
import org.eventt.features.overlay.OverlayWindow
import org.eventt.features.p2pmarket.CountBadge
import org.eventt.features.p2pmarket.P2pMarketScreen
import org.eventt.features.p2pmarket.rememberPendingBuyRequestCount
import org.eventt.features.settings.SettingsScreen
import org.eventt.features.tools.ToolsScreen
import org.eventt.features.wallet.WalletScreen
import org.eventt.features.watchlist.WatchlistScreen
import org.eventt.ui.common.RequestProgressDialog
import org.eventt.ui.theme.*
import org.eventt.update.UpdateChecker
import org.eventt.update.UpdateInfo
import org.eventt.update.UpdateProgress
import java.util.Locale

enum class AppScreen(
    val label: String,
    val icon: ImageVector,
) {
    DASHBOARD("Dashboard", Icons.Default.Dashboard),
    CHARACTERS("Characters", Icons.Default.Person),
    MARKET("Market", Icons.Default.Store),
    P2P_MARKET("P2P Market", Icons.AutoMirrored.Filled.CompareArrows),
    ANALYSIS("Analysis", Icons.Default.Analytics),
    ASSETS("Assets", Icons.Default.Inventory),
    WALLET("Wallet", Icons.Default.AccountBalance),
    ORDERS("Orders", Icons.Default.ShoppingCart),
    WATCHLIST("Watchlist", Icons.Default.Visibility),
    ALERTS("Alerts", Icons.Default.Notifications),
    CONTRACTS("Contracts", Icons.Default.Description),
    TOOLS("Tools", Icons.Default.Build),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
fun EventtApp() {
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
        // Player structure (citadel) names — same reasoning, runs alongside rather than
        // blocking startup on a network call.
        launch(Dispatchers.IO) { CitadelService.sync() }
        // Purges long-expired ESI cache rows — otherwise nothing ever deletes them and the
        // table grows without bound (found this at ~730MB / 55k dead rows on a real install).
        launch(Dispatchers.IO) { EsiCacheManager.cleanupExpired() }
        // VACUUM reclaims that deleted space back from the file, but it's a full rewrite that
        // needs exclusive DB access — only worth it when there's a meaningful amount of free
        // space, and throttled so it can't run on every single startup.
        launch(Dispatchers.IO) { DatabaseManager.vacuumIfNeeded() }
    }

    // Update check — runs in background, never blocks startup
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
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

    // Trade overlay window — independent of the main theme, lives at the app level
    var showOverlay by remember { mutableStateOf(false) }
    if (showOverlay) {
        OverlayWindow(onClose = { showOverlay = false })
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EveTypography,
    ) {
        var selectedScreen by remember { mutableStateOf(AppScreen.DASHBOARD) }
        var showProgressDialog by remember { mutableStateOf(false) }

        // Tell the global hotkey which screen's queue (Orders vs Station Trading) Ctrl+Z should
        // act on — every visited screen keeps running in the background, so this can't be inferred
        // from which queue was updated most recently.
        LaunchedEffect(selectedScreen) { GlobalHotkeyService.activeScreen = selectedScreen }

        // P2P Market incoming-request notifications — shown regardless of which tab is currently
        // open, since a new buy request otherwise stays invisible until you happen to check My
        // Orders yourself. Enriched with the item name (an extra DB round-trip) before display
        // rather than showing the bare reservation, since "Voidraven Blueprint x2" means something
        // to look at and "trade_id 9c5061b6…" doesn't.
        var incomingRequestNotices by remember { mutableStateOf<List<IncomingRequestNotice>>(emptyList()) }
        LaunchedEffect(Unit) {
            NostrRelayManager.events.collect { event ->
                if (event !is NostrRelayEvent.IncomingBuyRequest) return@collect
                val reservation = event.reservation
                val typeName =
                    withContext(Dispatchers.IO) {
                        val order = NostrOrderDao.getByCoordinate(reservation.orderUuid, reservation.sellerPubkey)
                        order?.let { StaticDataDao.getTypeById(it.typeId)?.name } ?: "an order"
                    }
                incomingRequestNotices = incomingRequestNotices + IncomingRequestNotice(reservation, typeName)
            }
        }

        Scaffold(
            topBar = {
                TopBar(
                    darkTheme = darkTheme,
                    currentScreen = selectedScreen,
                    onThemeToggle = { darkTheme = !darkTheme },
                    eveColors = eveColors,
                    onShowProgress = { showProgressDialog = true },
                    overlayActive = showOverlay,
                    onToggleOverlay = { showOverlay = !showOverlay },
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

                        // P2P Market incoming-request notification banners
                        incomingRequestNotices.forEach { notice ->
                            IncomingRequestBanner(
                                notice = notice,
                                onView = {
                                    selectedScreen = AppScreen.P2P_MARKET
                                    incomingRequestNotices = incomingRequestNotices - notice
                                },
                                onDismiss = { incomingRequestNotices = incomingRequestNotices - notice },
                            )
                        }

                        Row(modifier = Modifier.weight(1f)) {
                            val selectedContext by AppState.selectedContext.collectAsState()
                            Sidebar(
                                eveColors = eveColors,
                                selectedScreen = selectedScreen,
                                selectedContext = selectedContext,
                                onScreenSelected = { selectedScreen = it },
                            )
                            ScreenContent(selectedScreen, selectedContext)
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
                        colors =
                            ButtonDefaults.buttonColors(
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
                            Icons.Default.Close,
                            null,
                            Modifier.size(16.dp),
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
    overlayActive: Boolean = false,
    onToggleOverlay: () -> Unit = {},
) {
    Box {
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
                val esiRequests by RequestQueueManager.requests.collectAsState()
                val esiActive = esiRequests.count { it.status == RequestStatus.QUEUED || it.status == RequestStatus.IN_PROGRESS }
                val esiFailed = esiRequests.count { it.status == RequestStatus.FAILED }
                // Keeps spinning continuously rather than restarting from 0° each time a burst of
                // requests starts — only whether it's *applied* (below) depends on esiActive.
                val syncRotation by
                    rememberInfiniteTransition(label = "esiSyncSpin").animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
                        label = "esiSyncSpinAngle",
                    )
                IconButton(onClick = onShowProgress) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Show request progress",
                        tint =
                            when {
                                esiFailed > 0 -> Color(0xFFFF6B6B)
                                esiActive > 0 -> eveColors.accentColor
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        modifier = Modifier.rotate(if (esiActive > 0) syncRotation else 0f),
                    )
                }
                IconButton(onClick = onToggleOverlay) {
                    Icon(
                        imageVector = Icons.Default.Calculate,
                        contentDescription = "Trade overlay",
                        tint = if (overlayActive) eveColors.accentColor else MaterialTheme.colorScheme.onSurface,
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
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = eveColors.headerColor,
                ),
        )
        // App branding, centered in the bar regardless of the title/actions' widths.
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource("icon.png"),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "EVE Night Trade Tools",
                style = MaterialTheme.typography.titleMedium,
                color = eveColors.accentColor,
            )
        }
    }
}

@Composable
private fun Sidebar(
    eveColors: EveColors,
    selectedScreen: AppScreen,
    selectedContext: ViewContext?,
    onScreenSelected: (AppScreen) -> Unit,
) {
    // Re-fetched whenever the selection changes (e.g. after adding a character via
    // CharacterManagementScreen, which calls AppState.refreshCharacters()) so newly-added
    // characters/corps show up without an app restart.
    val characters =
        remember(selectedContext) {
            try {
                CharacterDao.getAll()
            } catch (_: Exception) {
                emptyList<CharacterModel>()
            }
        }
    // One entry per (corp, member character) pair rather than one per corp — picking an
    // arbitrary "acting character" for a corp broke opening items in the game client (ESI's
    // open-market-window call only does anything visible for whichever character is actually
    // logged into the local EVE client) and made it impossible to tell corp orders apart by who
    // placed them. Listing every locally-added member lets you pick the one you're actually
    // playing, same as the plain character list above.
    val corporations =
        remember(selectedContext) {
            try {
                val corpNames = CorporationDao.getAll().associate { (it["id"] as? Int) to (it["name"] as? String ?: "") }
                characters
                    .mapNotNull { char ->
                        val corpId = char.corporationId ?: return@mapNotNull null
                        Triple(corpId, corpNames[corpId] ?: char.corporationName ?: "", char)
                    }.sortedBy { (_, corpName, char) -> corpName + char.name }
            } catch (_: Exception) {
                emptyList()
            }
        }
    var charMenuExpanded by remember { mutableStateOf(false) }
    val selectedChar = (selectedContext as? ViewContext.Character)?.let { ctx -> characters.find { it.id == ctx.charId } }
    val selectedCorp = selectedContext as? ViewContext.Corporation
    val selectedCorpActingChar = selectedCorp?.let { corp -> characters.find { it.id == corp.actingCharacterId } }
    val headerLabel =
        when {
            selectedCorp != null -> "${selectedCorp.corporationName} (${selectedCorpActingChar?.name ?: "?"})"
            selectedChar != null -> selectedChar.name
            else -> "Select character"
        }
    val headerIcon = if (selectedCorp != null) Icons.Default.Business else Icons.Default.Person

    Surface(
        modifier = Modifier.width(200.dp).fillMaxHeight(),
        color = eveColors.headerColor,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp).fillMaxHeight()) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))

            // Character/corporation selector
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
                                headerIcon,
                                contentDescription = null,
                                tint = eveColors.accentColor,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                headerLabel,
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
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (char.id == selectedChar?.id) {
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

                        if (corporations.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                "Corporations",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                            corporations.forEach { (corpId, corpName, actingChar) ->
                                val isSelected = corpId == selectedCorp?.corporationId && actingChar.id == selectedCorp.actingCharacterId
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            if (isSelected) {
                                                Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = eveColors.accentColor)
                                            } else {
                                                Spacer(Modifier.size(14.dp))
                                            }
                                            Icon(Icons.Default.Business, null, Modifier.size(14.dp), tint = eveColors.accentColor)
                                            Text("$corpName (${actingChar.name})", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    },
                                    onClick = {
                                        AppState.selectCorporation(corpId, corpName, actingChar.id)
                                        charMenuExpanded = false
                                    },
                                )
                            }
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
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
                            modifier = Modifier.weight(1f),
                        )
                        if (screen == AppScreen.P2P_MARKET) {
                            CountBadge(rememberPendingBuyRequestCount())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SdeImportOverlay(state: StaticDataImporter.ImportState) {
    Box(
        modifier =
            Modifier
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

private fun formatAlertPrice(v: Double): String =
    when {
        v >= 1_000_000_000 -> String.format(Locale.US, "%.2fB ISK", v / 1_000_000_000)
        v >= 1_000_000 -> String.format(Locale.US, "%.2fM ISK", v / 1_000_000)
        v >= 1_000 -> String.format(Locale.US, "%.1fK ISK", v / 1_000)
        else -> String.format(Locale.US, "%,.2f ISK", v)
    }

private data class IncomingRequestNotice(
    val reservation: NostrReservationModel,
    val typeName: String,
)

@Composable
private fun IncomingRequestBanner(
    notice: IncomingRequestNotice,
    onView: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = Color(0xFF1B3A4B),
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.CompareArrows,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color(0xFF4FC3F7),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "New P2P Market buy request",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    "${notice.reservation.buyerChar.ifBlank { "Someone" }} wants ${notice.reservation.qty}x ${notice.typeName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
            TextButton(onClick = onView) { Text("View") }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, null, Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.6f))
            }
        }
    }
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
private fun ScreenContent(
    screen: AppScreen,
    selectedContext: ViewContext?,
) {
    val selectedCharId = selectedContext?.actingCharId
    // Track which screens have been visited so we only mount them on first visit,
    // but keep them in the composition afterwards to preserve their state.
    var visited by remember { mutableStateOf(setOf(screen)) }
    var dashboardRefreshTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(screen) {
        visited = visited + screen
        if (screen == AppScreen.DASHBOARD) dashboardRefreshTrigger++
    }

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
                        modifier =
                            Modifier
                                .then(if (active) Modifier.fillMaxSize() else Modifier.requiredSize(0.dp))
                                .clipToBounds(),
                    ) {
                        when (s) {
                            AppScreen.DASHBOARD -> DashboardScreen(context = selectedContext, refreshTrigger = dashboardRefreshTrigger)
                            AppScreen.CHARACTERS -> CharacterManagementScreen()
                            AppScreen.MARKET -> MarketBrowserScreen()
                            AppScreen.ANALYSIS -> MarketAnalysisScreen()
                            AppScreen.ASSETS -> AssetViewerScreen(context = selectedContext)
                            AppScreen.WALLET -> WalletScreen(context = selectedContext)
                            AppScreen.ORDERS -> OrdersScreen(context = selectedContext)
                            AppScreen.WATCHLIST -> WatchlistScreen()
                            AppScreen.ALERTS -> PriceAlertsScreen()
                            AppScreen.CONTRACTS -> ContractTrackerScreen(charId = selectedCharId)
                            AppScreen.TOOLS -> ToolsScreen(context = selectedContext)
                            AppScreen.P2P_MARKET -> P2pMarketScreen()
                            AppScreen.SETTINGS -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}
