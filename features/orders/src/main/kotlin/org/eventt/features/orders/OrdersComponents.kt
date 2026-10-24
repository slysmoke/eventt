package org.eventt.features.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eventt.core.database.OrderHistoryDao
import org.eventt.orders.generated.resources.*
import org.eventt.ui.common.*
import org.eventt.ui.common.AXIS_COLOR
import org.eventt.ui.common.Tip
import org.eventt.ui.common.formatIsk
import org.eventt.ui.common.formatVolume
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

// ── Sub-components ────────────────────────────────────────────────────────

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
            "active" -> positiveColor
            "cancelled" -> negativeColor
            "expired" -> negativeColor
            "pending" -> Color(0xFF74C0FC)
            else -> Color.Gray
        }
    Box(modifier = Modifier.size(7.dp).background(color, shape = MaterialTheme.shapes.small))
}

@Composable
internal fun <T> SortHeader(
    label: String,
    col: T,
    currentCol: T,
    dir: SortDir,
    onSort: (T) -> Unit,
    modifier: Modifier,
    rightAlign: Boolean = false,
    tooltip: String? = null,
) {
    Tip(tooltip, modifier) {
        val isActive = currentCol == col
        Text(
            label + if (isActive) (if (dir == SortDir.ASC) " ▲" else " ▼") else "",
            modifier = Modifier.fillMaxWidth().clickable { onSort(col) }.padding(end = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (isActive) MaterialTheme.colorScheme.primary else AXIS_COLOR,
            textAlign = if (rightAlign) TextAlign.End else TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun StaticHeader(
    label: String,
    modifier: Modifier,
    rightAlign: Boolean = false,
    tooltip: String? = null,
) {
    Tip(tooltip, modifier) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = AXIS_COLOR,
            textAlign = if (rightAlign) TextAlign.End else TextAlign.Start,
            maxLines = 1,
        )
    }
}

// Buy tab: the standing-order threshold, picked from a few steps (0 = off).
@Composable
internal fun StandingThreshold(
    pct: Double,
    onChange: (Double) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Tip(stringResource(Res.string.tip_standing_threshold)) {
            Segment(
                if (pct >
                    0
                ) {
                    stringResource(Res.string.standing_threshold, pct.toInt().toString())
                } else {
                    stringResource(Res.string.standing_off)
                },
                pct > 0,
            ) { open = true }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(0.0, 2.0, 3.0, 5.0, 10.0, 15.0, 20.0, 30.0).forEach { v ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (v >
                                0
                            ) {
                                stringResource(Res.string.standing_threshold, v.toInt().toString())
                            } else {
                                stringResource(Res.string.standing_off)
                            },
                        )
                    },
                    onClick = {
                        open = false
                        onChange(v)
                    },
                )
            }
        }
    }
}

// ── KPI strips ────────────────────────────────────────────────────────────

@Composable
private fun KpiRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), MaterialTheme.shapes.extraSmall),
        content = content,
    )
}

// Sell or Buy tab: the active orders' size, what's beaten (buy: outbid, standing apart), volume
// left, expiring soon, and on the sell side what they'd make at today's prices.
@Composable
internal fun OrdersSummaryBar(
    orders: List<CharacterOrder>,
    inventory: Map<Int, CostBasisService.InventoryItem>?,
    taxConfig: CostBasisService.TaxConfig = CostBasisService.TaxConfig(),
    comparisons: Map<Pair<Int, Long>, MarketComparison> = emptyMap(),
) {
    val active = orders.filter { it.state == "active" }
    val isBuy = active.firstOrNull()?.isBuyOrder ?: (inventory == null)
    val totalRemain = active.sumOf { it.volumeRemaining.toLong() }
    val totalEntered = active.sumOf { it.volumeTotal.toLong() }
    val beaten = active.count { it.isBeatenBy(comparisons[it.typeId to it.locationId]) }
    val standing = if (isBuy) active.count { StandingOrders.isStanding(it.price, comparisons[it.typeId to it.locationId]?.bestBuy) } else 0
    val expiring = active.count { it.timeLeftSeconds in 1 until 86400 }
    val relistFees = active.sumOf { it.relistFeesPaid }
    val expectedProfit =
        inventory?.let { inv ->
            active.sumOf { o ->
                val cb = inv[o.typeId]?.avgCostBasis ?: return@sumOf 0.0
                (o.price * taxConfig.sellMultiplier - cb) * o.volumeRemaining
            }
        }
    KpiRow {
        Kpi(stringResource(Res.string.s_active_orders), active.size.toString(), null, null, null)
        Kpi(
            stringResource(if (isBuy) Res.string.s_escrow else Res.string.s_total_isk),
            formatIsk(active.sumOf { it.total }),
            null,
            null,
            null,
        )
        Kpi(
            stringResource(if (isBuy) Res.string.overbid else Res.string.undercut),
            beaten.toString(),
            if (active.isNotEmpty()) String.format(Locale.US, "%.0f%%", beaten * 100.0 / active.size) else null,
            null,
            null,
            valueColor = UNDERCUT_COLOR.takeIf { beaten > 0 },
        )
        if (isBuy) Kpi(stringResource(Res.string.s_standing), standing.toString(), null, null, null, Res.string.tip_o_distance_buy)
        Kpi(
            stringResource(Res.string.s_volume),
            "${formatVolume(totalRemain)} / ${formatVolume(totalEntered)}",
            if (totalEntered > 0) String.format(Locale.US, "%.1f%%", totalRemain * 100.0 / totalEntered) else null,
            null,
            null,
        )
        Kpi(
            stringResource(Res.string.s_expiring_24h),
            expiring.toString(),
            null,
            null,
            null,
            valueColor =
                warningColor.takeIf {
                    expiring >
                        0
                },
        )
        Kpi(
            stringResource(Res.string.s_relist_fees),
            formatIsk(relistFees),
            null,
            null,
            null,
            valueColor =
                LOSS_COLOR.takeIf {
                    relistFees >
                        0
                },
            last = expectedProfit == null,
        )
        if (expectedProfit != null) {
            Kpi(
                stringResource(Res.string.s_expected_profit),
                signed(expectedProfit),
                null,
                null,
                null,
                valueColor = pnlColor(expectedProfit),
                last = true,
            )
        }
    }
}

