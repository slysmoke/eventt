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
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.everef.EveRefDao
import org.eventt.core.everef.EveRefService
import org.eventt.core.staticdata.CitadelService
import org.eventt.core.staticdata.StaticDataImporter
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    val syncState by EveRefService.state.collectAsState()
    val sdeState by StaticDataImporter.state.collectAsState()

    var selectedSource by remember { mutableStateOf("esi") }
    var periodMonths by remember { mutableStateOf(6) }
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
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        CharacterFeesCard()

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
                Text("Market History Source", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            HorizontalDivider()

            // ESI option
            SourceOption(
                selected = selectedSource == "esi",
                title = "ESI (EVE Online API)",
                description = "Fetches history on demand per item and region. Always current, no storage required.",
                onClick = { onSourceSelected("esi") },
            )

            // EveRef option
            SourceOption(
                selected = selectedSource == "everef",
                title = "EveRef Bulk Data",
                description =
                    "Downloads complete daily market history files from data.everef.net. " +
                        "Covers all items and regions. Data is ~3 days behind.",
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
                "History period to keep",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "1 month", 3 to "3 months", 6 to "6 months").forEach { (months, label) ->
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
                            "$downloadCount days downloaded  ($earliestDate → $latestDate)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "No data downloaded yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (lastSyncMillis != null) {
                    Text(
                        "Last sync: ${formatTimestamp(lastSyncMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (syncState.error != null) {
                    Text(
                        "Error: ${syncState.error}",
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
                Text(if (syncState.isRunning) "Syncing…" else "Sync Now")
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
                Text("Static Data (SDE)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            Text(
                "Universe data: types, market groups, regions, NPC stations. " +
                    "Downloaded from the official EVE Online SDE. Re-import to pick up NPC stations or new content.",
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
                            Text("$typeCount types loaded", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                            Text(
                                "No SDE data — import required",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (buildNumber != null) {
                        Text(
                            "Build: $buildNumber",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (importDate != null) {
                        Text(
                            "Imported: ${formatTimestamp(importDate)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (sdeState.error != null) {
                        Text(
                            "Error: ${sdeState.error}",
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
                            "Importing…"
                        } else if (typeCount > 0) {
                            "Re-import SDE"
                        } else {
                            "Import SDE"
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
                Text("Player Structures (Citadels)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            Text(
                "Downloads the community citadel database from slysmoke/evernus-db. " +
                    "Enables name and system resolution for player-owned structures in orders, assets, and wallet.",
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
                            Text("$citadelCount structures in database", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "No citadel data. Structures will show ID instead of name.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (lastSyncMillis != null) {
                        Text(
                            "Last sync: ${formatTimestamp(lastSyncMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!syncState.isRunning && syncState.status.isNotEmpty() && syncState.error == null) {
                        Text(syncState.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (syncState.error != null) {
                        Text(
                            "Error: ${syncState.error}",
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
                    Text(if (syncState.isRunning) "Syncing…" else "Sync Citadels")
                }
                if (syncState.isRunning) {
                    Text(syncState.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Percent, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Character Fees", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            Text(
                "Set the actual fees for each character. Used to calculate net profit in analysis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            characters.forEach { char ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(char.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TaxField(
                            label = "Sales Tax %",
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
                            label = "Broker's Fee %",
                            value = brokersFeeValues[char.id] ?: "3.00",
                            onValueChange = { v ->
                                brokersFeeValues[char.id] = v
                                v.toDoubleOrNull()?.let { pct ->
                                    scope.launch(Dispatchers.IO) { StaticDataDao.setCharBrokersFee(char.id, pct.coerceIn(0.0, 100.0)) }
                                }
                            },
                            modifier = Modifier.width(140.dp),
                        )

                        val tax = salesTaxValues[char.id]?.toDoubleOrNull() ?: 0.0
                        val fee = brokersFeeValues[char.id]?.toDoubleOrNull() ?: 0.0
                        Text(
                            "Total: ${"%.2f".format(tax + fee)}%",
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
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v ->
            // Allow digits, dot, and at most one dot
            val filtered = v.filter { it.isDigit() || it == '.' }
            if (filtered.count { it == '.' } <= 1) onValueChange(filtered)
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        suffix = { Text("%", style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        modifier = modifier,
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}
