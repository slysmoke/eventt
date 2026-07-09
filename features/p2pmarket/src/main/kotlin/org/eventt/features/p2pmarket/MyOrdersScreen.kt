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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.NostrOrderModel
import org.eventt.core.database.NostrReservationDao
import org.eventt.core.database.NostrReservationModel
import org.eventt.core.database.StaticDataDao
import org.eventt.core.model.StaticRegionModel
import org.eventt.core.nostr.MinLotUnit
import org.eventt.core.nostr.NostrRelayEvent
import org.eventt.core.nostr.NostrRelayManager
import org.eventt.core.nostr.OrderDraft
import org.eventt.core.nostr.OrderFilter
import org.eventt.core.nostr.OrderRepository
import org.eventt.core.nostr.OrderSide
import org.eventt.core.nostr.ReservationService
import java.util.Locale

@Composable
fun MyOrdersScreen() {
    val scope = rememberCoroutineScope()

    var allRegions by remember { mutableStateOf<List<StaticRegionModel>>(emptyList()) }
    var myOrders by remember { mutableStateOf<List<NostrOrderModel>>(emptyList()) }
    var incomingRequests by remember { mutableStateOf<List<NostrReservationModel>>(emptyList()) }
    var acceptedReservations by remember { mutableStateOf<List<NostrReservationModel>>(emptyList()) }

    var side by remember { mutableStateOf(OrderSide.SELL) }
    var itemName by remember { mutableStateOf("") }
    var regionId by remember { mutableStateOf<Int?>(null) }
    var priceText by remember { mutableStateOf("") }
    var qtyText by remember { mutableStateOf("") }
    var minLotText by remember { mutableStateOf("1") }
    var minLotUnit by remember { mutableStateOf(MinLotUnit.UNITS) }
    var traderChar by remember { mutableStateOf("") }
    var isPosting by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        allRegions = withContext(Dispatchers.IO) { StaticDataDao.getAllRegions() }
    }
    LaunchedEffect(Unit) {
        OrderRepository.browse(OrderFilter()).collect { orders ->
            myOrders = orders.filter { it.isMine }
        }
    }

    suspend fun reloadReservations() {
        val all = withContext(Dispatchers.IO) { NostrReservationDao.listForRole("seller") }
        incomingRequests = all.filter { it.status == "sent" }
        acceptedReservations = all.filter { it.status == "accepted" }
    }
    LaunchedEffect(Unit) {
        reloadReservations()
        NostrRelayManager.events.collect { event ->
            if (event is NostrRelayEvent.ReservationActivity) reloadReservations()
        }
    }

    fun submit() {
        val type = itemName.trim()
        val region = regionId
        val price = priceText.toDoubleOrNull()
        val qty = qtyText.toLongOrNull()
        val minLot = minLotText.toLongOrNull()

        formError =
            when {
                type.isEmpty() -> "Item name required"
                region == null -> "Pick a region"
                price == null || price <= 0 -> "Invalid price"
                qty == null || qty <= 0 -> "Invalid quantity"
                minLot == null || minLot <= 0 -> "Invalid min lot"
                else -> null
            }
        if (formError != null) return

        isPosting = true
        scope.launch(Dispatchers.IO) {
            val resolvedType = StaticDataDao.getTypeByExactName(type)
            if (resolvedType == null) {
                withContext(Dispatchers.Main) {
                    formError = "Unknown item name: $type"
                    isPosting = false
                }
                return@launch
            }
            val posted =
                OrderRepository.postNewOrder(
                    OrderDraft(
                        side = side,
                        typeId = resolvedType.typeId,
                        regionId = region!!,
                        price = price!!,
                        qtyTotal = qty!!,
                        minLot = minLot!!,
                        minLotUnit = minLotUnit,
                        traderChar = traderChar.trim(),
                    ),
                )
            withContext(Dispatchers.Main) {
                isPosting = false
                if (posted == null) {
                    formError = "No P2P Market identity set up yet — add one in Settings first."
                } else {
                    itemName = ""
                    priceText = ""
                    qtyText = ""
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Post a new order", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = side == OrderSide.SELL, onClick = { side = OrderSide.SELL }, label = { Text("Selling") })
                    FilterChip(selected = side == OrderSide.BUY, onClick = { side = OrderSide.BUY }, label = { Text("Buying") })
                }
                OutlinedTextField(
                    value = itemName,
                    onValueChange = { itemName = it },
                    label = { Text("Item name (exact)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RegionDropdown(allRegions, regionId) { regionId = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = { Text("Price / unit (ISK)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { qtyText = it },
                        label = { Text("Total quantity") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = minLotText,
                        onValueChange = { minLotText = it },
                        label = { Text("Min lot") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(selected = minLotUnit == MinLotUnit.UNITS, onClick = { minLotUnit = MinLotUnit.UNITS }, label = { Text("units") })
                    FilterChip(selected = minLotUnit == MinLotUnit.ISK, onClick = { minLotUnit = MinLotUnit.ISK }, label = { Text("ISK") })
                }
                OutlinedTextField(
                    value = traderChar,
                    onValueChange = { traderChar = it },
                    label = { Text("Your character name (shown to buyers, not verified)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                formError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { submit() }, enabled = !isPosting) {
                    if (isPosting) {
                        CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(if (isPosting) "Posting…" else "Post order")
                }
            }
        }

        if (incomingRequests.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Incoming requests", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                incomingRequests.forEach { reservation ->
                    ReservationRow(reservation) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    ReservationService.respond(reservation, accept = true)
                                    reloadReservations()
                                }
                            }) { Text("Accept") }
                            OutlinedButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    ReservationService.respond(reservation, accept = false)
                                    reloadReservations()
                                }
                            }) { Text("Decline") }
                        }
                    }
                }
            }
        }

        if (acceptedReservations.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Accepted — awaiting completion", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                acceptedReservations.forEach { reservation ->
                    ReservationRow(reservation) {
                        OutlinedButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                ReservationService.release(reservation)
                                reloadReservations()
                            }
                        }) { Text("Release (buyer no-show)") }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("My active orders", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (myOrders.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No active orders yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.height((myOrders.size * 96).coerceAtMost(600).dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(myOrders, key = { "${it.orderUuid}:${it.pubkey}" }) { order ->
                    MyOrderRow(order, onRenew = { scope.launch(Dispatchers.IO) { OrderRepository.renewOrder(order) } }, onCancel = {
                        scope.launch(Dispatchers.IO) { OrderRepository.cancelOrder(order) }
                    })
                }
            }
        }
    }
}

