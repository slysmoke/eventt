package org.eventt.features.p2pmarket

import org.eventt.core.esi.EsiClient
import org.eventt.core.model.PLEX_MARKET_REGION_ID
import org.eventt.core.model.PLEX_TYPE_ID
import org.eventt.core.nostr.OrderSide

data class SavingsResult(
    val marketPrice: Double,
    val savingsPct: Double,
)

data class RecommendedPrice(
    val basePrice: Double,
    val adjustmentPct: Double,
    val price: Double,
)

// Looking up PLEX orders in whatever region the order form has selected (e.g. The Forge) always
// comes back empty — PLEX only lists in its global virtual region (PLEX_MARKET_REGION_ID).

// The Forge (Jita) is New Eden's deepest, most liquid market — a recommended price sourced from
// wherever the order form happens to be posting (a quiet region might have zero relevant orders,
// or one stale outlier) is much less reliable than always pricing off the hub everyone actually
// trades in, then letting the user post that price into whichever region they choose.
private const val THE_FORGE_REGION_ID = 10000002

private fun effectiveMarketRegion(
    typeId: Int,
    regionId: Int,
): Int = if (typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else regionId

private fun referencePriceRegion(typeId: Int): Int = if (typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else THE_FORGE_REGION_ID

/**
 * Live "-X% vs market" badge — computed at render time against the order's own region (that's
 * the actual value proposition: skip the tax/broker fee you'd pay trading in that exact region on
 * the open market), never stored in the Nostr event itself so it's always current rather than a
 * stale snapshot from post time. It's a snapshot of *right now*, not a promise — market prices
 * move, so a favorable badge today doesn't guarantee the market wouldn't have been the better
 * choice by the time you'd actually have traded there. Blocking network call — run from
 * Dispatchers.IO, same convention as every other ESI-backed lookup in this app (ESI's own cache
 * layer keeps repeat calls cheap).
 */
object SavingsBadgeService {
    /**
     * [salesTaxPct] is the *viewer's* effective sales tax rate (e.g. 8.0, not 0.08) — only used for
     * a BUY order's comparison, see below.
     */
    fun computeSavings(
        typeId: Int,
        regionId: Int,
        side: OrderSide,
        orderPrice: Double,
        salesTaxPct: Double,
    ): SavingsResult? {
        val marketOrderType = if (side == OrderSide.SELL) "sell" else "buy"
        val marketPrice =
            EsiClient
                .getMarketRegionOrders(effectiveMarketRegion(typeId, regionId), orderType = marketOrderType, typeId = typeId)
                .mapNotNull { (it["price"] as? Number)?.toDouble() }
                .let { if (side == OrderSide.SELL) it.minOrNull() else it.maxOrNull() }
                ?: return null

        val savingsPct =
            when (side) {
                // Taking this SELL order instead of instantly buying on the market: the market
                // charges a buyer nothing extra (sales tax only ever falls on the seller), so it's
                // a plain price comparison against the market's own cheapest sell order.
                OrderSide.SELL -> (marketPrice - orderPrice) / marketPrice * 100.0
                // Filling this BUY order instead of instantly selling into the market's best buy
                // order: that market alternative costs sales tax, so the comparison has to be
                // against what you'd actually net after tax, not the raw buy-order price.
                OrderSide.BUY -> {
                    val netIfSoldOnMarket = marketPrice * (1 - salesTaxPct / 100.0)
                    (orderPrice - netIfSoldOnMarket) / netIfSoldOnMarket * 100.0
                }
            }
        return SavingsResult(marketPrice, savingsPct)
    }

    /**
     * Suggested post price for a new order — undercuts the relevant best price from The Forge by
     * whichever fee *you'd* have paid placing the equivalent order on the real market:
     * - SELL: best sell price minus [salesTaxPct] — a market sell order costs you sales tax on
     *   top of the broker fee, so shaving off the tax passes that saving to the buyer as a
     *   discount while you still net what the broker fee alone would have left you.
     * - BUY: best buy price minus [brokerFeePct] — a market buy order only costs you the broker
     *   fee (sales tax falls on whoever sells, not you), so this mirrors the SELL case with the
     *   fee that's actually yours to give up.
     *
     * Both percentages are e.g. 8.0 (not 0.08) — pass the poster's own effective rates (see
     * [org.eventt.core.database.StaticDataDao.getCharSalesTax]/`getCharBrokersFee`), since
     * skills/standings move them well away from the 8%/3% bases. Priced off The Forge regardless
     * of which region the order is actually being posted to (see [THE_FORGE_REGION_ID]) —
     * [typeId] alone decides the reference region. Null if The Forge has no orders for this type
     * yet (nothing to base a suggestion on).
     */
    fun recommendedPrice(
        typeId: Int,
        side: OrderSide,
        salesTaxPct: Double,
        brokerFeePct: Double,
    ): RecommendedPrice? {
        val marketOrderType = if (side == OrderSide.SELL) "sell" else "buy"
        val marketPrice =
            EsiClient
                .getMarketRegionOrders(referencePriceRegion(typeId), orderType = marketOrderType, typeId = typeId)
                .mapNotNull { (it["price"] as? Number)?.toDouble() }
                .let { if (side == OrderSide.SELL) it.minOrNull() else it.maxOrNull() }
                ?: return null

        val adjustmentPct = if (side == OrderSide.SELL) salesTaxPct else brokerFeePct
        val price = marketPrice * (1 - adjustmentPct / 100.0)
        return RecommendedPrice(basePrice = marketPrice, adjustmentPct = adjustmentPct, price = price)
    }
}
