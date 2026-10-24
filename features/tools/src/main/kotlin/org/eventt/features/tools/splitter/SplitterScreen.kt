package org.eventt.features.tools.splitter

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import org.eventt.features.tools.PasteBox
import org.eventt.features.tools.ToolsInputParser
import org.eventt.features.tools.pricing.PricingService
import org.eventt.tools.generated.resources.*
import org.eventt.ui.common.*
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.util.Locale
import kotlin.math.roundToInt

// EVE's SDE ship category — packaged_volume is only meaningfully populated for this category
// by this app's static-data importer (see core/staticdata/StaticDataImporter.kt).
private const val SHIP_CATEGORY_ID = 6

private fun formatM3(v: Double): String = String.format(Locale.US, "%,.1f m³", v)

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

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PasteBox(stringResource(Res.string.cargo_list), pasteText, Modifier.weight(1f)) { pasteText = it }
            Panel(stringResource(Res.string.constraints), Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = maxIskText,
                            onValueChange = {
                                maxIskText = it
                                scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(SplitterSettings.MAX_ISK, it) }
                            },
                            label = { Tip(stringResource(Res.string.tip_sp_max_isk)) { Text(stringResource(Res.string.max_isk_split)) } },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = maxVolumeText,
                            onValueChange = {
                                maxVolumeText = it
                                scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(SplitterSettings.MAX_VOLUME, it) }
                            },
                            label = { Tip(stringResource(Res.string.tip_sp_max_m3)) { Text(stringResource(Res.string.max_m3_split)) } },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tip(stringResource(Res.string.tip_sp_algorithm)) {
                            Text(stringResource(Res.string.algorithm), style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR)
                        }
                        SplitAlgorithm.entries.forEach { alg ->
                            val label = stringResource(if (alg == SplitAlgorithm.FILL_FIRST) Res.string.fill_first else Res.string.balanced)
                            Segment(if (recommended == alg) "$label ★" else label, algorithm == alg) {
                                algorithm = alg
                                scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(SplitterSettings.ALGORITHM, alg.name) }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tip(stringResource(Res.string.ship_label)) {
                            Text(stringResource(Res.string.ship_short), style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR)
                        }
                        ShipDropdown(
                            selectedTypeId = shipTypeId,
                            onSelect = {
                                shipTypeId = it
                                scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(SplitterSettings.SHIP_TYPE_ID, it.toString()) }
                            },
                        )
                    }
                    ActionButton(
                        stringResource(Res.string.calculate),
                        enabled = pasteText.isNotBlank(),
                        busy = isCalculating,
                    ) { calculate() }
                }
            }
        }

        val plan = activePlan
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plan == null) {
                Panel(stringResource(Res.string.results), Modifier.fillMaxSize()) { Hint(stringResource(Res.string.splitter_hint)) }
                return@Column
            }
            val maxIsk = maxIskText.toDoubleOrNull() ?: 0.0
            val maxVolume = maxVolumeText.toDoubleOrNull() ?: 0.0
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.dp, outline(), MaterialTheme.shapes.extraSmall)) {
                Kpi(stringResource(Res.string.kpi_cargo_value), formatIsk(plan.splits.sumOf { it.totalValue }), null, null, null)
                Kpi(stringResource(Res.string.kpi_cargo_volume), formatM3(plan.splits.sumOf { it.totalVolume }), null, null, null)
                Kpi(
                    stringResource(Res.string.kpi_splits),
                    plan.splits.size.toString(),
                    stringResource(Res.string.plans_summary, ffdPlan?.splits?.size ?: 0, balancedPlan?.splits?.size ?: 0),
                    null,
                    null,
                )
                Kpi(
                    stringResource(Res.string.kpi_unplaced),
                    plan.unplaced.size.toString(),
                    null,
                    null,
                    null,
                    valueColor = MaterialTheme.colorScheme.error.takeIf { plan.unplaced.isNotEmpty() },
                    last = true,
                )
            }
            if (warnings.isNotEmpty() || plan.unplaced.isNotEmpty()) {
                Panel(
                    stringResource(Res.string.warnings_n, warnings.size + plan.unplaced.size),
                    Modifier.fillMaxWidth().heightIn(max = 140.dp),
                ) {
                    LazyColumn {
                        items(plan.unplaced) { u ->
                            StatusLine("${u.name} × ${u.quantityRemaining}: ${u.reason}", MaterialTheme.colorScheme.error)
                        }
                        items(warnings) { w -> StatusLine("${w.lineText}: ${w.reason}", warningColor) }
                    }
                }
            }
            Panel(
                stringResource(Res.string.results),
                Modifier.fillMaxWidth().weight(1f),
                header = {
                    Spacer(Modifier.weight(1f))
                    if (charId == null) {
                        Text(stringResource(Res.string.select_char_push), style = MaterialTheme.typography.labelSmall, color = AXIS_COLOR)
                    } else if (plan.splits.isNotEmpty()) {
                        Segment(stringResource(Res.string.push_all_n, plan.splits.size), true) { if (!isPushing) confirmingPushAll = true }
                    }
                },
            ) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(plan.splits, key = { it.index }) { split ->
                        SplitCard(split, maxIsk, maxVolume, pushResults[split.index], canPush = charId != null && !isPushing) {
                            confirmingSplit = split
                        }
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

// One split: value and volume against the limits as fill bars, then its items.
@Composable
internal fun SplitCard(
    split: Split,
    maxIsk: Double,
    maxVolume: Double,
    push: FittingPushResult?,
    canPush: Boolean,
    onPush: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().border(1.dp, outline(), MaterialTheme.shapes.extraSmall).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.split_n, split.index), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            FillBar(formatIsk(split.totalValue) + " ISK", split.totalValue, maxIsk, Modifier.weight(1f))
            FillBar(formatM3(split.totalVolume), split.totalVolume, maxVolume, Modifier.weight(1f))
            Text(
                pluralStringResource(Res.plurals.item_types_n, split.itemTypeCount, split.itemTypeCount),
                style = MaterialTheme.typography.labelSmall,
                color = AXIS_COLOR,
            )
            when {
                push?.success == true -> {
                    StatusLine(stringResource(Res.string.pushed), positiveColor)
                }

                push?.success == false -> {
                    Tip(stringResource(Res.string.push_failed, push.error.orEmpty())) {
                        StatusLine(stringResource(Res.string.push_failed_short), MaterialTheme.colorScheme.error)
                    }
                }

                canPush -> {
                    Segment(stringResource(Res.string.push_to_esi), false, onPush)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        split.items.forEach { li ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TypeIcon(li.typeId, size = 16.dp)
                Text(
                    li.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "× ${formatVolume(li.quantity.toLong())}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = Mono,
                    modifier = Modifier.width(90.dp),
                    textAlign = TextAlign.End,
                )
                Text(
                    formatIsk(li.quantity * li.unitPrice),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = Mono,
                    color = AXIS_COLOR,
                    modifier = Modifier.width(90.dp),
                    textAlign = TextAlign.End,
                )
                Text(
                    formatM3(li.quantity * li.unitVolume),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = Mono,
                    color = AXIS_COLOR,
                    modifier = Modifier.width(110.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

// How full a split is against one limit; amber past 95%.
@Composable
private fun FillBar(
    label: String,
    value: Double,
    limit: Double,
    modifier: Modifier,
) {
    val f = if (limit > 0) (value / limit).toFloat().coerceIn(0f, 1f) else 0f
    val color = if (f > 0.95f) warningColor else MaterialTheme.colorScheme.primary
    Column(modifier) {
        Row {
            Text(label, style = MaterialTheme.typography.labelSmall, fontFamily = Mono, modifier = Modifier.weight(1f))
            Text("${(f * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = AXIS_COLOR)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(outline())) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(color))
        }
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
        Segment("$selectedName ▾", false) { expanded = true }
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
