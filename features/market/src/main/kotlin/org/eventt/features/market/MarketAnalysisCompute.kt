package org.eventt.features.market

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.eventt.core.database.MarketDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.PLEX_MARKET_REGION_ID
import org.eventt.core.model.PLEX_TYPE_ID
import org.eventt.core.model.eveSigFigStep
import kotlin.math.round

// ─── Analysis helpers (run on Dispatchers.IO) ─────────────────────────────

internal fun buildGroupSubtree(rootGroupId: Int): Set<Int> {
    val result = mutableSetOf<Int>()
    val queue = ArrayDeque<Int>()
    queue.add(rootGroupId)
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        result.add(id)
        StaticDataDao.getChildMarketGroups(id).forEach { queue.add(it.marketGroupId) }
    }
    return result
}

// ─── Analysis functions (run on Dispatchers.IO) ───────────────────────────

// True when a buy order sitting at a *different* location — another station, a citadel, a
// neighboring system — would still count as competing for `stationId`, because it sits exactly
// there or its stated `range` (region-wide, same solar system, or N jumps via distanceFromStation)
// actually reaches that far. Only buy orders have this "remote reach" mechanic in EVE — sell
// orders always require physically being at their exact location — so this is only ever applied
// to buy orders; sell-order matching stays a plain exact-location filter everywhere it's used.
// Shared by both Station Trading and Inter-Region, one call per side (station/region) they check.
private fun isBuyOrderReachable(
    order: Map<String, Any?>,
    stationId: Long?,
    stationSystemId: Int?,
    distanceFromStation: Map<Int, Int>,
    locationSystemCache: java.util.concurrent.ConcurrentHashMap<Long, Int?>?,
): Boolean {
    if (stationId == null) return true
    val loc = (order["location_id"] as? Number)?.toLong() ?: return false
    if (loc == stationId) return true
    if (stationSystemId == null) return false

    fun resolveSystemId(locationId: Long): Int? =
        locationSystemCache?.getOrPut(locationId) { StaticDataDao.getStationById(locationId)?.systemId }
            ?: StaticDataDao.getStationById(locationId)?.systemId
    return when (val range = order["range"] as? String ?: "station") {
        "region" -> {
            true
        }

        "solarsystem" -> {
            resolveSystemId(loc) == stationSystemId
        }

        "station" -> {
            false
        }

        else -> {
            val jumps = range.toIntOrNull()
            val orderSystemId = if (jumps != null) resolveSystemId(loc) else null
            val dist = orderSystemId?.let { distanceFromStation[it] }
            jumps != null && dist != null && dist <= jumps
        }
    }
}

internal fun computeOpportunityForType(
    typeId: Int,
    orders: List<Map<String, Any?>>,
    regionId: Int,
    minMarginPct: Double,
    minDailyVol: Long,
    maxBuyPrice: Double,
    minNetProfit: Double,
    brokerFeePct: Double,
    salesTaxPct: Double,
    stationId: Long? = null,
    historySource: String = "esi",
    stationSystemId: Int? = null,
    distanceFromStation: Map<Int, Int> = emptyMap(),
    locationSystemCache: java.util.concurrent.ConcurrentHashMap<Long, Int?>? = null,
    spikeFilter: SpikeFilter = SpikeFilter.ANY,
    spikePriceMultiplier: Double = 1.5,
    spikeVolumeMultiplier: Double = 5.0,
    spikeWindowDays: Int = 30,
): StationOpportunity? {
    fun Map<String, Any?>.loc() = (get("location_id") as? Number)?.toLong()

    val sells = orders.filter { (it["is_buy_order"] as? Boolean) == false && (stationId == null || it.loc() == stationId) }
    val buys =
        orders.filter { order ->
            (order["is_buy_order"] as? Boolean) == true &&
                isBuyOrderReachable(order, stationId, stationSystemId, distanceFromStation, locationSystemCache)
        }
    if (sells.isEmpty() || buys.isEmpty()) return null

    val bestSell = sells.minOf { (it["price"] as? Number)?.toDouble() ?: Double.MAX_VALUE }
    val bestBuy = buys.maxOf { (it["price"] as? Number)?.toDouble() ?: 0.0 }

    if (bestSell > maxBuyPrice) return null
    val grossProfit = bestSell - bestBuy
    if (grossProfit <= 0) return null
    val fees = (bestSell + bestBuy) * brokerFeePct / 100.0 + bestSell * salesTaxPct / 100.0
    val netProfit = grossProfit - fees
    // NET margin, after broker fees and sales tax — the same number the Trade Calc overlay shows
    // for the same prices. It used to be gross, which made "Margin ≥ 5%" pass trades whose real
    // margin was negative once ~4-5% of fees were paid.
    val marginPct = netProfit / bestSell * 100.0
    if (marginPct < minMarginPct) return null
    if (netProfit < minNetProfit) return null

    val type = StaticDataDao.getTypeById(typeId) ?: return null
    val history = fetchHistory(typeId, regionId, historySource, days = maxOf(30, spikeWindowDays))
    val medianDailyVol = medianDailyVolume(history)
    if (medianDailyVol < minDailyVol && minDailyVol > 0) return null

    val spikeDetected = detectPriceSpike(history, spikePriceMultiplier, spikeVolumeMultiplier, spikeWindowDays)
    if (spikeFilter == SpikeFilter.EXCLUDE && spikeDetected) return null
    if (spikeFilter == SpikeFilter.ONLY && !spikeDetected) return null

    return StationOpportunity(
        typeId = typeId,
        typeName = type.name,
        bestSell = bestSell,
        bestBuy = bestBuy,
        grossProfit = grossProfit,
        netProfit = netProfit,
        marginPct = marginPct,
        roiPct = netProfit / bestBuy * 100.0,
        dailyVolume = medianDailyVol,
        sellOrderCount = sells.size,
        buyOrderCount = buys.size,
        estimatedDailyProfit = netProfit * medianDailyVol.coerceAtLeast(1),
        priceChange7d = compute7dChange(history),
        spikeDetected = spikeDetected,
    )
}

