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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import org.eventt.ui.common.SearchField
import org.eventt.ui.common.Tip
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

private enum class BrowseSortColumn { PRICE, QTY, SAVINGS, EXPIRY }

@Composable
fun BrowseScreen() {
    val scope = rememberCoroutineScope()
    var sideFilter by remember { mutableStateOf<OrderSide?>(null) }
    var orders by remember { mutableStateOf<List<NostrOrderModel>>(emptyList()) }
    var rows by remember { mutableStateOf<List<BrowseRow>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var sortColumn by remember { mutableStateOf(BrowseSortColumn.EXPIRY) }
    var sortDirection by remember { mutableStateOf(SortDirection.ASC) }
    var activeIdentityPubkey by remember { mutableStateOf<String?>(null) }
    // The viewer's own effective sales tax rate — BUY orders' savings badge needs it (see
    // SavingsBadgeService.computeSavings); defaults to the 8% base rate with no active identity.
    var viewerSalesTaxPct by remember { mutableStateOf(8.0) }

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

    fun toggleSort(
        column: BrowseSortColumn,
        defaultDirection: SortDirection,
    ) {
        if (sortColumn == column) {
            sortDirection = if (sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
        } else {
            sortColumn = column
            sortDirection = defaultDirection
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

    val filtered = if (searchQuery.isBlank()) rows else rows.filter { it.typeName.contains(searchQuery, ignoreCase = true) }
    val displayedRows =
        when (sortColumn) {
            BrowseSortColumn.PRICE -> filtered.sortedBy { it.order.price }
            BrowseSortColumn.QTY -> filtered.sortedBy { it.order.qtyRemaining }
            BrowseSortColumn.SAVINGS -> filtered.sortedBy { it.savings?.savingsPct ?: Double.NEGATIVE_INFINITY }
            BrowseSortColumn.EXPIRY -> filtered.sortedBy { it.order.expiration }
        }.let { if (sortDirection == SortDirection.DESC) it.reversed() else it }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sideFilter == null, onClick = { sideFilter = null }, label = { Text(stringResource(Res.string.all)) })
            FilterChip(selected = sideFilter == OrderSide.SELL, onClick = {
                sideFilter = OrderSide.SELL
            }, label = { Text(stringResource(Res.string.filter_selling)) })
            FilterChip(selected = sideFilter == OrderSide.BUY, onClick = {
                sideFilter = OrderSide.BUY
            }, label = { Text(stringResource(Res.string.filter_buying)) })
        }
        Spacer(Modifier.height(8.dp))
        SearchField(query = searchQuery, onQueryChange = { searchQuery = it }, placeholder = stringResource(Res.string.search_item))
        Spacer(Modifier.height(12.dp))
        if (displayedRows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (rows.isEmpty()) {
                        stringResource(Res.string.no_orders_yet)
                    } else {
                        stringResource(Res.string.no_orders_match, searchQuery)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            BrowseTableHeader(sortColumn, sortDirection, onSort = ::toggleSort)
            HorizontalDivider()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(displayedRows, key = { "${it.order.orderUuid}:${it.order.pubkey}" }) { row ->
                    BrowseTableRow(row, scope, isOwnOrder = row.order.pubkey == activeIdentityPubkey)
                }
            }
        }
    }
}

@Composable
private fun BrowseTableHeader(
    sortColumn: BrowseSortColumn,
    sortDirection: SortDirection,
    onSort: (BrowseSortColumn, SortDirection) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(24.dp))
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
            modifier = Modifier.width(130.dp),
        )
        SortHeaderCell(
            stringResource(Res.string.h_price),
            Modifier.width(130.dp),
            active = sortColumn == BrowseSortColumn.PRICE,
            direction = sortDirection,
        ) { onSort(BrowseSortColumn.PRICE, SortDirection.DESC) }
        SortHeaderCell(
            stringResource(Res.string.h_qty),
            Modifier.width(80.dp),
            active = sortColumn == BrowseSortColumn.QTY,
            direction = sortDirection,
        ) { onSort(BrowseSortColumn.QTY, SortDirection.DESC) }
        SortHeaderCell(
            stringResource(Res.string.h_savings),
            Modifier.width(100.dp),
            active = sortColumn == BrowseSortColumn.SAVINGS,
            direction = sortDirection,
            tooltip = stringResource(Res.string.tip_p_savings),
        ) { onSort(BrowseSortColumn.SAVINGS, SortDirection.DESC) }
        Tip(stringResource(Res.string.tip_p_trader)) {
            Text(
                stringResource(Res.string.h_trader),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(160.dp),
            )
        }
        SortHeaderCell(
            stringResource(Res.string.h_expires),
            Modifier.width(110.dp),
            active = sortColumn == BrowseSortColumn.EXPIRY,
            direction = sortDirection,
            tooltip = stringResource(Res.string.tip_p_expires),
        ) { onSort(BrowseSortColumn.EXPIRY, SortDirection.ASC) }
        Spacer(Modifier.width(110.dp))
    }
}

