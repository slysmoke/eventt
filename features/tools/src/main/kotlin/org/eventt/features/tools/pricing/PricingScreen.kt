package org.eventt.features.tools.pricing

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.CorporationDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.ViewContext
import org.eventt.core.model.CharacterModel
import org.eventt.features.tools.ParseWarning
import org.eventt.features.tools.ToolsInputParser
import org.eventt.ui.common.ContentCard
import org.eventt.ui.common.formatPriceSimple
import org.eventt.ui.theme.positiveColor
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
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
                is Corporation -> "$name (corp-wide)"
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ContentCard(title = "Item list") {
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    placeholder = { Text("Paste inventory list (name<TAB>quantity per line)…") },
                    minLines = 5,
                )
                Spacer(Modifier.height(8.dp))
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
                    Text("Paste from clipboard")
                }
            }
        }

        item {
            ContentCard(title = "Pricing") {
                Text("Cost basis source (who bought this stock)", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
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
                val corpSource = costBasisSource as? CostBasisSource.Corporation
                if (corpSource != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Acting character (determines station, region & fees)", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    ActingCharacterDropdown(
                        members = allCharacters.filter { it.corporationId == corpSource.corpId },
                        selectedId = pricingActingCharId,
                        onSelect = {
                            pricingActingCharId = it
                            persistActingChar(it)
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        pricingActingCharId == null -> "Select a character to determine your current station."
                        stationInfo != null -> "Selling from: ${stationInfo!!.locationName}"
                        else -> "Could not determine a docked station for this character — station-scoped pricing will be skipped."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = marginLimitEnabled,
                        onCheckedChange = {
                            marginLimitEnabled = it
                            scope.launch(Dispatchers.IO) {
                                StaticDataDao.setSetting(PricingSettings.MARGIN_LIMIT_ENABLED, it.toString())
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Apply margin limit (off = always list at the market undercut — cost/margin columns still shown for reference)")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = marginText,
                    onValueChange = {
                        marginText = it
                        scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(PricingSettings.MARGIN_PCT, it) }
                    },
                    label = { Text("Margin %") },
                    singleLine = true,
                    enabled = marginLimitEnabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(0.4f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "The market's current lowest sell order at your station is always undercut by one price tick " +
                        "when it's cheaper than your margin target — orders sitting at other stations in the region " +
                        "don't count.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Target price is grossed up for your character's fees (Tax ${"%.2f".format(salesTaxPct)}%  ·  " +
                        "Broker ${"%.2f".format(brokerFeePct)}%) so the margin above is what you actually net.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { calculate() },
                    enabled =
                        !isCalculating &&
                            pasteText.isNotBlank() &&
                            pricingActingCharId != null &&
                            (!marginLimitEnabled || costBasisSource != null),
                ) {
                    if (isCalculating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Calculate")
                }
                if (marginLimitEnabled && costBasisSource == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Pick a cost basis source above (or add a character in Characters) to compute a margin target.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (warnings.isNotEmpty() || pricingWarnings.isNotEmpty()) {
            item {
                ContentCard(title = "Warnings (${warnings.size + pricingWarnings.size})") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        warnings.forEach { w ->
                            Text(
                                "• ${w.lineText}: ${w.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        pricingWarnings.forEach { w ->
                            Text(
                                "• ${w.itemName}: ${w.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        if (results.isNotEmpty()) {
            item {
                ContentCard(title = "Results") {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Item",
                                modifier = Modifier.weight(2f),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Qty",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Cost",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Target",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Market low",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Final",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Margin",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        results.forEach { r ->
                            val interactionSource = remember(r) { MutableInteractionSource() }
                            val isHovered by interactionSource.collectIsHoveredAsState()
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .hoverable(interactionSource)
                                        .background(
                                            if (isHovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent,
                                        ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(r.name, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                                Text("${r.quantity}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    r.costBasis?.let { formatPriceSimple(it) } ?: "—",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    r.targetPrice?.let { formatPriceSimple(it) } ?: "—",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    r.marketLowestSell?.let { formatPriceSimple(it) } ?: "—",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        r.finalPrice?.let { formatPriceSimple(it) } ?: "—",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    if (r.usedMarketPrice && r.finalPrice != null) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            Icons.Default.ArrowDownward,
                                            "Undercut market price",
                                            modifier = Modifier.size(12.dp),
                                            tint = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                                val margin = r.actualMarginPct
                                Text(
                                    margin?.let { "%+.1f%%".format(Locale.US, it) } ?: "—",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color =
                                        when {
                                            margin == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                            margin < 0 -> MaterialTheme.colorScheme.error
                                            else -> positiveColor
                                        },
                                )
                            }
                        }
                    }
                }
            }

            item {
                ContentCard(
                    title = "Copy list",
                    actions = {
                        TextButton(onClick = {
                            val sel = StringSelection(clipboardText)
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                        }) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy to clipboard")
                        }
                    },
                ) {
                    OutlinedTextField(
                        value = clipboardText,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        minLines = 4,
                    )
                }
            }
        }
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
        OutlinedButton(onClick = { expanded = true }, enabled = members.isNotEmpty()) {
            Icon(Icons.Default.Person, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(selectedName ?: "No local corp members")
            Spacer(Modifier.width(4.dp))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(16.dp))
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
        OutlinedButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Person, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(selected?.label ?: "Select a character or corporation…")
            Spacer(Modifier.width(4.dp))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (characters.isNotEmpty()) {
                Text(
                    "Characters",
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
                    "Corporations (corp-wide)",
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
                    text = { Text("No characters or corporations added yet", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }
}
