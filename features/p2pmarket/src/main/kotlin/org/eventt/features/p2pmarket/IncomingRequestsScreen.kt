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
import org.eventt.ui.common.*
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.StringResource
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

    val now = System.currentTimeMillis() / 1000
    Panel(
        if (sellerReservations.isEmpty()) {
            stringResource(Res.string.incoming_requests)
        } else {
            stringResource(Res.string.incoming_requests_n, sellerReservations.size)
        },
        Modifier.fillMaxSize(),
    ) {
        Text(stringResource(Res.string.incoming_desc), style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR)
        actionError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(4.dp))

        fun act(
            block: suspend () -> Boolean,
            err: StringResource,
        ) {
            scope.launch(Dispatchers.IO) {
                actionError = if (block()) null else getString(err)
                reloadReservations()
            }
        }
        Table(
            cols =
                listOf<Col<SellerReservationRowData>>(
                    Col(stringResource(Res.string.h_item), 2.4f, end = false) { it.typeName.lowercase() },
                    Col(stringResource(Res.string.h_region), 1.1f, end = false) { it.regionName },
                    Col(stringResource(Res.string.h_price), 1f) { it.price },
                    Col(stringResource(Res.string.h_qty), 0.6f) { it.reservation.qty },
                    Col(stringResource(Res.string.h_requester), 1.6f, end = false) { it.reservation.buyerChar.lowercase() },
                    Col(stringResource(Res.string.h_status), 1.3f, end = false) { it.reservation.status },
                    Col(stringResource(Res.string.h_requested), 1.1f) { it.reservation.requestedAt },
                    Col("", 1.7f),
                ),
            rows = sellerReservations,
            empty = stringResource(Res.string.no_incoming),
            defaultSort = 6,
            key = { it.reservation.tradeId },
            // New requests waiting on you stand out from accepted ones.
            highlight = { it.reservation.status == "sent" },
        ) { row ->
            val r = row.reservation
            listOf(
                Cell(row.typeName) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.orderSide?.let { OrderSideBadge(it) }
                            Text(row.typeName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (r.note.isNotBlank()) {
                            Text(
                                r.note,
                                style = MaterialTheme.typography.labelSmall,
                                color = AXIS_COLOR,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (r.status == "accepted") {
                            r.holdUntil?.let {
                                Text(
                                    stringResource(Res.string.held_until, Instant.ofEpochSecond(it).toString()),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                },
                Cell(row.regionName ?: "—", AXIS_COLOR),
                Cell(row.price?.let(::formatPriceAbbr) ?: "—"),
                Cell(r.qty.toString()),
                Cell(r.buyerChar) {
                    TraderCell(
                        r.buyerChar,
                        r.buyerCharacterId,
                        r.buyerPubkey,
                        role =
                            row.orderSide?.let {
                                stringResource(
                                    if (it ==
                                        OrderSide.SELL
                                    ) {
                                        Res.string.wants_to
                                    } else {
                                        Res.string.wants_to_sell
                                    },
                                )
                            },
                    )
                },
                Cell(
                    stringResource(if (r.status == "sent") Res.string.awaiting_response else Res.string.accepted_awaiting),
                    if (r.status == "sent") MaterialTheme.colorScheme.error else warningColor,
                ),
                Cell(stringResource(Res.string.time_ago, formatDurationShort(now - r.requestedAt)), AXIS_COLOR),
                Cell("") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (r.status == "sent") {
                            Segment(
                                stringResource(Res.string.accept),
                                true,
                            ) { act({ ReservationService.respond(r, accept = true) }, Res.string.err_accept) }
                            Segment(stringResource(Res.string.decline), false) {
                                act({ ReservationService.respond(r, accept = false) }, Res.string.err_decline)
                            }
                        } else {
                            Segment(stringResource(Res.string.mark_completed), true) {
                                act({ ReservationService.markCompleted(r) }, Res.string.err_receipt)
                            }
                            Segment(
                                stringResource(Res.string.release),
                                false,
                            ) { act({ ReservationService.release(r) }, Res.string.err_release) }
                        }
                    }
                },
            )
        }
    }
}
