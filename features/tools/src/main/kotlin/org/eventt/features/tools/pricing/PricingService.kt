package org.eventt.features.tools.pricing

import org.eventt.core.database.StaticDataDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.eveSigFigStep
import org.eventt.features.orders.CostBasisService
import org.eventt.features.tools.ResolvedItem
import kotlin.math.round

/**
 * Sell-pricing tool: for each pasted item, look up the FIFO purchase cost, apply a margin, and
 * always undercut the current market's lowest sell order by one EVE price tick (matching how
 * Orders' hotkey undercut works) whenever that's cheaper than the margin target — this isn't
 * optional. The [marginLimitEnabled] switch controls the final *price* only: off means always
 * hand back the market-undercut price regardless of cost basis. Cost basis itself is still looked
 * up whenever a source is available either way, so the resulting Margin column (cost basis vs.
 * finalPrice, net of fees) stays populated and informative even with the limit off.
 */
object PricingService {
    fun computePrices(
        items: List<ResolvedItem>,
        characterId: Int?,
        corporationId: Int?,
        actingCharId: Int?,
        marginPct: Double,
        marginLimitEnabled: Boolean,
    ): Pair<List<PricingResult>, List<PricingWarning>> {
        val warnings = mutableListOf<PricingWarning>()

        val regionId = actingCharId?.let { resolveRegionId(it) }
        if (regionId == null) {
            warnings += PricingWarning("(region)", "could not determine your character's current region from ESI — market lookups skipped")
        }

        // Sales tax + broker fee both come off a completed sale, so the margin target must be
        // grossed up to compensate — otherwise a requested "30% margin" quietly nets ~19% after
        // CCP's cut. Cost basis already bakes in the *buy*-side broker fee (see CostBasisService),
        // so only the sell-side fees need to be applied here.
        val salesTaxPct = actingCharId?.let { StaticDataDao.getCharSalesTax(it) } ?: 8.0
        val brokerFeePct = actingCharId?.let { StaticDataDao.getCharBrokersFee(it) } ?: 3.0
        val netProceedsRate = (1.0 - (salesTaxPct + brokerFeePct) / 100.0).coerceAtLeast(0.01)

        // Computed whenever a source is picked, independent of marginLimitEnabled — an explicit
        // null/null (no source chosen) is left alone rather than falling through to
        // CostBasisService/WalletDao's "no filter" behavior, which would silently pool every
        // locally-known character's and corp's transactions together.
        val fifo =
            if (characterId != null || corporationId != null) {
                CostBasisService.compute(characterId = characterId, corporationId = corporationId)
            } else {
                null
            }

        val marketBestSellByType: Map<Int, Double?> =
            if (regionId == null) {
                emptyMap()
            } else {
                items.associate { item ->
                    item.typeId to
                        try {
                            EsiClient
                                .getMarketRegionOrders(regionId, orderType = "sell", typeId = item.typeId)
                                .mapNotNull { (it["price"] as? Number)?.toDouble() }
                                .minOrNull()
                        } catch (e: Exception) {
                            null
                        }
                }
            }

        val results =
            items.map { item ->
                val marketLow = marketBestSellByType[item.typeId]
                val marketUndercut = marketLow?.let { undercutPrice(it) }
                if (regionId != null && marketLow == null) {
                    warnings += PricingWarning(item.name, "no sell orders found in your current region")
                }

                val cb = fifo?.avgCostBasisForType(item.typeId)
                if (fifo != null && cb == null) {
                    warnings += PricingWarning(item.name, "no purchase history found — cannot compute cost basis")
                }
                // Grossed up so the requested margin is realized net of sales tax + broker fee, not
                // before it, then rounded to a real EVE price tick like the market undercut is —
                // only meaningful as a *target price* when the margin limit is actually enforced.
                val target = if (marginLimitEnabled) cb?.let { roundToSigFig(it * (1.0 + marginPct / 100.0) / netProceedsRate) } else null

                val (final, usedMarket) = resolveFinalPrice(marginLimitEnabled, target, marketUndercut)
                PricingResult(
                    item.typeId,
                    item.name,
                    item.quantity,
                    cb,
                    target,
                    marketLow,
                    marketUndercut,
                    final,
                    usedMarket,
                    salesTaxPct,
                    brokerFeePct,
                )
            }
        return results to warnings
    }

    /** name\tprice, one line per item — skips items with no resolvable final price. */
    fun formatForClipboard(results: List<PricingResult>): String =
        results
            .filter { it.finalPrice != null }
            .joinToString("\n") { "${it.name}\t${"%.2f".format(it.finalPrice)}" }

    // internal (not private) — also used by SplitterService/SplitterScreen to price cargo off the
    // acting character's actual current-region sell orders instead of ESI's global adjusted_price.
    internal fun resolveRegionId(actingCharId: Int): Int? {
        val location = EsiClient.getCharacterLocation(actingCharId) ?: return null
        val systemId = (location["solar_system_id"] as? Number)?.toInt() ?: return null
        return StaticDataDao.getSystemRegionId(systemId)
    }

    // Rounds to the nearest EVE price tick, then steps one tick below it — the same "beat the
    // best sell order by one sigfig step" rule Orders' hotkey action uses.
    private fun undercutPrice(bestSell: Double): Double {
        val step = eveSigFigStep(bestSell)
        return round(bestSell / step) * step - step
    }

    // Rounds to the nearest EVE price tick (4 significant figures) without undercutting anything
    // — used for the margin target, which is a price to list at, not a price to beat.
    private fun roundToSigFig(price: Double): Double {
        if (price <= 0) return 0.01
        val step = eveSigFigStep(price)
        return round(price / step) * step
    }

    private fun resolveFinalPrice(
        marginLimitEnabled: Boolean,
        target: Double?,
        marketUndercut: Double?,
    ): Pair<Double?, Boolean> =
        when {
            !marginLimitEnabled -> marketUndercut to true
            target != null && marketUndercut != null ->
                if (marketUndercut < target) marketUndercut to true else target to false
            target != null -> target to false
            marketUndercut != null -> marketUndercut to true
            else -> null to false
        }
}