// ─── Order-book walkers (inter-region "real quantity" calculation) ────────

// (volume, exact accumulated profit, volume-weighted avg buy price, volume-weighted avg sell
// price) for the lots actually consumed by a walk. For the two single-sided walkers, the side
// that's held fixed just echoes its constant price back — only the walked side is a real average
// — so callers can use avgBuyPrice/avgSellPrice uniformly regardless of trade type. avgBuyPrice
// exists precisely because the best (first) lot's price used to stand in for the whole walked
// volume: e.g. SELL_TO_SELL might walk 10 cheap units plus 200 pricier ones, and the buy price
// shown to the user needs to reflect what the 210 units actually cost on average, not the price
// of the first 10.
internal data class LotWalkResult(
    val volume: Long,
    val profit: Double,
    val avgBuyPrice: Double,
    val avgSellPrice: Double,
)

// Walks a source SELL order book (ascending price — cheapest first) against a FIXED destination
// price, accumulating volume from each lot while its own NET margin (after fees and shipping,
// relative to the sell price — the same definition the opportunity-level filter uses) still
// clears minMarginPct. Stops at the first lot that doesn't clear it: prices only get worse
// deeper into the book, so nothing beyond that point would either.
internal fun walkSourceSellLots(
    lots: List<Pair<Double, Long>>, // (price, volume_remain), ascending by price
    fixedSellPrice: Double,
    shippingPerUnit: Double,
    minMarginPct: Double,
    feeForBuyPrice: (Double) -> Double,
): LotWalkResult {
    var volume = 0L
    var profit = 0.0
    var buyPriceSum = 0.0
    for ((buyPrice, qty) in lots) {
        if (qty <= 0) continue
        val gross = fixedSellPrice - buyPrice
        val net = gross - feeForBuyPrice(buyPrice) - shippingPerUnit
        val margin = if (fixedSellPrice > 0) net / fixedSellPrice * 100.0 else 0.0
        if (net <= 0 || margin < minMarginPct) break
        volume += qty
        profit += net * qty
        buyPriceSum += buyPrice * qty
    }
    val avgBuyPrice = if (volume > 0) buyPriceSum / volume else fixedSellPrice
    return LotWalkResult(volume, profit, avgBuyPrice, fixedSellPrice)
}

