package org.eventt.features.p2pmarket

import org.eventt.core.esi.EsiClient
import org.eventt.core.nostr.OrderSide

data class SavingsResult(
    val marketPrice: Double,
    val savingsPct: Double,
)

/**
 * Live "-X% vs market" badge — computed at render time against the order's own region (that's
 * the actual value proposition: skip the tax/broker fee you'd pay trading in that exact region on
 * the open market), never stored in the Nostr event itself so it's always current rather than a
 * stale snapshot from post time. Blocking network call — run from Dispatchers.IO, same convention
 * as every other ESI-backed lookup in this app (ESI's own cache layer keeps repeat calls cheap).
 */
object SavingsBadgeService {
    fun computeSavings(
        typeId: Int,
        regionId: Int,
        side: OrderSide,
        orderPrice: Double,
    ): SavingsResult? {
        val marketOrderType = if (side == OrderSide.SELL) "sell" else "buy"
        val marketPrice =
            EsiClient
                .getMarketRegionOrders(regionId, orderType = marketOrderType, typeId = typeId)
                .mapNotNull { (it["price"] as? Number)?.toDouble() }
                .let { if (side == OrderSide.SELL) it.minOrNull() else it.maxOrNull() }
                ?: return null

        // Selling OTC: cheaper than the market's cheapest sell order = savings for the buyer.
        // Buying OTC: paying more than the market's best buy order = savings for the seller
        // (they'd otherwise have to accept that lower buy-order price, or undercut it themselves).
        val savingsPct =
            when (side) {
                OrderSide.SELL -> (marketPrice - orderPrice) / marketPrice * 100.0
                OrderSide.BUY -> (orderPrice - marketPrice) / marketPrice * 100.0
            }
        return SavingsResult(marketPrice, savingsPct)
    }

    /**
     * Suggested post price for a new order: undercut (SELL) or overbid (BUY) the current market
     * best by [RECOMMENDED_UNDERCUT_PCT] — an OTC trader pays no sales tax/broker fee, so giving up
     * a couple percent versus the open market still nets more than trading through it, while
     * staying an attractive deal for the counterparty. Null if the region has no orders for this
     * type yet (nothing to base a suggestion on).
     */
    fun recommendedPrice(
        typeId: Int,
        regionId: Int,
        side: OrderSide,
    ): Double? {
        val marketOrderType = if (side == OrderSide.SELL) "sell" else "buy"
        val marketPrice =
            EsiClient
                .getMarketRegionOrders(regionId, orderType = marketOrderType, typeId = typeId)
                .mapNotNull { (it["price"] as? Number)?.toDouble() }
                .let { if (side == OrderSide.SELL) it.minOrNull() else it.maxOrNull() }
                ?: return null

        return when (side) {
            OrderSide.SELL -> marketPrice * (1 - RECOMMENDED_UNDERCUT_PCT)
            OrderSide.BUY -> marketPrice * (1 + RECOMMENDED_UNDERCUT_PCT)
        }
    }

    private const val RECOMMENDED_UNDERCUT_PCT = 0.02
}
