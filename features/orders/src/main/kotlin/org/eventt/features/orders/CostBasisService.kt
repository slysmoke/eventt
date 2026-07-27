package org.eventt.features.orders

import org.eventt.core.database.OrderHistoryDao
import org.eventt.core.database.WalletDao
import org.eventt.core.model.utcToLocalDateTime
import java.time.LocalDate

object CostBasisService {
    // Sales tax and broker fee rates for a character, loaded from settings.
    data class TaxConfig(
        val salesTaxPct: Double = 8.0,
        val brokerFeePct: Double = 3.0,
    ) {
        // Multiplier applied to buy price to get true acquisition cost (price + broker fee).
        val buyMultiplier: Double = 1.0 + brokerFeePct / 100.0

        // Multiplier applied to sell price to get net revenue (price − sales tax − broker fee).
        val sellMultiplier: Double = 1.0 - (salesTaxPct + brokerFeePct) / 100.0
    }

    data class InventoryItem(
        val typeId: Int,
        val typeName: String,
        val remainingQty: Int,
        val avgCostBasis: Double, // per unit, includes buy broker fee
        val totalCostBasis: Double,
        // Purchase date of the oldest remaining FIFO lot — how long the capital in this
        // item has been sitting unsold. Null only for legacy/synthetic results.
        val oldestLotDate: String? = null,
    ) {
        val daysHeld: Long?
            get() =
                oldestLotDate?.take(10)?.let {
                    runCatching {
                        java.time.temporal.ChronoUnit.DAYS
                            .between(LocalDate.parse(it), LocalDate.now())
                    }.getOrNull()
                }
    }

    // One FIFO buy lot: quantity remaining, per-unit cost (incl. broker fee), purchase date.
    private data class Lot(
        val qty: Int,
        val cost: Double,
        val date: String,
    )

    data class RealizedSellTx(
        val date: String,
        val typeId: Int,
        val qty: Int,
        val sellPrice: Double, // gross sell price per unit
        val costBasis: Double, // FIFO cost per unit (includes buy broker fee)
        val profit: Double, // net profit = (sellPrice×sellMult − costBasis) × qty
        val marginPct: Double, // net profit / total cost × 100
    )

    data class FifoResult(
        val inventory: Map<Int, InventoryItem>,
        val realizedSells: List<RealizedSellTx>, // sorted by date asc
        val taxConfig: TaxConfig,
        // Per-type average relist fee already netted out of realizedSells' profit/margin above —
        // exposed so a caller re-deriving a single order's own P&L (historyPnl) applies the same
        // adjustment instead of subtracting that order's exact relistFeesPaid a second time.
        val sellRelistPerUnit: Map<Int, Double> = emptyMap(),
    ) {
        val totalRealizedPnl: Double = realizedSells.sumOf { it.profit }
        val realizedByType: Map<Int, List<RealizedSellTx>> = realizedSells.groupBy { it.typeId }

        // Best available FIFO cost basis for a type: current inventory first,
        // then weighted average from historical realized sells.
        fun avgCostBasisForType(typeId: Int): Double? {
            inventory[typeId]?.avgCostBasis?.let { return it }
            val sells = realizedByType[typeId] ?: return null
            if (sells.isEmpty()) return null
            val totalQty = sells.sumOf { it.qty }.toDouble()
            return if (totalQty > 0) sells.sumOf { it.costBasis * it.qty } / totalQty else null
        }
    }

    // ESI doesn't link a wallet transaction to the order that placed it, so a specific closed
    // order's relist fees can't be pinned to the exact units it filled. Spread each closed order's
    // relist fees evenly across the units it filled, then average per type -- every buy/sell of
    // that type carries a representative share instead of the fee being lost (buy side) or handled
    // ad hoc in just one summary number (sell side, as it used to be).
    private fun relistPerUnitByType(
        characterId: Int?,
        corporationId: Int?,
        isBuyOrder: Boolean,
    ): Map<Int, Double> =
        OrderHistoryDao
            .getAll(characterId = characterId, corporationId = corporationId, isBuyOrder = isBuyOrder, limit = 10_000)
            .groupBy { it.typeId }
            .mapNotNull { (typeId, orders) ->
                val filled = orders.sumOf { it.volumeTotal - it.volumeRemaining }
                if (filled <= 0) null else typeId to orders.sumOf { it.relistFeesPaid } / filled
            }.toMap()

