package org.eventt.features.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.InventoryAdjustmentDao
import org.eventt.core.database.OrderHistoryDao
import org.eventt.core.model.utcToLocalDateTime
import org.eventt.ui.common.ensureVisible
import org.eventt.ui.common.formatIsk
import java.time.Instant

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
)

internal fun computeSellMetrics(
    order: CharacterOrder,
    inventory: Map<Int, CostBasisService.InventoryItem>,
    historyCostBasis: Map<Int, Double>,
    taxConfig: CostBasisService.TaxConfig,
    comparisons: Map<Pair<Int, Long>, MarketComparison>,
    competition: Map<Pair<Int, Long>, CompetitionService.Stats>,
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
    characterId: Int?,
    corporationId: Int?,
    actingCharId: Int?,
    onAdjusted: () -> Unit,
) {
    var sortCol by remember { mutableStateOf(InventorySortCol.NAME) }
    var sortDir by remember { mutableStateOf(SortDir.ASC) }
    var writeOffTarget by remember { mutableStateOf<CostBasisService.InventoryItem?>(null) }
    var writeOffDefaultQty by remember { mutableStateOf(0) }
    var showHistory by remember { mutableStateOf(false) }
    var showReconcile by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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

    fun writeOff(
        item: CostBasisService.InventoryItem,
        quantity: Int,
        reason: String,
    ) {
        scope.launch(Dispatchers.IO) {
            InventoryAdjustmentDao.insert(
                typeId = item.typeId,
                typeName = item.typeName,
                quantity = quantity,
                date = Instant.now().toString().utcToLocalDateTime(),
                reason = reason,
                characterId = characterId,
                corporationId = corporationId,
            )
            withContext(Dispatchers.Main) { onAdjusted() }
        }
    }

    Column {
        val writtenOff = fifoResult?.writtenOffCost ?: 0.0
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (writtenOff > 0) "Written off: ${formatIsk(writtenOff)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(enabled = actingCharId != null, onClick = { showReconcile = true }) { Text("Check assets") }
                TextButton(onClick = { showHistory = true }) { Text("Write-off history") }
            }
        }
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
            Spacer(Modifier.width(28.dp))
        }
        HorizontalDivider()
        LazyColumn {
            items(sorted, key = { it.item.typeId }) { m ->
                InventoryRow(
                    m.item,
                    m.sellPrice,
                    m.isOwnListing,
                    m.realizedPnl,
                    tax,
                    onWriteOff = {
                        writeOffTarget = m.item
                        writeOffDefaultQty = m.item.remainingQty
                    },
                )
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }

    writeOffTarget?.let { item ->
        WriteOffDialog(
            item = item,
            defaultQuantity = writeOffDefaultQty,
            onDismiss = { writeOffTarget = null },
            onConfirm = { qty, reason ->
                writeOff(item, qty, reason)
                writeOffTarget = null
            },
        )
    }

    if (showHistory) {
        WriteOffHistoryDialog(
            characterId = characterId,
            corporationId = corporationId,
            onDismiss = { showHistory = false },
            onUndone = onAdjusted,
        )
    }

    if (showReconcile) {
        val acting = actingCharId
        if (acting != null) {
            ReconcileDialog(
                inventory = inventory,
                characterId = characterId,
                corporationId = corporationId,
                actingCharId = acting,
                onDismiss = { showReconcile = false },
                onWriteOff = { item, shortfall ->
                    showReconcile = false
                    writeOffTarget = item
                    writeOffDefaultQty = shortfall
                },
            )
        }
    }
}

@Composable
private fun WriteOffDialog(
    item: CostBasisService.InventoryItem,
    defaultQuantity: Int,
    onDismiss: () -> Unit,
    onConfirm: (quantity: Int, reason: String) -> Unit,
) {
    var qtyText by remember { mutableStateOf(defaultQuantity.coerceIn(1, item.remainingQty).toString()) }
    var reason by remember { mutableStateOf("") }
    val qty = qtyText.toIntOrNull()
    val valid = qty != null && qty in 1..item.remainingQty

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write off ${item.typeName}") },
        text = {
            Column {
                Text(
                    "Removes this quantity from FIFO inventory with no matching sale — use for lost cargo or " +
                        "a purchase that was actually sold under a different character/corp. Doesn't touch realized P&L.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it },
                    label = { Text("Quantity (max ${item.remainingQty})") },
                    isError = !valid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(qty!!, reason) }) { Text("Write off") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun WriteOffHistoryDialog(
    characterId: Int?,
    corporationId: Int?,
    onDismiss: () -> Unit,
    onUndone: () -> Unit,
) {
    var adjustments by remember { mutableStateOf<List<InventoryAdjustmentDao.Adjustment>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val loaded = InventoryAdjustmentDao.getAll(characterId, corporationId)
            withContext(Dispatchers.Main) { adjustments = loaded }
        }
    }
    LaunchedEffect(characterId, corporationId) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write-off history") },
        text = {
            if (adjustments.isEmpty()) {
                Text("No write-offs recorded.", style = MaterialTheme.typography.bodySmall)
            } else {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    adjustments.reversed().forEach { adj ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${adj.typeName} × ${adj.quantity}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    listOf(adj.date.take(16), adj.reason).filter { it.isNotBlank() }.joinToString(" — "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    InventoryAdjustmentDao.delete(adj.id)
                                    withContext(Dispatchers.Main) {
                                        reload()
                                        onUndone()
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Undo, contentDescription = "Undo write-off", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private sealed class ReconcileState {
    data object Loading : ReconcileState()

    data class Error(
        val message: String,
    ) : ReconcileState()

    data class Done(
        val discrepancies: List<AssetReconciliationService.Discrepancy>,
    ) : ReconcileState()
}

// Refreshes the ESI asset snapshot then compares it against FIFO inventory (see
// AssetReconciliationService) -- surfaces exactly the drift a write-off is meant to correct,
// with a one-click shortcut into WriteOffDialog pre-filled with the missing quantity.
@Composable
private fun ReconcileDialog(
    inventory: Map<Int, CostBasisService.InventoryItem>,
    characterId: Int?,
    corporationId: Int?,
    actingCharId: Int,
    onDismiss: () -> Unit,
    onWriteOff: (CostBasisService.InventoryItem, shortfall: Int) -> Unit,
) {
    var state by remember { mutableStateOf<ReconcileState>(ReconcileState.Loading) }

    LaunchedEffect(characterId, corporationId, actingCharId) {
        state =
            try {
                val discrepancies =
                    withContext(Dispatchers.IO) {
                        AssetReconciliationService.reconcile(inventory, characterId, corporationId, actingCharId)
                    }
                ReconcileState.Done(discrepancies)
            } catch (e: Exception) {
                ReconcileState.Error(e.message ?: "Failed to refresh assets from ESI")
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check assets") },
        text = {
            when (val s = state) {
                is ReconcileState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Refreshing assets from ESI and comparing against FIFO inventory…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                is ReconcileState.Error -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                is ReconcileState.Done -> {
                    if (s.discrepancies.isEmpty()) {
                        Text(
                            "No discrepancies — every item FIFO says you should hold is actually in your assets.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            s.discrepancies.forEach { d ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(d.typeName, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "FIFO: ${d.fifoQty}   Assets: ${d.actualQty}   Missing: ${d.shortfall}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = {
                                        inventory[d.typeId]?.let { onWriteOff(it, d.shortfall) }
                                    }) { Text("Write off") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
