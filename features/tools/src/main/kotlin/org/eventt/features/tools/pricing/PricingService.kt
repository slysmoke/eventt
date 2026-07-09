package org.eventt.features.tools.pricing

import org.eventt.core.database.StaticDataDao
import org.eventt.core.esi.EsiClient
import org.eventt.features.orders.CostBasisService
import org.eventt.features.orders.eveSigFigStep
import org.eventt.features.tools.ResolvedItem
import kotlin.math.round

/**
 * Sell-pricing tool: for each pasted item, look up the FIFO purchase cost, apply a margin, and
 * always undercut the current market's lowest sell order by one EVE price tick (matching how
 * Orders' hotkey undercut works) whenever that's cheaper than the margin target — this isn't
 * optional. The [marginLimitEnabled] switch controls the margin side only: off means skip cost
 * basis entirely and just hand back the market-undercut price.
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

        val fifo = if (marginLimitEnabled) CostBasisService.compute(characterId = characterId, corporationId = corporationId) else null

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

                val cb = if (marginLimitEnabled) fifo?.avgCostBasisForType(item.typeId) else null
                if (marginLimitEnabled && cb == null) {
                    warnings += PricingWarning(item.name, "no purchase history found — cannot compute cost basis")
                }
                val target = cb?.let { it * (1.0 + marginPct / 100.0) }

                val (final, usedMarket) = resolveFinalPrice(marginLimitEnabled, target, marketUndercut)
                PricingResult(item.typeId, item.name, item.quantity, cb, target, marketLow, marketUndercut, final, usedMarket)
            }
        return results to warnings
    }

    /** name\tprice, one line per item — skips items with no resolvable final price. */
    fun formatForClipboard(results: List<PricingResult>): String =
        results
            .filter { it.finalPrice != null }
            .joinToString("\n") { "${it.name}\t${"%.2f".format(it.finalPrice)}" }

    private fun resolveRegionId(actingCharId: Int): Int? {
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
