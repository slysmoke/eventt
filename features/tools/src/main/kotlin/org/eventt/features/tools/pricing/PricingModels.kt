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
)

data class PricingWarning(
    val itemName: String,
    val reason: String,
)
