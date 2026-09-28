package org.eventt.features.tools.splitter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.ViewContext
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.PLEX_MARKET_REGION_ID
import org.eventt.core.model.PLEX_TYPE_ID
import org.eventt.core.model.stringBlocking
import org.eventt.features.tools.ParseWarning
import org.eventt.features.tools.ToolsInputParser
import org.eventt.features.tools.pricing.PricingService
import org.eventt.tools.generated.resources.*
import org.eventt.ui.common.ConfirmDialog
import org.eventt.ui.common.ContentCard
import org.eventt.ui.common.formatIsk
import org.eventt.ui.theme.positiveColor
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

// EVE's SDE ship category — packaged_volume is only meaningfully populated for this category
// by this app's static-data importer (see core/staticdata/StaticDataImporter.kt).
private const val SHIP_CATEGORY_ID = 6

private fun formatVolume(v: Double): String = "%.1f m³".format(v)

// Persisted across restarts — constraints and ship choice are per-user setup, not per-paste, so
// they shouldn't reset every time the app reopens.
private object SplitterSettings {
    const val MAX_ISK = "tools.splitter.maxIsk"
    const val MAX_VOLUME = "tools.splitter.maxVolume"
    const val ALGORITHM = "tools.splitter.algorithm"
    const val SHIP_TYPE_ID = "tools.splitter.shipTypeId"
}

