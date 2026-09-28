package org.eventt.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.AppLanguage
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.everef.EveRefDao
import org.eventt.core.everef.EveRefService
import org.eventt.core.marketlogs.MarketLogPaths
import org.eventt.core.staticdata.CitadelService
import org.eventt.core.staticdata.StaticDataImporter
import org.eventt.settings.generated.resources.*
import org.eventt.ui.common.Tip
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.JFileChooser

@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    val syncState by EveRefService.state.collectAsState()
    val sdeState by StaticDataImporter.state.collectAsState()

    var selectedSource by remember { mutableStateOf("esi") }
    var periodMonths by remember { mutableStateOf(1) }
    var downloadCount by remember { mutableStateOf(0) }
    var earliestDate by remember { mutableStateOf<String?>(null) }
    var latestDate by remember { mutableStateOf<String?>(null) }
    var lastSyncMillis by remember { mutableStateOf<Long?>(null) }

    val citadelSyncState by CitadelService.state.collectAsState()
    var citadelCount by remember { mutableStateOf(0) }
    var citadelLastSync by remember { mutableStateOf<Long?>(null) }

    var sdeBuildNumber by remember { mutableStateOf<String?>(null) }
    var sdeImportDate by remember { mutableStateOf<Long?>(null) }
    var sdeTypeCount by remember { mutableStateOf(0) }

    var marketLogPath by remember { mutableStateOf<String?>(null) }
    var marketLogAutoDetectFailed by remember { mutableStateOf(false) }
    val selectFolderTitle = stringResource(Res.string.select_marketlogs_folder)

    fun reloadStats() {
        downloadCount = EveRefDao.getDownloadCount()
        earliestDate = EveRefDao.getEarliestDate()
        latestDate = EveRefDao.getLatestDate()
        lastSyncMillis = EveRefService.getLastSyncMillis()
        citadelCount = CitadelService.getCitadelCount()
        citadelLastSync = CitadelService.getLastSyncMillis()
        sdeBuildNumber = StaticDataDao.getSetting("sde_build_number")
        sdeImportDate = StaticDataDao.getSetting("sde_import_date")?.toLongOrNull()
        sdeTypeCount = StaticDataDao.countTypes()
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            selectedSource = EveRefService.getSelectedSource()
            periodMonths = EveRefService.getHistoryPeriodMonths()
            reloadStats()
            marketLogPath = MarketLogPaths.getConfiguredPath() ?: MarketLogPaths.autoDetect()
        }
    }

    // Refresh stats once either sync finishes
    LaunchedEffect(syncState.isRunning) {
        if (!syncState.isRunning) {
            withContext(Dispatchers.IO) { reloadStats() }
        }
    }
    LaunchedEffect(citadelSyncState.isRunning) {
        if (!citadelSyncState.isRunning) {
            withContext(Dispatchers.IO) { reloadStats() }
        }
    }
    LaunchedEffect(sdeState.isRunning) {
        if (!sdeState.isRunning) {
            withContext(Dispatchers.IO) { reloadStats() }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(stringResource(Res.string.settings_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        LanguageCard()

        CharacterFeesCard()

        HotkeysCard()

        StreamOverlaySettingsCard()

        NostrIdentityCard()

        NostrRelaysCard()

        MarketLogsCard(
            path = marketLogPath,
            autoDetectFailed = marketLogAutoDetectFailed,
            onAutoDetect = {
                scope.launch(Dispatchers.IO) {
                    val detected = MarketLogPaths.autoDetect()
                    if (detected != null) {
                        MarketLogPaths.setConfiguredPath(detected)
                        withContext(Dispatchers.Main) {
                            marketLogPath = detected
                            marketLogAutoDetectFailed = false
                        }
                    } else {
                        withContext(Dispatchers.Main) { marketLogAutoDetectFailed = true }
                    }
                }
            },
            onBrowse = {
                val chooser =
                    JFileChooser().apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = selectFolderTitle
                        marketLogPath?.let { currentDirectory = File(it) }
                    }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    val selected = chooser.selectedFile.path
                    scope.launch(Dispatchers.IO) { MarketLogPaths.setConfiguredPath(selected) }
                    marketLogPath = selected
                    marketLogAutoDetectFailed = false
                }
            },
        )

        SdeCard(
            sdeState = sdeState,
            buildNumber = sdeBuildNumber,
            importDate = sdeImportDate,
            typeCount = sdeTypeCount,
            onImport = { scope.launch { StaticDataImporter.importAll() } },
        )

        CitadelCard(
            syncState = citadelSyncState,
            citadelCount = citadelCount,
            lastSyncMillis = citadelLastSync,
            onSync = { scope.launch { CitadelService.sync() } },
        )

        MarketHistorySourceCard(
            selectedSource = selectedSource,
            periodMonths = periodMonths,
            syncState = syncState,
            downloadCount = downloadCount,
            earliestDate = earliestDate,
            latestDate = latestDate,
            lastSyncMillis = lastSyncMillis,
            onSourceSelected = { source ->
                selectedSource = source
                scope.launch(Dispatchers.IO) { EveRefService.setSelectedSource(source) }
            },
            onPeriodSelected = { months ->
                periodMonths = months
                scope.launch(Dispatchers.IO) { EveRefService.setHistoryPeriodMonths(months) }
            },
            onSync = {
                scope.launch { EveRefService.sync() }
            },
        )
    }
}

