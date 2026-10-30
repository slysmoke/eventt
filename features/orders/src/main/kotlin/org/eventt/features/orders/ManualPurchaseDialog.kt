package org.eventt.features.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.MANUAL_LOCATION_NAME
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.WalletDao
import org.eventt.core.database.syntheticTransactionId
import org.eventt.core.model.StaticTypeModel
import org.eventt.orders.generated.resources.*
import org.eventt.ui.common.formatIsk
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.util.UUID

/** "1 234 567,89" / "1,234,567.89" / "1234567" → ISK; null when it isn't a positive number. */
internal fun parseIskInput(text: String): Double? {
    val t = text.filterNot { it.isWhitespace() || it == ' ' }
    // A comma is a decimal separator only when it's the sole separator and has ≤2 digits after it.
    val normalized =
        if ('.' !in t && t.count { it == ',' } == 1 && t.substringAfter(',').length <= 2) t.replace(',', '.') else t.replace(",", "")
    return normalized.toDoubleOrNull()?.takeIf { it > 0 }
}

/**
 * Books a purchase made outside the market (NES for PLEX, a reward, a gift) as a synthetic buy
 * transaction, so FIFO has a cost basis for it. Negative id = no broker fee in FIFO, same as P2P.
 * Dated at midnight so a sale later that day still matches it.
 */
internal fun bookManualPurchase(
    type: StaticTypeModel,
    quantity: Int,
    unitPrice: Double,
    date: LocalDate,
    characterId: Int?,
    corporationId: Int?,
) {
    WalletDao.insertTransaction(
        transactionId = syntheticTransactionId("manual", UUID.randomUUID()),
        date = "${date}T00:00:00",
        typeId = type.typeId,
        typeName = type.name,
        quantity = quantity,
        unitPrice = unitPrice,
        total = unitPrice * quantity,
        isBuy = true,
        clientId = 0,
        clientName = "",
        locationId = 0,
        locationName = MANUAL_LOCATION_NAME,
        isCorp = corporationId != null,
        characterId = characterId,
        corporationId = corporationId,
    )
}

@Composable
internal fun ManualPurchaseDialog(
    characterId: Int?,
    corporationId: Int?,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<StaticTypeModel?>(null) }
    var results by remember { mutableStateOf<List<StaticTypeModel>>(emptyList()) }
    var qtyText by remember { mutableStateOf("1") }
    var priceText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(LocalDate.now().toString()) }
    var existing by remember { mutableStateOf<List<WalletDao.ManualPurchase>>(emptyList()) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val loaded = WalletDao.getManualPurchases(characterId, corporationId)
            withContext(Dispatchers.Main) { existing = loaded }
        }
    }
    LaunchedEffect(characterId, corporationId) { reload() }

    LaunchedEffect(query, selected) {
        if (selected != null || query.trim().length < 3) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        results =
            withContext(
                Dispatchers.IO,
            ) { runCatching { StaticDataDao.searchMarketTypes(query.trim(), limit = 8) }.getOrDefault(emptyList()) }
    }

    val qty = qtyText.trim().toIntOrNull()?.takeIf { it > 0 }
    val price = parseIskInput(priceText)
    val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()?.takeIf { !it.isAfter(LocalDate.now()) }
    val type = selected
    val valid = type != null && qty != null && price != null && date != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.add_purchase_title)) },
        text = {
            Column(Modifier.widthIn(min = 420.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(Res.string.add_purchase_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = type?.name ?: query,
                    onValueChange = {
                        selected = null
                        query = it
                    },
                    label = { Text(stringResource(Res.string.item)) },
                    isError = type == null && query.isNotBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                results.forEach { t ->
                    Text(
                        t.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = t
                                    query = t.name
                                }.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { qtyText = it },
                        label = { Text(stringResource(Res.string.quantity)) },
                        isError = qty == null,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = { Text(stringResource(Res.string.unit_price_isk)) },
                        isError = priceText.isNotBlank() && price == null,
                        singleLine = true,
                        modifier = Modifier.weight(1.5f),
                    )
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text(stringResource(Res.string.date_ymd)) },
                        isError = date == null,
                        singleLine = true,
                        modifier = Modifier.weight(1.3f),
                    )
                }
                if (qty != null && price != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(Res.string.total_isk, formatIsk(qty * price)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (existing.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Text(
                        stringResource(Res.string.manual_purchases),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    existing.forEach { p ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${p.date.take(10)}  ${p.typeName} × ${p.quantity} @ ${formatIsk(p.unitPrice)}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    WalletDao.deleteManualPurchase(p.transactionId)
                                    withContext(Dispatchers.Main) {
                                        reload()
                                        onChanged()
                                    }
                                }
                            }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(Res.string.delete),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        bookManualPurchase(type!!, qty!!, price!!, date!!, characterId, corporationId)
                        withContext(Dispatchers.Main) {
                            onChanged()
                            onDismiss()
                        }
                    }
                },
            ) { Text(stringResource(Res.string.add_purchase)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}
