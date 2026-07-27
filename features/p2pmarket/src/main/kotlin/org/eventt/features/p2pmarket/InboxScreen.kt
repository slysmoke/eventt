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
import org.eventt.core.database.NostrOrderModel
import org.eventt.core.database.NostrReceiptDao
import org.eventt.core.database.NostrReservationDao
import org.eventt.core.database.NostrReservationModel
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.TransactionAttribution
import org.eventt.core.database.WalletDao
import org.eventt.core.nostr.NostrRelayEvent
import org.eventt.core.nostr.NostrRelayManager
import org.eventt.core.nostr.ReceiptService
import org.eventt.core.nostr.p2pTransactionId
import java.time.Instant

private data class InboxRowData(
    val reservation: NostrReservationModel,
    val typeName: String,
    val isConfirmed: Boolean,
    val attribution: TransactionAttribution?,
    // Fallback source for price/type_id when the reservation predates that snapshot (see
    // ReceiptService.bookCostBasisEntry) — null once every trade postdates the snapshot.
    val order: NostrOrderModel?,
)

private enum class InboxSortColumn { QTY, REQUESTED }

/**
 * Trades I've marked completed — a Status column shows whether the counterparty's own receipt has
 * landed yet. A trade only counts toward reputation ([org.eventt.core.nostr.ReputationAggregator])
 * once it's Confirmed here, so this is also where a stuck one-sided completion becomes visible.
 * Empty until at least one side of a trade has hit "Mark completed" — an accepted-but-not-yet-
 * completed reservation belongs on My Orders/My Requests, not here.
 */
@Composable
fun InboxScreen() {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<InboxRowData>>(emptyList()) }
    var sortColumn by remember { mutableStateOf(InboxSortColumn.REQUESTED) }
    var sortDirection by remember { mutableStateOf(SortDirection.DESC) }

    fun toggleSort(
        column: InboxSortColumn,
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
        val completed =
            withContext(Dispatchers.IO) {
                NostrReservationDao.listForRole("buyer", listOf("completed")) +
                    NostrReservationDao.listForRole("seller", listOf("completed"))
            }
        rows =
            withContext(Dispatchers.IO) {
                completed.map { reservation ->
                    val order = NostrOrderDao.getByCoordinate(reservation.orderUuid, reservation.orderPubkey)
                    val typeName = order?.let { StaticDataDao.getTypeById(it.typeId)?.name } ?: "Order ${reservation.orderUuid.take(8)}…"
                    val attribution = WalletDao.getAttribution(p2pTransactionId(reservation.tradeId, reservation.role))
                    InboxRowData(reservation, typeName, NostrReceiptDao.hasMutualReceipt(reservation.tradeId), attribution, order)
                }
            }
    }

    LaunchedEffect(Unit) {
        reload()
        NostrRelayManager.events.collect { event -> if (event is NostrRelayEvent.ReservationActivity) reload() }
    }

    val displayedRows =
        when (sortColumn) {
            InboxSortColumn.QTY -> rows.sortedBy { it.reservation.qty }
            InboxSortColumn.REQUESTED -> rows.sortedBy { it.reservation.requestedAt }
        }.let { if (sortDirection == SortDirection.DESC) it.reversed() else it }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (displayedRows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No completed trades yet — mark a trade completed from My Orders or My Requests once you've traded in-game",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            InboxTableHeader(sortColumn, sortDirection, onSort = ::toggleSort)
            HorizontalDivider()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(displayedRows, key = { it.reservation.tradeId }) { row ->
                    InboxTableRow(
                        row,
                        onAttributionChange = { attribution ->
                            scope.launch(Dispatchers.IO) {
                                val reservation = row.reservation
                                ReceiptService.bookCostBasisEntry(
                                    reservation,
                                    attribution,
                                    fallbackOrder = row.order,
                                    dateIfNew = Instant.ofEpochSecond(reservation.respondedAt ?: reservation.requestedAt).toString(),
                                )
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
private fun InboxTableHeader(
    sortColumn: InboxSortColumn,
    sortDirection: SortDirection,
    onSort: (InboxSortColumn, SortDirection) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Item", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        SortHeaderCell(
            "Qty",
            Modifier.width(80.dp),
            active = sortColumn == InboxSortColumn.QTY,
            direction = sortDirection,
        ) { onSort(InboxSortColumn.QTY, SortDirection.DESC) }
        Text("Role", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(80.dp))
        Text("Status", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(260.dp))
        Text(
            "Attributed to",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(160.dp),
        )
        SortHeaderCell(
            "Requested",
            Modifier.width(110.dp),
            active = sortColumn == InboxSortColumn.REQUESTED,
            direction = sortDirection,
        ) { onSort(InboxSortColumn.REQUESTED, SortDirection.DESC) }
    }
}

@Composable
private fun InboxTableRow(
    row: InboxRowData,
    onAttributionChange: (TransactionAttribution) -> Unit,
) {
    val reservation = row.reservation
    val nowSec = System.currentTimeMillis() / 1000

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.typeName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("${reservation.qty}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(80.dp))
        Text(reservation.role, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(80.dp))
        Text(
            if (row.isConfirmed) "Confirmed — counted toward reputation" else "Awaiting counterparty confirmation",
            style = MaterialTheme.typography.labelSmall,
            color = if (row.isConfirmed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(260.dp),
        )
        AttributionPicker(row.attribution, onAttributionChange, modifier = Modifier.width(160.dp))
        Text(
            "${formatDurationShort(nowSec - reservation.requestedAt)} ago",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(110.dp),
        )
    }
}