@Composable
private fun MarketHistorySourceCard(
    selectedSource: String,
    periodMonths: Int,
    syncState: EveRefService.SyncState,
    downloadCount: Int,
    earliestDate: String?,
    latestDate: String?,
    lastSyncMillis: Long?,
    onSourceSelected: (String) -> Unit,
    onPeriodSelected: (Int) -> Unit,
    onSync: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(Res.string.market_history_source),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            HorizontalDivider()

            // ESI option
            SourceOption(
                selected = selectedSource == "esi",
                title = stringResource(Res.string.source_esi_title),
                description = stringResource(Res.string.source_esi_desc),
                onClick = { onSourceSelected("esi") },
            )

            // EveRef option
            SourceOption(
                selected = selectedSource == "everef",
                title = stringResource(Res.string.source_everef_title),
                description = stringResource(Res.string.source_everef_desc),
                onClick = { onSourceSelected("everef") },
            )

            // EveRef-specific settings — only shown when EveRef is selected
            if (selectedSource == "everef") {
                HorizontalDivider()
                EveRefSettings(
                    periodMonths = periodMonths,
                    syncState = syncState,
                    downloadCount = downloadCount,
                    earliestDate = earliestDate,
                    latestDate = latestDate,
                    lastSyncMillis = lastSyncMillis,
                    onPeriodSelected = onPeriodSelected,
                    onSync = onSync,
                )
            }
        }
    }
}