@Composable
private fun BrowseTableRow(
    row: BrowseRow,
    scope: kotlinx.coroutines.CoroutineScope,
    isOwnOrder: Boolean,
) {
    val order = row.order
    val side = remember(order.side) { OrderSide.valueOf(order.side.uppercase()) }
    var showRequestDialog by remember { mutableStateOf(false) }
    var requestSent by remember(order.orderUuid, order.pubkey) { mutableStateOf(false) }
    var requestError by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (side == OrderSide.SELL) Icons.Default.Sell else Icons.Default.ShoppingCart,
            null,
            modifier = Modifier.width(24.dp),
            tint = if (side == OrderSide.SELL) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OrderSideBadge(side)
                Text(
                    row.typeName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(modifier = Modifier.size(20.dp), onClick = { AppState.openInMarket(order.typeId) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(Res.string.view_in_market),
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ExpiringSoonLabel(order.expiration)
        }
        Text(
            row.regionName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(130.dp),
        )
        Text(
            String.format(Locale.US, "%,.2f", order.price),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(130.dp),
        )
        Text("${order.qtyRemaining}/${order.qtyTotal}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(80.dp))
        Box(modifier = Modifier.width(100.dp)) {
            row.savings?.let { s ->
                Text(
                    "${if (s.savingsPct >= 0) "-" else "+"}${String.format(Locale.US, "%.1f", abs(s.savingsPct))}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (s.savingsPct >= 0) positiveColor else MaterialTheme.colorScheme.error,
                )
            }
        }
        Column(modifier = Modifier.width(160.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    order.traderChar.ifBlank { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (order.traderChar.isNotBlank()) TraderInfoButton(order.traderChar, order.traderCharId)
            }
            Text(
                "(${orderOwnerRole(side)})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PresenceBadge(order.pubkey)
            if (row.confirmedTrades > 0) {
                Text(
                    stringResource(Res.string.confirmed_n, row.confirmedTrades),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Box(modifier = Modifier.width(110.dp)) {
            val remainingSeconds = order.expiration - System.currentTimeMillis() / 1000
            Text(
                stringResource(Res.string.time_left, formatDurationShort(remainingSeconds)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.width(110.dp)) {
            if (!isOwnOrder) {
                // A SELL order is fulfilled by buying it; a BUY order is fulfilled by selling into
                // it — the button must say which one *you'd* be doing, not just "Request", or
                // it's ambiguous which direction the trade goes in a mixed sell/buy list.
                OutlinedButton(onClick = { showRequestDialog = true }, enabled = !requestSent, contentPadding = COMPACT_BUTTON_PADDING) {
                    val label =
                        when {
                            requestSent -> stringResource(Res.string.sent)
                            side == OrderSide.SELL -> stringResource(Res.string.buy)
                            else -> stringResource(Res.string.sell)
                        }
                    Text(label)
                }
            }
        }
    }

    if (showRequestDialog) {
        RequestReservationDialog(
            order = order,
            side = side,
            error = requestError,
            onDismiss = {
                showRequestDialog = false
                requestError = null
            },
            onSend = { qty, note ->
                scope.launch(Dispatchers.IO) {
                    val tradeId = ReservationService.sendRequest(order, qty, note)
                    withContext(Dispatchers.Main) {
                        if (tradeId != null) {
                            requestSent = true
                            showRequestDialog = false
                        } else {
                            requestError = getString(Res.string.err_no_identity_add)
                        }
                    }
                }
            },
        )
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
