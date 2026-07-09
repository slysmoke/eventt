package org.eventt.features.tools.pricing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.ViewContext
import org.eventt.features.tools.ParseWarning
import org.eventt.features.tools.ToolsInputParser
import org.eventt.ui.common.ContentCard
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

@Composable
fun PricingScreen(context: ViewContext?) {
    val scope = rememberCoroutineScope()
    val characterId = (context as? ViewContext.Character)?.charId
    val corporationId = (context as? ViewContext.Corporation)?.corporationId
    val actingCharId = context?.actingCharId

    var pasteText by remember { mutableStateOf("") }
    var marginText by remember { mutableStateOf("30") }
    var marginLimitEnabled by remember { mutableStateOf(true) }

    var isCalculating by remember { mutableStateOf(false) }
    var warnings by remember { mutableStateOf<List<ParseWarning>>(emptyList()) }
    var pricingWarnings by remember { mutableStateOf<List<PricingWarning>>(emptyList()) }
    var results by remember { mutableStateOf<List<PricingResult>>(emptyList()) }

    fun calculate() {
        val margin = marginText.toDoubleOrNull() ?: 0.0
        isCalculating = true
        scope.launch(Dispatchers.IO) {
            val (parsed, parseWarnings) = ToolsInputParser.parse(pasteText)
            val (resolved, resolveWarnings) = ToolsInputParser.resolve(parsed) { it.volume }
            val (pricingResults, priceWarnings) =
                PricingService.computePrices(
                    items = resolved,
                    characterId = characterId,
                    corporationId = corporationId,
                    actingCharId = actingCharId,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = marginLimitEnabled, onCheckedChange = { marginLimitEnabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Apply margin limit (off = always just undercut the current market)")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = marginText,
                    onValueChange = { marginText = it },
                    label = { Text("Margin %") },
                    singleLine = true,
                    enabled = marginLimitEnabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(0.4f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "The market's current lowest sell order is always undercut by one price tick when it's cheaper " +
                        "than your margin target — market region is read from your character's current location.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { calculate() }, enabled = !isCalculating && pasteText.isNotBlank() && actingCharId != null) {
                    if (isCalculating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Calculate")
                }
                if (actingCharId == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Select a character to determine your current market region.",
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
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        results.forEach { r ->
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(r.name, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                                Text("${r.quantity}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    r.costBasis?.let { "%.2f".format(it) } ?: "—",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    r.targetPrice?.let { "%.2f".format(it) } ?: "—",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    r.marketLowestSell?.let { "%.2f".format(it) } ?: "—",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        r.finalPrice?.let { "%.2f".format(it) } ?: "—",
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
