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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import org.eventt.core.nostr.ReservationService
import java.time.Instant
import java.util.Locale

private data class SellerReservationRowData(
    val reservation: NostrReservationModel,
    val typeName: String,
    val regionName: String?,
    val price: Double?,
)

/** Incoming buy requests on your own orders — accept/decline, then mark completed or release once accepted. */
@Composable
fun IncomingRequestsScreen() {
    val scope = rememberCoroutineScope()
    var sellerReservations by remember { mutableStateOf<List<SellerReservationRowData>>(emptyList()) }
    var actionError by remember { mutableStateOf<String?>(null) }

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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            if (sellerReservations.isEmpty()) "Incoming buy requests" else "Incoming buy requests (${sellerReservations.size})",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "When someone clicks Buy on one of your orders, their request shows up here for you to accept or decline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        actionError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(8.dp))
        if (sellerReservations.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No incoming requests yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            ReservationsTableHeader()
            HorizontalDivider()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(sellerReservations, key = { it.reservation.tradeId }) { row ->
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
        Text("", modifier = Modifier.width(260.dp))
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(260.dp)) {
            if (reservation.status == "sent") {
                Button(onClick = onAccept, contentPadding = COMPACT_BUTTON_PADDING) { Text("Accept") }
                OutlinedButton(onClick = onDecline, contentPadding = COMPACT_BUTTON_PADDING) { Text("Decline") }
            } else {
                Button(onClick = onMarkCompleted, contentPadding = COMPACT_BUTTON_PADDING) { Text("Mark completed") }
                OutlinedButton(onClick = onRelease, contentPadding = COMPACT_BUTTON_PADDING) { Text("Release") }
            }
        }
    }
}