    fun compute(
        characterId: Int? = null,
        corporationId: Int? = null,
        taxConfig: TaxConfig = TaxConfig(),
    ): FifoResult {
        val transactions = WalletDao.getAllTransactions(characterId, corporationId)
        val buyRelistPerUnit = relistPerUnitByType(characterId, corporationId, isBuyOrder = true)
        val sellRelistPerUnit = relistPerUnitByType(characterId, corporationId, isBuyOrder = false)

        val lots = mutableMapOf<Int, ArrayDeque<Lot>>()
        val typeNames = mutableMapOf<Int, String>()
        val realized = mutableListOf<RealizedSellTx>()

        for (tx in transactions) { // already sorted ASC by WalletDao
            typeNames[tx.typeId] = tx.typeName
            if (tx.isBuy) {
                // Store adjusted cost: price + broker fee paid to place the buy order, plus this
                // type's average buy-side relist fee per unit (see relistPerUnitByType). Neither
                // applies to a P2P trade — it's a direct exchange outside the market, with no
                // order (and so no broker fee/relists) behind it at all.
                val adjustedCost =
                    if (tx.isP2p) tx.unitPrice else tx.unitPrice * taxConfig.buyMultiplier + (buyRelistPerUnit[tx.typeId] ?: 0.0)
                lots.getOrPut(tx.typeId) { ArrayDeque() }.addLast(Lot(tx.quantity, adjustedCost, tx.date))
            } else {
                val queue = lots.getOrPut(tx.typeId) { ArrayDeque() }
                var remaining = tx.quantity
                var costConsumed = 0.0
                var qtyMatched = 0

                while (remaining > 0 && queue.isNotEmpty()) {
                    val lot = queue.removeFirst()
                    val consume = minOf(remaining, lot.qty)
                    costConsumed += consume * lot.cost
                    qtyMatched += consume
                    if (consume < lot.qty) queue.addFirst(lot.copy(qty = lot.qty - consume))
                    remaining -= consume
                }

                if (qtyMatched > 0) {
                    val cb = costConsumed / qtyMatched
                    // Net of tax/broker fee (sellMultiplier) and this type's average sell-side
                    // relist fee per unit (see relistPerUnitByType) — again, neither applies to a
                    // P2P disposal.
                    val netSellPrice =
                        if (tx.isP2p) tx.unitPrice else tx.unitPrice * taxConfig.sellMultiplier - (sellRelistPerUnit[tx.typeId] ?: 0.0)
                    val profit = qtyMatched * (netSellPrice - cb)
                    val margin = if (cb > 0) (netSellPrice - cb) / cb * 100.0 else 0.0
                    realized.add(RealizedSellTx(tx.date, tx.typeId, qtyMatched, tx.unitPrice, cb, profit, margin))
                }
            }
        }

        val inventory =
            lots
                .mapValues { (typeId, queue) ->
                    val qty = queue.sumOf { it.qty }
                    val cost = queue.sumOf { it.qty.toDouble() * it.cost }
                    InventoryItem(
                        typeId,
                        typeNames[typeId] ?: "",
                        qty,
                        if (qty > 0) cost / qty else 0.0,
                        cost,
                        oldestLotDate = queue.firstOrNull()?.date,
                    )
                }.filter { it.value.remainingQty > 0 }

        return FifoResult(inventory, realized, taxConfig, sellRelistPerUnit)
    }

    // Returns realized P&L for a specific fulfilled sell order using date+qty matching.
    // `issuedDate` comes from ActiveOrderDao/OrderHistoryDao, stored as the raw UTC timestamp ESI
    // sent (its offset is needed elsewhere for exact expiry/duration math). `result`'s sell dates
    // come from WalletDao, which converts to local time at ingestion -- so this comparison must
    // convert issuedDate the same way, or the two sides silently drift apart by the UTC offset.
    fun pnlForOrder(
        result: FifoResult,
        typeId: Int,
        issuedDate: String,
        filledQty: Int,
    ): Double? {
        if (filledQty <= 0) return null
        val localIssuedDate = issuedDate.utcToLocalDateTime()
        val sells =
            result.realizedByType[typeId]
                ?.filter { it.date >= localIssuedDate }
                ?: return null

        var remaining = filledQty
        var totalProfit = 0.0

        for (sell in sells) {
            if (remaining <= 0) break
            val take = minOf(remaining, sell.qty)
            totalProfit += sell.profit * (take.toDouble() / sell.qty)
            remaining -= take
        }

        return if (remaining < filledQty) totalProfit else null
    }
}

// FIFO cost-basis P&L rolled up by calendar window, mirroring PnlWindow's cash-flow
// rollups so Dashboard/Wallet can show both figures side by side.
data class RealizedPnlWindow(
    val todayPnl: Double,
    val pnl7d: Double,
    val pnl30d: Double,
    // Trailing 365 days, not calendar year — same "rolling window from today" convention as
    // pnl7d/pnl30d, not a Jan-1 reset (used by the Nostr trader leaderboard, issue #17).
    val pnl365d: Double,
    val pnlAll: Double,
)

fun CostBasisService.FifoResult.realizedPnlWindow(referenceDate: LocalDate = LocalDate.now()): RealizedPnlWindow {
    val today = referenceDate.toString()
    val week7Start = referenceDate.minusDays(7).toString()
    val month30Start = referenceDate.minusDays(30).toString()
    val year365Start = referenceDate.minusDays(365).toString()

    fun dayOf(sell: CostBasisService.RealizedSellTx) = sell.date.substring(0, 10)
    return RealizedPnlWindow(
        todayPnl = realizedSells.filter { dayOf(it) == today }.sumOf { it.profit },
        pnl7d = realizedSells.filter { dayOf(it) >= week7Start }.sumOf { it.profit },
        pnl30d = realizedSells.filter { dayOf(it) >= month30Start }.sumOf { it.profit },
        pnl365d = realizedSells.filter { dayOf(it) >= year365Start }.sumOf { it.profit },
        pnlAll = totalRealizedPnl,
    )
}