// Walks a destination BUY order book (descending price — best first) against a FIXED source
// price. Symmetric to walkSourceSellLots.
internal fun walkDestBuyLots(
    lots: List<Pair<Double, Long>>, // (price, volume_remain), descending by price
    fixedBuyPrice: Double,
    shippingPerUnit: Double,
    minMarginPct: Double,
    feeForSellPrice: (Double) -> Double,
): LotWalkResult {
    var volume = 0L
    var profit = 0.0
    var sellPriceSum = 0.0
    for ((sellPrice, qty) in lots) {
        if (qty <= 0) continue
        val gross = sellPrice - fixedBuyPrice
        val net = gross - feeForSellPrice(sellPrice) - shippingPerUnit
        val margin = if (sellPrice > 0) net / sellPrice * 100.0 else 0.0
        if (net <= 0 || margin < minMarginPct) break
        volume += qty
        profit += net * qty
        sellPriceSum += sellPrice * qty
    }
    val avgSellPrice = if (volume > 0) sellPriceSum / volume else fixedBuyPrice
    return LotWalkResult(volume, profit, fixedBuyPrice, avgSellPrice)
}

// Walks BOTH the source sell book and destination buy book at once (SELL_TO_BUY, where neither
// side is our own placed order): matches the cheapest remaining source lot against the best
// remaining destination lot, consuming whichever side's current lot runs out first — like merging
// two sorted runs. Stops as soon as the current pairing's margin no longer clears minMarginPct.
internal fun walkCrossedBook(
    sellLots: List<Pair<Double, Long>>, // ascending
    buyLots: List<Pair<Double, Long>>, // descending
    shippingPerUnit: Double,
    minMarginPct: Double,
    feeFor: (buyPrice: Double, sellPrice: Double) -> Double,
): LotWalkResult {
    var i = 0
    var j = 0
    var remainingSell = sellLots.getOrNull(0)?.second ?: 0L
    var remainingBuy = buyLots.getOrNull(0)?.second ?: 0L
    var volume = 0L
    var profit = 0.0
    var buyPriceSum = 0.0
    var sellPriceSum = 0.0
    while (i < sellLots.size && j < buyLots.size) {
        val buyPrice = sellLots[i].first
        val sellPrice = buyLots[j].first
        val gross = sellPrice - buyPrice
        val net = gross - feeFor(buyPrice, sellPrice) - shippingPerUnit
        val margin = if (sellPrice > 0) net / sellPrice * 100.0 else 0.0
        if (net <= 0 || margin < minMarginPct) break
        val take = minOf(remainingSell, remainingBuy)
        if (take <= 0) break
        volume += take
        profit += net * take
        buyPriceSum += buyPrice * take
        sellPriceSum += sellPrice * take
        remainingSell -= take
        remainingBuy -= take
        if (remainingSell <= 0) {
            i++
            remainingSell = sellLots.getOrNull(i)?.second ?: 0L
        }
        if (remainingBuy <= 0) {
            j++
            remainingBuy = buyLots.getOrNull(j)?.second ?: 0L
        }
    }
    val avgBuyPrice = if (volume > 0) buyPriceSum / volume else 0.0
    val avgSellPrice = if (volume > 0) sellPriceSum / volume else 0.0
    return LotWalkResult(volume, profit, avgBuyPrice, avgSellPrice)
}

// Solves netMarginPct = targetMarginPct for sellPrice, using this trade type's own fee formula
// (mirrors the `fees` calc further down in computeRegionOpportunityForType) — i.e. "what would I
// need to sell at, after tax/broker fees and shipping, to net exactly this much margin" — rather
// than a flat markup on buyPrice that ignores fees entirely. Returns null if the target margin is
// unreachable at any price (fees alone consume 100% or more of the sell price).
private fun targetSellPriceForMargin(
    tradeType: InterRegionTradeType,
    buyPrice: Double,
    shipping: Double,
    brokerFeePct: Double,
    salesTaxPct: Double,
    targetMarginPct: Double,
): Double? {
    val taxFrac = salesTaxPct / 100.0
    val brokerFrac = brokerFeePct / 100.0
    val marginFrac = targetMarginPct / 100.0
    val (cost, feeFrac) =
        when (tradeType) {
            InterRegionTradeType.SELL_TO_BUY -> {
                (buyPrice + shipping) to taxFrac
            }

            InterRegionTradeType.SELL_TO_SELL -> {
                (buyPrice + shipping) to (taxFrac + brokerFrac)
            }

            InterRegionTradeType.BUY_TO_BUY -> {
                (buyPrice * (1.0 + brokerFrac) + shipping) to taxFrac
            }

            InterRegionTradeType.BUY_TO_SELL, InterRegionTradeType.SAFE_BUY_TO_SELL -> {
                (buyPrice * (1.0 + brokerFrac) + shipping) to (taxFrac + brokerFrac)
            }
        }
    val denominator = 1.0 - feeFrac - marginFrac
    if (denominator <= 0.0) return null
    return cost / denominator
}

