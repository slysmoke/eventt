package org.eventt.features.p2pmarket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.NostrOrderDao
import org.eventt.core.database.NostrOrderModel
import org.eventt.core.database.NostrReservationDao
import org.eventt.core.database.NostrReservationModel
import org.eventt.core.database.StaticDataDao
import org.eventt.core.model.StaticRegionModel
import org.eventt.core.model.StaticTypeModel
import org.eventt.core.nostr.MinLotUnit
import org.eventt.core.nostr.NostrIdentityService
import org.eventt.core.nostr.NostrRelayEvent
import org.eventt.core.nostr.NostrRelayManager
import org.eventt.core.nostr.OrderDraft
import org.eventt.core.nostr.OrderFilter
import org.eventt.core.nostr.OrderRepository
import org.eventt.core.nostr.OrderSide
import org.eventt.core.nostr.PostOrderResult
import org.eventt.core.nostr.ReservationService
import org.eventt.ui.common.SearchField
import java.time.Instant
import java.util.Locale

private data class MyOrderRowData(
    val order: NostrOrderModel,
    val typeName: String,
)

private data class SellerReservationRowData(
    val reservation: NostrReservationModel,
    val typeName: String,
    val regionName: String?,
    val price: Double?,
)

private enum class MyOrdersSortColumn { PRICE, QTY, EXPIRY }

