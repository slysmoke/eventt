package org.eventt.features.dashboard

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.eventt.core.database.ActiveOrderDao
import org.eventt.core.database.AssetDao
import org.eventt.core.database.ItemValueSnapshotDao
import org.eventt.core.database.MarketTopSnapshotDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.WalletDao
import org.eventt.core.esi.EsiClient
import org.eventt.features.orders.CostBasisService
import org.eventt.features.orders.WalletSyncService
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val THE_FORGE = 10000002
private const val JITA_44 = 60003760L

// A top-of-book snapshot older than this says nothing about where an order stands now.
private val SNAPSHOT_FRESH = Duration.ofMinutes(30)

// Transaction dates are stored in local time in this exact shape (see utcToLocalDateTime).
private val TX_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

// One calendar day of the dashboard's history. Wallet is the journal's end-of-day balance summed
// over every wallet in scope, escrow is rebuilt from the journal (see getEscrowFlowByDay), stock is
// open FIFO lots at cost. Items (assets + listed sell orders, at market) are only known from the
// first day the dashboard recorded them; before that they're estimated (see buildDays).
internal data class Day(
    val day: String,
    val wallet: Double,
    val escrow: Double,
    val stock: Double,
    val realized: Double,
    val items: Double? = null,
) {
    // Items, or stock at cost when there's nothing to estimate them from yet.
    val holdings get() = items ?: stock
    val netWorth get() = wallet + escrow + holdings
}

// Each map is sparse (only days something happened, yyyy-MM-dd keys); every series holds its
// last value across the days in between. Days before the first items snapshot get stock at cost
// plus that first day's gap between the two — whatever FIFO doesn't track (ships, loot, builds)
// and market vs cost, held constant — so switching to real snapshots isn't a jump in net worth. [walletNow] replaces the last day's wallet with the live
// ESI balance — the journal can lag it by up to an hour.
internal fun buildDays(
    walletRows: List<WalletDao.BalanceRow>,
    stockCostByDay: Map<String, Double>,
    escrowFlowByDay: Map<String, Double>,
    itemsByDay: Map<String, Double>,
    realizedByDay: Map<String, Double>,
    walletNow: Double?,
    today: LocalDate = LocalDate.now(),
): List<Day> {
    val first =
        listOfNotNull(
            walletRows.firstOrNull()?.date?.take(10),
            stockCostByDay.keys.minOrNull(),
            realizedByDay.keys.minOrNull(),
            escrowFlowByDay.keys.minOrNull(),
        ).minOrNull()?.let(LocalDate::parse)?.coerceAtMost(today) ?: today
    val balances = mutableMapOf<String, Double>()
    var row = 0
    var stock = 0.0
    var escrow = 0.0
    var items: Double? = null
    val days =
        generateSequence(first) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .map { date ->
                val d = date.toString()
                while (row < walletRows.size && walletRows[row].date.take(10) <= d) {
                    balances[walletRows[row].wallet] = walletRows[row].balance
                    row++
                }
                stockCostByDay[d]?.let { stock = it }
                escrow += escrowFlowByDay[d] ?: 0.0
                itemsByDay[d]?.let { items = it }
                val wallet = if (date == today && walletNow != null) walletNow else balances.values.sum()
                // Clamped: escrow placed before the journal's 30-day ESI window shows as a negative start.
                Day(d, wallet, escrow.coerceAtLeast(0.0), stock, realizedByDay[d] ?: 0.0, items)
            }.toList()
    val firstItems = days.firstOrNull { it.items != null } ?: return days
    val gap = firstItems.items!! - firstItems.stock
    return days.map { if (it.items == null) it.copy(items = it.stock + gap) else it }
}

internal data class OrderRow(
    val order: ActiveOrderDao.ActiveOrderRecord,
    // Best competing price from the last fresh top-of-book snapshot; null when there's none.
    val best: Double?,
    val outbid: Boolean,
    val expiresIn: Duration?,
) {
    val isk get() = order.price * order.volumeRemaining
}

