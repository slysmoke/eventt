package org.eventt.features.tools.pricing

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.CorporationDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.ViewContext
import org.eventt.core.model.CharacterModel
import org.eventt.core.model.stringBlocking
import org.eventt.features.tools.ParseWarning
import org.eventt.features.tools.PasteBox
import org.eventt.features.tools.PricingTotals
import org.eventt.features.tools.ToolsInputParser
import org.eventt.features.tools.pricingTotals
import org.eventt.tools.generated.resources.*
import org.eventt.ui.common.*
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.stringResource
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.Locale

// Comma-grouped, fixed 2-decimal ISK amount — unlike the K/M/B abbreviations used for rough
// totals elsewhere in the app, per-unit sell-order prices need full precision to be usable as-is.

// Persisted across restarts — the margin target and whether it's enforced are per-user setup.
private object PricingSettings {
    const val MARGIN_PCT = "tools.pricing.marginPct"
    const val MARGIN_LIMIT_ENABLED = "tools.pricing.marginLimitEnabled"
    const val SOURCE_TYPE = "tools.pricing.sourceType"
    const val SOURCE_ID = "tools.pricing.sourceId"
    const val ACTING_CHAR_ID = "tools.pricing.actingCharId"
}

// Who to pull purchase-price history from for the cost-basis calculation — independent of the
// currently active character/corp view, since who bought the stock isn't necessarily whoever
// you're currently looking at (e.g. checking corp margins while viewing your own character).
private sealed class CostBasisSource {
    data class Character(
        val charId: Int,
        val name: String,
    ) : CostBasisSource()

    data class Corporation(
        val corpId: Int,
        val name: String,
    ) : CostBasisSource()

    val label: String
        get() =
            when (this) {
                is Character -> name
                is Corporation -> stringBlocking(Res.string.corp_wide, name)
            }
}

