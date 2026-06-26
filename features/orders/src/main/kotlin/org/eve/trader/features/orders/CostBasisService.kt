package org.eve.trader.features.orders

import org.eve.trader.core.database.WalletDao

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
        val avgCostBasis: Double,   // per unit, includes buy broker fee
        val totalCostBasis: Double,
    )

    data class RealizedSellTx(
        val date: String,
        val typeId: Int,
        val qty: Int,
        val sellPrice: Double,      // gross sell price per unit
        val costBasis: Double,      // FIFO cost per unit (includes buy broker fee)
        val profit: Double,         // net profit = (sellPrice×sellMult − costBasis) × qty
        val marginPct: Double,      // net profit / total cost × 100
    )

    data class FifoResult(
        val inventory: Map<Int, InventoryItem>,
        val realizedSells: List<RealizedSellTx>,   // sorted by date asc
        val taxConfig: TaxConfig,
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

    fun compute(characterId: Int, taxConfig: TaxConfig = TaxConfig()): FifoResult {
        val transactions = WalletDao.getAllTransactions(characterId)

        val lots      = mutableMapOf<Int, ArrayDeque<Pair<Int, Double>>>()
        val typeNames = mutableMapOf<Int, String>()
        val realized  = mutableListOf<RealizedSellTx>()

        for (tx in transactions) {  // already sorted ASC by WalletDao
            typeNames[tx.typeId] = tx.typeName
            if (tx.isBuy) {
                // Store adjusted cost: price + broker fee paid to place the buy order.
                val adjustedCost = tx.unitPrice * taxConfig.buyMultiplier
                lots.getOrPut(tx.typeId) { ArrayDeque() }.addLast(tx.quantity to adjustedCost)
            } else {
                val queue = lots.getOrPut(tx.typeId) { ArrayDeque() }
                var remaining    = tx.quantity
                var costConsumed = 0.0
                var qtyMatched   = 0

                while (remaining > 0 && queue.isNotEmpty()) {
                    val (lotQty, lotPrice) = queue.removeFirst()
                    val consume = minOf(remaining, lotQty)
                    costConsumed += consume * lotPrice
                    qtyMatched   += consume
                    if (consume < lotQty) queue.addFirst((lotQty - consume) to lotPrice)
                    remaining -= consume
                }

                if (qtyMatched > 0) {
                    val cb            = costConsumed / qtyMatched
                    val netSellPrice  = tx.unitPrice * taxConfig.sellMultiplier
                    val profit        = qtyMatched * (netSellPrice - cb)
                    val margin        = if (cb > 0) (netSellPrice - cb) / cb * 100.0 else 0.0
                    realized.add(RealizedSellTx(tx.date, tx.typeId, qtyMatched, tx.unitPrice, cb, profit, margin))
                }
            }
        }

        val inventory = lots.mapValues { (typeId, queue) ->
            val qty  = queue.sumOf { it.first }
            val cost = queue.sumOf { it.first.toDouble() * it.second }
            InventoryItem(typeId, typeNames[typeId] ?: "", qty, if (qty > 0) cost / qty else 0.0, cost)
        }.filter { it.value.remainingQty > 0 }

        return FifoResult(inventory, realized, taxConfig)
    }

    // Returns realized P&L for a specific fulfilled sell order using date+qty matching.
    fun pnlForOrder(
        result: FifoResult,
        typeId: Int,
        issuedDate: String,
        filledQty: Int,
    ): Double? {
        if (filledQty <= 0) return null
        val sells = result.realizedByType[typeId]
            ?.filter { it.date >= issuedDate }
            ?: return null

        var remaining   = filledQty
        var totalProfit = 0.0

        for (sell in sells) {
            if (remaining <= 0) break
            val take     = minOf(remaining, sell.qty)
            totalProfit += sell.profit * (take.toDouble() / sell.qty)
            remaining   -= take
        }

        return if (remaining < filledQty) totalProfit else null
    }
}
