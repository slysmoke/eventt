package org.eventt.features.overlay

import org.eventt.core.model.eveUndercutPrice

// The Trade Calc overlay's numbers, kept out of the composables so they can be tested.

/** Broker fee and sales tax as fractions (3% = 0.03). */
internal data class Fees(
    val broker: Double,
    val tax: Double,
) {
    // Net ISK per 1 ISK of gross: a sell order pays broker + tax, an instant sell only tax.
    val orderSellNet get() = 1.0 - broker - tax
    val instantSellNet get() = 1.0 - tax
}

/** Station-trading flip: post a buy order at [buy], then a sell order at [sell]. */
internal data class Flip(
    val brokerBuy: Double,
    val brokerSell: Double,
    val salesTax: Double,
    val profit: Double,
    // Net profit relative to the sell price, the same margin Market Analysis shows.
    val marginPct: Double,
    // Net profit relative to the ISK put in (buy price + broker fee).
    val roiPct: Double,
)

internal fun flip(
    sell: Double,
    buy: Double,
    fees: Fees,
): Flip {
    val cost = buy * (1 + fees.broker)
    val profit = sell * fees.orderSellNet - cost
    return Flip(
        brokerBuy = buy * fees.broker,
        brokerSell = sell * fees.broker,
        salesTax = sell * fees.tax,
        profit = profit,
        marginPct = if (sell > 0) profit / sell * 100 else 0.0,
        roiPct = if (cost > 0) profit / cost * 100 else 0.0,
    )
}

/**
 * What stock bought at [cost] per unit (all-in, buy broker fee included — the FIFO cost basis)
 * is worth now: relisted one tick under the best sell, or dumped into the best buy.
 */
internal data class Position(
    val cost: Double,
    val breakEvenOrder: Double,
    val breakEvenInstant: Double,
    // Per unit; null when that side of the book is unknown.
    val relist: Double?,
    val dump: Double?,
)

internal fun position(
    cost: Double,
    sell: Double?,
    buy: Double?,
    fees: Fees,
) = Position(
    cost = cost,
    breakEvenOrder = cost / fees.orderSellNet.coerceAtLeast(0.01),
    breakEvenInstant = cost / fees.instantSellNet.coerceAtLeast(0.01),
    relist = sell?.let { eveUndercutPrice(it) * fees.orderSellNet - cost },
    dump = buy?.let { it * fees.instantSellNet - cost },
)

// Volume-weighted average price across the closest [depthPct]% of order rows by count (rounded
// up, min 1) — cheapest rows for sell, priciest for buy. A more realistic "quick fill" reference
// than either the single best price (only ever true for the very first unit) or the full Buy
// out/Sell out total (walks the entire book, unrealistically deep for one trade).
internal fun avgTopPrice(
    levels: List<Pair<Double, Long>>,
    cheapestFirst: Boolean,
    depthPct: Int,
): Double? {
    if (levels.isEmpty()) return null
    val sorted = if (cheapestFirst) levels.sortedBy { it.first } else levels.sortedByDescending { it.first }
    // Integer ceiling division instead of Math.ceil, to sidestep float rounding.
    val topCount = ((sorted.size * depthPct + 99) / 100).coerceAtLeast(1)
    val top = sorted.take(topCount)
    val vol = top.sumOf { it.second }
    return if (vol > 0) top.sumOf { it.first * it.second } / vol else top.map { it.first }.average()
}