@Composable
fun MyOrdersScreen() {
    val scope = rememberCoroutineScope()

    var allRegions by remember { mutableStateOf<List<StaticRegionModel>>(emptyList()) }
    var myOrders by remember { mutableStateOf<List<NostrOrderModel>>(emptyList()) }
    var myOrderRows by remember { mutableStateOf<List<MyOrderRowData>>(emptyList()) }
    var myOrdersSortColumn by remember { mutableStateOf(MyOrdersSortColumn.EXPIRY) }
    var myOrdersSortDirection by remember { mutableStateOf(SortDirection.ASC) }
    var sellerReservations by remember { mutableStateOf<List<SellerReservationRowData>>(emptyList()) }

    var showPostForm by remember { mutableStateOf(false) }
    var side by remember { mutableStateOf(OrderSide.SELL) }
    var itemQuery by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf<StaticTypeModel?>(null) }
    var itemSearchResults by remember { mutableStateOf<List<StaticTypeModel>>(emptyList()) }
    var regionId by remember { mutableStateOf<Int?>(null) }
    var priceText by remember { mutableStateOf("") }
    var qtyText by remember { mutableStateOf("") }
    var minLotText by remember { mutableStateOf("1") }
    var minLotUnit by remember { mutableStateOf(MinLotUnit.UNITS) }
    var tradingAs by remember { mutableStateOf<String?>(null) }
    var isPosting by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        allRegions = withContext(Dispatchers.IO) { StaticDataDao.getAllRegions() }
    }
    LaunchedEffect(Unit) {
        OrderRepository.browse(OrderFilter()).collect { orders ->
            myOrders = orders.filter { it.isMine }
        }
    }
    LaunchedEffect(myOrders) {
        myOrderRows =
            withContext(Dispatchers.IO) {
                myOrders.map { order -> MyOrderRowData(order, StaticDataDao.getTypeById(order.typeId)?.name ?: "Type #${order.typeId}") }
            }
    }

    fun toggleMyOrdersSort(
        column: MyOrdersSortColumn,
        defaultDirection: SortDirection,
    ) {
        if (myOrdersSortColumn == column) {
            myOrdersSortDirection = if (myOrdersSortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
        } else {
            myOrdersSortColumn = column
            myOrdersSortDirection = defaultDirection
        }
    }
    // Refreshed every time this tab is (re)entered, so switching the active character in Settings
    // is reflected here without a manual reload.
    LaunchedEffect(Unit) {
        val identity = withContext(Dispatchers.IO) { NostrIdentityService.getActiveIdentity() }
        tradingAs =
            identity?.let { id ->
                id.characterId?.let { withContext(Dispatchers.IO) { CharacterDao.getById(it)?.name } } ?: id.label
            }
    }
    // Only pre-fills when empty — mirrors PriceAlertsScreen's convention so a manually-edited
    // price is never silently clobbered by a later item/region change.
    LaunchedEffect(selectedType, regionId, side) {
        val type = selectedType
        val region = regionId
        if (type != null && region != null && priceText.isEmpty()) {
            val recommended = withContext(Dispatchers.IO) { SavingsBadgeService.recommendedPrice(type.typeId, region, side) }
            recommended?.let { priceText = String.format(Locale.US, "%.2f", it) }
        }
    }

    suspend fun reloadReservations() {
        val all = withContext(Dispatchers.IO) { NostrReservationDao.listForRole("seller") }
        val relevant = all.filter { it.status == "sent" || it.status == "accepted" }
        sellerReservations =
            withContext(Dispatchers.IO) {
                relevant
                    .map { reservation ->
                        val order = NostrOrderDao.getByCoordinate(reservation.orderUuid, reservation.sellerPubkey)
                        SellerReservationRowData(
                            reservation = reservation,
                            typeName =
                                order?.let { StaticDataDao.getTypeById(it.typeId)?.name }
                                    ?: "Order ${reservation.orderUuid.take(8)}… (expired/purged)",
                            regionName = order?.let { StaticDataDao.getRegionById(it.regionId)?.name },
                            price = order?.price,
                        )
                    }.sortedWith(compareBy({ it.reservation.status != "sent" }, { -it.reservation.requestedAt }))
            }
    }
    LaunchedEffect(Unit) {
        reloadReservations()
        NostrRelayManager.events.collect { event ->
            if (event is NostrRelayEvent.ReservationActivity) reloadReservations()
        }
    }

    fun submit() {
        val type = selectedType
        val region = regionId
        val price = priceText.toDoubleOrNull()
        val qty = qtyText.toLongOrNull()
        val minLot = minLotText.toLongOrNull()

        formError =
            when {
                type == null -> "Pick an item from the suggestions"
                region == null -> "Pick a region"
                price == null || price <= 0 -> "Invalid price"
                qty == null || qty <= 0 -> "Invalid quantity"
                minLot == null || minLot <= 0 -> "Invalid min lot"
                else -> null
            }
        if (formError != null) return

        isPosting = true
        scope.launch(Dispatchers.IO) {
            val identity = NostrIdentityService.getActiveIdentity()
            if (identity == null) {
                withContext(Dispatchers.Main) {
                    formError = "No P2P Market identity set up yet — pick a character in Settings first."
                    isPosting = false
                }
                return@launch
            }
            val traderName = identity.characterId?.let { CharacterDao.getById(it)?.name } ?: identity.label
            val result =
                OrderRepository.postNewOrder(
                    OrderDraft(
                        side = side,
                        typeId = type!!.typeId,
                        regionId = region!!,
                        price = price!!,
                        qtyTotal = qty!!,
                        minLot = minLot!!,
                        minLotUnit = minLotUnit,
                        traderChar = traderName,
                        traderCharId = identity.characterId,
                    ),
                )
            withContext(Dispatchers.Main) {
                isPosting = false
                when (result) {
                    is PostOrderResult.Posted -> {
                        itemQuery = ""
                        selectedType = null
                        priceText = ""
                        qtyText = ""
                        showPostForm = false
                    }
                    PostOrderResult.NoIdentity -> formError = "No P2P Market identity set up yet — pick a character in Settings first."
                    PostOrderResult.RateLimited ->
                        formError = "You've posted too many new orders recently — try again in a bit."
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        if (!showPostForm) {
            OutlinedButton(onClick = { showPostForm = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Post new order")
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Post a new order", style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { showPostForm = false }) { Icon(Icons.Default.Close, "Collapse", Modifier.size(18.dp)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = side == OrderSide.SELL, onClick = { side = OrderSide.SELL }, label = { Text("Selling") })
                        FilterChip(selected = side == OrderSide.BUY, onClick = { side = OrderSide.BUY }, label = { Text("Buying") })
                    }
                    SearchField(
                        query = itemQuery,
                        onQueryChange = { q ->
                            itemQuery = q
                            selectedType = null
                            if (q.length >= 2) {
                                scope.launch(Dispatchers.IO) { itemSearchResults = StaticDataDao.searchMarketTypes(q, limit = 10) }
                            } else {
                                itemSearchResults = emptyList()
                            }
                        },
                        placeholder = "Search item...",
                    )
                    if (itemSearchResults.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.heightIn(max = 140.dp)) {
                            items(itemSearchResults, key = { it.typeId }) { type ->
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedType = type
                                                itemQuery = type.name
                                                itemSearchResults = emptyList()
                                                priceText = ""
                                            }.padding(8.dp),
                                ) {
                                    Text(type.name, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
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
                        FilterChip(
                            selected = minLotUnit == MinLotUnit.UNITS,
                            onClick = { minLotUnit = MinLotUnit.UNITS },
                            label = { Text("units") },
                        )
                        FilterChip(
                            selected = minLotUnit == MinLotUnit.ISK,
                            onClick = { minLotUnit = MinLotUnit.ISK },
                            label = { Text("ISK") },
                        )
                    }
                    Text(
                        tradingAs?.let { "Trading as: $it" } ?: "No character selected — pick one in Settings first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tradingAs != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
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
        }

        actionError?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            if (sellerReservations.isEmpty()) "Incoming buy requests" else "Incoming buy requests (${sellerReservations.size})",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "When someone clicks Buy on one of your orders, their request shows up here for you to accept or decline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (sellerReservations.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No incoming requests yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            ReservationsTableHeader()
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                sellerReservations.forEach { row ->
                    SellerReservationTableRow(
                        row,
                        onAccept = {
                            scope.launch(Dispatchers.IO) {
                                val ok = ReservationService.respond(row.reservation, accept = true)
                                actionError = if (ok) null else "Couldn't accept — is your P2P Market identity still set up?"
                                reloadReservations()
                            }
                        },
                        onDecline = {
                            scope.launch(Dispatchers.IO) {
                                val ok = ReservationService.respond(row.reservation, accept = false)
                                actionError = if (ok) null else "Couldn't decline — is your P2P Market identity still set up?"
                                reloadReservations()
                            }
                        },
                        onMarkCompleted = {
                            scope.launch(Dispatchers.IO) {
                                val ok = ReservationService.markCompleted(row.reservation)
                                actionError =
                                    if (ok) null else "Couldn't publish the completion receipt — is your P2P Market identity still set up?"
                                reloadReservations()
                            }
                        },
                        onRelease = {
                            scope.launch(Dispatchers.IO) {
                                val ok = ReservationService.release(row.reservation)
                                actionError = if (ok) null else "Couldn't release — is your P2P Market identity still set up?"
                                reloadReservations()
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("My active orders", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (myOrderRows.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No active orders yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val sortedRows =
                when (myOrdersSortColumn) {
                    MyOrdersSortColumn.PRICE -> myOrderRows.sortedBy { it.order.price }
                    MyOrdersSortColumn.QTY -> myOrderRows.sortedBy { it.order.qtyRemaining }
                    MyOrdersSortColumn.EXPIRY -> myOrderRows.sortedBy { it.order.expiration }
                }.let { if (myOrdersSortDirection == SortDirection.DESC) it.reversed() else it }

            MyOrdersTableHeader(myOrdersSortColumn, myOrdersSortDirection, onSort = ::toggleMyOrdersSort)
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier.height((sortedRows.size * 48).coerceAtMost(500).dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(sortedRows, key = { "${it.order.orderUuid}:${it.order.pubkey}" }) { row ->
                    MyOrderTableRow(
                        row,
                        onRenew = { scope.launch(Dispatchers.IO) { OrderRepository.renewOrder(row.order) } },
                        onCancel = { scope.launch(Dispatchers.IO) { OrderRepository.cancelOrder(row.order) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReservationsTableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Item", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("Region", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(120.dp))
        Text("Price", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(130.dp))
        Text("Qty", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(60.dp))
        Text("Buyer", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(150.dp))
        Text("Status", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(150.dp))
        Text("Requested", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp))
        Text("", modifier = Modifier.width(240.dp))
    }
}

@Composable
private fun SellerReservationTableRow(
    row: SellerReservationRowData,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onMarkCompleted: () -> Unit,
    onRelease: () -> Unit,
) {
    val reservation = row.reservation
    val nowSec = System.currentTimeMillis() / 1000

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.typeName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (reservation.note.isNotBlank()) {
                Text(
                    reservation.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (reservation.status == "accepted") {
                reservation.holdUntil?.let {
                    Text("Held until ${Instant.ofEpochSecond(it)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            row.regionName ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(120.dp),
        )
        Text(
            row.price?.let { String.format(Locale.US, "%,.2f", it) } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(130.dp),
        )
        Text("${reservation.qty}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(60.dp))
        Row(modifier = Modifier.width(150.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                reservation.buyerChar.ifBlank { "${reservation.buyerPubkey.take(12)}…" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (reservation.buyerChar.isNotBlank()) TraderInfoButton(reservation.buyerChar, reservation.buyerCharacterId)
        }
        Text(
            if (reservation.status == "sent") "Awaiting your response" else "Accepted — awaiting completion",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp),
        )
        Text(
            "${formatDurationShort(nowSec - reservation.requestedAt)} ago",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(110.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(240.dp)) {
            if (reservation.status == "sent") {
                Button(onClick = onAccept) { Text("Accept") }
                OutlinedButton(onClick = onDecline) { Text("Decline") }
            } else {
                Button(onClick = onMarkCompleted) { Text("Mark completed") }
                OutlinedButton(onClick = onRelease) { Text("Release") }
            }
        }
    }
}

@Composable
private fun MyOrdersTableHeader(
    sortColumn: MyOrdersSortColumn,
    sortDirection: SortDirection,
    onSort: (MyOrdersSortColumn, SortDirection) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Side", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(60.dp))
        Text("Item", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        SortHeaderCell(
            "Price",
            Modifier.width(130.dp),
            active = sortColumn == MyOrdersSortColumn.PRICE,
            direction = sortDirection,
        ) { onSort(MyOrdersSortColumn.PRICE, SortDirection.DESC) }
        SortHeaderCell(
            "Qty",
            Modifier.width(90.dp),
            active = sortColumn == MyOrdersSortColumn.QTY,
            direction = sortDirection,
        ) { onSort(MyOrdersSortColumn.QTY, SortDirection.DESC) }
        SortHeaderCell(
            "Expires",
            Modifier.width(110.dp),
            active = sortColumn == MyOrdersSortColumn.EXPIRY,
            direction = sortDirection,
        ) { onSort(MyOrdersSortColumn.EXPIRY, SortDirection.ASC) }
        Spacer(Modifier.width(170.dp))
    }
}

@Composable
private fun MyOrderTableRow(
    row: MyOrderRowData,
    onRenew: () -> Unit,
    onCancel: () -> Unit,
) {
    val order = row.order
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(order.side.uppercase(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(60.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.typeName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ExpiringSoonLabel(order.expiration)
        }
        Text(
            String.format(Locale.US, "%,.2f", order.price),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(130.dp),
        )
        Text("${order.qtyRemaining}/${order.qtyTotal}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(90.dp))
        Text(
            formatDurationShort(order.expiration - System.currentTimeMillis() / 1000) + " left",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(170.dp)) {
            OutlinedButton(onClick = onRenew) { Text("Renew") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
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
