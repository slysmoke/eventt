package org.eventt.features.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eventt.core.database.OrderHistoryDao
import org.eventt.ui.common.formatIsk
import java.util.Locale

// ── Sub-components ────────────────────────────────────────────────────────

// Corp view: narrows the Sell/Buy tables (and the Ctrl+Z queue) down to one member's orders.
@Composable
internal fun IssuerFilterChip(
    issuerNames: Map<Int, String>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val sortedIssuers = remember(issuerNames) { issuerNames.entries.sortedBy { it.value } }

    Box {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            onClick = { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(
                    selected?.let { issuerNames[it] } ?: "All characters",
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (selected == null) Icon(Icons.Default.Check, null, Modifier.size(14.dp)) else Spacer(Modifier.size(14.dp))
                        Text("All characters")
                    }
                },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            sortedIssuers.forEach { (charId, name) ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (selected == charId) Icon(Icons.Default.Check, null, Modifier.size(14.dp)) else Spacer(Modifier.size(14.dp))
                            Text(name)
                        }
                    },
                    onClick = {
                        onSelect(charId)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun VolumeBar(
    remaining: Int,
    total: Int,
    isSell: Boolean,
    modifier: Modifier,
) {
    val fraction = if (total > 0) remaining.toFloat() / total.toFloat() else 0f
    val barColor = if (isSell) VOL_SELL else VOL_BUY

    Box(
        modifier =
            modifier
                .height(18.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(barColor)
                    .align(Alignment.CenterStart),
        )
        Text(
            "${formatNumber(remaining)}/${formatNumber(total)}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun StatusDot(state: String) {
    val color =
        when (state) {
            "active" -> Color(0xFF69DB7C)
            "cancelled" -> Color(0xFFFF6B6B)
            "expired" -> Color(0xFFFF6B6B)
            "pending" -> Color(0xFF74C0FC)
            else -> Color.Gray
        }
    Box(modifier = Modifier.size(7.dp).background(color, shape = MaterialTheme.shapes.small))
}

@Composable
internal fun SortHeader(
    label: String,
    col: SortCol,
    currentCol: SortCol,
    dir: SortDir,
    onSort: (SortCol) -> Unit,
    modifier: Modifier,
    rightAlign: Boolean = false,
) {
    val isActive = currentCol == col
    val labelColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.clickable { onSort(col) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (rightAlign) Arrangement.End else Arrangement.Start,
    ) {
        if (isActive && rightAlign) {
            Icon(
                if (dir ==
                    SortDir.ASC
                ) {
                    Icons.Default.ArrowUpward
                } else {
                    Icons.Default.ArrowDownward
                },
                null,
                Modifier.size(12.dp),
                tint = labelColor,
            )
            Spacer(Modifier.width(2.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = labelColor,
        )
        if (isActive && !rightAlign) {
            Spacer(Modifier.width(2.dp))
            Icon(
                if (dir ==
                    SortDir.ASC
                ) {
                    Icons.Default.ArrowUpward
                } else {
                    Icons.Default.ArrowDownward
                },
                null,
                Modifier.size(12.dp),
                tint = labelColor,
            )
        }
    }
}

@Composable
internal fun StaticHeader(
    label: String,
    modifier: Modifier,
    rightAlign: Boolean = false,
) {
    Text(
        label,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (rightAlign) TextAlign.End else TextAlign.Start,
    )
}

// ── Summary bars ──────────────────────────────────────────────────────────

@Composable
internal fun OrdersSummaryBar(
    orders: List<CharacterOrder>,
    inventory: Map<Int, CostBasisService.InventoryItem>?,
    taxConfig: CostBasisService.TaxConfig = CostBasisService.TaxConfig(),
) {
    val active = orders.filter { it.state == "active" }
    val totalRemain = active.sumOf { it.volumeRemaining }
    val totalEntered = active.sumOf { it.volumeTotal }
    val pct = if (totalEntered > 0) totalRemain * 100.0 / totalEntered else 0.0
    val totalIsk = active.sumOf { it.total }
    val totalProfit =
        inventory?.let {
            active
                .sumOf { o ->
                    val cb = it[o.typeId]?.avgCostBasis ?: return@sumOf 0.0
                    (o.price * taxConfig.sellMultiplier - cb) * o.volumeRemaining
                }.takeIf { it != 0.0 }
        }

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryItem("Active orders", active.size.toString())
            SummaryItem("Volume", "${formatNumber(totalRemain)}/${formatNumber(totalEntered)} (${String.format(Locale.US, "%.1f", pct)}%)")
            SummaryItem("Total ISK", "${formatIsk(totalIsk)} ISK")
            if (totalProfit != null) {
                val color = if (totalProfit >= 0) PROFIT_COLOR else LOSS_COLOR
                SummaryItem("Expected profit", "${formatIsk(totalProfit)} ISK", color)
            }
            val totalRelistFees = active.sumOf { it.relistFeesPaid }
            if (totalRelistFees > 0) {
                SummaryItem("Relist fees", "${formatIsk(totalRelistFees)} ISK", LOSS_COLOR)
            }
        }
    }
}

@Composable
internal fun HistorySummaryBar(
    orders: List<OrderHistoryDao.OrderHistoryRecord>,
    fifoResult: CostBasisService.FifoResult?,
) {
    val effectiveStates = orders.associateWith { effectiveOrderState(it) }
    val fulfilled = effectiveStates.count { it.value == "fulfilled" }
    val partiallyFilled = effectiveStates.count { it.value == "partially_filled" }
    val cancelled = effectiveStates.count { it.value == "cancelled" }
    val expired = effectiveStates.count { it.value == "expired" }
    val totalSold =
        orders
            .filter { !it.isBuyOrder && effectiveStates[it] in setOf("fulfilled", "partially_filled") }
            .sumOf { it.price * (it.volumeTotal - it.volumeRemaining) }
    val totalPnl = fifoResult?.totalRealizedPnl

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryItem("Total", orders.size.toString())
            SummaryItem("Fulfilled", fulfilled.toString())
            if (partiallyFilled > 0) SummaryItem("Partially filled", partiallyFilled.toString())
            SummaryItem("Cancelled", cancelled.toString())
            SummaryItem("Expired", expired.toString())
            if (totalSold > 0) SummaryItem("Sell volume", "${formatIsk(totalSold)} ISK")
            if (totalPnl != null) {
                val color = if (totalPnl >= 0) PROFIT_COLOR else LOSS_COLOR
                SummaryItem("Total realized P&L", "${formatIsk(totalPnl)} ISK", color)
            }
        }
    }
}

@Composable
internal fun InventorySummaryBar(
    fifoResult: CostBasisService.FifoResult,
    inventory: Map<Int, CostBasisService.InventoryItem>,
) {
    val totalCost = inventory.values.sumOf { it.totalCostBasis }
    val totalPnl = fifoResult.totalRealizedPnl
    val pnlColor = if (totalPnl >= 0) PROFIT_COLOR else LOSS_COLOR

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryItem("Items", inventory.size.toString())
            SummaryItem("Inventory cost", "${formatIsk(totalCost)} ISK")
            SummaryItem("All-time realized P&L", "${formatIsk(totalPnl)} ISK", pnlColor)
        }
    }
}

