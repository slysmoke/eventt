package org.eventt.features.market

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.eventt.market.generated.resources.*
import org.eventt.ui.common.Tip
import org.eventt.ui.common.formatPriceAbbr
import org.eventt.ui.common.formatVolume
import org.eventt.ui.common.onRightClick
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.stringResource
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.Locale

// ─── Table headers ────────────────────────────────────────────────────────

@Composable
internal fun StationHeader(
    sort: StationSortCol,
    asc: Boolean,
    onSort: (StationSortCol) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.width(28.dp),
            )
            ACol(stringResource(Res.string.col_item), StationSortCol.NAME, sort, asc, onSort, Modifier.weight(1f))
            ACol(
                stringResource(Res.string.col_buy_at),
                StationSortCol.BUY_PRICE,
                sort,
                asc,
                onSort,
                Modifier.width(95.dp),
                tooltip = stringResource(Res.string.tip_st_buy_at),
            )
            ACol(
                stringResource(Res.string.col_sell_at),
                StationSortCol.SELL_PRICE,
                sort,
                asc,
                onSort,
                Modifier.width(95.dp),
                tooltip = stringResource(Res.string.tip_st_sell_at),
            )
            ACol(
                stringResource(Res.string.col_margin),
                StationSortCol.MARGIN,
                sort,
                asc,
                onSort,
                Modifier.width(65.dp),
                tooltip = stringResource(Res.string.tip_st_margin),
            )
            ACol(
                stringResource(Res.string.col_roi),
                StationSortCol.ROI,
                sort,
                asc,
                onSort,
                Modifier.width(65.dp),
                tooltip = stringResource(Res.string.tip_st_roi),
            )
            ACol(
                stringResource(Res.string.col_net_unit),
                StationSortCol.NET_PROFIT,
                sort,
                asc,
                onSort,
                Modifier.width(95.dp),
                tooltip = stringResource(Res.string.tip_st_net),
            )
            ACol(
                stringResource(Res.string.col_7d),
                StationSortCol.TREND_7D,
                sort,
                asc,
                onSort,
                Modifier.width(65.dp),
                tooltip = stringResource(Res.string.tip_st_7d),
            )
            ACol(
                stringResource(Res.string.col_vol_day),
                StationSortCol.VOLUME,
                sort,
                asc,
                onSort,
                Modifier.width(75.dp),
                tooltip = stringResource(Res.string.tip_st_vol),
            )
            ACol(
                stringResource(Res.string.col_est_daily),
                StationSortCol.DAILY_PROFIT,
                sort,
                asc,
                onSort,
                Modifier.width(95.dp),
                tooltip = stringResource(Res.string.tip_st_est_daily),
            )
            Tip(stringResource(Res.string.tip_st_orders), Modifier.width(95.dp)) {
                Text(
                    stringResource(Res.string.orders_sell_buy),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
internal fun RegionHeader(
    sort: RegionSortCol,
    asc: Boolean,
    onSort: (RegionSortCol) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.width(28.dp),
            )
            ACol(stringResource(Res.string.col_item), RegionSortCol.NAME, sort, asc, onSort, Modifier.weight(1f))
            ACol(
                stringResource(Res.string.col_buy),
                RegionSortCol.BUY_PRICE,
                sort,
                asc,
                onSort,
                Modifier.width(95.dp),
                tooltip = stringResource(Res.string.tip_ir_buy),
            )
            ACol(
                stringResource(Res.string.col_sell),
                RegionSortCol.SELL_PRICE,
                sort,
                asc,
                onSort,
                Modifier.width(95.dp),
                tooltip = stringResource(Res.string.tip_ir_sell),
            )
            ACol(
                stringResource(Res.string.col_margin),
                RegionSortCol.MARGIN,
                sort,
                asc,
                onSort,
                Modifier.width(65.dp),
                tooltip = stringResource(Res.string.tip_ir_margin),
            )
            ACol(
                stringResource(Res.string.col_roi),
                RegionSortCol.ROI,
                sort,
                asc,
                onSort,
                Modifier.width(65.dp),
                tooltip = stringResource(Res.string.tip_ir_roi),
            )
            ACol(
                stringResource(Res.string.col_m3_unit),
                RegionSortCol.ITEM_VOL,
                sort,
                asc,
                onSort,
                Modifier.width(70.dp),
                tooltip = stringResource(Res.string.tip_ir_m3),
            )
            ACol(
                stringResource(Res.string.col_ship_unit),
                RegionSortCol.SHIPPING,
                sort,
                asc,
                onSort,
                Modifier.width(90.dp),
                tooltip = stringResource(Res.string.tip_ir_ship),
            )
            ACol(
                stringResource(Res.string.col_net_unit),
                RegionSortCol.NET_PROFIT,
                sort,
                asc,
                onSort,
                Modifier.width(95.dp),
                tooltip = stringResource(Res.string.tip_ir_net),
            )
            ACol(
                stringResource(Res.string.col_7d),
                RegionSortCol.TREND_7D,
                sort,
                asc,
                onSort,
                Modifier.width(65.dp),
                tooltip = stringResource(Res.string.tip_ir_7d),
            )
            ACol(
                stringResource(Res.string.col_vol_day),
                RegionSortCol.VOLUME,
                sort,
                asc,
                onSort,
                Modifier.width(70.dp),
                tooltip = stringResource(Res.string.tip_ir_vol),
            )
            ACol(
                stringResource(Res.string.col_qty_to_buy),
                RegionSortCol.QTY_TO_BUY,
                sort,
                asc,
                onSort,
                Modifier.width(85.dp),
                tooltip = stringResource(Res.string.tip_ir_qty),
            )
            ACol(
                stringResource(Res.string.col_net_vol),
                RegionSortCol.NET_VOL,
                sort,
                asc,
                onSort,
                Modifier.width(95.dp),
                tooltip = stringResource(Res.string.tip_ir_net_vol),
            )
        }
    }
}