@Composable
fun PricingScreen(context: ViewContext?) {
    val scope = rememberCoroutineScope()
    // The global left-nav selection — only used as a default; the acting character actually used
    // for fees/region/station is [pricingActingCharId] below, which can diverge from it (e.g. when
    // pricing a corp's stock, you may want to act as a different corp-mate than the one currently
    // selected app-wide).
    val globalActingCharId = context?.actingCharId

    var pasteText by remember { mutableStateOf("") }
    var marginText by remember { mutableStateOf("30") }
    var marginLimitEnabled by remember { mutableStateOf(true) }

    var salesTaxPct by remember { mutableStateOf(8.0) }
    var brokerFeePct by remember { mutableStateOf(3.0) }
    var stationInfo by remember { mutableStateOf<ActingLocation?>(null) }

    var allCharacters by remember { mutableStateOf<List<CharacterModel>>(emptyList()) }
    var allCorporations by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var costBasisSource by remember { mutableStateOf<CostBasisSource?>(null) }
    // Who to use for fees/region/station — locked to the cost-basis character when that's a single
    // character (no ambiguity), or freely pickable among the corp's locally-known members when the
    // cost-basis source is a whole corporation, via the dropdown below. Falls back to the app-wide
    // selection until a cost-basis source is chosen.
    var pricingActingCharId by remember { mutableStateOf(context?.actingCharId) }

    fun persistCostBasisSource(source: CostBasisSource) {
        scope.launch(Dispatchers.IO) {
            when (source) {
                is CostBasisSource.Character -> {
                    StaticDataDao.setSetting(PricingSettings.SOURCE_TYPE, "character")
                    StaticDataDao.setSetting(PricingSettings.SOURCE_ID, source.charId.toString())
                }

                is CostBasisSource.Corporation -> {
                    StaticDataDao.setSetting(PricingSettings.SOURCE_TYPE, "corporation")
                    StaticDataDao.setSetting(PricingSettings.SOURCE_ID, source.corpId.toString())
                }
            }
        }
    }

    fun persistActingChar(id: Int?) {
        scope.launch(Dispatchers.IO) {
            StaticDataDao.setSetting(PricingSettings.ACTING_CHAR_ID, id?.toString() ?: "")
        }
    }

    // Default acting character for a freshly picked cost-basis source: the character itself when
    // it's a single character, otherwise the app-wide selection if it happens to be a member of
    // that corp, otherwise just the first locally-known member.
    fun defaultActingCharFor(
        source: CostBasisSource,
        characters: List<CharacterModel>,
    ): Int? =
        when (source) {
            is CostBasisSource.Character -> {
                source.charId
            }

            is CostBasisSource.Corporation -> {
                val members = characters.filter { it.corporationId == source.corpId }
                globalActingCharId?.takeIf { id -> members.any { it.id == id } } ?: members.firstOrNull()?.id
            }
        }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            StaticDataDao.getSetting(PricingSettings.MARGIN_PCT)?.let { marginText = it }
            StaticDataDao.getSetting(PricingSettings.MARGIN_LIMIT_ENABLED)?.let { marginLimitEnabled = it == "true" }

            val characters = CharacterDao.getAll()
            val corporations =
                CorporationDao.getAll().mapNotNull { m ->
                    (m["id"] as? Int)?.let { id -> id to (m["name"] as? String ?: "") }
                }
            allCharacters = characters
            allCorporations = corporations

            val savedType = StaticDataDao.getSetting(PricingSettings.SOURCE_TYPE)
            val savedId = StaticDataDao.getSetting(PricingSettings.SOURCE_ID)?.toIntOrNull()
            val restored =
                when (savedType) {
                    "character" -> characters.find { it.id == savedId }?.let { CostBasisSource.Character(it.id, it.name) }
                    "corporation" -> corporations.find { it.first == savedId }?.let { CostBasisSource.Corporation(it.first, it.second) }
                    else -> null
                }
            val source =
                restored ?: when (val ctx = context) {
                    is ViewContext.Character -> characters.find { it.id == ctx.charId }?.let { CostBasisSource.Character(it.id, it.name) }
                    is ViewContext.Corporation -> CostBasisSource.Corporation(ctx.corporationId, ctx.corporationName)
                    else -> null
                }
            costBasisSource = source

            val savedActingId = StaticDataDao.getSetting(PricingSettings.ACTING_CHAR_ID)?.toIntOrNull()
            pricingActingCharId =
                source?.let { s ->
                    when (s) {
                        is CostBasisSource.Character -> {
                            s.charId
                        }

                        is CostBasisSource.Corporation -> {
                            val members = characters.filter { it.corporationId == s.corpId }
                            savedActingId?.takeIf { id -> members.any { it.id == id } } ?: defaultActingCharFor(s, characters)
                        }
                    }
                } ?: context?.actingCharId
        }
    }

    // Before any cost-basis source is chosen, keep following the app-wide selection — once a
    // source is picked, [pricingActingCharId] becomes independently managed (see onSelect below).
    LaunchedEffect(globalActingCharId) {
        if (costBasisSource == null) pricingActingCharId = globalActingCharId
    }

    LaunchedEffect(pricingActingCharId) {
        val cid =
            pricingActingCharId ?: run {
                stationInfo = null
                return@LaunchedEffect
            }
        withContext(Dispatchers.IO) {
            salesTaxPct = StaticDataDao.getCharSalesTax(cid)
            brokerFeePct = StaticDataDao.getCharBrokersFee(cid)
            stationInfo = PricingService.resolveActingLocation(cid)
        }
    }

    var isCalculating by remember { mutableStateOf(false) }
    var warnings by remember { mutableStateOf<List<ParseWarning>>(emptyList()) }
    var pricingWarnings by remember { mutableStateOf<List<PricingWarning>>(emptyList()) }
    var results by remember { mutableStateOf<List<PricingResult>>(emptyList()) }

    fun calculate() {
        val margin = marginText.toDoubleOrNull() ?: 0.0
        val source = costBasisSource
        isCalculating = true
        scope.launch(Dispatchers.IO) {
            val (parsed, parseWarnings) = ToolsInputParser.parse(pasteText)
            val (resolved, resolveWarnings) = ToolsInputParser.resolve(parsed) { it.volume }
            val (pricingResults, priceWarnings) =
                PricingService.computePrices(
                    items = resolved,
                    characterId = (source as? CostBasisSource.Character)?.charId,
                    corporationId = (source as? CostBasisSource.Corporation)?.corpId,
                    actingCharId = pricingActingCharId,
                    marginPct = margin,
                    marginLimitEnabled = marginLimitEnabled,
                )
            withContext(Dispatchers.Main) {
                warnings = parseWarnings + resolveWarnings
                pricingWarnings = priceWarnings
                results = pricingResults
                isCalculating = false
            }
        }
    }

    val clipboardText = remember(results) { PricingService.formatForClipboard(results) }

    val canCalculate = pasteText.isNotBlank() && pricingActingCharId != null && (!marginLimitEnabled || costBasisSource != null)

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PasteBox(stringResource(Res.string.item_list), pasteText, Modifier.weight(1f)) { pasteText = it }
            Panel(stringResource(Res.string.pricing), Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Labeled(stringResource(Res.string.cost_basis_source)) {
                        CostBasisSourceDropdown(
                            characters = allCharacters,
                            corporations = allCorporations,
                            selected = costBasisSource,
                            onSelect = {
                                costBasisSource = it
                                persistCostBasisSource(it)
                                pricingActingCharId = defaultActingCharFor(it, allCharacters)
                                persistActingChar(pricingActingCharId)
                            },
                        )
                    }
                    val corpSource = costBasisSource as? CostBasisSource.Corporation
                    if (corpSource != null) {
                        Labeled(stringResource(Res.string.acting_character)) {
                            ActingCharacterDropdown(
                                members = allCharacters.filter { it.corporationId == corpSource.corpId },
                                selectedId = pricingActingCharId,
                                onSelect = {
                                    pricingActingCharId = it
                                    persistActingChar(it)
                                },
                            )
                        }
                    }
                    when {
                        pricingActingCharId == null -> {
                            StatusLine(stringResource(Res.string.select_char_station))
                        }

                        stationInfo != null -> {
                            StatusLine(
                                stringResource(Res.string.selling_from, stationInfo!!.locationName),
                                positiveColor,
                            )
                        }

                        else -> {
                            StatusLine(stringResource(Res.string.no_docked_station), warningColor)
                        }
                    }
                    StatusLine(stringResource(Res.string.fees_line, "%.2f".format(salesTaxPct), "%.2f".format(brokerFeePct)))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tip(stringResource(Res.string.apply_margin_limit)) {
                            Segment(stringResource(Res.string.margin_limit), marginLimitEnabled) {
                                marginLimitEnabled = !marginLimitEnabled
                                scope.launch(Dispatchers.IO) {
                                    StaticDataDao.setSetting(PricingSettings.MARGIN_LIMIT_ENABLED, marginLimitEnabled.toString())
                                }
                            }
                        }
                        OutlinedTextField(
                            value = marginText,
                            onValueChange = {
                                marginText = it
                                scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(PricingSettings.MARGIN_PCT, it) }
                            },
                            label = { Tip(stringResource(Res.string.tip_pr_margin_pct)) { Text(stringResource(Res.string.margin_pct)) } },
                            singleLine = true,
                            enabled = marginLimitEnabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                            modifier = Modifier.width(120.dp),
                        )
                    }
                    Text(stringResource(Res.string.undercut_note), style = MaterialTheme.typography.labelSmall, color = AXIS_COLOR)
                    Text(
                        stringResource(Res.string.grossed_up_note, "%.2f".format(salesTaxPct), "%.2f".format(brokerFeePct)),
                        style = MaterialTheme.typography.labelSmall,
                        color = AXIS_COLOR,
                    )
                    if (marginLimitEnabled && costBasisSource == null) StatusLine(stringResource(Res.string.pick_cost_basis), warningColor)
                    ActionButton(stringResource(Res.string.calculate), enabled = canCalculate, busy = isCalculating) { calculate() }
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (results.isEmpty() && warnings.isEmpty() && pricingWarnings.isEmpty()) {
                Panel(stringResource(Res.string.results), Modifier.fillMaxSize()) { Hint(stringResource(Res.string.pricing_hint)) }
                return@Column
            }
            PricingTotalsStrip(remember(results) { pricingTotals(results) })
            if (warnings.isNotEmpty() || pricingWarnings.isNotEmpty()) {
                Panel(
                    stringResource(Res.string.warnings_n, warnings.size + pricingWarnings.size),
                    Modifier.fillMaxWidth().heightIn(max = 140.dp),
                ) {
                    LazyColumn {
                        items(warnings) { w -> StatusLine("${w.lineText}: ${w.reason}", warningColor) }
                        items(pricingWarnings) { w -> StatusLine("${w.itemName}: ${w.reason}", warningColor) }
                    }
                }
            }
            Panel(stringResource(Res.string.results), Modifier.fillMaxWidth().weight(1f)) { ResultsTable(results) }
            Panel(
                stringResource(Res.string.copy_list),
                Modifier.fillMaxWidth().height(150.dp),
                header = {
                    Spacer(Modifier.weight(1f))
                    Segment(stringResource(Res.string.copy_to_clipboard), true) {
                        val sel = StringSelection(clipboardText)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                    }
                },
            ) {
                OutlinedTextField(
                    value = clipboardText,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxSize(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                )
            }
        }
    }
}

