package org.eventt.features.tools.pricing

data class PricingResult(
    val typeId: Int,
    val name: String,
    val quantity: Int,
    val costBasis: Double?,
    val targetPrice: Double?,
    val marketLowestSell: Double?,
    val marketUndercutPrice: Double?,
    val finalPrice: Double?,
    // true when finalPrice came from undercutting the market rather than the margin target —
    // either because the margin limit is off, or because the market undercut was cheaper.
    val usedMarketPrice: Boolean,
    // Character's sell-side fees, same value on every row — a completed sale loses both sales tax
    // and broker fee off the top, so they're needed to turn a listed price into what's actually
    // kept (cost basis already includes the *buy*-side broker fee, see CostBasisService).
    val salesTaxPct: Double,
    val brokerFeePct: Double,
) {
    private val netProceedsRate: Double get() = (1.0 - (salesTaxPct + brokerFeePct) / 100.0).coerceAtLeast(0.0)

    // The margin actually realized at finalPrice, net of sales tax + broker fee — not the same as
    // the requested margin % input whenever usedMarketPrice is true, since the market undercut can
    // land anywhere relative to cost basis, including below it (a loss). Null when there's no cost
    // basis to compare against.
    val actualMarginPct: Double?
        get() =
            if (costBasis != null && costBasis > 0 && finalPrice != null) {
                (finalPrice * netProceedsRate - costBasis) / costBasis * 100.0
            } else {
                null
            }
}

data class PricingWarning(
    val itemName: String,
    val reason: String,
)