internal fun loadOrderRows(
    characterId: Int?,
    corporationId: Int?,
    now: Instant = Instant.now(),
): List<OrderRow> {
    val since = now.minus(SNAPSHOT_FRESH).toEpochMilli()
    return ActiveOrderDao.getAll(characterId, corporationId).filter { it.state == "active" }.map { o ->
        val scopeId = if (o.isBuyOrder) o.regionId.toLong() else o.locationId
        val snap = MarketTopSnapshotDao.getWindow(o.typeId, scopeId, o.isBuyOrder, since).lastOrNull()
        val outbid = snap != null && !snap.bestIsMine && (if (o.isBuyOrder) snap.bestPrice > o.price else snap.bestPrice < o.price)
        val expires =
            runCatching { Duration.between(now, Instant.parse(o.issued).plus(Duration.ofDays(o.duration.toLong()))) }.getOrNull()
        OrderRow(o, snap?.bestPrice, outbid, expires)
    }
}

// Lowest Jita 4-4 sell per type — what the stock would have to undercut to sell. ESI's own cache
// keeps repeat loads cheap; a type with no Jita sell order is left out.
internal suspend fun jitaSellPrices(typeIds: Set<Int>): Map<Int, Double> =
    coroutineScope {
        val semaphore = Semaphore(8)
        typeIds
            .map { typeId ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            EsiClient
                                .getMarketRegionOrders(THE_FORGE, orderType = "sell", typeId = typeId)
                                .filter { (it["location_id"] as? Number)?.toLong() == JITA_44 }
                                .mapNotNull { (it["price"] as? Number)?.toDouble() }
                                .minOrNull()
                        }.getOrNull()?.let { typeId to it }
                    }
                }
            }.mapNotNull { it.await() }
            .toMap()
    }

internal data class Position(
    val typeId: Int,
    val name: String,
    val qty: Int,
    val avgCost: Double,
    val cost: Double,
    // Jita sell, and what selling there would net after tax and broker fee.
    val price: Double?,
    val netValue: Double?,
    val daysHeld: Long?,
    // Date of the oldest open lot.
    val since: String?,
) {
    val unrealized get() = netValue?.minus(cost)
    val unrealizedPct get() = unrealized?.let { if (cost > 0) it / cost * 100 else null }
}

internal fun positions(
    fifo: CostBasisService.FifoResult,
    prices: Map<Int, Double>,
): List<Position> =
    fifo.inventory.values
        .map {
            val price = prices[it.typeId]
            Position(
                it.typeId,
                it.typeName,
                it.remainingQty,
                it.avgCostBasis,
                it.totalCostBasis,
                price,
                price?.let { p -> p * fifo.taxConfig.sellMultiplier * it.remainingQty },
                it.daysHeld,
                it.oldestLotDate,
            )
        }.sortedByDescending { it.cost }

// Trading figures over one date range (inclusive yyyy-MM-dd bounds).
internal data class RangeStats(
    val realized: Double,
    val sells: Int,
    val winRatePct: Double?,
    // Gross profit / gross loss; null with no losing sale.
    val profitFactor: Double?,
    // Realized profit / cost of what was sold.
    val marginPct: Double?,
    val turnover: Double,
    val fees: Double,
    // Sales minus purchases minus fees — what actually left or entered the wallet.
    val cashFlow: Double,
    val bestDay: Day?,
    val worstDay: Day?,
    val avgDay: Double,
    // Deepest fall of cumulative realized P&L from its own running peak, ISK.
    val maxDrawdown: Double,
    val netWorthChange: Double,
    val netWorthChangePct: Double?,
    // Quantity-weighted days a sold unit was held.
    val avgHeldDays: Double?,
    // Average ISK tied up in trading (escrow + stock at cost) over the range, and realized
    // profit on it — plain and scaled to a year.
    val avgCapital: Double,
    val roiPct: Double?,
    val roiYearPct: Double?,
)

