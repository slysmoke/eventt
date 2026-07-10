package org.eventt.features.p2pmarket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import org.eventt.core.database.NostrOrderDao
import org.eventt.core.database.NostrReservationDao
import org.eventt.core.database.NostrReservationModel
import org.eventt.core.database.StaticDataDao
import org.eventt.core.nostr.NostrRelayEvent
import org.eventt.core.nostr.NostrRelayManager
import org.eventt.core.nostr.OrderSide
import org.eventt.core.nostr.ReservationService
import java.time.Instant
import java.util.Locale

// "Stale" is a pure client-side UI label — the seller may still answer after this, no protocol
// timeout exists. It just tells the buyer "this has been quiet a while," nothing more.
private const val STALE_AFTER_SECONDS = 48L * 3600

private data class RequestRowData(
    val reservation: NostrReservationModel,
    val typeName: String,
    val regionName: String?,
    val price: Double?,
    val counterpartyLabel: String,
    val counterpartyCharacterName: String?,
    val counterpartyCharacterId: Int?,
    // Null for a purged/expired order — falls back to neutral labeling (see RequestTableRow).
    val orderSide: OrderSide?,
)

private enum class MyRequestsSortColumn { PRICE, QTY, REQUESTED }

// Once completed, a request belongs on Inbox only (see InboxScreen) — everything before that
// (still pending, accepted-but-not-yet-completed, or resolved without completing) stays visible
// here so there's always somewhere to see it.
private val VISIBLE_STATUSES = listOf("sent", "accepted", "declined", "cancelled", "released")

@Composable
fun MyRequestsScreen() {
    val scope = rememberCoroutineScope()
    var requests by remember { mutableStateOf<List<RequestRowData>>(emptyList()) }
    var sortColumn by remember { mutableStateOf(MyRequestsSortColumn.REQUESTED) }
    var sortDirection by remember { mutableStateOf(SortDirection.DESC) }

    fun toggleSort(
        column: MyRequestsSortColumn,
        defaultDirection: SortDirection,
    ) {
        if (sortColumn == column) {
            sortDirection = if (sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
        } else {
            sortColumn = column
            sortDirection = defaultDirection
        }
    }

    suspend fun reload() {
        val raw = withContext(Dispatchers.IO) { NostrReservationDao.listForRole("buyer", VISIBLE_STATUSES) }
        requests =
            withContext(Dispatchers.IO) {
                raw.map { reservation ->
                    val order = NostrOrderDao.getByCoordinate(reservation.orderUuid, reservation.orderPubkey)
                    val counterpartyCharacterName = order?.traderChar?.ifBlank { null }
                    RequestRowData(
                        reservation = reservation,
                        typeName =
                            order?.let { StaticDataDao.getTypeById(it.typeId)?.name }
                                ?: "Order ${reservation.orderUuid.take(8)}… (expired/purged)",
                        regionName = order?.let { StaticDataDao.getRegionById(it.regionId)?.name },
                        price = order?.price,
                        counterpartyLabel = counterpartyCharacterName ?: "${reservation.sellerPubkey.take(12)}…",
                        counterpartyCharacterName = counterpartyCharacterName,
                        counterpartyCharacterId = order?.traderCharId,
                        orderSide = order?.side?.let { runCatching { OrderSide.valueOf(it.uppercase()) }.getOrNull() },
                    )
                }
            }
    }

    LaunchedEffect(Unit) {
        reload()
        NostrRelayManager.events.collect { event ->
            if (event is NostrRelayEvent.ReservationActivity) reload()
        }
    }

    val displayedRequests =
        when (sortColumn) {
            MyRequestsSortColumn.PRICE -> requests.sortedBy { it.price ?: Double.NEGATIVE_INFINITY }
            MyRequestsSortColumn.QTY -> requests.sortedBy { it.reservation.qty }
            MyRequestsSortColumn.REQUESTED -> requests.sortedBy { it.reservation.requestedAt }
        }.let { if (sortDirection == SortDirection.DESC) it.reversed() else it }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (displayedRequests.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No requests sent yet — request a reservation from Browse", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            MyRequestsTableHeader(sortColumn, sortDirection, onSort = ::toggleSort)
            HorizontalDivider()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(displayedRequests, key = { it.reservation.tradeId }) { row ->
                    RequestTableRow(
                        row,
                        onMarkCompleted = {
                            scope.launch(Dispatchers.IO) {
                                ReservationService.markCompleted(row.reservation)
                                reload()
                            }
                        },
                        onCancel = {
                            scope.launch(Dispatchers.IO) {
                                ReservationService.cancel(row.reservation)
                                reload()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MyRequestsTableHeader(
    sortColumn: MyRequestsSortColumn,
    sortDirection: SortDirection,
    onSort: (MyRequestsSortColumn, SortDirection) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Item", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("Region", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(130.dp))
        SortHeaderCell(
            "Price",
            Modifier.width(130.dp),
            active = sortColumn == MyRequestsSortColumn.PRICE,
            direction = sortDirection,
        ) { onSort(MyRequestsSortColumn.PRICE, SortDirection.DESC) }
        SortHeaderCell(
            "Qty",
            Modifier.width(70.dp),
            active = sortColumn == MyRequestsSortColumn.QTY,
            direction = sortDirection,
        ) { onSort(MyRequestsSortColumn.QTY, SortDirection.DESC) }
        Text(
            "Counterparty",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(160.dp),
        )
        Text("Status", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(150.dp))
        SortHeaderCell(
            "Requested",
            Modifier.width(110.dp),
            active = sortColumn == MyRequestsSortColumn.REQUESTED,
            direction = sortDirection,
        ) { onSort(MyRequestsSortColumn.REQUESTED, SortDirection.DESC) }
        Text("", modifier = Modifier.width(150.dp))
    }
}

@Composable
private fun RequestTableRow(
    row: RequestRowData,
    onMarkCompleted: () -> Unit,
    onCancel: () -> Unit,
) {
    val reservation = row.reservation
    val nowSec = System.currentTimeMillis() / 1000
    val isStale = reservation.status == "sent" && nowSec - reservation.requestedAt > STALE_AFTER_SECONDS
    val statusLabel =
        when {
            reservation.status == "sent" && isStale -> "Stale (no response yet)"
            reservation.status == "sent" -> "Sent — awaiting response"
            reservation.status == "accepted" -> "Accepted"
            reservation.status == "declined" -> "Declined"
            reservation.status == "completed" -> "Completed"
            reservation.status == "released" -> "Released by seller"
            reservation.status == "cancelled" -> "Cancelled"
            else -> reservation.status
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.orderSide?.let { OrderSideBadge(it) }
                Text(row.typeName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            row.orderSide?.let {
                Text(
                    "You're ${if (requesterRole(it) == "Buyer") "buying" else "selling"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                if (reservation.contactChar.isNotBlank()) {
                    Text("Contact in-game: ${reservation.contactChar}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            row.regionName ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(130.dp),
        )
        Text(
            row.price?.let { String.format(Locale.US, "%,.2f", it) } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(130.dp),
        )
        Text("${reservation.qty}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(70.dp))
        Row(modifier = Modifier.width(160.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.counterpartyLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.counterpartyCharacterName?.let { TraderInfoButton(it, row.counterpartyCharacterId) }
        }
        Text(
            statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (reservation.status == "declined") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
        Box(modifier = Modifier.width(150.dp)) {
            if (reservation.status == "accepted") {
                OutlinedButton(onClick = onMarkCompleted) { Text("Mark completed") }
            } else if (reservation.status == "sent") {
                OutlinedButton(onClick = onCancel) { Text("Cancel request") }
            }
        }
    }
}