@Composable
private fun SummaryItem(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label + ":", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

// ── History P&L helper ────────────────────────────────────────────────────

internal fun historyPnl(
    order: OrderHistoryDao.OrderHistoryRecord,
    fifoResult: CostBasisService.FifoResult?,
): Pair<Double?, Double?> {
    if (order.isBuyOrder || fifoResult == null) return null to null
    val filled = order.volumeTotal - order.volumeRemaining
    if (filled <= 0) return null to null // nothing actually sold (cancelled/expired with no fills)

    val taxConfig = fifoResult.taxConfig
    val netSellPrice = order.price * taxConfig.sellMultiplier

    // Relist fees the order accumulated while active come straight off its profit — they were
    // paid to keep this exact order competitive, so they belong to it, not to general overhead.
    val fifoProfit = CostBasisService.pnlForOrder(fifoResult, order.typeId, order.issued, filled)
    if (fifoProfit != null) {
        val cb = netSellPrice - fifoProfit / filled
        val margin = if (cb > 0) (netSellPrice - cb) / cb * 100 else 0.0
        return (fifoProfit - order.relistFeesPaid) to margin
    }

    val cb = fifoResult.avgCostBasisForType(order.typeId) ?: return null to null
    val profit = (netSellPrice - cb) * filled - order.relistFeesPaid
    val margin = if (cb > 0) (netSellPrice - cb) / cb * 100 else 0.0
    return profit to margin
}

// ESI's order-history `state` is only ever "expired" or "cancelled" — CCP never reports
// "fulfilled", even when an order sold out completely before its duration ran out. The only
// reliable signal for that is volume_remain vs volume_total, so this derives a more useful
// four-way status ("fulfilled" / "partially_filled" / the raw "cancelled" / the raw "expired")
// that the raw ESI field alone can't distinguish.
internal fun effectiveOrderState(order: OrderHistoryDao.OrderHistoryRecord): String =
    when {
        order.volumeRemaining <= 0 -> "fulfilled"
        order.volumeRemaining < order.volumeTotal -> "partially_filled"
        else -> order.state // nothing filled at all: the raw "cancelled" or "expired" stands
    }

// ── Formatters ────────────────────────────────────────────────────────────

internal fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "—"
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

internal fun timeLeftColor(seconds: Long): Color =
    when {
        seconds <= 0 -> Color(0xFFFF6B6B)
        seconds < 86400 -> Color(0xFFFFD43B)
        else -> Color.Unspecified
    }

internal fun formatNumber(value: Int): String = "%,d".format(value)
