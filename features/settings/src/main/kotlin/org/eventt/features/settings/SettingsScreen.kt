package org.eventt.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import org.eventt.ui.common.*
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.StringResource
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

    var section by remember { mutableStateOf(SettingsSection.GENERAL) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.settings_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .border(1.dp, outline(), MaterialTheme.shapes.extraSmall)
                    .padding(6.dp),
            ) {
                SettingsSection.entries.forEach { s ->
                    // Data sources the app can't work well without: no SDE, or no marketlogs folder.
                    val warn = s == SettingsSection.DATA && (sdeTypeCount == 0 || marketLogPath == null)
                    NavRow(s, s == section, warn) { section = s }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (section) {
                    SettingsSection.GENERAL -> {
                        LanguageCard()
                    }

                    SettingsSection.TRADING -> {
                        CharacterFeesCard()
                    }

                    SettingsSection.HOTKEYS -> {
                        HotkeysCard()
                        StreamOverlaySettingsCard()
                    }

                    SettingsSection.P2P -> {
                        NostrIdentityCard()
                        NostrRelaysCard()
                    }

                    SettingsSection.DATA -> {
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
                            onSync = { scope.launch { EveRefService.sync() } },
                        )
                    }
                }
            }
        }
    }
}

private enum class SettingsSection(
    val label: StringResource,
    val icon: ImageVector,
) {
    GENERAL(Res.string.sec_general, Icons.Default.Language),
    TRADING(Res.string.sec_trading, Icons.Default.Percent),
    HOTKEYS(Res.string.sec_hotkeys, Icons.Default.Keyboard),
    P2P(Res.string.sec_p2p, Icons.Default.Podcasts),
    DATA(Res.string.sec_data, Icons.Default.Storage),
}

@Composable
private fun NavRow(
    s: SettingsSection,
    selected: Boolean,
    warn: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(accent.copy(alpha = 0.1f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(s.icon, null, Modifier.size(16.dp), tint = if (selected) accent else AXIS_COLOR)
        Text(
            stringResource(s.label),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (warn) Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.error, CircleShape))
    }
}

/** One settings block: a terminal panel with an optional one-paragraph description on top. */
@Composable
internal fun Section(
    title: String,
    desc: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Panel(title, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 2.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR) }
            content()
        }
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
    Section(stringResource(Res.string.market_history_source)) {
        SourceOption(
            selected = selectedSource == "esi",
            title = stringResource(Res.string.source_esi_title),
            description = stringResource(Res.string.source_esi_desc),
            onClick = { onSourceSelected("esi") },
        )
        SourceOption(
            selected = selectedSource == "everef",
            title = stringResource(Res.string.source_everef_title),
            description = stringResource(Res.string.source_everef_desc),
            onClick = { onSourceSelected("everef") },
        )
        // EveRef-specific settings — only shown when EveRef is selected
        if (selectedSource == "everef") {
            HorizontalDivider(color = outline())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.history_period_to_keep), style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR)
                Spacer(Modifier.width(4.dp))
                listOf(1 to Res.string.period_1_month, 3 to Res.string.period_3_months).forEach { (months, label) ->
                    Segment(stringResource(label), periodMonths == months) { if (!syncState.isRunning) onPeriodSelected(months) }
                }
            }
            if (downloadCount > 0 && earliestDate != null && latestDate != null) {
                StatusLine(
                    pluralStringResource(Res.plurals.days_downloaded, downloadCount, downloadCount, earliestDate, latestDate),
                    positiveColor,
                )
            } else {
                StatusLine(stringResource(Res.string.no_data_downloaded))
            }
            lastSyncMillis?.let { StatusLine(stringResource(Res.string.last_sync, formatTimestamp(it))) }
            syncState.error?.let { StatusLine(stringResource(Res.string.error_prefix, it), MaterialTheme.colorScheme.error) }
            SyncControls(
                stringResource(if (syncState.isRunning) Res.string.syncing else Res.string.sync_now),
                syncState.isRunning,
                syncState.progress,
                syncState.status,
                onSync,
            )
        }
    }
}