internal fun rangeStats(
    days: List<Day>,
    sells: List<CostBasisService.RealizedSellTx>,
    txs: List<Map<String, Any?>>,
    feesByDay: Map<String, Double>,
): RangeStats {
    val from = days.firstOrNull()?.day ?: ""
    val to = days.lastOrNull()?.day ?: ""

    fun inRange(date: String) = date.take(10) in from..to
    val s = sells.filter { inRange(it.date) }
    val gain = s.filter { it.profit > 0 }.sumOf { it.profit }
    val loss = -s.filter { it.profit < 0 }.sumOf { it.profit }
    val cost = s.sumOf { it.costBasis * it.qty }
    val fees = feesByDay.filterKeys { inRange(it) }.values.sum()
    val rangeTx = txs.filter { inRange(it["date"] as? String ?: "") }
    val sold = rangeTx.filter { it["is_buy"] != true }.sumOf(::txTotal)
    val bought = rangeTx.filter { it["is_buy"] == true }.sumOf(::txTotal)
    var peak = 0.0
    var cum = 0.0
    var drawdown = 0.0
    days.forEach {
        cum += it.realized
        peak = maxOf(peak, cum)
        drawdown = maxOf(drawdown, peak - cum)
    }
    val active = days.filter { it.realized != 0.0 }
    val start = days.firstOrNull()?.netWorth ?: 0.0
    val change = (days.lastOrNull()?.netWorth ?: 0.0) - start
    val qty = s.sumOf { it.qty }
    val capital = if (days.isEmpty()) 0.0 else days.sumOf { it.escrow + it.stock } / days.size
    val roi = if (capital > 0) s.sumOf { it.profit } / capital * 100 else null
    return RangeStats(
        realized = s.sumOf { it.profit },
        sells = s.size,
        winRatePct = if (s.isEmpty()) null else s.count { it.profit > 0 } * 100.0 / s.size,
        profitFactor = if (loss > 0) gain / loss else null,
        marginPct = if (cost > 0) s.sumOf { it.profit } / cost * 100 else null,
        turnover = s.sumOf { it.sellPrice * it.qty },
        fees = fees,
        cashFlow = sold - bought - fees,
        bestDay = active.maxByOrNull { it.realized }?.takeIf { it.realized > 0 },
        worstDay = active.minByOrNull { it.realized }?.takeIf { it.realized < 0 },
        avgDay = if (days.isEmpty()) 0.0 else days.sumOf { it.realized } / days.size,
        maxDrawdown = drawdown,
        netWorthChange = change,
        netWorthChangePct = if (start > 0) change / start * 100 else null,
        avgHeldDays = if (qty > 0) s.sumOf { it.heldDays * it.qty } / qty else null,
        avgCapital = capital,
        roiPct = roi,
        roiYearPct = roi?.let { it * 365 / days.size },
    )
}

internal data class ItemPnl(
    val typeId: Int,
    val name: String,
    val qty: Int,
    val profit: Double,
    val marginPct: Double?,
    val lastSold: String,
    val avgHeldDays: Double,
)

internal fun itemPnl(
    sells: List<CostBasisService.RealizedSellTx>,
    from: String,
    nameOf: (Int) -> String,
): List<ItemPnl> =
    sells
        .filter { it.date.take(10) >= from }
        .groupBy { it.typeId }
        .map { (typeId, list) ->
            val cost = list.sumOf { it.costBasis * it.qty }
            val profit = list.sumOf { it.profit }
            ItemPnl(
                typeId,
                nameOf(typeId),
                list.sumOf { it.qty },
                profit,
                if (cost > 0) profit / cost * 100 else null,
                list.maxOf { it.date },
                list.sumOf { it.heldDays * it.qty } / list.sumOf { it.qty },
            )
        }.sortedByDescending { it.profit }

// One slice of the stock: a group's share of what open positions are worth (Jita net value where
// known, cost otherwise).
internal data class Allocation(
    val group: String,
    val value: Double,
    val sharePct: Double,
    val types: Int,
)

internal fun allocation(
    pos: List<Position>,
    groupOf: Map<Int, String>,
    other: String,
): List<Allocation> {
    fun value(p: Position) = p.netValue ?: p.cost
    val total = pos.sumOf(::value).takeIf { it > 0 } ?: return emptyList()
    return pos
        .groupBy { groupOf[it.typeId] ?: other }
        .map { (g, list) -> Allocation(g, list.sumOf(::value), list.sumOf(::value) / total * 100, list.size) }
        .sortedByDescending { it.value }
}

// Share of the stock's value in its five biggest positions.
internal fun top5SharePct(pos: List<Position>): Double? {
    fun value(p: Position) = p.netValue ?: p.cost
    val total = pos.sumOf(::value).takeIf { it > 0 } ?: return null
    return pos
        .map(::value)
        .sortedDescending()
        .take(5)
        .sum() / total * 100
}

// Everything the dashboard shows, read from the local DB in one go. Names and market groups are
// resolved here too, so drawing never touches the DB.
internal class Snapshot(
    val days: List<Day>,
    val fifo: CostBasisService.FifoResult,
    val feesByDay: Map<String, Double>,
    val txs: List<Map<String, Any?>>,
    val orders: List<OrderRow>,
    val names: Map<Int, String>,
    val groups: Map<Int, String>,
) {
    val sellOrdersNet get() = sellOrdersNet(orders, fifo.taxConfig)
}

