package org.eventt.features.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import org.eventt.core.database.OrderHistoryDao
import org.eventt.ui.common.ensureVisible

// ── Sorting ───────────────────────────────────────────────────────────────

// Buy-only: sorts by a value derived from market comparison, which doesn't live on CharacterOrder
// itself -- computed once per row in computeBuyMetrics, sorted here.
private fun sortBuyMetrics(
    list: List<BuyOrderMetrics>,
    col: SortCol,
    dir: SortDir,
): List<BuyOrderMetrics> {
    val sorted =
        when (col) {
            SortCol.NAME -> list.sortedBy { it.order.typeName }
            SortCol.PRICE -> list.sortedBy { it.order.price }
            SortCol.RELIST -> list.sortedBy { it.order.relistFeesPaid }
            SortCol.MARGIN -> list.sortedBy { it.marginPct ?: Double.NEGATIVE_INFINITY }
            SortCol.BEST_MARGIN -> list.sortedBy { it.bestMarginPct ?: Double.NEGATIVE_INFINITY }
            SortCol.VOLUME -> list.sortedBy { it.order.volumeRemaining }
            SortCol.TOTAL -> list.sortedBy { it.order.total }
            SortCol.TIME_LEFT -> list.sortedBy { it.order.timeLeftSeconds }
            SortCol.ORDER_AGE -> list.sortedBy { it.order.orderAgeSeconds }
            SortCol.COST, SortCol.PROFIT -> list // Sell-only columns, not shown here
        }
    return if (dir == SortDir.DESC) sorted.reversed() else sorted
}

// Sell-only: sorts by a value derived from cost basis / market comparison, neither of which lives
// on CharacterOrder itself -- computed once per row in computeSellMetrics, sorted here.
private fun sortSellMetrics(
    list: List<SellOrderMetrics>,
    col: SortCol,
    dir: SortDir,
): List<SellOrderMetrics> {
    val sorted =
        when (col) {
            SortCol.NAME -> list.sortedBy { it.order.typeName }
            SortCol.COST -> list.sortedBy { it.costBasis ?: Double.NEGATIVE_INFINITY }
            SortCol.PRICE -> list.sortedBy { it.order.price }
            SortCol.RELIST -> list.sortedBy { it.order.relistFeesPaid }
            SortCol.PROFIT -> list.sortedBy { it.totalProfit ?: Double.NEGATIVE_INFINITY }
            SortCol.MARGIN -> list.sortedBy { it.marginPct ?: Double.NEGATIVE_INFINITY }
            SortCol.BEST_MARGIN -> list.sortedBy { it.bestMarginPct ?: Double.NEGATIVE_INFINITY }
            SortCol.VOLUME -> list.sortedBy { it.order.volumeRemaining }
            SortCol.TIME_LEFT -> list.sortedBy { it.order.timeLeftSeconds }
            SortCol.TOTAL, SortCol.ORDER_AGE -> list // Buy-only columns, not shown here
        }
    return if (dir == SortDir.DESC) sorted.reversed() else sorted
}

// ── Tables ────────────────────────────────────────────────────────────────

// Every per-row derived value the Sell table shows or sorts by, computed once so sorting doesn't
// have to re-derive it and rows don't duplicate the math.
internal data class SellOrderMetrics(
    val order: CharacterOrder,
    val comparison: MarketComparison?,
    val costBasis: Double?,
    val isEstimated: Boolean,
    val totalProfit: Double?,
    val marginPct: Double?,
    val bestMarginPct: Double?,
    val updatesRemaining: Int?,
    // Beaten: another sell order at the same station is currently cheaper than ours.
    val isBeaten: Boolean,
)