// Button plus, while it runs (or once it has made progress), a progress bar with the status text.
@Composable
private fun SyncControls(
    label: String,
    running: Boolean,
    progress: Float,
    status: String,
    onClick: () -> Unit,
) {
    ActionButton(label, busy = running, onClick = onClick)
    if (running || progress > 0f) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text(status, style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR)
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(description, style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR)
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
    Section(stringResource(Res.string.sde_title), stringResource(Res.string.sde_desc)) {
        if (typeCount > 0) {
            StatusLine(pluralStringResource(Res.plurals.types_loaded, typeCount, typeCount), positiveColor)
        } else {
            StatusLine(stringResource(Res.string.no_sde_data), MaterialTheme.colorScheme.error)
        }
        buildNumber?.let { StatusLine(stringResource(Res.string.sde_build, it)) }
        importDate?.let { StatusLine(stringResource(Res.string.sde_imported, formatTimestamp(it))) }
        sdeState.error?.let { StatusLine(stringResource(Res.string.error_prefix, it), MaterialTheme.colorScheme.error) }
        SyncControls(
            stringResource(
                when {
                    sdeState.isRunning -> Res.string.importing
                    typeCount > 0 -> Res.string.reimport_sde
                    else -> Res.string.import_sde
                },
            ),
            sdeState.isRunning,
            sdeState.progress,
            sdeState.status,
            onImport,
        )
    }
}

@Composable
private fun CitadelCard(
    syncState: CitadelService.SyncState,
    citadelCount: Int,
    lastSyncMillis: Long?,
    onSync: () -> Unit,
) {
    Section(stringResource(Res.string.citadels_title), stringResource(Res.string.citadels_desc)) {
        if (citadelCount > 0) {
            StatusLine(pluralStringResource(Res.plurals.structures_in_db, citadelCount, citadelCount), positiveColor)
        } else {
            StatusLine(stringResource(Res.string.no_citadel_data))
        }
        lastSyncMillis?.let { StatusLine(stringResource(Res.string.last_sync, formatTimestamp(it))) }
        if (!syncState.isRunning && syncState.status.isNotEmpty() && syncState.error == null) {
            StatusLine(syncState.status, MaterialTheme.colorScheme.primary)
        }
        syncState.error?.let { StatusLine(stringResource(Res.string.error_prefix, it), MaterialTheme.colorScheme.error) }
        SyncControls(
            stringResource(if (syncState.isRunning) Res.string.syncing else Res.string.sync_citadels),
            syncState.isRunning,
            0f,
            syncState.status,
            onSync,
        )
    }
}

@Composable
private fun MarketLogsCard(
    path: String?,
    autoDetectFailed: Boolean,
    onAutoDetect: () -> Unit,
    onBrowse: () -> Unit,
) {
    Section(stringResource(Res.string.marketlogs_title), stringResource(Res.string.marketlogs_desc)) {
        if (path != null) {
            StatusLine(path, positiveColor)
        } else {
            StatusLine(stringResource(Res.string.not_configured), MaterialTheme.colorScheme.error)
        }
        if (autoDetectFailed) StatusLine(stringResource(Res.string.autodetect_failed), MaterialTheme.colorScheme.error)
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            StatusLine(stringResource(Res.string.macos_path_warning), warningColor)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(stringResource(Res.string.auto_detect), onClick = onAutoDetect)
            ActionButton(stringResource(Res.string.browse), onClick = onBrowse)
        }
    }
}

@Composable
private fun LanguageCard() {
    val scope = rememberCoroutineScope()
    // What's running now vs what's saved: the choice only applies on the next launch.
    val active = remember { AppLanguage.get() }
    var selected by remember { mutableStateOf(active) }

    Section(stringResource(Res.string.language)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppLanguage.SUPPORTED.forEach { (code, name) ->
                Segment(name, selected == code) {
                    selected = code
                    scope.launch(Dispatchers.IO) { AppLanguage.set(code) }
                }
            }
        }
        if (selected != active) StatusLine(stringResource(Res.string.language_restart), warningColor)
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

    if (characters.isEmpty()) {
        Section(stringResource(Res.string.character_fees), stringResource(Res.string.character_fees_desc)) {
            StatusLine(stringResource(Res.string.no_characters_yet))
        }
        return
    }

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

    Section(stringResource(Res.string.character_fees), stringResource(Res.string.character_fees_desc)) {
        characters.forEach { char ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CharacterPortrait(char.id, size = 24.dp)
                    Text(char.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }

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
                        fontFamily = Mono,
                        color = AXIS_COLOR,
                    )
                }
            }

            if (char != characters.last()) HorizontalDivider(color = outline())
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
