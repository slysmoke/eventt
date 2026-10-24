package org.eventt.features.p2pmarket

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.AppState
import org.eventt.core.database.NostrOrderModel
import org.eventt.core.database.StaticDataDao
import org.eventt.core.model.stringBlocking
import org.eventt.core.nostr.NostrIdentityService
import org.eventt.core.nostr.OrderFilter
import org.eventt.core.nostr.OrderRepository
import org.eventt.core.nostr.OrderSide
import org.eventt.core.nostr.ReputationAggregator
import org.eventt.core.nostr.ReservationService
import org.eventt.p2pmarket.generated.resources.*
import org.eventt.ui.common.*
import org.eventt.ui.theme.positiveColor
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.math.abs

private data class BrowseRow(
    val order: NostrOrderModel,
    val typeName: String,
    val regionName: String,
    val savings: SavingsResult?,
    val confirmedTrades: Int,
)

@Composable
fun BrowseScreen() {
    val scope = rememberCoroutineScope()
    var sideFilter by remember { mutableStateOf<OrderSide?>(null) }
    var orders by remember { mutableStateOf<List<NostrOrderModel>>(emptyList()) }
    var rows by remember { mutableStateOf<List<BrowseRow>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var activeIdentityPubkey by remember { mutableStateOf<String?>(null) }
    // The viewer's own effective sales tax rate — BUY orders' savings badge needs it (see
    // SavingsBadgeService.computeSavings); defaults to the 8% base rate with no active identity.
    var viewerSalesTaxPct by remember { mutableStateOf(8.0) }
    var requestFor by remember { mutableStateOf<NostrOrderModel?>(null) }
    var requestError by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(setOf<String>()) }

    // Refreshed every time this tab is (re)entered, so switching the active character in Settings
    // immediately changes which order(s) are treated as "yours" here — order.isMine is a stale,
    // stored-at-post-time flag (true for any of your characters, forever), not a live "can I
    // request this with my CURRENTLY active identity" check, which is what the button needs.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val identity = NostrIdentityService.getActiveIdentity()
            activeIdentityPubkey = identity?.pubkey
            viewerSalesTaxPct = identity?.characterId?.let { StaticDataDao.getCharSalesTax(it) } ?: 8.0
        }
    }

    LaunchedEffect(sideFilter) {
        OrderRepository.browse(OrderFilter(side = sideFilter)).collect { orders = it }
    }
    LaunchedEffect(orders, viewerSalesTaxPct) {
        rows =
            withContext(Dispatchers.IO) {
                orders.map { order ->
                    val side = OrderSide.valueOf(order.side.uppercase())
                    BrowseRow(
                        order = order,
                        typeName = StaticDataDao.getTypeById(order.typeId)?.name ?: stringBlocking(Res.string.type_n, order.typeId),
                        regionName =
                            StaticDataDao.getRegionById(order.regionId)?.name ?: stringBlocking(Res.string.region_n, order.regionId),
                        savings = SavingsBadgeService.computeSavings(order.typeId, order.regionId, side, order.price, viewerSalesTaxPct),
                        confirmedTrades = ReputationAggregator.confirmedTradeCount(order.pubkey),
                    )
                }
            }
    }

    val shown =
        remember(
            rows,
            searchQuery,
        ) { if (searchQuery.isBlank()) rows else rows.filter { it.typeName.contains(searchQuery, ignoreCase = true) } }

    Panel(
        null,
        Modifier.fillMaxSize(),
        header = {
            Segment(stringResource(Res.string.all), sideFilter == null) { sideFilter = null }
            Segment(stringResource(Res.string.filter_selling), sideFilter == OrderSide.SELL) { sideFilter = OrderSide.SELL }
            Segment(stringResource(Res.string.filter_buying), sideFilter == OrderSide.BUY) { sideFilter = OrderSide.BUY }
            Spacer(Modifier.weight(1f))
        },
    ) {
        TerminalSearch(searchQuery, { searchQuery = it }, stringResource(Res.string.search_item), Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        val now = System.currentTimeMillis() / 1000
        val cols =
            listOf<Col<BrowseRow>>(
                Col(stringResource(Res.string.h_side), 0.5f, end = false) { it.order.side },
                Col(stringResource(Res.string.h_item), 2.4f, end = false) { it.typeName.lowercase() },
                Col(stringResource(Res.string.h_region), 1.1f, end = false) { it.regionName },
                Col(stringResource(Res.string.h_price), 1f) { it.order.price },
                Col(stringResource(Res.string.h_qty), 0.8f) { it.order.qtyRemaining },
                Col(stringResource(Res.string.h_value), 0.9f) { it.order.price * it.order.qtyRemaining },
                Col(
                    stringResource(Res.string.h_savings),
                    0.7f,
                    tip = stringResource(Res.string.tip_p_savings),
                    sort = { it.savings?.savingsPct },
                ),
                Col(stringResource(Res.string.h_trader), 1.6f, end = false, tip = stringResource(Res.string.tip_p_trader), sort = {
                    it.order.traderChar.lowercase()
                }),
                Col(
                    stringResource(Res.string.h_expires),
                    0.8f,
                    tip = stringResource(Res.string.tip_p_expires),
                    sort = { it.order.expiration },
                ),
                Col("", 0.7f),
            )
        Table(
            cols = cols,
            rows = shown,
            empty =
                if (rows.isEmpty()) stringResource(Res.string.no_orders_yet) else stringResource(Res.string.no_orders_match, searchQuery),
            defaultSort = 8,
            defaultDesc = false,
            key = { "${it.order.orderUuid}:${it.order.pubkey}" },
            highlight = { it.order.pubkey == activeIdentityPubkey },
        ) { row ->
            val order = row.order
            val side = OrderSide.valueOf(order.side.uppercase())
            val key = "${order.orderUuid}:${order.pubkey}"
            listOf(
                Cell(order.side) { OrderSideBadge(side) },
                Cell(row.typeName) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TypeIcon(order.typeId, size = 18.dp)
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(row.typeName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ExpiringSoonLabel(order.expiration)
                        }
                        IconButton(modifier = Modifier.size(18.dp), onClick = { AppState.openInMarket(order.typeId) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(Res.string.view_in_market),
                                modifier = Modifier.size(12.dp),
                                tint = AXIS_COLOR,
                            )
                        }
                    }
                },
                Cell(row.regionName, AXIS_COLOR),
                Cell(formatPriceAbbr(order.price)),
                Cell("${formatVolume(order.qtyRemaining)}/${formatVolume(order.qtyTotal)}"),
                Cell(formatIsk(order.price * order.qtyRemaining), AXIS_COLOR),
                Cell(
                    row.savings?.let { s ->
                        "${if (s.savingsPct >= 0) "-" else "+"}${String.format(Locale.US, "%.1f", abs(s.savingsPct))}%"
                    }
                        ?: "—",
                    row.savings?.let { if (it.savingsPct >= 0) positiveColor else MaterialTheme.colorScheme.error } ?: AXIS_COLOR,
                ),
                Cell(
                    order.traderChar,
                ) { TraderCell(order.traderChar, order.traderCharId, order.pubkey, row.confirmedTrades, orderOwnerRole(side)) },
                Cell(formatDurationShort(order.expiration - now), AXIS_COLOR),
                Cell("") {
                    // A SELL order is fulfilled by buying it; a BUY order by selling into it — the
                    // button says which one *you'd* be doing.
                    if (order.pubkey != activeIdentityPubkey) {
                        Segment(
                            stringResource(
                                when {
                                    key in sent -> Res.string.sent
                                    side == OrderSide.SELL -> Res.string.buy
                                    else -> Res.string.sell
                                },
                            ),
                            key !in sent,
                        ) { if (key !in sent) requestFor = order }
                    }
                },
            )
        }
    }

    requestFor?.let { order ->
        RequestReservationDialog(
            order = order,
            side = OrderSide.valueOf(order.side.uppercase()),
            error = requestError,
            onDismiss = {
                requestFor = null
                requestError = null
            },
            onSend = { qty, note ->
                scope.launch(Dispatchers.IO) {
                    val tradeId = ReservationService.sendRequest(order, qty, note)
                    withContext(Dispatchers.Main) {
                        if (tradeId != null) {
                            sent = sent + "${order.orderUuid}:${order.pubkey}"
                            requestFor = null
                        } else {
                            requestError = getString(Res.string.err_no_identity_add)
                        }
                    }
                }
            },
        )
    }
}