private fun computeSellMetrics(
    order: CharacterOrder,
    inventory: Map<Int, CostBasisService.InventoryItem>,
    historyCostBasis: Map<Int, Double>,
    taxConfig: CostBasisService.TaxConfig,
    comparisons: Map<Pair<Int, Long>, MarketComparison>,
    relistDiscountPct: Double,
): SellOrderMetrics {
    val comparison = comparisons[order.typeId to order.locationId]
    val costBasis = inventory[order.typeId]?.avgCostBasis ?: historyCostBasis[order.typeId]
    val isEstimated = inventory[order.typeId] == null && costBasis != null
    val netSellPrice = order.price * taxConfig.sellMultiplier
    // Whole remaining position, not per-unit: profit/margin should reflect what this order
    // actually nets after the relist fees already sunk into it, not just its current listed price.
    val totalProfit = costBasis?.let { order.volumeRemaining * (netSellPrice - it) - order.relistFeesPaid }
    val marginPct =
        costBasis?.let { cb ->
            totalProfit?.let { p -> if (cb > 0 && order.volumeRemaining > 0) p / (cb * order.volumeRemaining) * 100 else null }
        }
    // "If I matched the top competing sell price instead" against my own cost basis -- not the
    // region market-flip metric BuyOrderRow uses, since a sell order already owns the item.
    val bestMarginPct = sellMarginPct(comparison?.bestSell, costBasis, taxConfig)
    val updatesRemaining =
        OrderFeeService.estimateUpdatesRemaining(
            volumeRemaining = order.volumeRemaining,
            price = order.price,
            netSellPricePerUnit = netSellPrice,
            costBasis = costBasis,
            brokerFeePct = taxConfig.brokerFeePct,
            relistCount = order.relistCount,
            relistFeesPaid = order.relistFeesPaid,
            relistDiscountPct = relistDiscountPct,
        )
    val isBeaten = comparison?.bestSell != null && comparison.bestSell < order.price
    return SellOrderMetrics(order, comparison, costBasis, isEstimated, totalProfit, marginPct, bestMarginPct, updatesRemaining, isBeaten)
}

