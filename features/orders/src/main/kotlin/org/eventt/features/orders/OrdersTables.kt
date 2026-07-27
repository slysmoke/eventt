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

            // Ascending = most contested first (least time on top); no data sorts last.
            SortCol.COMPETITION -> list.sortedBy { it.competition?.timeOnTopPct ?: 2.0 }

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

            SortCol.TOTAL -> list.sortedBy { it.order.total }

            SortCol.TIME_LEFT -> list.sortedBy { it.order.timeLeftSeconds }

            // Ascending = most contested first (least time on top); no data sorts last.
            SortCol.COMPETITION -> list.sortedBy { it.competition?.timeOnTopPct ?: 2.0 }

            SortCol.ORDER_AGE -> list // Buy-only column, not shown here
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
    val competition: CompetitionService.Stats?,
    // Buying out same-station competitors cheaper than this order and reselling against it --
    // null when there's no profitable volume to buy (including when buyout hint is off, since
    // callers only pass a populated competingSellBooks map when it's on).
    val buyoutPlan: BuyoutPlan?,
)

// Volume, blended average cost, and total profit of buying out same-station competitors cheaper
// than targetPrice and reselling that stock against this order.
internal data class BuyoutPlan(
    val volume: Long,
    val avgCost: Double,
    val totalProfit: Double,
)

// Walks the competing sell book from cheapest, buying out every whole layer priced strictly below
// maxBuyPrice and stopping at the first layer that isn't (sorted ascending, so nothing after that
// point could help either) -- mixing any two prices each <= maxBuyPrice can only ever blend to <=
// maxBuyPrice, so no partial-layer break-even math is needed except to apply the volume cap, which
// can land mid-layer. maxBuyPrice and resalePrice are deliberately separate: resalePrice (this
// order's own price) is what profit is measured against, while maxBuyPrice is how cheap a layer
// has to be to even qualify -- e.g. capped further by a reference hub's price, so only genuinely
// underpriced competitors get bought out, not merely-cheaper-than-me ones. maxVolume is this
// order's own remaining volume: no point recommending a buyout bigger than it can absorb.
internal fun computeBuyoutPlan(
    competingSellOrders: List<Pair<Double, Long>>,
    maxBuyPrice: Double,
    resalePrice: Double,
    maxVolume: Long,
): BuyoutPlan? {
    if (maxVolume <= 0) return null
    var volume = 0L
    var cost = 0.0
    for ((price, layerVolume) in competingSellOrders.sortedBy { it.first }) {
        if (price >= maxBuyPrice || volume >= maxVolume) break
        val take = minOf(layerVolume, maxVolume - volume)
        volume += take
        cost += take * price
    }
    if (volume <= 0) return null
    return BuyoutPlan(volume, cost / volume, resalePrice * volume - cost)
}