@Composable
internal fun HistorySummaryBar(
    orders: List<OrderHistoryDao.OrderHistoryRecord>,
    fifoResult: CostBasisService.FifoResult?,
) {
    val states = orders.map { effectiveOrderState(it) }
    val totalSold =
        orders
            .filter { !it.isBuyOrder && effectiveOrderState(it) in setOf("fulfilled", "partially_filled") }
            .sumOf { it.price * (it.volumeTotal - it.volumeRemaining) }
    val totalPnl = fifoResult?.totalRealizedPnl
    KpiRow {
        Kpi(stringResource(Res.string.s_total), orders.size.toString(), null, null, null)
        Kpi(stringResource(Res.string.s_fulfilled), states.count { it == "fulfilled" }.toString(), null, null, null)
        Kpi(stringResource(Res.string.s_partially_filled), states.count { it == "partially_filled" }.toString(), null, null, null)
        Kpi(stringResource(Res.string.s_cancelled), states.count { it == "cancelled" }.toString(), null, null, null)
        Kpi(stringResource(Res.string.s_expired), states.count { it == "expired" }.toString(), null, null, null)
        Kpi(stringResource(Res.string.s_sell_volume), formatIsk(totalSold), null, null, null)
        Kpi(
            stringResource(Res.string.s_total_realized),
            totalPnl?.let(::signed) ?: "—",
            null,
            null,
            null,
            valueColor = pnlColor(totalPnl),
            last = true,
        )
    }
}

@Composable
internal fun InventorySummaryBar(
    fifoResult: CostBasisService.FifoResult,
    inventory: Map<Int, CostBasisService.InventoryItem>,
) {
    val held = inventory.values.mapNotNull { it.daysHeld }
    KpiRow {
        Kpi(stringResource(Res.string.s_items), inventory.size.toString(), null, null, null)
        Kpi(stringResource(Res.string.s_inventory_cost), formatIsk(inventory.values.sumOf { it.totalCostBasis }), null, null, null)
        Kpi(
            stringResource(Res.string.s_held_30d),
            held.count { it > 30 }.toString(),
            null,
            null,
            null,
            valueColor =
                warningColor.takeIf {
                    held.any {
                        it >
                            30
                    }
                },
        )
        Kpi(
            stringResource(Res.string.s_all_time_realized),
            signed(fifoResult.totalRealizedPnl),
            null,
            null,
            null,
            valueColor = pnlColor(fifoResult.totalRealizedPnl),
            last = true,
        )
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

    // Profit and margin both come straight from the real matched sell transactions (see
    // CostBasisService.pnlForOrder) — not from order.price, which is just this order's last ESI
    // listing snapshot and drifts from the actual fill prices once a standing order gets
    // re-priced over its lifetime.
    CostBasisService.pnlForOrder(fifoResult, order.typeId, order.issued, filled)?.let { (profit, margin) ->
        return profit to margin
    }

    // Fallback: no realized-sell rows matched this order (e.g. it predates tracked wallet
    // history) — approximate from this type's average cost basis and the order's listed price.
    val taxConfig = fifoResult.taxConfig
    val netSellPrice = order.price * taxConfig.sellMultiplier - (fifoResult.sellRelistPerUnit[order.typeId] ?: 0.0)
    val cb = fifoResult.avgCostBasisForType(order.typeId) ?: return null to null
    val profit = (netSellPrice - cb) * filled
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

@Composable
internal fun timeLeftColor(seconds: Long): Color =
    when {
        seconds <= 0 -> negativeColor
        seconds < 86400 -> warningColor
        else -> Color.Unspecified
    }

internal fun formatNumber(value: Int): String = formatVolume(value.toLong())
