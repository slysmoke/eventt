package org.eventt.features.orders

import kotlin.math.floor

// Relist/modification-fee tracking for a single market order. Modifying an order's price charges
// another broker's fee, discounted by the Relist Discount: Fee = (1-RD)*BR*V2 + BR*max(V2-V1, 0),
// min 100 ISK, where V1/V2 are the order's *total* value (price x volume remaining) before/after
// the change -- broker fees are charged on total order value, not on the per-unit price -- and RD
// is the Relist Discount from the Advanced Broker Relations skill: 50% base, +6 percentage points
// per level (level 0-5 -> 50/56/62/68/74/80%). Verified against EVE University's worked examples.
//
// The wallet journal has no order_id on a brokers_fee entry to link it back to a specific order,
// and only refreshes hourly -- so fees here are computed ourselves by diffing an order's price
// against its last-seen price on every refresh (see OrdersScreen's merge into ActiveOrderDao).
// A same-price relist (which still costs the relist portion in-game) is invisible to this
// approach since nothing observable changes; only a detected price change is counted.
object OrderFeeService {
    // Advanced Broker Relations skill level (0-5) -> Relist Discount percentage: 50% base + 6
    // percentage points per level.
    fun relistDiscountPct(skillLevel: Int): Double = 50.0 + 6.0 * skillLevel.coerceIn(0, 5)

    // Fee charged for relisting an order from oldPrice to newPrice, both against the same
    // remaining volume (we only detect a price change, not a quantity change, as a modification).
    fun computeModificationFee(
        oldPrice: Double,
        newPrice: Double,
        volumeRemaining: Int,
        brokerFeePct: Double,
        relistDiscountPct: Double,
    ): Double {
        val br = brokerFeePct / 100.0
        val rd = relistDiscountPct / 100.0
        val oldValue = oldPrice * volumeRemaining
        val newValue = newPrice * volumeRemaining
        val fee = maxOf(0.0, br * (newValue - oldValue)) + (1.0 - rd) * br * newValue
        return fee.coerceAtLeast(100.0)
    }

    // ponytail: heuristic estimate, not an exact countdown -- assumes future modification fees
    // resemble past ones for this order (or, with no history yet, a same-price relist floor:
    // Fee = (1-RD)*BR*V, V = price*volumeRemaining). Real future fees depend on unknown future
    // price deltas, so this is necessarily approximate.
    fun estimateUpdatesRemaining(
        volumeRemaining: Int,
        price: Double,
        netSellPricePerUnit: Double,
        costBasis: Double?,
        brokerFeePct: Double,
        relistCount: Int,
        relistFeesPaid: Double,
        relistDiscountPct: Double,
    ): Int? {
        val cost = costBasis ?: return null
        val remainingMargin = volumeRemaining * (netSellPricePerUnit - cost) - relistFeesPaid

        val br = brokerFeePct / 100.0
        val rd = relistDiscountPct / 100.0
        val avgFeePerUpdate =
            if (relistCount > 0) {
                relistFeesPaid / relistCount
            } else {
                ((1.0 - rd) * br * price * volumeRemaining).coerceAtLeast(100.0)
            }
        if (avgFeePerUpdate <= 0) return null

        return floor(remainingMargin / avgFeePerUpdate).toInt().coerceAtLeast(0)
    }
}