@Composable
internal fun SellOrdersTable(
    orders: List<CharacterOrder>,
    sortCol: SortCol,
    sortDir: SortDir,
    onSort: (SortCol) -> Unit,
    inventory: Map<Int, CostBasisService.InventoryItem>,
    taxConfig: CostBasisService.TaxConfig,
    historyCostBasis: Map<Int, Double>,
    comparisons: Map<Pair<Int, Long>, MarketComparison>,
    relistDiscountPct: Double,
    showBeatenOnly: Boolean,
    selectedOrderId: Long?,
    activeOrderId: Long?,
    onSelect: (Long) -> Unit,
    onAction: (CharacterOrder) -> Unit,
) {
    val metrics =
        remember(orders, inventory, historyCostBasis, taxConfig, comparisons, relistDiscountPct) {
            orders.map { computeSellMetrics(it, inventory, historyCostBasis, taxConfig, comparisons, relistDiscountPct) }
        }
    val visible = if (showBeatenOnly) metrics.filter { it.isBeaten } else metrics
    val sorted = sortSellMetrics(visible, sortCol, sortDir)

    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                    ).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortHeader("Name", SortCol.NAME, sortCol, sortDir, onSort, Modifier.weight(3f))
            SortHeader("Cost", SortCol.COST, sortCol, sortDir, onSort, Modifier.weight(1.8f))
            SortHeader("Price / Best", SortCol.PRICE, sortCol, sortDir, onSort, Modifier.weight(2.4f))
            SortHeader("Relist", SortCol.RELIST, sortCol, sortDir, onSort, Modifier.weight(1.8f))
            SortHeader("Profit", SortCol.PROFIT, sortCol, sortDir, onSort, Modifier.weight(1.8f))
            SortHeader("Margin", SortCol.MARGIN, sortCol, sortDir, onSort, Modifier.weight(1.2f))
            SortHeader("Best Margin", SortCol.BEST_MARGIN, sortCol, sortDir, onSort, Modifier.weight(1.4f))
            SortHeader("Volume", SortCol.VOLUME, sortCol, sortDir, onSort, Modifier.weight(2.5f))
            SortHeader("Time Left", SortCol.TIME_LEFT, sortCol, sortDir, onSort, Modifier.weight(1.5f))
            StaticHeader("", Modifier.width(36.dp))
        }
        HorizontalDivider()
        val listState = rememberLazyListState()
        // The hotkey cycles activeOrderId through the list, but the highlighted row moves
        // independently of scroll position — without this, cycling can walk the active row
        // off-screen with no visual indication of where it went.
        LaunchedEffect(activeOrderId, sorted) {
            val idx = sorted.indexOfFirst { it.order.orderId == activeOrderId }
            if (idx >= 0) listState.ensureVisible(idx)
        }
        LazyColumn(state = listState) {
            items(sorted, key = { it.order.orderId }) { m ->
                SellOrderRow(
                    metrics = m,
                    isSelected = selectedOrderId == m.order.orderId,
                    isActiveInGame = activeOrderId == m.order.orderId,
                    onSelect = { onSelect(m.order.orderId) },
                    onAction = { onAction(m.order) },
                )
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

// Every per-row derived value the Buy table shows or sorts by, computed once so sorting doesn't
// have to re-derive it and rows don't duplicate the math.
internal data class BuyOrderMetrics(
    val order: CharacterOrder,
    val comparison: MarketComparison?,
    val marginPct: Double?,
    val bestMarginPct: Double?,
    // Overbid: another buy order region-wide currently pays more than ours.
    val isOverbid: Boolean,
)

private fun computeBuyMetrics(
    order: CharacterOrder,
    taxConfig: CostBasisService.TaxConfig,
    comparisons: Map<Pair<Int, Long>, MarketComparison>,
): BuyOrderMetrics {
    val comparison = comparisons[order.typeId to order.locationId]
    // Margin if this order fills and the item is resold at the current best sell price.
    val marginPct = computeMarginPct(order.price, comparison?.bestSell, taxConfig)
    val bestMarginPct = computeBestMarginPct(comparison, taxConfig)
    val isOverbid = comparison?.bestBuy != null && comparison.bestBuy > order.price
    return BuyOrderMetrics(order, comparison, marginPct, bestMarginPct, isOverbid)
}

@Composable
internal fun BuyOrdersTable(
    orders: List<CharacterOrder>,
    sortCol: SortCol,
    sortDir: SortDir,
    onSort: (SortCol) -> Unit,
    taxConfig: CostBasisService.TaxConfig,
    comparisons: Map<Pair<Int, Long>, MarketComparison>,
    showOverbidOnly: Boolean,
    selectedOrderId: Long?,
    activeOrderId: Long?,
    onSelect: (Long) -> Unit,
    onAction: (CharacterOrder) -> Unit,
) {
    val metrics =
        remember(orders, taxConfig, comparisons) {
            orders.map { computeBuyMetrics(it, taxConfig, comparisons) }
        }
    val visible = if (showOverbidOnly) metrics.filter { it.isOverbid } else metrics
    val sorted = sortBuyMetrics(visible, sortCol, sortDir)

    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                    ).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortHeader("Name", SortCol.NAME, sortCol, sortDir, onSort, Modifier.weight(3f))
            SortHeader("Price / Best", SortCol.PRICE, sortCol, sortDir, onSort, Modifier.weight(2.4f))
            SortHeader("Relist", SortCol.RELIST, sortCol, sortDir, onSort, Modifier.weight(1.6f))
            SortHeader("Margin", SortCol.MARGIN, sortCol, sortDir, onSort, Modifier.weight(1.2f))
            SortHeader("Best Margin", SortCol.BEST_MARGIN, sortCol, sortDir, onSort, Modifier.weight(1.4f))
            SortHeader("Volume", SortCol.VOLUME, sortCol, sortDir, onSort, Modifier.weight(2.5f))
            SortHeader("Total", SortCol.TOTAL, sortCol, sortDir, onSort, Modifier.weight(2f))
            SortHeader("Time Left", SortCol.TIME_LEFT, sortCol, sortDir, onSort, Modifier.weight(1.5f))
            SortHeader("Order Age", SortCol.ORDER_AGE, sortCol, sortDir, onSort, Modifier.weight(1.5f))
            StaticHeader("", Modifier.width(36.dp))
        }
        HorizontalDivider()
        val listState = rememberLazyListState()
        // The hotkey cycles activeOrderId through the list, but the highlighted row moves
        // independently of scroll position — without this, cycling can walk the active row
        // off-screen with no visual indication of where it went.
        LaunchedEffect(activeOrderId, sorted) {
            val idx = sorted.indexOfFirst { it.order.orderId == activeOrderId }
            if (idx >= 0) listState.ensureVisible(idx)
        }
        LazyColumn(state = listState) {
            items(sorted, key = { it.order.orderId }) { m ->
                BuyOrderRow(
                    metrics = m,
                    isSelected = selectedOrderId == m.order.orderId,
                    isActiveInGame = activeOrderId == m.order.orderId,
                    onSelect = { onSelect(m.order.orderId) },
                    onAction = { onAction(m.order) },
                )
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
internal fun OrderHistoryTable(
    orders: List<OrderHistoryDao.OrderHistoryRecord>,
    fifoResult: CostBasisService.FifoResult?,
) {
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                    ).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaticHeader("Name", Modifier.weight(3f))
            StaticHeader("Type", Modifier.weight(1f))
            StaticHeader("State", Modifier.weight(1.5f))
            StaticHeader("Price", Modifier.weight(2f))
            StaticHeader("Profit", Modifier.weight(2f))
            StaticHeader("Margin", Modifier.weight(1.2f))
            StaticHeader("Volume", Modifier.weight(2f))
            StaticHeader("Issued", Modifier.weight(2f))
            StaticHeader("Station", Modifier.weight(2.5f))
        }
        HorizontalDivider()
        LazyColumn {
            items(orders, key = { it.orderId }) { order ->
                val (pnl, margin) = historyPnl(order, fifoResult)
                OrderHistoryRow(order, pnl, margin)
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
internal fun InventoryTable(
    inventory: Map<Int, CostBasisService.InventoryItem>,
    sellOrders: List<CharacterOrder>,
    fifoResult: CostBasisService.FifoResult?,
    marketPrices: Map<Int, Double>,
) {
    val sellByType = sellOrders.filter { !it.isBuyOrder && it.state == "active" }.groupBy { it.typeId }
    val realizedByType = fifoResult?.realizedByType ?: emptyMap()
    val items = inventory.values.sortedBy { it.typeName }

    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                    ).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaticHeader("Name", Modifier.weight(3f))
            StaticHeader("Qty", Modifier.weight(1.5f))
            StaticHeader("Age", Modifier.weight(1f))
            StaticHeader("Avg Cost", Modifier.weight(2f))
            StaticHeader("Total Cost", Modifier.weight(2f))
            StaticHeader("Sell Price", Modifier.weight(2f))
            StaticHeader("Profit/unit", Modifier.weight(2f))
            StaticHeader("Margin", Modifier.weight(1.2f))
            StaticHeader("Realized P&L", Modifier.weight(2f))
        }
        HorizontalDivider()
        val tax = fifoResult?.taxConfig ?: CostBasisService.TaxConfig()
        LazyColumn {
            items(items, key = { it.typeId }) { item ->
                val activeOrder = sellByType[item.typeId]?.maxByOrNull { it.price }
                val realized = realizedByType[item.typeId]?.sumOf { it.profit }
                val sellPrice = activeOrder?.price ?: marketPrices[item.typeId]
                val isOwnListing = activeOrder != null
                InventoryRow(item, sellPrice, isOwnListing, realized, tax)
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}