@Composable
private fun Labeled(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AXIS_COLOR)
        content()
    }
}

@Composable
internal fun PricingTotalsStrip(t: PricingTotals) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.dp, outline(), MaterialTheme.shapes.extraSmall)) {
        Kpi(stringResource(Res.string.kpi_items), t.items.toString(), null, null, null)
        Kpi(stringResource(Res.string.kpi_list_value), formatIsk(t.listValue), null, null, null, Res.string.tip_kpi_list_value)
        Kpi(stringResource(Res.string.col_cost), formatIsk(t.cost), null, null, null)
        Kpi(
            stringResource(Res.string.kpi_net_profit),
            signed(t.netProfit),
            t.marginPct?.let { String.format(Locale.US, "%+.1f%%", it) },
            pnlColor(t.netProfit),
            null,
            Res.string.tip_kpi_net_profit,
            valueColor = pnlColor(t.netProfit),
        )
        Kpi(stringResource(Res.string.kpi_undercut), t.undercut.toString(), null, null, null, Res.string.tip_kpi_undercut)
        Kpi(
            stringResource(Res.string.kpi_below_cost),
            t.belowCost.toString(),
            null,
            null,
            null,
            valueColor = MaterialTheme.colorScheme.error.takeIf { t.belowCost > 0 },
            last = true,
        )
    }
}