internal fun computeSellMetrics(
    order: CharacterOrder,
    inventory: Map<Int, CostBasisService.InventoryItem>,
    historyCostBasis: Map<Int, Double>,
    taxConfig: CostBasisService.TaxConfig,
    comparisons: Map<Pair<Int, Long>, MarketComparison>,
    competition: Map<Pair<Int, Long>, CompetitionService.Stats>,
    relistDiscountPct: Double,
    competingSellBooks: Map<Pair<Int, Long>, List<Pair<Double, Long>>> = emptyMap(),
    jitaSellPrices: Map<Int, Double?> = emptyMap(),
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
    // Matching that price means relisting this order, which charges another modification fee on
    // top of whatever relist fees are already sunk into it -- net out both, the same way
    // totalProfit does, or "Best Margin" would overstate what relisting to the front actually nets.
    val bestMarginPct =
        comparison?.bestSell?.let { bestSell ->
            costBasis?.let { cb ->
                if (cb > 0 && order.volumeRemaining > 0) {
                    val modFee =
                        OrderFeeService.computeModificationFee(
                            oldPrice = order.price,
                            newPrice = bestSell,
                            volumeRemaining = order.volumeRemaining,
                            brokerFeePct = taxConfig.brokerFeePct,
                            relistDiscountPct = relistDiscountPct,
                        )
                    val bestProfit =
                        order.volumeRemaining * (bestSell * taxConfig.sellMultiplier - cb) - order.relistFeesPaid - modFee
                    bestProfit / (cb * order.volumeRemaining) * 100
                } else {
                    null
                }
            }
        }
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
    val competitionStats = competition[order.typeId to order.locationId]
    // Jita comparison is required, not just a nice-to-have -- a competitor undercutting me is
    // only worth buying out when their price is *also* below Jita's, i.e. a genuine steal by
    // galaxy standards, not just "cheaper than me." No Jita data yet -> no recommendation.
    val jitaPrice = jitaSellPrices[order.typeId]
    val buyoutPlan =
        jitaPrice?.let {
            computeBuyoutPlan(
                competingSellOrders = competingSellBooks[order.typeId to order.locationId] ?: emptyList(),
                maxBuyPrice = minOf(order.price, it),
                resalePrice = order.price,
                maxVolume = order.volumeRemaining.toLong(),
            )
        }
    return SellOrderMetrics(
        order,
        comparison,
        costBasis,
        isEstimated,
        totalProfit,
        marginPct,
        bestMarginPct,
        updatesRemaining,
        isBeaten,
        competitionStats,
        buyoutPlan,
    )
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
    competition: Map<Pair<Int, Long>, CompetitionService.Stats>,
    relistDiscountPct: Double,
    showBeatenOnly: Boolean,
    selectedOrderId: Long?,
    activeOrderId: Long?,
    onSelect: (Long) -> Unit,
    onAction: (CharacterOrder) -> Unit,
    buyoutHintEnabled: Boolean = false,
    competingSellBooks: Map<Pair<Int, Long>, List<Pair<Double, Long>>> = emptyMap(),
    jitaSellPrices: Map<Int, Double?> = emptyMap(),
    onBuyout: (CharacterOrder, BuyoutPlan) -> Unit = { _, _ -> },
) {
    val metrics =
        remember(
            orders,
            inventory,
            historyCostBasis,
            taxConfig,
            comparisons,
            competition,
            relistDiscountPct,
            competingSellBooks,
            jitaSellPrices,
        ) {
            orders.map {
                computeSellMetrics(
                    it,
                    inventory,
                    historyCostBasis,
                    taxConfig,
                    comparisons,
                    competition,
                    relistDiscountPct,
                    competingSellBooks,
                    jitaSellPrices,
                )
            }
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
            if (buyoutHintEnabled) {
                StaticHeader("Buyout", Modifier.weight(1.8f))
            }
            SortHeader("Relist", SortCol.RELIST, sortCol, sortDir, onSort, Modifier.weight(1.8f))
            SortHeader("Profit", SortCol.PROFIT, sortCol, sortDir, onSort, Modifier.weight(1.8f))
            SortHeader("Margin", SortCol.MARGIN, sortCol, sortDir, onSort, Modifier.weight(1.2f))
            SortHeader("Best Margin", SortCol.BEST_MARGIN, sortCol, sortDir, onSort, Modifier.weight(1.4f))
            SortHeader("Volume", SortCol.VOLUME, sortCol, sortDir, onSort, Modifier.weight(2.5f))
            SortHeader("Total", SortCol.TOTAL, sortCol, sortDir, onSort, Modifier.weight(2f))
            SortHeader("Competition", SortCol.COMPETITION, sortCol, sortDir, onSort, Modifier.weight(1.8f))
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
                    buyoutHintEnabled = buyoutHintEnabled,
                    onBuyout = { plan -> onBuyout(m.order, plan) },
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
    val competition: CompetitionService.Stats?,
)

private fun computeBuyMetrics(
    order: CharacterOrder,
    taxConfig: CostBasisService.TaxConfig,
    comparisons: Map<Pair<Int, Long>, MarketComparison>,
    competition: Map<Pair<Int, Long>, CompetitionService.Stats>,
): BuyOrderMetrics {
    val comparison = comparisons[order.typeId to order.locationId]
    // Margin if this order fills and the item is resold at the current best sell price.
    val marginPct = computeMarginPct(order.price, comparison?.bestSell, taxConfig)
    val bestMarginPct = computeBestMarginPct(comparison, taxConfig)
    val isOverbid = comparison?.bestBuy != null && comparison.bestBuy > order.price
    // Buy competition is scoped to the region, matching recordTopSnapshots.
    val competitionStats = competition[order.typeId to order.regionId.toLong()]
    return BuyOrderMetrics(order, comparison, marginPct, bestMarginPct, isOverbid, competitionStats)
}

@Composable
internal fun BuyOrdersTable(
    orders: List<CharacterOrder>,
    sortCol: SortCol,
    sortDir: SortDir,
    onSort: (SortCol) -> Unit,
    taxConfig: CostBasisService.TaxConfig,
    comparisons: Map<Pair<Int, Long>, MarketComparison>,
    competition: Map<Pair<Int, Long>, CompetitionService.Stats>,
    showOverbidOnly: Boolean,
    selectedOrderId: Long?,
    activeOrderId: Long?,
    onSelect: (Long) -> Unit,
    onAction: (CharacterOrder) -> Unit,
) {
    val metrics =
        remember(orders, taxConfig, comparisons, competition) {
            orders.map { computeBuyMetrics(it, taxConfig, comparisons, competition) }
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
            SortHeader("Competition", SortCol.COMPETITION, sortCol, sortDir, onSort, Modifier.weight(1.8f))
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

internal enum class HistorySortCol { NAME, TYPE, STATE, PRICE, PROFIT, MARGIN, VOLUME, ISSUED, STATION }

// Profit/margin aren't stored on OrderHistoryRecord (historyPnl derives them from the FIFO
// result), so they're computed once here rather than re-derived by both the sort and each row.
internal data class HistoryRowMetrics(
    val order: OrderHistoryDao.OrderHistoryRecord,
    val pnl: Double?,
    val marginPct: Double?,
)

private fun sortHistoryMetrics(
    list: List<HistoryRowMetrics>,
    col: HistorySortCol,
    dir: SortDir,
): List<HistoryRowMetrics> {
    val sorted =
        when (col) {
            HistorySortCol.NAME -> list.sortedBy { it.order.typeName }
            HistorySortCol.TYPE -> list.sortedBy { it.order.isBuyOrder }
            HistorySortCol.STATE -> list.sortedBy { effectiveOrderState(it.order) }
            HistorySortCol.PRICE -> list.sortedBy { it.order.price }
            HistorySortCol.PROFIT -> list.sortedBy { it.pnl ?: Double.NEGATIVE_INFINITY }
            HistorySortCol.MARGIN -> list.sortedBy { it.marginPct ?: Double.NEGATIVE_INFINITY }
            HistorySortCol.VOLUME -> list.sortedBy { it.order.volumeTotal }
            HistorySortCol.ISSUED -> list.sortedBy { it.order.issued }
            HistorySortCol.STATION -> list.sortedBy { it.order.stationName }
        }
    return if (dir == SortDir.DESC) sorted.reversed() else sorted
}

@Composable
internal fun OrderHistoryTable(
    orders: List<OrderHistoryDao.OrderHistoryRecord>,
    fifoResult: CostBasisService.FifoResult?,
) {
    var sortCol by remember { mutableStateOf(HistorySortCol.ISSUED) }
    var sortDir by remember { mutableStateOf(SortDir.DESC) }

    fun toggleSort(col: HistorySortCol) {
        if (sortCol == col) {
            sortDir = if (sortDir == SortDir.ASC) SortDir.DESC else SortDir.ASC
        } else {
            sortCol = col
            sortDir = SortDir.DESC
        }
    }

    val metrics =
        remember(orders, fifoResult) {
            orders.map { order ->
                val (pnl, margin) = historyPnl(order, fifoResult)
                HistoryRowMetrics(order, pnl, margin)
            }
        }
    val sorted = sortHistoryMetrics(metrics, sortCol, sortDir)

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
            SortHeader("Name", HistorySortCol.NAME, sortCol, sortDir, ::toggleSort, Modifier.weight(3f))
            SortHeader("Type", HistorySortCol.TYPE, sortCol, sortDir, ::toggleSort, Modifier.weight(1f))
            SortHeader("State", HistorySortCol.STATE, sortCol, sortDir, ::toggleSort, Modifier.weight(1.5f))
            SortHeader("Price", HistorySortCol.PRICE, sortCol, sortDir, ::toggleSort, Modifier.weight(2f))
            SortHeader("Profit", HistorySortCol.PROFIT, sortCol, sortDir, ::toggleSort, Modifier.weight(2f))
            SortHeader("Margin", HistorySortCol.MARGIN, sortCol, sortDir, ::toggleSort, Modifier.weight(1.2f))
            SortHeader("Volume", HistorySortCol.VOLUME, sortCol, sortDir, ::toggleSort, Modifier.weight(2f))
            SortHeader("Issued", HistorySortCol.ISSUED, sortCol, sortDir, ::toggleSort, Modifier.weight(2f))
            SortHeader("Station", HistorySortCol.STATION, sortCol, sortDir, ::toggleSort, Modifier.weight(2.5f))
        }
        HorizontalDivider()
        LazyColumn {
            items(sorted, key = { it.order.orderId }) { m ->
                OrderHistoryRow(m.order, m.pnl, m.marginPct)
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

internal enum class InventorySortCol { NAME, QTY, AGE, AVG_COST, TOTAL_COST, SELL_PRICE, PROFIT, MARGIN, REALIZED_PNL }

// Sell price/profit/margin/realized P&L all depend on the active sell order + FIFO lookups below,
// computed once here rather than re-derived by both the sort and each row.
private data class InventoryRowMetrics(
    val item: CostBasisService.InventoryItem,
    val sellPrice: Double?,
    val isOwnListing: Boolean,
    val profitPerUnit: Double?,
    val marginPct: Double?,
    val realizedPnl: Double?,
)

private fun sortInventoryMetrics(
    list: List<InventoryRowMetrics>,
    col: InventorySortCol,
    dir: SortDir,
): List<InventoryRowMetrics> {
    val sorted =
        when (col) {
            InventorySortCol.NAME -> list.sortedBy { it.item.typeName }
            InventorySortCol.QTY -> list.sortedBy { it.item.remainingQty }
            InventorySortCol.AGE -> list.sortedBy { it.item.daysHeld ?: -1 }
            InventorySortCol.AVG_COST -> list.sortedBy { it.item.avgCostBasis }
            InventorySortCol.TOTAL_COST -> list.sortedBy { it.item.totalCostBasis }
            InventorySortCol.SELL_PRICE -> list.sortedBy { it.sellPrice ?: Double.NEGATIVE_INFINITY }
            InventorySortCol.PROFIT -> list.sortedBy { it.profitPerUnit ?: Double.NEGATIVE_INFINITY }
            InventorySortCol.MARGIN -> list.sortedBy { it.marginPct ?: Double.NEGATIVE_INFINITY }
            InventorySortCol.REALIZED_PNL -> list.sortedBy { it.realizedPnl ?: Double.NEGATIVE_INFINITY }
        }
    return if (dir == SortDir.DESC) sorted.reversed() else sorted
}

@Composable
internal fun InventoryTable(
    inventory: Map<Int, CostBasisService.InventoryItem>,
    sellOrders: List<CharacterOrder>,
    fifoResult: CostBasisService.FifoResult?,
    marketPrices: Map<Int, Double>,
) {
    var sortCol by remember { mutableStateOf(InventorySortCol.NAME) }
    var sortDir by remember { mutableStateOf(SortDir.ASC) }

    fun toggleSort(col: InventorySortCol) {
        if (sortCol == col) {
            sortDir = if (sortDir == SortDir.ASC) SortDir.DESC else SortDir.ASC
        } else {
            sortCol = col
            sortDir = SortDir.DESC
        }
    }

    val sellByType = sellOrders.filter { !it.isBuyOrder && it.state == "active" }.groupBy { it.typeId }
    val realizedByType = fifoResult?.realizedByType ?: emptyMap()
    val tax = fifoResult?.taxConfig ?: CostBasisService.TaxConfig()

    val metrics =
        remember(inventory, sellByType, realizedByType, marketPrices, tax) {
            inventory.values.map { item ->
                val activeOrder = sellByType[item.typeId]?.maxByOrNull { it.price }
                val realized = realizedByType[item.typeId]?.sumOf { it.profit }
                val sellPrice = activeOrder?.price ?: marketPrices[item.typeId]
                val netSellPrice = sellPrice?.let { it * tax.sellMultiplier }
                val profitPerUnit = netSellPrice?.let { it - item.avgCostBasis }
                val marginPct = profitPerUnit?.let { if (item.avgCostBasis > 0) it / item.avgCostBasis * 100 else null }
                InventoryRowMetrics(item, sellPrice, activeOrder != null, profitPerUnit, marginPct, realized)
            }
        }
    val sorted = sortInventoryMetrics(metrics, sortCol, sortDir)

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
            SortHeader("Name", InventorySortCol.NAME, sortCol, sortDir, ::toggleSort, Modifier.weight(3f))
            SortHeader("Qty", InventorySortCol.QTY, sortCol, sortDir, ::toggleSort, Modifier.weight(1.5f))
            SortHeader("Age", InventorySortCol.AGE, sortCol, sortDir, ::toggleSort, Modifier.weight(1f))
            SortHeader("Avg Cost", InventorySortCol.AVG_COST, sortCol, sortDir, ::toggleSort, Modifier.weight(2f))
            SortHeader("Total Cost", InventorySortCol.TOTAL_COST, sortCol, sortDir, ::toggleSort, Modifier.weight(2f))
            SortHeader("Sell Price", InventorySortCol.SELL_PRICE, sortCol, sortDir, ::toggleSort, Modifier.weight(2f))
            SortHeader("Profit/unit", InventorySortCol.PROFIT, sortCol, sortDir, ::toggleSort, Modifier.weight(2f))
            SortHeader("Margin", InventorySortCol.MARGIN, sortCol, sortDir, ::toggleSort, Modifier.weight(1.2f))
            SortHeader("Realized P&L", InventorySortCol.REALIZED_PNL, sortCol, sortDir, ::toggleSort, Modifier.weight(2f))
        }
        HorizontalDivider()
        LazyColumn {
            items(sorted, key = { it.item.typeId }) { m ->
                InventoryRow(m.item, m.sellPrice, m.isOwnListing, m.realizedPnl, tax)
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}
