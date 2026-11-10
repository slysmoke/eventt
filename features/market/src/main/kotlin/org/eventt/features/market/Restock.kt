package org.eventt.features.market

import org.eventt.core.database.ActiveOrderDao
import org.eventt.core.database.AssetDao
import org.eventt.core.database.WalletDao
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/** What to re-buy for one item: [need] units, from selling [perDay] with [stock] already on hand. */
data class Restock(
    val need: Long,
    val perDay: Double,
    val stock: Long,
)

// A one-off sale shouldn't read as a fast seller: the rate is never taken over fewer days.
private const val MIN_RESTOCK_SPAN_DAYS = 14L

/**
 * Items you sold at the destination within [historyDays] whose [stock] no longer covers
 * [coverDays] of your own sales there.
 *
 * The rate is units sold over the span from first to last sale in the window, not over the whole
 * window, so a route paused a month ago still shows the pace it had while it was running.
 * ponytail: days an item sat sold out inside that span still count, so the rate reads low for
 * items that kept running dry; track days-in-stock if that matters.
 */
internal fun computeRestock(
    txs: List<WalletDao.RawTxRecord>,
    isDest: (Long) -> Boolean,
    stock: Map<Int, Long>,
    today: LocalDate,
    historyDays: Int,
    coverDays: Int,
): Map<Int, Restock> {
    val from = today.minusDays(historyDays.toLong())
    return txs
        .filter { !it.isBuy && !it.isP2p && isDest(it.locationId) }
        .map { it to LocalDate.parse(it.date.take(10)) }
        .filter { (_, day) -> day >= from }
        .groupBy { (tx, _) -> tx.typeId }
        .mapNotNull { (typeId, rows) ->
            val span = ChronoUnit.DAYS.between(rows.minOf { it.second }, rows.maxOf { it.second }) + 1
            val perDay = rows.sumOf { it.first.quantity.toLong() } / span.coerceAtLeast(MIN_RESTOCK_SPAN_DAYS).toDouble()
            val have = stock[typeId] ?: 0L
            val need = ceil(perDay * coverDays).toLong() - have
            if (need > 0) typeId to Restock(need, perDay, have) else null
        }.toMap()
}

/**
 * [computeRestock] over every locally known character and corporation. Stock is what is already
 * working for the route: sell orders and items at the destination, plus items and open buy orders
 * at the source (bought or being bought, not hauled yet). Goods in a courier contract are invisible
 * to ESI assets, so re-run after they land.
 */
internal fun loadRestock(
    buyRegionId: Int,
    buyStationId: Long?,
    sellRegionId: Int,
    sellStationId: Long?,
    sellRegionStationIds: Set<Long>,
    historyDays: Int,
    coverDays: Int,
): Map<Int, Restock> {
    val orders = ActiveOrderDao.getAll()
    // Fitted modules are part of a ship, not stock for sale.
    val assets = AssetDao.getAll().filter { "Slot" !in it.locationFlag }

    fun atBuy(
        region: Int,
        location: Long,
    ) = if (buyStationId != null) location == buyStationId else region == buyRegionId

    fun atSell(
        region: Int,
        location: Long,
    ) = if (sellStationId != null) location == sellStationId else region == sellRegionId

    val stock = HashMap<Int, Long>()
    for (o in orders) {
        val counts = if (o.isBuyOrder) atBuy(o.regionId, o.locationId) else atSell(o.regionId, o.locationId)
        if (counts) stock.merge(o.typeId, o.volumeRemaining.toLong(), Long::plus)
    }
    for (a in assets) {
        if (atBuy(a.regionId, a.stationId) || atSell(a.regionId, a.stationId)) stock.merge(a.typeId, a.quantity.toLong(), Long::plus)
    }
    // Transactions carry no region, so with no station picked the destination is every place in
    // the sell region we know of: its NPC stations plus any structure we hold orders or items in.
    val sellLocations =
        sellRegionStationIds +
            orders.filter { it.regionId == sellRegionId }.map { it.locationId } +
            assets.filter { it.regionId == sellRegionId }.map { it.stationId }
    return computeRestock(
        WalletDao.getAllTransactions(),
        isDest = { if (sellStationId != null) it == sellStationId else it in sellLocations },
        stock = stock,
        today = LocalDate.now(),
        historyDays = historyDays,
        coverDays = coverDays,
    )
}