@Composable
private fun ReservationRow(
    reservation: NostrReservationModel,
    actions: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Qty ${reservation.qty} · order ${reservation.orderUuid.take(8)}…", style = MaterialTheme.typography.bodyMedium)
            if (reservation.note.isNotBlank()) {
                Text(reservation.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            reservation.holdUntil?.let {
                Text(
                    "Held until ${java.time.Instant.ofEpochSecond(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            actions()
        }
    }
}

@Composable
private fun MyOrderRow(
    order: NostrOrderModel,
    onRenew: () -> Unit,
    onCancel: () -> Unit,
) {
    var typeName by remember(order.typeId) { mutableStateOf("Type #${order.typeId}") }
    LaunchedEffect(order.typeId) {
        withContext(Dispatchers.IO) { StaticDataDao.getTypeById(order.typeId)?.name }?.let { typeName = it }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${order.side.uppercase()} — $typeName", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${String.format(Locale.US, "%,.2f", order.price)} ISK · ${order.qtyRemaining}/${order.qtyTotal} remaining",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRenew) { Text("Renew") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun RegionDropdown(
    regions: List<StaticRegionModel>,
    selectedRegionId: Int?,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = regions.find { it.regionId == selectedRegionId }?.name ?: "Select region…"

    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selectedName) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            regions.forEach { region ->
                DropdownMenuItem(
                    text = { Text(region.name) },
                    onClick = {
                        onSelect(region.regionId)
                        expanded = false
                    },
                )
            }
        }
    }
}