@Composable
internal fun ResultsTable(results: List<PricingResult>) {
    val cols =
        listOf<Col<PricingResult>>(
            Col(stringResource(Res.string.col_item), 2.4f, end = false) { it.name.lowercase() },
            Col(stringResource(Res.string.col_qty), 0.7f) { it.quantity },
            Col(stringResource(Res.string.col_cost), 1f, tip = stringResource(Res.string.tip_pr_cost)) { it.costBasis },
            Col(stringResource(Res.string.col_target), 1f, tip = stringResource(Res.string.tip_pr_target)) { it.targetPrice },
            Col(stringResource(Res.string.col_market_low), 1f, tip = stringResource(Res.string.tip_pr_market_low)) { it.marketLowestSell },
            Col(stringResource(Res.string.col_final), 1.1f, tip = stringResource(Res.string.tip_pr_final)) { it.finalPrice },
            Col(stringResource(Res.string.col_margin), 0.8f, tip = stringResource(Res.string.tip_pr_margin)) { it.actualMarginPct },
            Col(stringResource(Res.string.col_total), 0.9f) { (it.finalPrice ?: 0.0) * it.quantity },
        )
    // Alphabetical by default; any column re-sorts. The copy list below keeps the paste's order.
    Table(cols = cols, rows = results, empty = "", defaultSort = 0, defaultDesc = false, key = { "${it.typeId}:${it.name}" }) { r ->
        val margin = r.actualMarginPct
        listOf(
            Cell(r.name) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TypeIcon(r.typeId, size = 18.dp)
                    Text(r.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            Cell(formatVolume(r.quantity.toLong())),
            Cell(r.costBasis?.let(::formatPriceSimple) ?: "—", AXIS_COLOR),
            Cell(r.targetPrice?.let(::formatPriceSimple) ?: "—", AXIS_COLOR),
            Cell(r.marketLowestSell?.let(::formatPriceSimple) ?: "—", AXIS_COLOR),
            // ↓ = listed at the market undercut rather than the margin target.
            Cell(
                (r.finalPrice?.let(::formatPriceSimple) ?: "—") + if (r.usedMarketPrice && r.finalPrice != null) " ↓" else "",
                if (r.usedMarketPrice && r.finalPrice != null) MaterialTheme.colorScheme.tertiary else null,
            ),
            Cell(
                margin?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "—",
                when {
                    margin == null -> AXIS_COLOR
                    margin < 0 -> MaterialTheme.colorScheme.error
                    else -> positiveColor
                },
            ),
            Cell(r.finalPrice?.let { formatIsk(it * r.quantity) } ?: "—"),
        )
    }
}

// Restricted to one corp's locally-known members — picks who actually undocks and lists the
// order, since that determines the region/station/fees, separately from whose purchase history
// the cost basis is pooled from above.
@Composable
private fun ActingCharacterDropdown(
    members: List<CharacterModel>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = members.find { it.id == selectedId }?.name

    Box {
        Segment((selectedName ?: stringResource(Res.string.no_local_corp_members)) + " ▾", false) {
            if (members.isNotEmpty()) expanded = true
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            members.sortedBy { it.name }.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name) },
                    onClick = {
                        onSelect(c.id)
                        expanded = false
                    },
                    leadingIcon =
                        if (c.id == selectedId) {
                            { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@Composable
private fun CostBasisSourceDropdown(
    characters: List<CharacterModel>,
    corporations: List<Pair<Int, String>>,
    selected: CostBasisSource?,
    onSelect: (CostBasisSource) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Segment((selected?.label ?: stringResource(Res.string.select_char_or_corp)) + " ▾", selected != null) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (characters.isNotEmpty()) {
                Text(
                    stringResource(Res.string.characters),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                characters.sortedBy { it.name }.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c.name) },
                        onClick = {
                            onSelect(CostBasisSource.Character(c.id, c.name))
                            expanded = false
                        },
                        leadingIcon =
                            if (selected is CostBasisSource.Character && selected.charId == c.id) {
                                { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                            } else {
                                null
                            },
                    )
                }
            }
            if (corporations.isNotEmpty()) {
                if (characters.isNotEmpty()) HorizontalDivider()
                Text(
                    stringResource(Res.string.corporations_corp_wide),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                corporations.sortedBy { it.second }.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelect(CostBasisSource.Corporation(id, name))
                            expanded = false
                        },
                        leadingIcon =
                            if (selected is CostBasisSource.Corporation && selected.corpId == id) {
                                { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                            } else {
                                null
                            },
                    )
                }
            }
            if (characters.isEmpty() && corporations.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.no_chars_or_corps), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }
}
