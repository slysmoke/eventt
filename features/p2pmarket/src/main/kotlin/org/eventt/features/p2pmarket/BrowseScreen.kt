package org.eventt.features.p2pmarket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.NostrOrderModel
import org.eventt.core.database.StaticDataDao
import org.eventt.core.nostr.OrderFilter
import org.eventt.core.nostr.OrderRepository
import org.eventt.core.nostr.OrderSide
import org.eventt.core.nostr.ReputationAggregator
import org.eventt.core.nostr.ReservationService
import java.util.Locale
import kotlin.math.abs

private val PositiveColor = Color(0xFF69DB7C)

@Composable
fun BrowseScreen() {
    var sideFilter by remember { mutableStateOf<OrderSide?>(null) }
    var orders by remember { mutableStateOf<List<NostrOrderModel>>(emptyList()) }

    LaunchedEffect(sideFilter) {
        OrderRepository.browse(OrderFilter(side = sideFilter)).collect { orders = it }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sideFilter == null, onClick = { sideFilter = null }, label = { Text("All") })
            FilterChip(selected = sideFilter == OrderSide.SELL, onClick = { sideFilter = OrderSide.SELL }, label = { Text("Selling") })
            FilterChip(selected = sideFilter == OrderSide.BUY, onClick = { sideFilter = OrderSide.BUY }, label = { Text("Buying") })
        }
        Spacer(Modifier.height(12.dp))
        if (orders.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No orders yet — post one (once identity/relays are set up) or wait for others to appear",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(orders, key = { "${it.orderUuid}:${it.pubkey}" }) { order -> OrderRow(order) }
            }
        }
    }
}

@Composable
private fun OrderRow(order: NostrOrderModel) {
    val scope = rememberCoroutineScope()
    var typeName by remember(order.typeId) { mutableStateOf("Type #${order.typeId}") }
    var regionName by remember(order.regionId) { mutableStateOf("Region #${order.regionId}") }
    var savings by remember(order.orderUuid, order.pubkey, order.price) { mutableStateOf<SavingsResult?>(null) }
    var showRequestDialog by remember { mutableStateOf(false) }
    var requestSent by remember(order.orderUuid, order.pubkey) { mutableStateOf(false) }
    var confirmedTrades by remember(order.pubkey) { mutableStateOf(0) }
    val side = remember(order.side) { OrderSide.valueOf(order.side.uppercase()) }

    LaunchedEffect(order.typeId) {
        withContext(Dispatchers.IO) { StaticDataDao.getTypeById(order.typeId)?.name }?.let { typeName = it }
    }
    LaunchedEffect(order.regionId) {
        withContext(Dispatchers.IO) { StaticDataDao.getRegionById(order.regionId)?.name }?.let { regionName = it }
    }
    LaunchedEffect(order.orderUuid, order.pubkey, order.price) {
        savings = withContext(Dispatchers.IO) { SavingsBadgeService.computeSavings(order.typeId, order.regionId, side, order.price) }
    }
    LaunchedEffect(order.pubkey) {
        confirmedTrades = ReputationAggregator.confirmedTradeCount(order.pubkey)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (side == OrderSide.SELL) Icons.Default.Sell else Icons.Default.ShoppingCart,
                null,
                tint = if (side == OrderSide.SELL) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(typeName, style = MaterialTheme.typography.bodyMedium)
                Text(regionName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(String.format(Locale.US, "%,.2f ISK", order.price), style = MaterialTheme.typography.bodyMedium)
            Text("${order.qtyRemaining}/${order.qtyTotal}", style = MaterialTheme.typography.bodySmall)
            savings?.let { s ->
                Text(
                    "${if (s.savingsPct >= 0) "-" else "+"}${String.format(Locale.US, "%.1f", abs(s.savingsPct))}% vs market",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (s.savingsPct >= 0) PositiveColor else MaterialTheme.colorScheme.error,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    order.traderChar.ifBlank { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (confirmedTrades > 0) {
                    Text(
                        "$confirmedTrades confirmed trade${if (confirmedTrades == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (!order.isMine) {
                OutlinedButton(onClick = { showRequestDialog = true }, enabled = !requestSent) {
                    Text(if (requestSent) "Sent" else "Request")
                }
            }
        }
    }

    if (showRequestDialog) {
        RequestReservationDialog(
            order = order,
            onDismiss = { showRequestDialog = false },
            onSend = { qty, note ->
                scope.launch(Dispatchers.IO) {
                    val tradeId = ReservationService.sendRequest(order, qty, note)
                    if (tradeId != null) requestSent = true
                }
                showRequestDialog = false
            },
        )
    }
}

@Composable
private fun RequestReservationDialog(
    order: NostrOrderModel,
    onDismiss: () -> Unit,
    onSend: (qty: Long, note: String) -> Unit,
) {
    var qtyText by remember { mutableStateOf(order.minLot.toString()) }
    var note by remember { mutableStateOf("") }
    val qty = qtyText.toLongOrNull()
    val valid = qty != null && qty in order.minLot..order.qtyRemaining

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request reservation") },
        text = {
            Column {
                Text(
                    "Min lot ${order.minLot} · ${order.qtyRemaining} remaining",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = qtyText, onValueChange = { qtyText = it }, label = { Text("Quantity") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { qty?.let { onSend(it, note) } }, enabled = valid) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
