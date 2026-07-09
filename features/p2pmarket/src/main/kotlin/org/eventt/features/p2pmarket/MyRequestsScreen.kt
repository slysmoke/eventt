package org.eventt.features.p2pmarket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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
    val sellerLabel: String,
)

@Composable
fun MyRequestsScreen() {
    val scope = rememberCoroutineScope()
    var requests by remember { mutableStateOf<List<RequestRowData>>(emptyList()) }

    suspend fun reload() {
        val raw = withContext(Dispatchers.IO) { NostrReservationDao.listForRole("buyer") }
        requests =
            withContext(Dispatchers.IO) {
                raw.map { reservation ->
                    val order = NostrOrderDao.getByCoordinate(reservation.orderUuid, reservation.orderPubkey)
                    RequestRowData(
                        reservation = reservation,
                        typeName = order?.let { StaticDataDao.getTypeById(it.typeId)?.name } ?: "Order ${reservation.orderUuid.take(8)}… (expired/purged)",
                        regionName = order?.let { StaticDataDao.getRegionById(it.regionId)?.name },
                        price = order?.price,
                        sellerLabel = order?.traderChar?.ifBlank { null } ?: "${reservation.sellerPubkey.take(12)}…",
                    )
                }.sortedByDescending { it.reservation.requestedAt }
            }
    }

    LaunchedEffect(Unit) {
        reload()
        NostrRelayManager.events.collect { event ->
            if (event is NostrRelayEvent.ReservationActivity) reload()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (requests.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No requests sent yet — request a reservation from Browse", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(requests, key = { it.reservation.tradeId }) { row ->
                    RequestRow(row, onMarkCompleted = {
                        scope.launch(Dispatchers.IO) {
                            ReservationService.markCompleted(row.reservation)
                            reload()
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun RequestRow(
    row: RequestRowData,
    onMarkCompleted: () -> Unit,
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
            else -> reservation.status
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.typeName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Qty ${reservation.qty}" + (row.regionName?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                row.price?.let { Text(String.format(Locale.US, "%,.2f ISK", it), style = MaterialTheme.typography.bodyMedium) }
                Column(horizontalAlignment = Alignment.End) {
                    Text(row.sellerLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (reservation.status == "declined") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (reservation.note.isNotBlank()) {
                Text(reservation.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (reservation.status == "accepted") {
                HorizontalDivider()
                reservation.holdUntil?.let {
                    Text("Held until ${Instant.ofEpochSecond(it)}", style = MaterialTheme.typography.labelSmall)
                }
                if (reservation.contactChar.isNotBlank()) {
                    Text("Contact in-game: ${reservation.contactChar}", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = onMarkCompleted) { Text("I completed this trade") }
            }
        }
    }
}