// What the listed items would bring in: the broker fee was paid when listing, so only sales tax.
internal fun sellOrdersNet(
    orders: List<OrderRow>,
    tax: CostBasisService.TaxConfig,
) = orders.filter { !it.order.isBuyOrder }.sumOf { it.isk } * (1 - tax.salesTaxPct / 100)

// Everything ever traded with one client. A transaction's client is whoever was on the other side
// of it: is_buy=true means we bought, so the client sold to us; is_buy=false means they bought.
internal data class Counterparty(
    val name: String,
    val boughtFromUs: Double,
    val soldToUs: Double,
    val count: Int,
) {
    val total get() = boughtFromUs + soldToUs
}

internal fun txTotal(tx: Map<String, Any?>): Double {
    val unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0
    val quantity = (tx["quantity"] as? Number)?.toInt() ?: 0
    return (tx["total"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: (unitPrice * quantity)
}

internal fun counterparties(
    txs: List<Map<String, Any?>>,
    unknown: String,
): List<Counterparty> =
    txs
        .groupBy { (it["client_name"] as? String)?.ifEmpty { null } ?: unknown }
        .map { (name, list) ->
            val (ourBuys, ourSells) = list.partition { it["is_buy"] == true }
            Counterparty(name, ourSells.sumOf(::txTotal), ourBuys.sumOf(::txTotal), list.size)
        }

// Buy/sell fills over the last 24 hours: count and ISK per side.
internal data class Fills24h(
    val buys: Int,
    val buyIsk: Double,
    val sells: Int,
    val sellIsk: Double,
)

internal fun fills24h(
    txs: List<Map<String, Any?>>,
    now: LocalDateTime = LocalDateTime.now(),
): Fills24h {
    val since = now.minusHours(24).format(TX_TIME)
    val recent = txs.filter { (it["date"] as? String ?: "") >= since }
    val (b, s) = recent.partition { it["is_buy"] == true }
    return Fills24h(b.size, b.sumOf(::txTotal), s.size, s.sumOf(::txTotal))
}

// Reads everything the dashboard shows for one scope (null ids = every character and corp) and
// records today's item value. [walletNow] is the live ESI balance when there is one.
internal fun loadSnapshot(
    qChar: Int?,
    qCorp: Int?,
    scopeKey: String,
    actingCharId: Int?,
    walletNow: Double?,
): Snapshot {
    val taxConfig =
        actingCharId?.let {
            CostBasisService.TaxConfig(StaticDataDao.getCharSalesTax(it), StaticDataDao.getCharBrokersFee(it))
        } ?: CostBasisService.TaxConfig()
    val fifo = CostBasisService.compute(qChar, qCorp, taxConfig)
    val orders = loadOrderRows(qChar, qCorp)
    val typeIds = fifo.inventory.keys + fifo.realizedSells.map { it.typeId }
    // limit = -1 is SQLite for "no limit" — counterparties rank over the whole history.
    val txs = WalletSyncService.resolveMissingClientNames(WalletDao.getTransactions(qChar, qCorp, limit = -1))
    val names = typeIds.associateWith { id -> fifo.inventory[id]?.typeName?.ifEmpty { null } ?: StaticDataDao.getTypeName(id) ?: "#$id" }
    val groups =
        fifo.inventory.keys
            .mapNotNull { id -> StaticDataDao.getGroupNameForType(id)?.let { id to it } }
            .toMap()
    // Assets come from AssetWatchService's background refresh, already priced at Jita sell.
    ItemValueSnapshotDao.upsert(
        scopeKey,
        LocalDate.now().toString(),
        AssetDao.getTotalValue(qChar, qCorp) + sellOrdersNet(orders, taxConfig),
    )
    val days =
        buildDays(
            WalletDao.getBalanceHistory(qChar, qCorp),
            fifo.stockCostByDay,
            WalletDao.getEscrowFlowByDay(qChar, qCorp),
            ItemValueSnapshotDao.getAll(scopeKey),
            fifo.realizedSells.groupBy { it.date.take(10) }.mapValues { (_, s) -> s.sumOf { it.profit } },
            walletNow,
        )
    return Snapshot(days, fifo, WalletDao.getFeesByDay(qChar, qCorp), txs, orders, names, groups)
}
