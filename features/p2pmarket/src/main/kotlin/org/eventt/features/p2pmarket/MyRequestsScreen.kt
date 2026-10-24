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
import org.eventt.core.model.stringBlocking
import org.eventt.core.nostr.NostrRelayEvent
import org.eventt.core.nostr.NostrRelayManager
import org.eventt.core.nostr.OrderSide
import org.eventt.core.nostr.ReservationService
import org.eventt.p2pmarket.generated.resources.*
import org.eventt.ui.common.*
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.stringResource
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

// Once completed, a request belongs on Inbox only (see InboxScreen) — everything before that
// (still pending, accepted-but-not-yet-completed, or resolved without completing) stays visible
// here so there's always somewhere to see it.
private val VISIBLE_STATUSES = listOf("sent", "accepted", "declined", "cancelled", "released")

@Composable
fun MyRequestsScreen() {
    val scope = rememberCoroutineScope()
    var requests by remember { mutableStateOf<List<RequestRowData>>(emptyList()) }

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
                                ?: stringBlocking(Res.string.order_expired, reservation.orderUuid.take(8)),
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

    val now = System.currentTimeMillis() / 1000
    Panel(stringResource(Res.string.tab_my_requests), Modifier.fillMaxSize()) {
        Table(
            cols =
                listOf<Col<RequestRowData>>(
                    Col(stringResource(Res.string.h_item), 2.4f, end = false) { it.typeName.lowercase() },
                    Col(stringResource(Res.string.h_region), 1.1f, end = false) { it.regionName },
                    Col(stringResource(Res.string.h_price), 1f) { it.price },
                    Col(stringResource(Res.string.h_qty), 0.6f) { it.reservation.qty },
                    Col(stringResource(Res.string.h_counterparty), 1.6f, end = false) { it.counterpartyLabel.lowercase() },
                    Col(stringResource(Res.string.h_status), 1f, end = false) { it.reservation.status },
                    Col(stringResource(Res.string.h_requested), 1.1f) { it.reservation.requestedAt },
                    Col("", 1.2f),
                ),
            rows = requests,
            empty = stringResource(Res.string.no_requests_sent),
            defaultSort = 6,
            key = { it.reservation.tradeId },
            highlight = { it.reservation.status == "accepted" },
        ) { row ->
            val r = row.reservation
            val isStale = r.status == "sent" && now - r.requestedAt > STALE_AFTER_SECONDS
            listOf(
                Cell(row.typeName) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.orderSide?.let { OrderSideBadge(it) }
                            Text(row.typeName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        row.orderSide?.let {
                            Text(
                                stringResource(if (it == OrderSide.SELL) Res.string.youre_buying else Res.string.youre_selling),
                                style = MaterialTheme.typography.labelSmall,
                                color = AXIS_COLOR,
                            )
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
                            if (r.contactChar.isNotBlank()) {
                                Text(stringResource(Res.string.contact_ingame, r.contactChar), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                },
                Cell(row.regionName ?: "—", AXIS_COLOR),
                Cell(row.price?.let(::formatPriceAbbr) ?: "—"),
                Cell(r.qty.toString()),
                Cell(
                    row.counterpartyLabel,
                ) { TraderCell(row.counterpartyCharacterName.orEmpty(), row.counterpartyCharacterId, r.sellerPubkey) },
                Cell(
                    stringResource(
                        when {
                            isStale -> Res.string.st_stale
                            r.status == "sent" -> Res.string.st_sent
                            r.status == "accepted" -> Res.string.st_accepted
                            r.status == "declined" -> Res.string.st_declined
                            r.status == "completed" -> Res.string.st_completed
                            r.status == "released" -> Res.string.st_released
                            else -> Res.string.st_cancelled
                        },
                    ),
                    when (r.status) {
                        "declined" -> MaterialTheme.colorScheme.error
                        "accepted" -> warningColor
                        "sent" -> MaterialTheme.colorScheme.onSurface
                        else -> AXIS_COLOR
                    },
                ),
                Cell(stringResource(Res.string.time_ago, formatDurationShort(now - r.requestedAt)), AXIS_COLOR),
                Cell("") {
                    when (r.status) {
                        "accepted" -> {
                            Segment(stringResource(Res.string.mark_completed), true) {
                                scope.launch(Dispatchers.IO) {
                                    ReservationService.markCompleted(r)
                                    reload()
                                }
                            }
                        }

                        "sent" -> {
                            Segment(stringResource(Res.string.cancel_request), false) {
                                scope.launch(Dispatchers.IO) {
                                    ReservationService.cancel(r)
                                    reload()
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}
