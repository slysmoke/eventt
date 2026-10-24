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
import org.eventt.core.model.stringBlocking
import org.eventt.core.nostr.NostrRelayEvent
import org.eventt.core.nostr.NostrRelayManager
import org.eventt.core.nostr.ReceiptService
import org.eventt.core.nostr.p2pTransactionId
import org.eventt.p2pmarket.generated.resources.*
import org.eventt.ui.common.*
import org.eventt.ui.theme.positiveColor
import org.jetbrains.compose.resources.stringResource
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
                    val typeName =
                        order?.let { StaticDataDao.getTypeById(it.typeId)?.name }
                            ?: stringBlocking(Res.string.order_n, reservation.orderUuid.take(8))
                    val attribution = WalletDao.getAttribution(p2pTransactionId(reservation.tradeId, reservation.role))
                    InboxRowData(reservation, typeName, NostrReceiptDao.hasMutualReceipt(reservation.tradeId), attribution, order)
                }
            }
    }

    LaunchedEffect(Unit) {
        reload()
        NostrRelayManager.events.collect { event -> if (event is NostrRelayEvent.ReservationActivity) reload() }
    }

    val now = System.currentTimeMillis() / 1000
    Panel(stringResource(Res.string.tab_inbox), Modifier.fillMaxSize()) {
        Table(
            cols =
                listOf<Col<InboxRowData>>(
                    Col(stringResource(Res.string.h_item), 2.4f, end = false) { it.typeName.lowercase() },
                    Col(stringResource(Res.string.h_qty), 0.6f) { it.reservation.qty },
                    Col(
                        stringResource(Res.string.h_role),
                        0.6f,
                        end = false,
                        tip = stringResource(Res.string.tip_p_role),
                        sort = { it.reservation.role },
                    ),
                    Col(
                        stringResource(Res.string.h_status),
                        2f,
                        end = false,
                        tip = stringResource(Res.string.tip_p_inbox_status),
                        sort = { it.isConfirmed },
                    ),
                    Col(
                        stringResource(
                            Res.string.h_attributed_to,
                        ),
                        1.4f,
                        end = false,
                        tip = stringResource(Res.string.tip_p_attributed),
                        sort = {
                            it.attribution?.toString()
                        },
                    ),
                    Col(stringResource(Res.string.h_requested), 1.1f) { it.reservation.requestedAt },
                ),
            rows = rows,
            empty = stringResource(Res.string.no_completed_trades),
            defaultSort = 5,
            key = { it.reservation.tradeId },
        ) { row ->
            val r = row.reservation
            listOf(
                Cell(row.typeName) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.order?.let { TypeIcon(it.typeId, size = 18.dp) }
                        Text(row.typeName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                Cell(r.qty.toString()),
                Cell(r.role, AXIS_COLOR),
                Cell(
                    stringResource(if (row.isConfirmed) Res.string.confirmed_rep else Res.string.awaiting_confirmation),
                    if (row.isConfirmed) positiveColor else AXIS_COLOR,
                ),
                Cell("") {
                    AttributionPicker(row.attribution, { attribution ->
                        scope.launch(Dispatchers.IO) {
                            ReceiptService.bookCostBasisEntry(
                                r,
                                attribution,
                                fallbackOrder = row.order,
                                dateIfNew = Instant.ofEpochSecond(r.respondedAt ?: r.requestedAt).toString(),
                            )
                            reload()
                        }
                    })
                },
                Cell(stringResource(Res.string.time_ago, formatDurationShort(now - r.requestedAt)), AXIS_COLOR),
            )
        }
    }
}
