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
    val history = fetchHistory(typeId, regionId, historySource)
    val medianDailyVol = medianDailyVolume(history)
    if (medianDailyVol < minDailyVol && minDailyVol > 0) return null

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
    )
}

// ─── Order-book walkers (inter-region "real quantity" calculation) ────────

// Walks a source SELL order book (ascending price — cheapest first) against a FIXED destination
// price, accumulating volume from each lot while its own NET margin (after fees and shipping,
// relative to the sell price — the same definition the opportunity-level filter uses) still
// clears minMarginPct. Stops at the first lot that doesn't clear it: prices only get worse
// deeper into the book, so nothing beyond that point would either. Returns (volume, exact
// accumulated profit for that volume).
internal fun walkSourceSellLots(
    lots: List<Pair<Double, Long>>, // (price, volume_remain), ascending by price
    fixedSellPrice: Double,
    shippingPerUnit: Double,
    minMarginPct: Double,
    feeForBuyPrice: (Double) -> Double,
): Pair<Long, Double> {
    var volume = 0L
    var profit = 0.0
    for ((buyPrice, qty) in lots) {
        if (qty <= 0) continue
        val gross = fixedSellPrice - buyPrice
        val net = gross - feeForBuyPrice(buyPrice) - shippingPerUnit
        val margin = if (fixedSellPrice > 0) net / fixedSellPrice * 100.0 else 0.0
        if (net <= 0 || margin < minMarginPct) break
        volume += qty
        profit += net * qty
    }
    return volume to profit
}

// Walks a destination BUY order book (descending price — best first) against a FIXED source
// price. Symmetric to walkSourceSellLots.
internal fun walkDestBuyLots(
    lots: List<Pair<Double, Long>>, // (price, volume_remain), descending by price
    fixedBuyPrice: Double,
    shippingPerUnit: Double,
    minMarginPct: Double,
    feeForSellPrice: (Double) -> Double,
): Pair<Long, Double> {
    var volume = 0L
    var profit = 0.0
    for ((sellPrice, qty) in lots) {
        if (qty <= 0) continue
        val gross = sellPrice - fixedBuyPrice
        val net = gross - feeForSellPrice(sellPrice) - shippingPerUnit
        val margin = if (sellPrice > 0) net / sellPrice * 100.0 else 0.0
        if (net <= 0 || margin < minMarginPct) break
        volume += qty
        profit += net * qty
    }
    return volume to profit
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
): Pair<Long, Double> {
    var i = 0
    var j = 0
    var remainingSell = sellLots.getOrNull(0)?.second ?: 0L
    var remainingBuy = buyLots.getOrNull(0)?.second ?: 0L
    var volume = 0L
    var profit = 0.0
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
    return volume to profit
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

    val grossProfit = sellPrice - buyPrice
    val fees =
        when (tradeType) {
            InterRegionTradeType.SELL_TO_BUY -> {
                sellPrice * salesTaxPct / 100.0
            }

            InterRegionTradeType.SELL_TO_SELL -> {
                sellPrice * (salesTaxPct + brokerFeePct) / 100.0
            }

            InterRegionTradeType.BUY_TO_BUY -> {
                buyPrice * brokerFeePct / 100.0 + sellPrice * salesTaxPct / 100.0
            }

            InterRegionTradeType.BUY_TO_SELL, InterRegionTradeType.SAFE_BUY_TO_SELL -> {
                buyPrice * brokerFeePct / 100.0 + sellPrice * (salesTaxPct + brokerFeePct) / 100.0
            }
        }
    val netProfit = grossProfit - fees - shipping
    // NET margin relative to the sell price — same convention as Station Trading and the Trade
    // Calc overlay. The old gross/buyPrice figure lives on as roiPct below.
    val marginPct = netProfit / sellPrice * 100.0
    if (marginPct < minMarginPct) return null

    // Real achievable quantity: walk whichever side(s) represent existing order-book liquidity
    // (not our own placed order) from the best price down, stopping once a lot's own margin drops
    // below minMarginPct — a deep, cheap/expensive tail lot shouldn't inflate the "buy this many"
    // figure just because the *best* price on the book was great.
    val (profitableVolume, profitableTotalProfit) =
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
                0L to 0.0
            }
        }

    val sellHistory = fetchHistory(typeId, sellRegionId, historySource)
    val buyHistory = fetchHistory(typeId, buyRegionId, historySource)
    val volSell = medianDailyVolume(sellHistory)
    val volBuy = medianDailyVolume(buyHistory)

    // Total profit potential (net per unit x achievable volume), not just the per-unit figure --
    // 100k/unit x 100 units/day beats 6M on a single unit, but the old per-unit-only check let the
    // single unit through and rejected the real opportunity. BUY_TO_SELL/SAFE_BUY_TO_SELL have no
    // real order book to walk (both legs are our own placed orders, so profitableTotalProfit is
    // always 0 for them) -- they use the destination's estimated daily volume instead.
    val totalProfit =
        if (tradeType == InterRegionTradeType.BUY_TO_SELL || tradeType == InterRegionTradeType.SAFE_BUY_TO_SELL) {
            netProfit * volSell
        } else {
            profitableTotalProfit
        }
    if (totalProfit < minNetProfit) return null

    return RegionOpportunity(
        typeId = typeId,
        typeName = type.name,
        buyRegionName = buyRegionName,
        sellRegionName = sellRegionName,
        buyPrice = buyPrice,
        sellPrice = sellPrice,
        grossProfit = grossProfit,
        netProfit = netProfit,
        profitableVolume = profitableVolume,
        profitableTotalProfit = profitableTotalProfit,
        marginPct = marginPct,
        // Return on the capital actually outlaid per unit: the item plus its hauling cost.
        roiPct = netProfit / (buyPrice + shipping) * 100.0,
        itemVolumeM3 = itemVol,
        shippingCostPerUnit = shipping,
        dailyVolume = volSell,
        dailyVolumeSrc = volBuy,
        priceChange7d = compute7dChange(sellHistory),
        buyVsAvg7dPct = compute7dAvgDeviation(buyHistory, buyPrice) { it.average },
        sellVsAvg7dPct = compute7dAvgDeviation(sellHistory, sellPrice) { it.average },
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

private fun fetchHistory(
    typeId: Int,
    regionId: Int,
    historySource: String,
): List<org.eventt.core.model.MarketHistoryModel> {
    val effectiveRegionId = if (typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else regionId
    if (historySource != "esi") {
        return MarketDao.getHistory(typeId, effectiveRegionId, 30)
    }
    val dbHistory = MarketDao.getHistoryBySource(typeId, effectiveRegionId, 30, source = "esi")
    if (dbHistory.isNotEmpty()) return dbHistory
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
        MarketDao.getHistoryBySource(typeId, effectiveRegionId, 30, source = "esi")
    } catch (_: Exception) {
        emptyList()
    }
}