internal fun computeRegionOpportunityForType(
    typeId: Int,
    buyRegionOrders: List<Map<String, Any?>>,
    sellRegionOrders: List<Map<String, Any?>>,
    buyRegionId: Int,
    sellRegionId: Int,
    buyRegionName: String,
    sellRegionName: String,
    tradeType: InterRegionTradeType,
    filterMarketGroupIds: Set<Int>?,
    iskPerM3: Double,
    maxCargoM3: Double,
    minMarginPct: Double,
    minNetProfit: Double,
    brokerFeePct: Double,
    salesTaxPct: Double,
    buyStationId: Long? = null,
    sellStationId: Long? = null,
    historySource: String = "esi",
    // Same citadel/jump-range reachability as Station Trading (see isBuyOrderReachable), applied
    // independently to each side's buy orders — srcBuy (an order you'd place at the buy region)
    // and dstBuy (an existing order you'd instantly sell into at the sell region) each have their
    // own station/system/distance context since the two regions are unrelated.
    buyStationSystemId: Int? = null,
    buyDistanceFromStation: Map<Int, Int> = emptyMap(),
    sellStationSystemId: Int? = null,
    sellDistanceFromStation: Map<Int, Int> = emptyMap(),
    locationSystemCache: java.util.concurrent.ConcurrentHashMap<Long, Int?>? = null,
    // See the comment where this is applied to sellPrice below — off by default, this is an
    // opt-in sanity check you switch on to see what profit looks like at a conservative,
    // cost-based sell price rather than whatever the (possibly thin/unreliable) market shows.
    marginLimitEnabled: Boolean = false,
    marginLimitPct: Double = 0.0,
    // Alternative to the iskPerM3 volume-based shipping estimate: many courier/freight services
    // price a haul as a percentage of the cargo's value (collateral risk) rather than its bulk --
    // more realistic for expensive, low-volume items where m3-based shipping understates the real
    // cost. When on, shipping = buyPrice * shippingCostPct / 100 instead of itemVol * iskPerM3.
    shippingByCostEnabled: Boolean = false,
    shippingCostPct: Double = 0.0,
    spikeFilter: SpikeFilter = SpikeFilter.ANY,
    spikePriceMultiplier: Double = 1.5,
    spikeVolumeMultiplier: Double = 5.0,
    spikeWindowDays: Int = 30,
): RegionOpportunity? {
    fun Map<String, Any?>.price() = (get("price") as? Number)?.toDouble() ?: 0.0

    fun Map<String, Any?>.isBuyOrd() = get("is_buy_order") as? Boolean == true

    fun Map<String, Any?>.loc() = (get("location_id") as? Number)?.toLong()

    fun Map<String, Any?>.volRemain() = (get("volume_remain") as? Number)?.toLong() ?: 0L

    val buyFiltered = if (buyStationId != null) buyRegionOrders.filter { it.loc() == buyStationId } else buyRegionOrders
    val sellFiltered = if (sellStationId != null) sellRegionOrders.filter { it.loc() == sellStationId } else sellRegionOrders

    // Real, walkable order books — cheapest sell first / best buy first — for the two sides that
    // represent consuming someone else's existing liquidity rather than placing our own order.
    val srcSellLots = buyFiltered.filter { !it.isBuyOrd() }.map { it.price() to it.volRemain() }.sortedBy { it.first }
    val dstBuyLots =
        sellRegionOrders
            .filter {
                it.isBuyOrd() &&
                    isBuyOrderReachable(it, sellStationId, sellStationSystemId, sellDistanceFromStation, locationSystemCache)
            }.map { it.price() to it.volRemain() }
            .sortedByDescending { it.first }

    val srcSell = srcSellLots.firstOrNull()?.first
    val srcBuy =
        buyRegionOrders
            .filter {
                it.isBuyOrd() &&
                    isBuyOrderReachable(it, buyStationId, buyStationSystemId, buyDistanceFromStation, locationSystemCache)
            }.maxOfOrNull { it.price() }
    val dstBuy = dstBuyLots.firstOrNull()?.first
    val dstSell = sellFiltered.filter { !it.isBuyOrd() }.minOfOrNull { it.price() }

    val buyPrice =
        when (tradeType) {
            InterRegionTradeType.SELL_TO_BUY, InterRegionTradeType.SELL_TO_SELL -> {
                srcSell
            }

            InterRegionTradeType.BUY_TO_BUY, InterRegionTradeType.BUY_TO_SELL -> {
                srcBuy
            }

            // Priced off the sell side, not the buy side — see the enum's doc comment. But it must
            // still never sit at or behind the current best buy order (it would just never fill,
            // parked behind someone else's bid) — when the fee-net price would tie or lose, bump it
            // one EVE price-tick above the current best buy instead, so it becomes the winning bid.
            InterRegionTradeType.SAFE_BUY_TO_SELL -> {
                srcSell?.let { sell ->
                    val raw = sell * (1.0 - (salesTaxPct + brokerFeePct) / 100.0)
                    if (srcBuy != null && raw <= srcBuy) {
                        val step = eveSigFigStep(srcBuy)
                        round(srcBuy / step) * step + step
                    } else {
                        val step = eveSigFigStep(raw)
                        round(raw / step) * step
                    }
                }
            }
        } ?: return null
    val rawSellPrice =
        when (tradeType) {
            InterRegionTradeType.SELL_TO_BUY, InterRegionTradeType.BUY_TO_BUY -> dstBuy
            InterRegionTradeType.SELL_TO_SELL, InterRegionTradeType.BUY_TO_SELL, InterRegionTradeType.SAFE_BUY_TO_SELL -> dstSell
        }

    val type = StaticDataDao.getTypeById(typeId) ?: return null
    if (filterMarketGroupIds != null && type.marketGroupId !in filterMarketGroupIds) return null

    val itemVol = type.packagedVolume.takeIf { it > 0 } ?: type.volume.takeIf { it > 0 } ?: 1.0
    if (itemVol > maxCargoM3) return null

    val shipping = if (shippingByCostEnabled) buyPrice * shippingCostPct / 100.0 else itemVol * iskPerM3

    // A single stale/outlier order can make the destination's best price look far better than
    // it actually is to trade at — a thin market's "best sell" might be one listing nobody's
    // going to pay, and its "best buy" one lowball nobody's going to fill either. targetSellPrice
    // solves for the sell price that nets exactly marginLimitPct after this trade type's own
    // tax/broker/shipping costs (not a flat markup on buyPrice that ignores them), so every profit
    // figure below reflects a realistic, cost-based margin instead of trusting whatever number the
    // order book happened to have. When the destination has no order at all (rawSellPrice null —
    // e.g. the market's simply sold out), that computed price is the only thing to go on, so it's
    // used outright rather than skipping the item.
    val targetSellPrice =
        if (marginLimitEnabled) {
            targetSellPriceForMargin(tradeType, buyPrice, shipping, brokerFeePct, salesTaxPct, marginLimitPct)
        } else {
            null
        }
    val sellPrice =
        when {
            rawSellPrice != null && targetSellPrice != null -> minOf(rawSellPrice, targetSellPrice)
            rawSellPrice != null -> rawSellPrice
            targetSellPrice != null -> targetSellPrice
            else -> return null
        }

    if (sellPrice <= buyPrice) return null

    // Fee formula per trade type, parameterized on whichever buy/sell price is in play — reused
    // below both for the best-price headline numbers and, after the walk, for the volume-weighted
    // average price numbers (their fee shape is identical, only the prices plugged in differ).
    fun feesFor(
        bp: Double,
        sp: Double,
    ) = when (tradeType) {
        InterRegionTradeType.SELL_TO_BUY -> {
            sp * salesTaxPct / 100.0
        }

        InterRegionTradeType.SELL_TO_SELL -> {
            sp * (salesTaxPct + brokerFeePct) / 100.0
        }

        InterRegionTradeType.BUY_TO_BUY -> {
            bp * brokerFeePct / 100.0 + sp * salesTaxPct / 100.0
        }

        InterRegionTradeType.BUY_TO_SELL, InterRegionTradeType.SAFE_BUY_TO_SELL -> {
            bp * brokerFeePct / 100.0 + sp * (salesTaxPct + brokerFeePct) / 100.0
        }
    }

    val grossProfit = sellPrice - buyPrice
    val fees = feesFor(buyPrice, sellPrice)
    val netProfit = grossProfit - fees - shipping
    // NET margin relative to the sell price — same convention as Station Trading and the Trade
    // Calc overlay. The old gross/buyPrice figure lives on as roiPct below.
    val marginPct = netProfit / sellPrice * 100.0
    if (marginPct < minMarginPct) return null

    // Real achievable quantity: walk whichever side(s) represent existing order-book liquidity
    // (not our own placed order) from the best price down, stopping once a lot's own margin drops
    // below minMarginPct — a deep, cheap/expensive tail lot shouldn't inflate the "buy this many"
    // figure just because the *best* price on the book was great.
    val walkResult =
        when (tradeType) {
            InterRegionTradeType.BUY_TO_BUY -> {
                walkDestBuyLots(dstBuyLots, fixedBuyPrice = buyPrice, shippingPerUnit = shipping, minMarginPct = minMarginPct) { sellP ->
                    buyPrice * brokerFeePct / 100.0 + sellP * salesTaxPct / 100.0
                }
            }

            InterRegionTradeType.SELL_TO_SELL -> {
                walkSourceSellLots(srcSellLots, fixedSellPrice = sellPrice, shippingPerUnit = shipping, minMarginPct = minMarginPct) {
                    sellPrice * (salesTaxPct + brokerFeePct) / 100.0
                }
            }

            InterRegionTradeType.SELL_TO_BUY -> {
                walkCrossedBook(srcSellLots, dstBuyLots, shippingPerUnit = shipping, minMarginPct = minMarginPct) { _, sellP ->
                    sellP * salesTaxPct / 100.0
                }
            }

            InterRegionTradeType.BUY_TO_SELL, InterRegionTradeType.SAFE_BUY_TO_SELL -> {
                LotWalkResult(0L, 0.0, buyPrice, sellPrice)
            }
        }
    val profitableVolume = walkResult.volume
    val profitableTotalProfit = walkResult.profit

    // The headline buy/sell prices above are the *best* price on the book — real for a single
    // unit, but misleading once the walk had to reach past it to fill profitableVolume units.
    // Once volume was actually walked, replace them with the volume-weighted average price paid/
    // received for that volume, and recompute profit/margin from those — so "Buy" and "Margin" in
    // the UI describe the whole batch, not just its first, cheapest unit. Every fee formula above
    // is affine in the walked price(s), so the average-of-margins equals the margin-of-averages:
    // this isn't an approximation, it's the exact same number the walk already accounted for.
    val (finalBuyPrice, finalSellPrice) =
        if (profitableVolume > 0) walkResult.avgBuyPrice to walkResult.avgSellPrice else buyPrice to sellPrice
    val finalGrossProfit = finalSellPrice - finalBuyPrice
    val finalFees = feesFor(finalBuyPrice, finalSellPrice)
    val finalNetProfit = finalGrossProfit - finalFees - shipping
    val finalMarginPct = finalNetProfit / finalSellPrice * 100.0

    val sellHistory = fetchHistory(typeId, sellRegionId, historySource, days = maxOf(30, spikeWindowDays))
    val buyHistory = fetchHistory(typeId, buyRegionId, historySource, days = maxOf(30, spikeWindowDays))
    val volSell = medianDailyVolume(sellHistory)
    val volBuy = medianDailyVolume(buyHistory)

    // Either leg spiking is enough to flag the route — a manipulated price on either side makes
    // the trade look better (or worse) than it durably is, whether or not it's already settled.
    val spikeDetected =
        detectPriceSpike(sellHistory, spikePriceMultiplier, spikeVolumeMultiplier, spikeWindowDays) ||
            detectPriceSpike(buyHistory, spikePriceMultiplier, spikeVolumeMultiplier, spikeWindowDays)
    if (spikeFilter == SpikeFilter.EXCLUDE && spikeDetected) return null
    if (spikeFilter == SpikeFilter.ONLY && !spikeDetected) return null

    // Total profit potential (net per unit x achievable volume), not just the per-unit figure --
    // 100k/unit x 100 units/day beats 6M on a single unit, but the old per-unit-only check let the
    // single unit through and rejected the real opportunity. BUY_TO_SELL/SAFE_BUY_TO_SELL have no
    // real order book to walk (both legs are our own placed orders, so profitableTotalProfit is
    // always 0 for them) -- they use the destination's estimated daily volume instead.
    val totalProfit =
        if (tradeType == InterRegionTradeType.BUY_TO_SELL || tradeType == InterRegionTradeType.SAFE_BUY_TO_SELL) {
            finalNetProfit * volSell
        } else {
            profitableTotalProfit
        }
    if (totalProfit < minNetProfit) return null

    return RegionOpportunity(
        typeId = typeId,
        typeName = type.name,
        buyRegionName = buyRegionName,
        sellRegionName = sellRegionName,
        buyPrice = finalBuyPrice,
        sellPrice = finalSellPrice,
        grossProfit = finalGrossProfit,
        netProfit = finalNetProfit,
        profitableVolume = profitableVolume,
        profitableTotalProfit = profitableTotalProfit,
        marginPct = finalMarginPct,
        // Return on the capital actually outlaid per unit: the item plus its hauling cost.
        roiPct = finalNetProfit / (finalBuyPrice + shipping) * 100.0,
        itemVolumeM3 = itemVol,
        shippingCostPerUnit = shipping,
        dailyVolume = volSell,
        dailyVolumeSrc = volBuy,
        priceChange7d = compute7dChange(sellHistory),
        buyVsAvg7dPct = compute7dAvgDeviation(buyHistory, finalBuyPrice) { it.average },
        sellVsAvg7dPct = compute7dAvgDeviation(sellHistory, finalSellPrice) { it.average },
        spikeDetected = spikeDetected,
    )
}

