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
import org.eventt.core.model.stringBlocking
import org.eventt.core.nostr.NostrRelayEvent
import org.eventt.core.nostr.NostrRelayManager
import org.eventt.core.nostr.OrderSide
import org.eventt.core.nostr.ReservationService
import org.eventt.p2pmarket.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.util.Locale

private data class SellerReservationRowData(
    val reservation: NostrReservationModel,
    val typeName: String,
    val regionName: String?,
    val price: Double?,
    // Null for a purged/expired order — falls back to neutral labeling (see SellerReservationTableRow).
    val orderSide: OrderSide?,
)

/**
 * Incoming requests on your own orders — accept/decline, then mark completed or release once
 * accepted. "Incoming" is deliberately not "incoming buy requests": your own order can be a SELL
 * or a BUY, so someone requesting it might be offering to buy from you *or* to sell to you.
 */
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
                                    ?: stringBlocking(Res.string.order_expired, reservation.orderUuid.take(8)),
                            regionName = order?.let { StaticDataDao.getRegionById(it.regionId)?.name },
                            price = order?.price,
                            orderSide = order?.side?.let { runCatching { OrderSide.valueOf(it.uppercase()) }.getOrNull() },
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
            if (sellerReservations.isEmpty()) {
                stringResource(Res.string.incoming_requests)
            } else {
                stringResource(Res.string.incoming_requests_n, sellerReservations.size)
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(Res.string.incoming_desc),
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
                Text(stringResource(Res.string.no_incoming), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                actionError = if (ok) null else getString(Res.string.err_accept)
                                reloadReservations()
                            }
                        },
                        onDecline = {
                            scope.launch(Dispatchers.IO) {
                                val ok = ReservationService.respond(row.reservation, accept = false)
                                actionError = if (ok) null else getString(Res.string.err_decline)
                                reloadReservations()
                            }
                        },
                        onMarkCompleted = {
                            scope.launch(Dispatchers.IO) {
                                val ok = ReservationService.markCompleted(row.reservation)
                                actionError =
                                    if (ok) null else getString(Res.string.err_receipt)
                                reloadReservations()
                            }
                        },
                        onRelease = {
                            scope.launch(Dispatchers.IO) {
                                val ok = ReservationService.release(row.reservation)
                                actionError = if (ok) null else getString(Res.string.err_release)
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
        Text(
            stringResource(Res.string.h_item),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(Res.string.h_region),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(120.dp),
        )
        Text(
            stringResource(Res.string.h_price),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(130.dp),
        )
        Text(
            stringResource(Res.string.h_qty),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(60.dp),
        )
        Text(
            stringResource(Res.string.h_requester),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(150.dp),
        )
        Text(
            stringResource(Res.string.h_status),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(150.dp),
        )
        Text(
            stringResource(Res.string.h_requested),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(110.dp),
        )
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.orderSide?.let { OrderSideBadge(it) }
                Text(row.typeName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    Text(
                        stringResource(Res.string.held_until, Instant.ofEpochSecond(it).toString()),
                        style = MaterialTheme.typography.labelSmall,
                    )
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
        Column(modifier = Modifier.width(150.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    reservation.buyerChar.ifBlank { "${reservation.buyerPubkey.take(12)}…" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (reservation.buyerChar.isNotBlank()) TraderInfoButton(reservation.buyerChar, reservation.buyerCharacterId)
            }
            PresenceBadge(reservation.buyerPubkey)
            row.orderSide?.let {
                Text(
                    stringResource(if (it == OrderSide.SELL) Res.string.wants_to else Res.string.wants_to_sell),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            if (reservation.status ==
                "sent"
            ) {
                stringResource(Res.string.awaiting_response)
            } else {
                stringResource(Res.string.accepted_awaiting)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp),
        )
        Text(
            stringResource(Res.string.time_ago, formatDurationShort(nowSec - reservation.requestedAt)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(110.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(260.dp)) {
            if (reservation.status == "sent") {
                Button(onClick = onAccept, contentPadding = COMPACT_BUTTON_PADDING) { Text(stringResource(Res.string.accept)) }
                OutlinedButton(onClick = onDecline, contentPadding = COMPACT_BUTTON_PADDING) { Text(stringResource(Res.string.decline)) }
            } else {
                Button(
                    onClick = onMarkCompleted,
                    contentPadding = COMPACT_BUTTON_PADDING,
                ) { Text(stringResource(Res.string.mark_completed)) }
                OutlinedButton(onClick = onRelease, contentPadding = COMPACT_BUTTON_PADDING) { Text(stringResource(Res.string.release)) }
            }
        }
    }
}
