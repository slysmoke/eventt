package org.eventt.features.orders

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eventt.core.database.AppState
import org.eventt.core.database.OrderHistoryDao
import org.eventt.orders.generated.resources.*
import org.eventt.ui.common.formatIsk
import org.eventt.ui.common.onRightClick
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// ── Rows ──────────────────────────────────────────────────────────────────

@Composable
internal fun SellOrderRow(
    metrics: SellOrderMetrics,
    isSelected: Boolean,
    isActiveInGame: Boolean,
    onSelect: () -> Unit,
    onAction: () -> Unit,
) {
    val order = metrics.order
    val comparison = metrics.comparison
    val costBasis = metrics.costBasis
    val isEstimated = metrics.isEstimated
    val totalProfit = metrics.totalProfit
    val marginPct = metrics.marginPct
    val bestMarginPct = metrics.bestMarginPct
    val isBeaten = metrics.isBeaten
    val profitColor = totalProfit?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val bestMarginColor = bestMarginPct?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val rowBg =
        when {
            isActiveInGame -> ACTIVE_IN_GAME.copy(alpha = 0.15f)
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else -> Color.Transparent
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(rowBg)
                .clickable { onSelect() }
                .onRightClick {
                    ItemDetailRequest.target =
                        ItemDetailTarget(order.typeId, order.typeName, order.regionId, order.locationId.takeIf { !order.isBuyOrder })
                }.padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name + status dot
        Row(
            modifier = Modifier.weight(3f).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatusDot(order.state)
            if (isBeaten) {
                Icon(
                    Icons.Default.ArrowDownward,
                    contentDescription = stringResource(Res.string.undercut),
                    modifier = Modifier.size(11.dp),
                    tint = UNDERCUT_COLOR,
                )
            }
            Text(
                order.typeName,
                style = MaterialTheme.typography.bodyMedium,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ItemDetailButton(
                ItemDetailTarget(
                    order.typeId,
                    order.typeName,
                    order.regionId,
                    // Sell competition is per-station; buy orders compete region-wide.
                    order.locationId.takeIf { !order.isBuyOrder },
                ),
            )
            ViewInMarketButton(order.typeId)
        }

        Text(
            costBasis?.let { if (isEstimated) "~${formatIsk(it)}" else formatIsk(it) } ?: "—",
            modifier = Modifier.weight(1.8f),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (isEstimated) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )

        // Price column: order price + competing price below if beaten
        Column(modifier = Modifier.weight(2.4f)) {
            Text(
                formatIsk(order.price),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isBeaten) UNDERCUT_COLOR else SELL_COLOR,
            )
            val bestSell = comparison?.bestSell
            if (isBeaten && bestSell != null) {
                Text(
                    stringResource(Res.string.best_price, formatIsk(bestSell)),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = UNDERCUT_COLOR.copy(alpha = 0.8f),
                )
            }
        }

        RelistCell(order.relistCount, order.relistFeesPaid, metrics.updatesRemaining, modifier = Modifier.weight(1.8f))

        Text(
            totalProfit?.let { (if (isEstimated) "~" else "") + formatIsk(it) } ?: "—",
            modifier = Modifier.weight(1.8f),
            style = MaterialTheme.typography.bodySmall,
            color = if (isEstimated) profitColor.copy(alpha = 0.7f) else profitColor,
            fontWeight = if (totalProfit != null) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            marginPct?.let { (if (isEstimated) "~" else "") + "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall,
            color = if (isEstimated) profitColor.copy(alpha = 0.7f) else profitColor,
        )
        Text(
            bestMarginPct?.let { "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodySmall,
            color = bestMarginColor,
        )
        VolumeBar(order.volumeRemaining, order.volumeTotal, isSell = true, modifier = Modifier.weight(2.5f).padding(horizontal = 4.dp))
        Text(formatIsk(order.total), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
        CompetitionCell(metrics.competition, modifier = Modifier.weight(1.8f))
        Text(
            formatDuration(order.timeLeftSeconds),
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.bodySmall,
            color = timeLeftColor(order.timeLeftSeconds),
        )

        // Action button: open market in-game + copy overbid price
        IconButton(modifier = Modifier.size(36.dp), onClick = onAction) {
            Icon(
                Icons.Default.OpenInBrowser,
                contentDescription = stringResource(Res.string.open_in_game),
                modifier = Modifier.size(16.dp),
                tint =
                    when {
                        isActiveInGame -> ACTIVE_IN_GAME
                        isBeaten -> UNDERCUT_COLOR
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

/**
 * Two-line competition summary from a week of top-of-book snapshots (see CompetitionService):
 * a level word, then "time on top · rivals · median survival". "…" while the window is still
 * too thin to judge; "—" when there's no data at all (stats haven't been fetched yet).
 * Hovering explains every number in plain words.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompetitionCell(
    stats: CompetitionService.Stats?,
    modifier: Modifier = Modifier,
) {
    if (stats == null) {
        Text("—", modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val (label, color) =
        when (stats.level) {
            CompetitionService.Level.COLLECTING -> {
                stringResource(Res.string.comp_collecting) to
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            }

            CompetitionService.Level.CALM -> {
                stringResource(Res.string.comp_calm) to PROFIT_COLOR
            }

            CompetitionService.Level.CONTESTED -> {
                stringResource(Res.string.comp_contested) to UNDERCUT_COLOR
            }

            CompetitionService.Level.BOT_WAR -> {
                stringResource(Res.string.comp_bot_war) to LOSS_COLOR
            }
        }
    TooltipArea(
        tooltip = { CompetitionTooltip(stats, label) },
        modifier = modifier,
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.SemiBold)
            if (stats.level != CompetitionService.Level.COLLECTING) {
                val details =
                    buildList {
                        add(stringResource(Res.string.top_pct, (stats.timeOnTopPct * 100).toInt()))
                        if (stats.competitors > 0) add(pluralStringResource(Res.plurals.rivals_n, stats.competitors, stats.competitors))
                        // Median ticks I survive on top, in wall-clock terms (one tick ≈ 5 min).
                        stats.medianBeatTicks?.let { add(stringResource(Res.string.minutes_approx, (it * 5).toInt())) }
                    }.joinToString(" · ")
                Text(
                    details,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// Jumps to the Market tab pre-loaded with this item — AppState.pendingMarketTypeId is the
// cross-tab signal EventtApp/MarketBrowserScreen watch for this.
// Which item's detail dialog OrdersScreen should show. Module-level state rather than an onClick
// threaded through every table and row signature -- ponytail: one dialog at a time is all the UI
// ever needs.
internal data class ItemDetailTarget(
    val typeId: Int,
    val typeName: String,
    // 0 = unknown (history/inventory rows carry no region) -- OrdersScreen falls back to Jita.
    val regionId: Int = 0,
    val stationId: Long? = null,
)

internal object ItemDetailRequest {
    var target by mutableStateOf<ItemDetailTarget?>(null)
}

@Composable
internal fun ItemDetailButton(target: ItemDetailTarget) {
    IconButton(modifier = Modifier.size(20.dp), onClick = { ItemDetailRequest.target = target }) {
        Icon(
            Icons.AutoMirrored.Filled.ShowChart,
            contentDescription = stringResource(Res.string.open_chart),
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ViewInMarketButton(typeId: Int) {
    IconButton(modifier = Modifier.size(20.dp), onClick = { AppState.openInMarket(typeId) }) {
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = stringResource(Res.string.view_in_market),
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompetitionTooltip(
    stats: CompetitionService.Stats,
    levelLabel: String,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(10.dp).widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val levelHint =
                when (stats.level) {
                    CompetitionService.Level.COLLECTING -> {
                        stringResource(Res.string.hint_collecting)
                    }

                    CompetitionService.Level.CALM -> {
                        stringResource(Res.string.hint_calm)
                    }

                    CompetitionService.Level.CONTESTED -> {
                        stringResource(Res.string.hint_contested)
                    }

                    CompetitionService.Level.BOT_WAR -> {
                        stringResource(Res.string.hint_bot_war)
                    }
                }
            Text(
                stringResource(Res.string.tooltip_head, levelLabel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(levelHint, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            TooltipStatLine(
                stringResource(Res.string.top_pct, (stats.timeOnTopPct * 100).toInt()),
                stringResource(Res.string.tt_top_desc),
            )
            TooltipStatLine(
                pluralStringResource(Res.plurals.rivals_n, stats.competitors, stats.competitors),
                stringResource(Res.string.tt_rivals_desc),
            )
            stats.medianBeatTicks?.let {
                TooltipStatLine(stringResource(Res.string.minutes_approx, (it * 5).toInt()), stringResource(Res.string.tt_median_desc))
            }
            stats.fastBeatShare?.let {
                TooltipStatLine(
                    stringResource(Res.string.instant_pct, (it * 100).toInt()),
                    stringResource(Res.string.tt_instant_desc),
                )
            }
            if (stats.beatHourCoverage > 0) {
                TooltipStatLine(
                    stringResource(Res.string.hours_24, stats.beatHourCoverage),
                    stringResource(Res.string.tt_hours_desc),
                )
            }
            TooltipStatLine(stringResource(Res.string.ticks_n, stats.ticks), stringResource(Res.string.tt_ticks_desc, stats.ticks * 5 / 60))
        }
    }
}

@Composable
private fun TooltipStatLine(
    value: String,
    explanation: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp))
        Text(explanation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Modification fees paid so far ("N× · total") plus an estimated countdown to zero margin
// ("~N left"), stacked like the Price column's undercut sub-line. updatesRemaining is omitted
// (buy orders, or no cost basis to estimate against) rather than shown as a misleading "—".
@Composable
private fun RelistCell(
    relistCount: Int,
    relistFeesPaid: Double,
    updatesRemaining: Int?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            if (relistCount > 0) "$relistCount× · ${formatIsk(relistFeesPaid)}" else "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (updatesRemaining != null) {
            Text(
                stringResource(Res.string.left_approx, updatesRemaining),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
internal fun BuyOrderRow(
    metrics: BuyOrderMetrics,
    isSelected: Boolean,
    isActiveInGame: Boolean,
    onSelect: () -> Unit,
    onAction: () -> Unit,
) {
    val order = metrics.order
    val comparison = metrics.comparison
    val isOverbid = metrics.isOverbid
    val marginPct = metrics.marginPct
    val marginColor = marginPct?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val bestMarginPct = metrics.bestMarginPct
    val bestMarginColor = bestMarginPct?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val rowBg =
        when {
            isActiveInGame -> ACTIVE_IN_GAME.copy(alpha = 0.15f)
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else -> Color.Transparent
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(rowBg)
                .clickable { onSelect() }
                .onRightClick {
                    ItemDetailRequest.target =
                        ItemDetailTarget(order.typeId, order.typeName, order.regionId, order.locationId.takeIf { !order.isBuyOrder })
                }.padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(3f).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatusDot(order.state)
            if (isOverbid) {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = stringResource(Res.string.overbid),
                    modifier = Modifier.size(11.dp),
                    tint = UNDERCUT_COLOR,
                )
            }
            Text(
                order.typeName,
                style = MaterialTheme.typography.bodyMedium,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ItemDetailButton(
                ItemDetailTarget(
                    order.typeId,
                    order.typeName,
                    order.regionId,
                    // Sell competition is per-station; buy orders compete region-wide.
                    order.locationId.takeIf { !order.isBuyOrder },
                ),
            )
            ViewInMarketButton(order.typeId)
        }

        // Price column: order price + competing price below if overbid
        Column(modifier = Modifier.weight(2.4f)) {
            Text(formatIsk(order.price), style = MaterialTheme.typography.bodyMedium, color = if (isOverbid) UNDERCUT_COLOR else BUY_COLOR)
            val bestBuy = comparison?.bestBuy
            if (isOverbid && bestBuy != null) {
                Text(
                    stringResource(Res.string.best_price, formatIsk(bestBuy)),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = UNDERCUT_COLOR.copy(alpha = 0.8f),
                )
            }
        }

        // No "~N left" estimate here: unlike a sell order, a buy order has no committed cost
        // basis yet to measure remaining margin against, only a speculative future resale price.
        RelistCell(order.relistCount, order.relistFeesPaid, updatesRemaining = null, modifier = Modifier.weight(1.6f))

        Text(
            marginPct?.let { "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall,
            color = marginColor,
        )
        Text(
            bestMarginPct?.let { "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodySmall,
            color = bestMarginColor,
        )

        VolumeBar(order.volumeRemaining, order.volumeTotal, isSell = false, modifier = Modifier.weight(2.5f).padding(horizontal = 4.dp))
        Text(formatIsk(order.total), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
        CompetitionCell(metrics.competition, modifier = Modifier.weight(1.8f))
        Text(
            formatDuration(order.timeLeftSeconds),
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.bodySmall,
            color = timeLeftColor(order.timeLeftSeconds),
        )
        Text(
            formatDuration(order.orderAgeSeconds),
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        IconButton(modifier = Modifier.size(36.dp), onClick = onAction) {
            Icon(
                Icons.Default.OpenInBrowser,
                contentDescription = stringResource(Res.string.open_in_game),
                modifier = Modifier.size(16.dp),
                tint =
                    when {
                        isActiveInGame -> ACTIVE_IN_GAME
                        isOverbid -> UNDERCUT_COLOR
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@Composable
internal fun OrderHistoryRow(
    order: OrderHistoryDao.OrderHistoryRecord,
    pnl: Double?,
    marginPct: Double?,
) {
    val effectiveState = effectiveOrderState(order)
    val stateColor =
        when (effectiveState) {
            "fulfilled" -> positiveColor
            "partially_filled" -> Color(0xFF74C0FC)
            "cancelled" -> negativeColor
            else -> warningColor
        }
    val profitColor = pnl?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .onRightClick { ItemDetailRequest.target = ItemDetailTarget(order.typeId, order.typeName) }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(3f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                order.typeName,
                style = MaterialTheme.typography.bodyMedium,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            ItemDetailButton(ItemDetailTarget(order.typeId, order.typeName))
        }
        Text(
            if (order.isBuyOrder) stringResource(Res.string.buy) else stringResource(Res.string.sell),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (order.isBuyOrder) BUY_COLOR else SELL_COLOR,
        )
        Text(
            orderStateLabel(effectiveState),
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.bodySmall,
            color = stateColor,
        )
        Text(formatIsk(order.price), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
        Text(
            pnl?.let { formatIsk(it) } ?: "—",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = profitColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (pnl != null) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            marginPct?.let { "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall,
            color = profitColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${formatNumber(order.volumeRemaining)}/${formatNumber(order.volumeTotal)}",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            order.issued.take(16).replace("T", " "),
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            order.stationName,
            modifier = Modifier.weight(2.5f).padding(start = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}

@Composable
internal fun InventoryRow(
    item: CostBasisService.InventoryItem,
    sellPrice: Double?,
    isOwnListing: Boolean,
    realizedPnl: Double?,
    taxConfig: CostBasisService.TaxConfig,
    onWriteOff: () -> Unit,
) {
    val netSellPrice = sellPrice?.let { it * taxConfig.sellMultiplier }
    val profitPerUnit = netSellPrice?.let { it - item.avgCostBasis }
    val marginPct = profitPerUnit?.let { if (item.avgCostBasis > 0) it / item.avgCostBasis * 100 else null }
    val profitColor = profitPerUnit?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val realizedColor = realizedPnl?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .onRightClick { ItemDetailRequest.target = ItemDetailTarget(item.typeId, item.typeName) }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(3f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.typeName,
                style = MaterialTheme.typography.bodyMedium,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            ItemDetailButton(ItemDetailTarget(item.typeId, item.typeName))
        }
        Text(formatNumber(item.remainingQty), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodyMedium)
        val daysHeld = item.daysHeld
        Text(
            daysHeld?.let { stringResource(Res.string.days_short, it) } ?: "—",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            // Stale stock: capital stuck for over 30 days gets the same orange as beaten orders.
            color = if (daysHeld != null && daysHeld > 30) UNDERCUT_COLOR else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            formatIsk(item.avgCostBasis),
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(formatIsk(item.totalCostBasis), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
        Text(
            sellPrice?.let { formatIsk(it) } ?: "—",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = if (isOwnListing) SELL_COLOR else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // Whole stack at this sell price, net of tax/broker fee.
            profitPerUnit?.let { formatIsk(it * item.remainingQty) } ?: "—",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = profitColor,
            fontWeight = if (profitPerUnit != null) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            marginPct?.let { "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall,
            color = profitColor,
        )
        Text(
            realizedPnl?.let { formatIsk(it) } ?: "—",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = realizedColor,
            fontWeight = if (realizedPnl != null) FontWeight.SemiBold else FontWeight.Normal,
        )
        IconButton(modifier = Modifier.size(28.dp), onClick = onWriteOff) {
            Icon(
                Icons.Default.RemoveShoppingCart,
                contentDescription = stringResource(Res.string.write_off),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun orderStateLabel(state: String): String =
    when (state) {
        "fulfilled" -> stringResource(Res.string.st_fulfilled)
        "partially_filled" -> stringResource(Res.string.st_partially_filled)
        "cancelled" -> stringResource(Res.string.st_cancelled)
        "expired" -> stringResource(Res.string.st_expired)
        "active" -> stringResource(Res.string.st_active)
        else -> state.split("_").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