// ─── History helpers ──────────────────────────────────────────────────────

private fun compute7dChange(history: List<org.eventt.core.model.MarketHistoryModel>): Double {
    // history is sorted DESC (newest first)
    val recent = history.take(7)
    if (recent.size < 2) return Double.NaN
    val latest = recent.first().average
    val oldest = recent.last().average
    if (oldest <= 0.0) return Double.NaN
    return (latest - oldest) / oldest * 100.0
}

/**
 * Median daily volume over the last [windowDays] calendar days — sturdier than the mean against a
 * single freak day (a one-off wholesale dump/buyout shouldn't make "typical daily volume" look
 * bigger than it really is). Same missing-days-are-zero-volume-days handling as the average
 * calculation it replaces: ESI omits days with no trades entirely rather than returning a
 * zero-volume row, so [history] can have fewer than [windowDays] entries for illiquid items —
 * those missing days are padded in as explicit zeros before taking the median, or a thin item
 * that only traded on 2 of the last 30 days would show its median as "whatever those 2 busy days
 * happened to be," not the mostly-quiet volume it actually has.
 */
internal fun medianDailyVolume(
    history: List<org.eventt.core.model.MarketHistoryModel>,
    windowDays: Int = 30,
): Long {
    // history is "last N trading-day rows", which for a thin item can reach back far more than
    // windowDays calendar days (ESI just omits no-trade days rather than rows LIMIT-ed by date) —
    // so missing-day padding has to be based on the actual calendar span covered, not row count,
    // or a handful of old trades scattered over months gets treated as "no gaps" and never zeroed.
    val cutoffDate =
        java.time.LocalDate
            .now()
            .minusDays(windowDays.toLong())
            .toString()
    val recentVolumes = history.filter { it.date.take(10) >= cutoffDate }.map { it.volume }
    val missingDays = (windowDays - recentVolumes.size).coerceAtLeast(0)
    val volumes = (recentVolumes + List(missingDays) { 0L }).sorted()
    if (volumes.isEmpty()) return 0L
    val mid = volumes.size / 2
    return if (volumes.size % 2 == 0) (volumes[mid - 1] + volumes[mid]) / 2 else volumes[mid]
}