// ─── Table rows ───────────────────────────────────────────────────────────

private val STATION_ACTIVE_IN_GAME = Color(0xFF4A90D9) // blue — currently open in EVE client via the hotkey

@Composable
internal fun StationRow(
    opp: StationOpportunity,
    index: Int,
    selected: Boolean,
    isActiveInGame: Boolean = false,
    volCapEnabled: Boolean = false,
    volCapPct: Double = 100.0,
    onShowDetails: (Int) -> Unit = {},
) {
    val effVol = stationEffVol(opp, volCapEnabled, volCapPct)
    val bg =
        when {
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            index % 2 == 1 -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.025f)
            else -> Color.Transparent
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(bg)
                .onRightClick { onShowDetails(opp.typeId) }
                .then(if (isActiveInGame) Modifier.border(BorderStroke(1.dp, STATION_ACTIVE_IN_GAME)) else Modifier)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.width(28.dp),
        )
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                opp.typeName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
                color = if (isActiveInGame) STATION_ACTIVE_IN_GAME else Color.Unspecified,
                fontWeight = if (isActiveInGame) FontWeight.Bold else FontWeight.Normal,
            )
            IconButton(onClick = { onShowDetails(opp.typeId) }, modifier = Modifier.size(20.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ShowChart,
                    contentDescription = stringResource(Res.string.open_chart),
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        PriceText(opp.bestBuy, negativeColor, Modifier.width(95.dp))
        PriceText(opp.bestSell, positiveColor, Modifier.width(95.dp))
        MarginText(opp.marginPct, Modifier.width(65.dp))
        MarginText(opp.roiPct, Modifier.width(65.dp))
        PriceText(opp.netProfit, positiveColor, Modifier.width(95.dp))
        TrendText(opp.priceChange7d, Modifier.width(65.dp))
        Text(
            if (effVol > 0) formatVolume(effVol) else "—",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.width(75.dp),
        )
        Text(
            if (opp.netProfit * effVol > 0) formatPriceAbbr(opp.netProfit * effVol) else "—",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(95.dp),
        )
        Text(
            stringResource(Res.string.order_counts, opp.sellOrderCount, opp.buyOrderCount),
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.width(95.dp),
        )
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
}

@Composable
internal fun RegionRow(
    opp: RegionOpportunity,
    index: Int,
    tradeType: InterRegionTradeType,
    selected: Boolean,
    volCapEnabled: Boolean = false,
    volCapPct: Double = 100.0,
    isActiveInGame: Boolean = false,
    onShowDetails: (Int) -> Unit = {},
) {
    val effVol = regionEffVol(opp, volCapEnabled, volCapPct)
    val qtyToBuy = regionFinalVol(opp, tradeType, volCapEnabled, volCapPct)
    val dailyProfit = regionDailyProfit(opp, tradeType, volCapEnabled, volCapPct)
    val bg =
        when {
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            index % 2 == 1 -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.025f)
            else -> Color.Transparent
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(bg)
                .onRightClick { onShowDetails(opp.typeId) }
                .then(if (isActiveInGame) Modifier.border(BorderStroke(1.dp, STATION_ACTIVE_IN_GAME)) else Modifier)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.width(28.dp),
        )
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                opp.typeName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
                color = if (isActiveInGame) STATION_ACTIVE_IN_GAME else Color.Unspecified,
                fontWeight = if (isActiveInGame) FontWeight.Bold else FontWeight.Normal,
            )
            IconButton(onClick = { onShowDetails(opp.typeId) }, modifier = Modifier.size(20.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ShowChart,
                    contentDescription = stringResource(Res.string.open_chart),
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(Modifier.width(95.dp)) {
            PriceText(opp.buyPrice, Color.White, Modifier.fillMaxWidth())
            Avg7dDeviationText(opp.buyVsAvg7dPct)
        }
        Column(Modifier.width(95.dp)) {
            PriceText(opp.sellPrice, Color.White, Modifier.fillMaxWidth())
            Avg7dDeviationText(opp.sellVsAvg7dPct)
        }
        MarginText(opp.marginPct, Modifier.width(65.dp))
        MarginText(opp.roiPct, Modifier.width(65.dp))
        Text(
            formatVolume(opp.itemVolumeM3),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.width(70.dp),
        )
        PriceText(opp.shippingCostPerUnit, warningColor, Modifier.width(90.dp))
        PriceText(opp.netProfit, positiveColor, Modifier.width(95.dp), bold = true)
        TrendText(opp.priceChange7d, Modifier.width(65.dp))
        Text(
            if (effVol > 0) formatVolume(effVol) else "—",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.width(70.dp),
        )
        Text(
            if (qtyToBuy > 0) formatVolume(qtyToBuy) else "—",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(85.dp),
        )
        PriceText(dailyProfit, Color(0xFF4DABF7), Modifier.width(95.dp), bold = true)
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
}

// ─── Shared UI helpers ────────────────────────────────────────────────────

@Composable
private fun <T> ACol(
    label: String,
    col: T,
    current: T,
    asc: Boolean,
    onSort: (T) -> Unit,
    modifier: Modifier = Modifier,
    tooltip: String? = null,
) {
    Tip(tooltip, modifier) {
        val active = col == current
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onSort(col) }.padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
            )
            if (active) {
                Icon(
                    if (asc) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    null,
                    Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PriceText(
    value: Double,
    color: Color,
    modifier: Modifier,
    bold: Boolean = false,
) {
    Text(
        formatPriceAbbr(value),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        modifier = modifier,
    )
}

@Composable
private fun TrendText(
    changePct: Double,
    modifier: Modifier,
) {
    if (changePct.isNaN()) {
        Text(
            "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = modifier,
        )
        return
    }
    val positive = changePct >= 0
    val color = if (positive) positiveColor else negativeColor
    val arrow = if (positive) "▲" else "▼"
    Text(
        "$arrow ${String.format(Locale.US, "%.1f", Math.abs(changePct))}%",
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier,
    )
}

/**
 * Small "vs 7d avg" line shown under a Buy/Sell price cell — see [compute7dAvgDeviation]. Colored
 * by how far above the 7-day average the price sits, not by whether that's "good" for a buyer vs
 * seller: below average is always green, 0-10% above is normal (white), 11-20% orange, beyond
 * that red. Renders nothing while there's no history yet, rather than a placeholder dash — this
 * is a secondary annotation under the price, not its own column that needs to hold a slot.
 */
@Composable
private fun Avg7dDeviationText(deviationPct: Double) {
    if (deviationPct.isNaN()) return
    val color =
        when {
            deviationPct < 0 -> positiveColor
            deviationPct <= 10.0 -> Color.White
            deviationPct <= 20.0 -> warningColor
            else -> negativeColor
        }
    Text(
        stringResource(Res.string.vs_7d, (if (deviationPct >= 0) "+" else "") + String.format(Locale.US, "%.1f", deviationPct)),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
private fun MarginText(
    pct: Double,
    modifier: Modifier,
) {
    val color =
        when {
            pct >= 20 -> positiveColor
            pct >= 10 -> warningColor
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        }
    Text(
        "${String.format(Locale.US, "%.1f", pct)}%",
        style = MaterialTheme.typography.bodySmall,
        color = color,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}

@Composable
internal fun AnalysisEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: String,
    secondary: String,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier =
                    Modifier
                        .size(72.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            }
            Text(primary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}

// ─── Drag-select helpers ──────────────────────────────────────────────────

internal fun itemIndexAt(
    y: Float,
    state: LazyListState,
): Int? =
    state.layoutInfo.visibleItemsInfo
        .firstOrNull { y >= it.offset && y < it.offset + it.size }
        ?.index

// ─── Selection bar ────────────────────────────────────────────────────────

@Composable
internal fun SelectionBar(
    count: Int,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.CheckBox,
                null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                stringResource(Res.string.n_selected, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onCopy,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(Res.string.copy_list), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(
                onClick = onClear,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text(stringResource(Res.string.clear), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
}

internal fun copyToClipboard(text: String) {
    val sel = StringSelection(text)
    Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
}