@Composable
private fun SourceOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick, modifier = Modifier.padding(top = 2.dp))
        Column(
            modifier = Modifier.weight(1f).let { if (!selected) it else it },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EveRefSettings(
    periodMonths: Int,
    syncState: EveRefService.SyncState,
    downloadCount: Int,
    earliestDate: String?,
    latestDate: String?,
    lastSyncMillis: Long?,
    onPeriodSelected: (Int) -> Unit,
    onSync: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Period selector
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(Res.string.history_period_to_keep),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    1 to stringResource(Res.string.period_1_month),
                    3 to stringResource(Res.string.period_3_months),
                ).forEach { (months, label) ->
                    FilterChip(
                        selected = periodMonths == months,
                        onClick = { onPeriodSelected(months) },
                        label = { Text(label) },
                        enabled = !syncState.isRunning,
                    )
                }
            }
        }

        // Status row
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (downloadCount > 0 && earliestDate != null && latestDate != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            pluralStringResource(Res.plurals.days_downloaded, downloadCount, downloadCount, earliestDate, latestDate),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(Res.string.no_data_downloaded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (lastSyncMillis != null) {
                    Text(
                        stringResource(Res.string.last_sync, formatTimestamp(lastSyncMillis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (syncState.error != null) {
                    Text(
                        stringResource(Res.string.error_prefix, syncState.error.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Sync button + progress
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSync,
                enabled = !syncState.isRunning,
            ) {
                if (syncState.isRunning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Default.Sync, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (syncState.isRunning) stringResource(Res.string.syncing) else stringResource(Res.string.sync_now))
            }

            if (syncState.isRunning || syncState.progress > 0f) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { syncState.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        syncState.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SdeCard(
    sdeState: StaticDataImporter.ImportState,
    buildNumber: String?,
    importDate: Long?,
    typeCount: Int,
    onImport: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(Res.string.sde_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            Text(
                stringResource(Res.string.sde_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (typeCount > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                pluralStringResource(Res.plurals.types_loaded, typeCount, typeCount),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                            Text(
                                stringResource(Res.string.no_sde_data),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (buildNumber != null) {
                        Text(
                            stringResource(Res.string.sde_build, buildNumber),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (importDate != null) {
                        Text(
                            stringResource(Res.string.sde_imported, formatTimestamp(importDate)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (sdeState.error != null) {
                        Text(
                            stringResource(Res.string.error_prefix, sdeState.error.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onImport, enabled = !sdeState.isRunning) {
                    if (sdeState.isRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (sdeState.isRunning) {
                            stringResource(Res.string.importing)
                        } else if (typeCount > 0) {
                            stringResource(Res.string.reimport_sde)
                        } else {
                            stringResource(Res.string.import_sde)
                        },
                    )
                }
                if (sdeState.isRunning) {
                    Text(sdeState.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (sdeState.isRunning || sdeState.progress > 0f) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { sdeState.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        sdeState.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CitadelCard(
    syncState: CitadelService.SyncState,
    citadelCount: Int,
    lastSyncMillis: Long?,
    onSync: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.LocationCity, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(Res.string.citadels_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                stringResource(Res.string.citadels_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // Status
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (citadelCount > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                pluralStringResource(Res.plurals.structures_in_db, citadelCount, citadelCount),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                stringResource(Res.string.no_citadel_data),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (lastSyncMillis != null) {
                        Text(
                            stringResource(Res.string.last_sync, formatTimestamp(lastSyncMillis)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!syncState.isRunning && syncState.status.isNotEmpty() && syncState.error == null) {
                        Text(syncState.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (syncState.error != null) {
                        Text(
                            stringResource(Res.string.error_prefix, syncState.error.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSync, enabled = !syncState.isRunning) {
                    if (syncState.isRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Sync, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (syncState.isRunning) stringResource(Res.string.syncing) else stringResource(Res.string.sync_citadels))
                }
                if (syncState.isRunning) {
                    Text(syncState.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MarketLogsCard(
    path: String?,
    autoDetectFailed: Boolean,
    onAutoDetect: () -> Unit,
    onBrowse: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(Res.string.marketlogs_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                stringResource(Res.string.marketlogs_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (path != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(path, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                            Text(
                                stringResource(Res.string.not_configured),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (autoDetectFailed) {
                        Text(
                            stringResource(Res.string.autodetect_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (System.getProperty("os.name").lowercase().contains("mac")) {
                        Text(
                            stringResource(Res.string.macos_path_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAutoDetect) { Text(stringResource(Res.string.auto_detect)) }
                OutlinedButton(onClick = onBrowse) { Text(stringResource(Res.string.browse)) }
            }
        }
    }
}

@Composable
private fun LanguageCard() {
    val scope = rememberCoroutineScope()
    // What's running now vs what's saved: the choice only applies on the next launch.
    val active = remember { AppLanguage.get() }
    var selected by remember { mutableStateOf(active) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(Res.string.language), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Box {
                var expanded by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { expanded = true }) {
                    Text(AppLanguage.SUPPORTED.getValue(selected))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AppLanguage.SUPPORTED.forEach { (code, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                expanded = false
                                selected = code
                                scope.launch(Dispatchers.IO) { AppLanguage.set(code) }
                            },
                        )
                    }
                }
            }
            if (selected != active) {
                Text(
                    stringResource(Res.string.language_restart),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

private fun formatTimestamp(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

@Composable
private fun CharacterFeesCard() {
    val scope = rememberCoroutineScope()
    val characters =
        remember {
            try {
                CharacterDao.getAll()
            } catch (_: Exception) {
                emptyList()
            }
        }

    if (characters.isEmpty()) return

    // Load current tax values for all characters
    val salesTaxValues =
        remember {
            mutableStateMapOf<Int, String>().also { map ->
                characters.forEach { char ->
                    map[char.id] = "%.2f".format(StaticDataDao.getCharSalesTax(char.id))
                }
            }
        }
    val brokersFeeValues =
        remember {
            mutableStateMapOf<Int, String>().also { map ->
                characters.forEach { char ->
                    map[char.id] = "%.2f".format(StaticDataDao.getCharBrokersFee(char.id))
                }
            }
        }
    val relistSkillValues =
        remember {
            mutableStateMapOf<Int, String>().also { map ->
                characters.forEach { char ->
                    map[char.id] = StaticDataDao.getCharRelistSkillLevel(char.id).toString()
                }
            }
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Percent, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(Res.string.character_fees),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                stringResource(Res.string.character_fees_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            characters.forEach { char ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(char.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TaxField(
                            label = stringResource(Res.string.sales_tax_pct),
                            tooltip = stringResource(Res.string.tip_set_sales_tax),
                            value = salesTaxValues[char.id] ?: "8.00",
                            onValueChange = { v ->
                                salesTaxValues[char.id] = v
                                v.toDoubleOrNull()?.let { pct ->
                                    scope.launch(Dispatchers.IO) { StaticDataDao.setCharSalesTax(char.id, pct.coerceIn(0.0, 100.0)) }
                                }
                            },
                            modifier = Modifier.width(140.dp),
                        )

                        TaxField(
                            label = stringResource(Res.string.brokers_fee_pct),
                            tooltip = stringResource(Res.string.tip_set_broker_fee),
                            value = brokersFeeValues[char.id] ?: "3.00",
                            onValueChange = { v ->
                                brokersFeeValues[char.id] = v
                                v.toDoubleOrNull()?.let { pct ->
                                    scope.launch(Dispatchers.IO) { StaticDataDao.setCharBrokersFee(char.id, pct.coerceIn(0.0, 100.0)) }
                                }
                            },
                            modifier = Modifier.width(140.dp),
                        )

                        OutlinedTextField(
                            value = relistSkillValues[char.id] ?: "0",
                            onValueChange = { v ->
                                val filtered = v.filter { it.isDigit() }.take(1)
                                relistSkillValues[char.id] = filtered
                                filtered.toIntOrNull()?.let { level ->
                                    scope.launch(Dispatchers.IO) { StaticDataDao.setCharRelistSkillLevel(char.id, level.coerceIn(0, 5)) }
                                }
                            },
                            label = {
                                Tip(stringResource(Res.string.tip_set_relist_skill)) {
                                    Text(
                                        stringResource(Res.string.adv_broker_relations_lvl),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.width(180.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )

                        val tax = salesTaxValues[char.id]?.toDoubleOrNull() ?: 0.0
                        val fee = brokersFeeValues[char.id]?.toDoubleOrNull() ?: 0.0
                        Text(
                            stringResource(Res.string.fees_total, "%.2f".format(tax + fee)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (char != characters.last()) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TaxField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    tooltip: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v ->
            // Allow digits, dot, and at most one dot
            val filtered = v.filter { it.isDigit() || it == '.' }
            if (filtered.count { it == '.' } <= 1) onValueChange(filtered)
        },
        label = { Tip(tooltip) { Text(label, style = MaterialTheme.typography.labelSmall) } },
        suffix = { Text("%", style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        modifier = modifier,
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}