/**
 * How far [currentPrice] sits from the average of the last 7 days' [selector] value — "is this
 * price unusually cheap/expensive right now," as opposed to [compute7dChange]'s day-over-day
 * trend. Positive = currentPrice is above the 7-day average. NaN with no history yet.
 */
private fun compute7dAvgDeviation(
    history: List<org.eventt.core.model.MarketHistoryModel>,
    currentPrice: Double,
    selector: (org.eventt.core.model.MarketHistoryModel) -> Double,
): Double {
    val recent = history.take(7)
    if (recent.isEmpty()) return Double.NaN
    val avg7d = recent.map(selector).average()
    if (avg7d <= 0.0) return Double.NaN
    return (currentPrice - avg7d) / avg7d * 100.0
}

/**
 * True when [history] shows a sharp price *or* volume spike somewhere in the window — e.g. a
 * one-off buyout or wash-trading event, not a genuine price move. Matches whether that spike has
 * since settled back down *or* is still the most recent day (a live spike is at least as
 * unreliable to trade against as one that's already reverted). Price and volume get their own
 * threshold rather than sharing one -- volume's day-to-day swing is naturally far wider than
 * price's even on an ordinary day (one buyer clearing a stack is common and not a price signal),
 * so the same multiplier for both either misses real price spikes or flags every liquid item on
 * volume noise alone. Either threshold crossing on its own is enough to flag the item.
 */