@Composable
fun SplitterScreen(context: ViewContext?) {
    val scope = rememberCoroutineScope()
    val charId = (context as? ViewContext.Character)?.charId

    var pasteText by remember { mutableStateOf("") }
    var maxIskText by remember { mutableStateOf("3500000000") }
    var maxVolumeText by remember { mutableStateOf("320000") }
    var algorithm by remember { mutableStateOf(SplitAlgorithm.FILL_FIRST) }
    var shipTypeId by remember { mutableStateOf(ShipFittingCatalog.HAULERS.first().typeId) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            StaticDataDao.getSetting(SplitterSettings.MAX_ISK)?.let { maxIskText = it }
            StaticDataDao.getSetting(SplitterSettings.MAX_VOLUME)?.let { maxVolumeText = it }
            StaticDataDao.getSetting(SplitterSettings.ALGORITHM)?.let { name ->
                SplitAlgorithm.entries.find { it.name == name }?.let { algorithm = it }
            }
            StaticDataDao.getSetting(SplitterSettings.SHIP_TYPE_ID)?.toIntOrNull()?.let { id ->
                if (ShipFittingCatalog.HAULERS.any { it.typeId == id }) shipTypeId = id
            }
        }
    }

    var isCalculating by remember { mutableStateOf(false) }
    var warnings by remember { mutableStateOf<List<ParseWarning>>(emptyList()) }
    var ffdPlan by remember { mutableStateOf<SplitPlan?>(null) }
    var balancedPlan by remember { mutableStateOf<SplitPlan?>(null) }
    var recommended by remember { mutableStateOf<SplitAlgorithm?>(null) }

    var pushResults by remember { mutableStateOf<Map<Int, FittingPushResult>>(emptyMap()) }
    var isPushing by remember { mutableStateOf(false) }
    var confirmingSplit by remember { mutableStateOf<Split?>(null) }
    var confirmingPushAll by remember { mutableStateOf(false) }

    val activePlan = if (algorithm == SplitAlgorithm.FILL_FIRST) ffdPlan else balancedPlan

    fun calculate() {
        isCalculating = true
        pushResults = emptyMap()
        val maxIsk = maxIskText.toDoubleOrNull() ?: 0.0
        val maxVolume = maxVolumeText.toDoubleOrNull() ?: 0.0
        val actingCharId = context?.actingCharId
        scope.launch(Dispatchers.IO) {
            val (parsed, parseWarnings) = ToolsInputParser.parse(pasteText)
            val (resolved, resolveWarnings) =
                ToolsInputParser.resolve(parsed) { type ->
                    if (type.categoryId == SHIP_CATEGORY_ID && type.packagedVolume > 0) type.packagedVolume else type.volume
                }

            val priceWarnings = mutableListOf<ParseWarning>()
            // ESI's /markets/prices/ ("adjusted_price"/"average_price") is a global, insurance-style
            // 30-day rolling average — often nowhere close to what these items actually sell for right
            // now. Value cargo off the acting character's current-region live sell orders instead,
            // falling back to the global average only for items with no local sell orders at all.
            val regionId = actingCharId?.let { PricingService.resolveRegionId(it) }
            if (regionId == null) {
                priceWarnings +=
                    ParseWarning(
                        stringBlocking(Res.string.region_paren),
                        stringBlocking(Res.string.warn_no_region),
                    )
            }
            val globalPrices = EsiClient.getMarketPrices()
            // PLEX only trades in its own global market region, never the character's home region —
            // resolve it there regardless of whether the character's own region was found.
            val regionSellByType: Map<Int, Double?> =
                coroutineScope {
                    val semaphore = Semaphore(8)
                    resolved
                        .mapNotNull { item ->
                            val itemRegionId = if (item.typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else regionId
                            itemRegionId ?: return@mapNotNull null
                            async {
                                semaphore.withPermit {
                                    item.typeId to
                                        runCatching {
                                            EsiClient
                                                .getMarketRegionOrders(itemRegionId, orderType = "sell", typeId = item.typeId)
                                                .mapNotNull { (it["price"] as? Number)?.toDouble() }
                                                .minOrNull()
                                        }.getOrNull()
                                }
                            }
                        }.awaitAll()
                        .toMap()
                }

            val lineItems =
                resolved.map { item ->
                    val regionSell = regionSellByType[item.typeId]
                    val price = regionSell ?: globalPrices[item.typeId]
                    if (regionId != null && regionSell == null) {
                        priceWarnings +=
                            ParseWarning(item.name, stringBlocking(Res.string.warn_no_live_sells))
                    }
                    if (price == null) {
                        priceWarnings += ParseWarning(item.name, stringBlocking(Res.string.warn_no_price))
                    }
                    SplitLineItem(item.typeId, item.name, item.quantity, price ?: 0.0, item.unitVolume)
                }
            val constraints = SplitConstraints(maxIsk, maxVolume)
            val (ffd, balanced) = SplitterService.computeBoth(lineItems, constraints)
            withContext(Dispatchers.Main) {
                warnings = parseWarnings + resolveWarnings + priceWarnings
                ffdPlan = ffd
                balancedPlan = balanced
                recommended = SplitterService.recommend(ffd, balanced)
                isCalculating = false
            }
        }
    }

    fun pushSplits(splits: List<Split>) {
        val cid = charId ?: return
        isPushing = true
        scope.launch(Dispatchers.IO) {
            val results = FittingPushService.pushAll(cid, splits, shipTypeId)
            withContext(Dispatchers.Main) {
                pushResults = pushResults + results.associateBy { it.split.index }
                isPushing = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ContentCard(title = stringResource(Res.string.cargo_list)) {
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    placeholder = { Text(stringResource(Res.string.paste_inventory)) },
                    minLines = 5,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val clip =
                            try {
                                Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
                            } catch (_: Exception) {
                                null
                            }
                        if (clip != null) pasteText = clip
                    }) {
                        Icon(Icons.Default.ContentPaste, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(Res.string.paste_from_clipboard))
                    }
                }
            }
        }

        item {
            ContentCard(title = stringResource(Res.string.constraints)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = maxIskText,
                        onValueChange = {
                            maxIskText = it
                            scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(SplitterSettings.MAX_ISK, it) }
                        },
                        label = { Text(stringResource(Res.string.max_isk_split)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxVolumeText,
                        onValueChange = {
                            maxVolumeText = it
                            scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(SplitterSettings.MAX_VOLUME, it) }
                        },
                        label = { Text(stringResource(Res.string.max_m3_split)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.algorithm), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = algorithm == SplitAlgorithm.FILL_FIRST,
                        onClick = {
                            algorithm = SplitAlgorithm.FILL_FIRST
                            scope.launch(
                                Dispatchers.IO,
                            ) { StaticDataDao.setSetting(SplitterSettings.ALGORITHM, SplitAlgorithm.FILL_FIRST.name) }
                        },
                        label = {
                            Text(
                                stringResource(Res.string.fill_first) +
                                    if (recommended == SplitAlgorithm.FILL_FIRST) " " + stringResource(Res.string.recommended) else "",
                            )
                        },
                    )
                    FilterChip(
                        selected = algorithm == SplitAlgorithm.BALANCED,
                        onClick = {
                            algorithm = SplitAlgorithm.BALANCED
                            scope.launch(
                                Dispatchers.IO,
                            ) { StaticDataDao.setSetting(SplitterSettings.ALGORITHM, SplitAlgorithm.BALANCED.name) }
                        },
                        label = {
                            Text(
                                stringResource(Res.string.balanced) +
                                    if (recommended == SplitAlgorithm.BALANCED) " " + stringResource(Res.string.recommended) else "",
                            )
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.ship_label), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                ShipDropdown(
                    selectedTypeId = shipTypeId,
                    onSelect = {
                        shipTypeId = it
                        scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(SplitterSettings.SHIP_TYPE_ID, it.toString()) }
                    },
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { calculate() }, enabled = !isCalculating && pasteText.isNotBlank()) {
                    if (isCalculating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(Res.string.calculate))
                }
            }
        }

        if (warnings.isNotEmpty()) {
            item {
                ContentCard(title = stringResource(Res.string.warnings_n, warnings.size)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        warnings.forEach { w ->
                            Text(
                                "• ${w.lineText}: ${w.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        val plan = activePlan
        if (plan != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.plans_summary, ffdPlan?.splits?.size ?: 0, balancedPlan?.splits?.size ?: 0),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (charId != null && plan.splits.isNotEmpty()) {
                        Button(onClick = { confirmingPushAll = true }, enabled = !isPushing) {
                            Text(stringResource(Res.string.push_all_n, plan.splits.size))
                        }
                    }
                }
                if (charId == null) {
                    Text(
                        stringResource(Res.string.select_char_push),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (plan.unplaced.isNotEmpty()) {
                item {
                    ContentCard(title = stringResource(Res.string.could_not_fit_n, plan.unplaced.size)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            plan.unplaced.forEach { u ->
                                Text(
                                    "• ${u.name} × ${u.quantityRemaining}: ${u.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            items(plan.splits, key = { it.index }) { split ->
                val pushResult = pushResults[split.index]
                ContentCard(
                    title =
                        stringResource(
                            Res.string.split_title,
                            split.index,
                            formatIsk(split.totalValue),
                            formatVolume(split.totalVolume),
                        ),
                    actions = {
                        when {
                            pushResult?.success == true -> {
                                Icon(Icons.Default.CheckCircle, stringResource(Res.string.pushed), tint = positiveColor)
                            }

                            pushResult?.success == false -> {
                                Icon(
                                    Icons.Default.Error,
                                    pushResult.error,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }

                            charId != null -> {
                                TextButton(onClick = { confirmingSplit = split }, enabled = !isPushing) {
                                    Text(stringResource(Res.string.push_to_esi))
                                }
                            }
                        }
                    },
                ) {
                    Text(
                        pluralStringResource(Res.plurals.item_types_n, split.itemTypeCount, split.itemTypeCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Column {
                        split.items.forEach { li ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(li.name, style = MaterialTheme.typography.bodySmall)
                                Text("× ${li.quantity}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (pushResult?.success == false) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(Res.string.push_failed, pushResult.error.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    confirmingSplit?.let { split ->
        ConfirmDialog(
            title = stringResource(Res.string.push_split_title),
            message =
                stringResource(Res.string.push_split_msg, split.index, formatIsk(split.totalValue), split.itemTypeCount),
            onDismiss = { confirmingSplit = null },
            onConfirm = { pushSplits(listOf(split)) },
            confirmText = stringResource(Res.string.push),
        )
    }
    if (confirmingPushAll) {
        val splits = activePlan?.splits ?: emptyList()
        ConfirmDialog(
            title = stringResource(Res.string.push_all_title),
            message = stringResource(Res.string.push_all_msg, splits.size),
            onDismiss = { confirmingPushAll = false },
            onConfirm = { pushSplits(splits) },
            confirmText = stringResource(Res.string.push_all),
        )
    }
}

@Composable
private fun ShipDropdown(
    selectedTypeId: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = ShipFittingCatalog.HAULERS.find { it.typeId == selectedTypeId }?.name ?: "—"

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selectedName)
            Spacer(Modifier.width(4.dp))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ShipFittingCatalog.HAULERS.forEach { hauler ->
                DropdownMenuItem(
                    text = { Text(hauler.name) },
                    onClick = {
                        onSelect(hauler.typeId)
                        expanded = false
                    },
                    leadingIcon =
                        if (hauler.typeId == selectedTypeId) {
                            { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                        } else {
                            null
                        },
                )
            }
        }
    }
}