/** Counterparty cell shared by the P2P tables: name with the info button, presence, and their confirmed-trade count. */
@Composable
internal fun TraderCell(
    name: String,
    characterId: Int?,
    pubkey: String,
    confirmedTrades: Int = 0,
    role: String? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name.ifBlank { "${pubkey.take(12)}…" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (name.isNotBlank()) TraderInfoButton(name, characterId)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            role?.let { Text("($it)", style = MaterialTheme.typography.labelSmall, color = AXIS_COLOR) }
            PresenceBadge(pubkey)
            if (confirmedTrades > 0) {
                Text(
                    stringResource(Res.string.confirmed_n, confirmedTrades),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun RequestReservationDialog(
    order: NostrOrderModel,
    side: OrderSide,
    error: String?,
    onDismiss: () -> Unit,
    onSend: (qty: Long, note: String) -> Unit,
) {
    var qtyText by remember { mutableStateOf(order.minLot.toString()) }
    var note by remember { mutableStateOf("") }
    val qty = qtyText.toLongOrNull()
    val valid = qty != null && qty in order.minLot..order.qtyRemaining

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (side ==
                    OrderSide.SELL
                ) {
                    stringResource(Res.string.request_to_buy)
                } else {
                    stringResource(Res.string.offer_to_sell)
                },
            )
        },
        text = {
            Column {
                Text(
                    stringResource(Res.string.min_lot_remaining, order.minLot, order.qtyRemaining),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = qtyText, onValueChange = {
                    qtyText = it
                }, label = { Text(stringResource(Res.string.quantity)) }, singleLine = true)
                Text(
                    stringResource(Res.string.total_isk, qty?.let { String.format(Locale.US, "%,.2f", it * order.price) } ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = note, onValueChange = {
                    note = it
                }, label = { Text(stringResource(Res.string.note_optional)) }, singleLine = true)
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { qty?.let { onSend(it, note) } }, enabled = valid) { Text(stringResource(Res.string.send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}