internal fun detectPriceSpike(
    history: List<org.eventt.core.model.MarketHistoryModel>,
    priceMultiplier: Double = 1.5,
    volumeMultiplier: Double = 5.0,
    windowDays: Int = 30,
): Boolean {
    val recent = history.take(windowDays)
    if (recent.size < 3) return false
    return hasOutlierPeak(recent.map { it.average }, priceMultiplier) ||
        hasOutlierPeak(recent.map { it.volume.toDouble() }, volumeMultiplier)
}

// True when the single highest value in [values] is at least [multiplier]x the median of every
// other value — the baseline excludes the peak itself so the spike can't drag its own baseline up.
private fun hasOutlierPeak(
    values: List<Double>,
    multiplier: Double,
): Boolean {
    val peakIdx = values.indices.maxBy { values[it] }
    val peak = values[peakIdx]
    if (peak <= 0.0) return false
    val baselineSample = values.filterIndexed { i, _ -> i != peakIdx }.sorted()
    val mid = baselineSample.size / 2
    val baseline = if (baselineSample.size % 2 == 0) (baselineSample[mid - 1] + baselineSample[mid]) / 2.0 else baselineSample[mid]
    if (baseline <= 0.0) return false
    return peak >= baseline * multiplier
}

private fun fetchHistory(
    typeId: Int,
    regionId: Int,
    historySource: String,
    // The 7d-change/median-volume/spike stats below all self-limit by date once they have the
    // rows, so raising this to accommodate a wider spike window doesn't disturb them.
    days: Int = 30,
): List<org.eventt.core.model.MarketHistoryModel> {
    val effectiveRegionId = if (typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else regionId
    if (historySource != "esi") {
        return MarketDao.getHistory(typeId, effectiveRegionId, days)
    }
    val dbHistory = MarketDao.getHistoryBySource(typeId, effectiveRegionId, days, source = "esi")
    // A cache hit here used to be trusted forever, no matter how old — a type/region pair that
    // hadn't been re-scanned in weeks kept serving that first-ever fetch's history indefinitely.
    // medianDailyVolume's cutoff is calendar-relative to *now*, so once the newest cached day
    // fell far enough behind, every row was outside its window and volume silently read as 0/"—"
    // for an item that's actually still trading fine. ESI's daily history usually lags real time
    // by about a day, so anything newer than that in the cache is still worth trusting as-is.
    val newestCachedDate = dbHistory.firstOrNull()?.date
    val staleCutoffDate =
        java.time.LocalDate
            .now()
            .minusDays(2)
            .toString()
    if (newestCachedDate != null && newestCachedDate >= staleCutoffDate) return dbHistory
    return try {
        val entries = EsiClient.getMarketRegionHistory(effectiveRegionId, typeId)
        entries.forEach { entry ->
            runCatching {
                MarketDao.insertHistory(
                    org.eventt.core.model.MarketHistoryModel(
                        typeId = typeId,
                        regionId = effectiveRegionId,
                        date = entry["date"] as? String ?: "",
                        average = (entry["average"] as? Number)?.toDouble() ?: 0.0,
                        volume = (entry["volume"] as? Number)?.toLong() ?: 0L,
                        orderCount = (entry["order_count"] as? Number)?.toLong() ?: 0L,
                        highest = (entry["highest"] as? Number)?.toDouble() ?: 0.0,
                        lowest = (entry["lowest"] as? Number)?.toDouble() ?: 0.0,
                    ),
                )
            }
        }
        MarketDao.getHistoryBySource(typeId, effectiveRegionId, days, source = "esi")
    } catch (_: Exception) {
        // Live refresh failed (ESI down/degraded) -- stale data understates volume/misses recent
        // price moves, but it's still a better answer than treating the item as having none at all.
        dbHistory
    }
}
