package org.eve.trader.features.orders

import org.eve.trader.core.database.WalletDao

object CostBasisService {

    data class InventoryItem(
        val typeId: Int,
        val typeName: String,
        val remainingQty: Int,
        val avgCostBasis: Double,
        val totalCostBasis: Double,
    )

    data class RealizedSellTx(
        val date: String,
        val typeId: Int,
        val qty: Int,
        val sellPrice: Double,
        val costBasis: Double,
        val profit: Double,
        val marginPct: Double,
    )

    data class FifoResult(
        val inventory: Map<Int, InventoryItem>,
        val realizedSells: List<RealizedSellTx>,   // sorted by date asc
    ) {
        val totalRealizedPnl: Double = realizedSells.sumOf { it.profit }
        val realizedByType: Map<Int, List<RealizedSellTx>> = realizedSells.groupBy { it.typeId }
    }

    fun compute(characterId: Int): FifoResult {
        val transactions = WalletDao.getAllTransactions(characterId)

        val lots      = mutableMapOf<Int, ArrayDeque<Pair<Int, Double>>>()
        val typeNames = mutableMapOf<Int, String>()
        val realized  = mutableListOf<RealizedSellTx>()

        for (tx in transactions) {  // already sorted ASC by WalletDao
            typeNames[tx.typeId] = tx.typeName
            if (tx.isBuy) {
                lots.getOrPut(tx.typeId) { ArrayDeque() }.addLast(tx.quantity to tx.unitPrice)
            } else {
                val queue = lots.getOrPut(tx.typeId) { ArrayDeque() }
                var remaining       = tx.quantity
                var costConsumed    = 0.0
                var qtyMatched      = 0

                while (remaining > 0 && queue.isNotEmpty()) {
                    val (lotQty, lotPrice) = queue.removeFirst()
                    val consume = minOf(remaining, lotQty)
                    costConsumed += consume * lotPrice
                    qtyMatched   += consume
                    if (consume < lotQty) queue.addFirst((lotQty - consume) to lotPrice)
                    remaining -= consume
                }

                if (qtyMatched > 0) {
                    val cb      = costConsumed / qtyMatched
                    val profit  = qtyMatched * (tx.unitPrice - cb)
                    val margin  = if (cb > 0) (tx.unitPrice - cb) / cb * 100.0 else 0.0
                    realized.add(RealizedSellTx(tx.date, tx.typeId, qtyMatched, tx.unitPrice, cb, profit, margin))
                }
            }
        }

        val inventory = lots.mapValues { (typeId, queue) ->
            val qty   = queue.sumOf { it.first }
            val cost  = queue.sumOf { it.first.toDouble() * it.second }
            InventoryItem(typeId, typeNames[typeId] ?: "", qty, if (qty > 0) cost / qty else 0.0, cost)
        }.filter { it.value.remainingQty > 0 }

        return FifoResult(inventory, realized)
    }

    // Returns realized P&L for a specific fulfilled sell order using date+qty matching.
    // Consumes realized sells chronologically from the order's issued date until fill qty is exhausted.
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

        var remaining    = filledQty
        var totalProfit  = 0.0

        for (sell in sells) {
            if (remaining <= 0) break
            val take     = minOf(remaining, sell.qty)
            totalProfit += sell.profit * (take.toDouble() / sell.qty)
            remaining   -= take
        }

        return if (remaining < filledQty) totalProfit else null
    }
}
