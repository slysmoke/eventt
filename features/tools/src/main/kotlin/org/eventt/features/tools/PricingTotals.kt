package org.eventt.features.tools

import org.eventt.features.tools.pricing.PricingResult

/** Totals over a pricing run: what the list is worth at the final prices and what it nets over cost. */
internal data class PricingTotals(
    val items: Int,
    val listValue: Double,
    // Only rows with both a cost basis and a final price count toward cost and profit.
    val cost: Double,
    val netProfit: Double,
    val belowCost: Int,
    val undercut: Int,
) {
    val marginPct: Double? get() = if (cost > 0) netProfit / cost * 100 else null
}

internal fun pricingTotals(results: List<PricingResult>): PricingTotals {
    val known = results.filter { it.costBasis != null && it.costBasis > 0 && it.finalPrice != null }
    return PricingTotals(
        items = results.size,
        listValue = results.sumOf { (it.finalPrice ?: 0.0) * it.quantity },
        cost = known.sumOf { it.costBasis!! * it.quantity },
        netProfit = known.sumOf { (it.actualMarginPct!! / 100) * it.costBasis!! * it.quantity },
        belowCost = known.count { it.actualMarginPct!! < 0 },
        undercut = results.count { it.usedMarketPrice && it.finalPrice != null },
    )
}
