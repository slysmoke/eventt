package org.eventt.features.orders

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import org.eventt.ui.common.formatIsk
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor

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
                .padding(horizontal = 8.dp, vertical = 3.dp),
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
                Icon(Icons.Default.ArrowDownward, contentDescription = "Undercut", modifier = Modifier.size(11.dp), tint = UNDERCUT_COLOR)
            }
            Text(
                order.typeName,
                style = MaterialTheme.typography.bodyMedium,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                modifier = Modifier.weight(1f),
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
                    "Best: ${formatIsk(bestSell)}",
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
                contentDescription = "Open in game & copy price",
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
            CompetitionService.Level.COLLECTING -> "collecting…" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            CompetitionService.Level.CALM -> "Calm" to PROFIT_COLOR
            CompetitionService.Level.CONTESTED -> "Contested" to UNDERCUT_COLOR
            CompetitionService.Level.BOT_WAR -> "Bot war" to LOSS_COLOR
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
                        add("top ${(stats.timeOnTopPct * 100).toInt()}%")
                        if (stats.competitors > 0) add("${stats.competitors} ${if (stats.competitors == 1) "rival" else "rivals"}")
                        // Median ticks I survive on top, in wall-clock terms (one tick ≈ 5 min).
                        stats.medianBeatTicks?.let { add("~${(it * 5).toInt()}m") }
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
@Composable
private fun ViewInMarketButton(typeId: Int) {
    IconButton(modifier = Modifier.size(20.dp), onClick = { AppState.openInMarket(typeId) }) {
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = "View in Market",
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
        Column(modifier = Modifier.padding(10.dp).widthIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val levelHint =
                when (stats.level) {
                    CompetitionService.Level.COLLECTING -> {
                        "Collecting data — under an hour of snapshots so far. Stats build up while the app is running."
                    }

                    CompetitionService.Level.CALM -> {
                        "Calm — you hold the top most of the time, or nobody competes at all."
                    }

                    CompetitionService.Level.CONTESTED -> {
                        "Contested — you're being undercut at a human pace."
                    }

                    CompetitionService.Level.BOT_WAR -> {
                        "Bot war — near-instant re-undercuts around the clock. Fighting this with relists mostly burns fees."
                    }
                }
            Text(
                "$levelLabel — snapshots of this order book are taken every ~5 min (ESI tick), kept for 7 days.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(levelHint, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            TooltipStatLine(
                "top ${(stats.timeOnTopPct * 100).toInt()}%",
                "share of time your order was the best price — i.e. the one actually selling",
            )
            TooltipStatLine(
                "${stats.competitors} ${if (stats.competitors == 1) "rival" else "rivals"}",
                "distinct competing orders seen at the top (price edits keep an order's id, so this ≈ people)",
            )
            stats.medianBeatTicks?.let {
                TooltipStatLine("~${(it * 5).toInt()}m", "median time you keep the top before someone retakes it")
            }
            stats.fastBeatShare?.let {
                TooltipStatLine(
                    "${(it * 100).toInt()}% instant",
                    "of the times you were beaten, this share happened by the very next 5-min tick",
                )
            }
            if (stats.beatHourCoverage > 0) {
                TooltipStatLine(
                    "${stats.beatHourCoverage}/24 hours",
                    "distinct UTC hours of day with a beat — 16+ with instant beats means a bot, humans sleep",
                )
            }
            TooltipStatLine("${stats.ticks} ticks", "sample size behind all of the above (~${stats.ticks * 5 / 60}h observed)")
        }
    }
}

@Composable
private fun TooltipStatLine(
    value: String,
    explanation: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(80.dp))
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
                "~$updatesRemaining left",
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
                .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(3f).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatusDot(order.state)
            if (isOverbid) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Overbid", modifier = Modifier.size(11.dp), tint = UNDERCUT_COLOR)
            }
            Text(
                order.typeName,
                style = MaterialTheme.typography.bodyMedium,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ViewInMarketButton(order.typeId)
        }

        // Price column: order price + competing price below if overbid
        Column(modifier = Modifier.weight(2.4f)) {
            Text(formatIsk(order.price), style = MaterialTheme.typography.bodyMedium, color = if (isOverbid) UNDERCUT_COLOR else BUY_COLOR)
            val bestBuy = comparison?.bestBuy
            if (isOverbid && bestBuy != null) {
                Text(
                    "Best: ${formatIsk(bestBuy)}",
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
                contentDescription = "Open in game & copy price",
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            order.typeName,
            modifier = Modifier.weight(3f),
            style = MaterialTheme.typography.bodyMedium,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Text(
            if (order.isBuyOrder) "Buy" else "Sell",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (order.isBuyOrder) BUY_COLOR else SELL_COLOR,
        )
        Text(
            effectiveState.split("_").joinToString(" ") { it.replaceFirstChar(Char::uppercase) },
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.typeName,
            modifier = Modifier.weight(3f),
            style = MaterialTheme.typography.bodyMedium,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Text(formatNumber(item.remainingQty), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodyMedium)
        val daysHeld = item.daysHeld
        Text(
            daysHeld?.let { "${it}d" } ?: "—",
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
            profitPerUnit?.let { formatIsk(it) } ?: "—",
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
                contentDescription = "Write off",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
