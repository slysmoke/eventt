package org.eventt.features.market

import org.eventt.core.database.StaticDataDao
import kotlin.math.sqrt

// ─── Materials Investment (long-term dip-buying / DCA analysis) ───────────
//
// Idea: minerals, ice products, moon materials and PI materials (the "Materials" group under
// Manufacture & Research) are production *inputs* with continuous industry demand, so unlike a
// ship or module they don't structurally trend to zero -- their price mean-reverts around a
// mining/refining cost floor. That makes "buy more the further it drops below its own recent
// average" a reasonable long-horizon strategy for this specific market group, which is why the
// candidate list here is ranked purely by how far *below its own history* an item currently sits,
// not by margin or daily flip profit like the other two tabs.

internal data class MaterialCandidate(
    val typeId: Int,
    val typeName: String,
    val currentPrice: Double,
    val avgPrice: Double,
    val highPrice: Double,
    val lowPrice: Double,
    // (current - avg) / avg * 100 -- negative means currently cheap relative to its own history.
    val vsAvgPct: Double,
    // (current - high) / high * 100 -- always <= 0, how far below the lookback-window high it sits.
    val drawdownFromHighPct: Double,
    val trendPct: Double,
    val volatilityPct: Double,
    val dailyVolume: Long,
    val spikeDetected: Boolean,
)

internal enum class MaterialSortCol { NAME, CURRENT, AVG, DRAWDOWN, VS_AVG, TREND, VOLATILITY, VOLUME, ALLOCATED }

internal fun sortMaterials(
    list: List<AllocatedMaterial>,
    col: MaterialSortCol,
    asc: Boolean,
): List<AllocatedMaterial> {
    val cmp: Comparator<AllocatedMaterial> =
        when (col) {
            MaterialSortCol.NAME -> compareBy { it.candidate.typeName }
            MaterialSortCol.CURRENT -> compareBy { it.candidate.currentPrice }
            MaterialSortCol.AVG -> compareBy { it.candidate.avgPrice }
            MaterialSortCol.DRAWDOWN -> compareBy { it.candidate.drawdownFromHighPct }
            MaterialSortCol.VS_AVG -> compareBy { it.candidate.vsAvgPct }
            MaterialSortCol.TREND -> compareBy { if (it.candidate.trendPct.isNaN()) Double.MIN_VALUE else it.candidate.trendPct }
            MaterialSortCol.VOLATILITY -> compareBy { it.candidate.volatilityPct }
            MaterialSortCol.VOLUME -> compareBy { it.candidate.dailyVolume }
            MaterialSortCol.ALLOCATED -> compareBy { it.allocatedIsk }
        }
    return if (asc) list.sortedWith(cmp) else list.sortedWith(cmp.reversed())
}

/**
 * Builds a dip-buying candidate for one type, or null if it fails a filter or lacks enough
 * history to judge. `currentPrice` is the live best sell (what you'd actually pay to buy in now),
 * not a historical row -- history alone always lags real time by about a day.
 */
internal fun computeMaterialCandidate(
    typeId: Int,
    orders: List<Map<String, Any?>>,
    regionId: Int,
    lookbackDays: Int,
    minDailyVol: Long,
    minDiscountPct: Double,
    maxVolatilityPct: Double,
    historySource: String,
    spikeFilter: SpikeFilter,
    spikePriceMultiplier: Double,
    spikeVolumeMultiplier: Double,
): MaterialCandidate? {
    val sells = orders.filter { (it["is_buy_order"] as? Boolean) == false }
    if (sells.isEmpty()) return null
    val currentPrice = sells.minOf { (it["price"] as? Number)?.toDouble() ?: Double.MAX_VALUE }
    if (currentPrice <= 0.0 || currentPrice == Double.MAX_VALUE) return null

    val fullHistory = fetchHistory(typeId, regionId, historySource, days = lookbackDays)
    val window = fullHistory.take(lookbackDays)
    // Require most of the window to actually have trades -- a couple of stale rows scattered over
    // months isn't enough to call today's price "cheap" or "expensive" relative to.
    if (window.size < (lookbackDays / 3).coerceAtLeast(5)) return null

    val avgPrice = window.map { it.average }.average()
    if (avgPrice <= 0.0) return null
    val highPrice = window.maxOf { it.highest.takeIf { h -> h > 0 } ?: it.average }
    val lowPrice = window.minOf { it.lowest.takeIf { l -> l > 0 } ?: it.average }

    val variance = window.map { (it.average - avgPrice) * (it.average - avgPrice) }.average()
    val volatilityPct = sqrt(variance) / avgPrice * 100.0
    if (maxVolatilityPct > 0.0 && volatilityPct > maxVolatilityPct) return null

    val dailyVolume = medianDailyVolume(fullHistory, lookbackDays)
    if (dailyVolume < minDailyVol) return null

    val spikeDetected = detectPriceSpike(fullHistory, spikePriceMultiplier, spikeVolumeMultiplier, lookbackDays, currentPrice)
    if (spikeFilter == SpikeFilter.EXCLUDE && spikeDetected) return null
    if (spikeFilter == SpikeFilter.ONLY && !spikeDetected) return null

    val vsAvgPct = (currentPrice - avgPrice) / avgPrice * 100.0
    if (vsAvgPct > -minDiscountPct) return null // not currently cheap enough vs its own history

    val type = StaticDataDao.getTypeById(typeId) ?: return null
    return MaterialCandidate(
        typeId = typeId,
        typeName = type.name,
        currentPrice = currentPrice,
        avgPrice = avgPrice,
        highPrice = highPrice,
        lowPrice = lowPrice,
        vsAvgPct = vsAvgPct,
        drawdownFromHighPct = if (highPrice > 0) (currentPrice - highPrice) / highPrice * 100.0 else 0.0,
        trendPct = compute7dChange(fullHistory),
        volatilityPct = volatilityPct,
        dailyVolume = dailyVolume,
        spikeDetected = spikeDetected,
    )
}

// One rung of the dip-buying ladder: buy `iskAmount` more once price reaches `triggerPrice`. Later
// levels (deeper price drops) carry a bigger weight than earlier ones -- "buy more the further it
// falls" is the whole point of a materials DCA ladder, not an equal-size split.
internal data class LadderLevel(
    val level: Int,
    val triggerPrice: Double,
    val iskAmount: Double,
    val qty: Long,
)

internal data class AllocatedMaterial(
    val candidate: MaterialCandidate,
    val allocatedIsk: Double,
    val ladder: List<LadderLevel>,
)

/**
 * Spreads `totalBudget` across the most-discounted candidates, weighted by how far below their
 * own average price they currently sit, and capped per item both by `maxPerItemPct` of the budget
 * and by a liquidity ceiling (`liquidityDays` worth of the item's own median daily trading value --
 * don't park more ISK in one material than the market could plausibly absorb).
 */
internal fun allocateBudget(
    candidates: List<MaterialCandidate>,
    totalBudget: Double,
    maxItems: Int,
    maxPerItemPct: Double,
    liquidityDays: Double,
    ladderLevels: Int,
    ladderStepPct: Double,
): List<AllocatedMaterial> {
    if (totalBudget <= 0.0 || candidates.isEmpty() || maxItems <= 0) return emptyList()

    val picked = candidates.sortedBy { it.vsAvgPct }.take(maxItems)
    val weights = picked.map { (-it.vsAvgPct).coerceAtLeast(0.0) }
    if (weights.sum() <= 0.0) return emptyList()

    val caps =
        picked.mapIndexed { i, c ->
            minOf(totalBudget * maxPerItemPct / 100.0, c.dailyVolume * c.currentPrice * liquidityDays)
        }

    val alloc = DoubleArray(picked.size)
    var remaining = totalBudget
    val active = picked.indices.toMutableSet()
    // ponytail: two-pass proportional water-filling rather than an iterative solver -- this is an
    // advisory split for a human to review before manually placing orders, not an exact optimizer,
    // so leftover ISK from a capped item not perfectly redistributing on the first pass is fine.
    repeat(2) {
        if (active.isEmpty() || remaining <= 0.01) return@repeat
        val activeWeightSum = active.sumOf { weights[it] }
        if (activeWeightSum <= 0.0) return@repeat
        for (i in active.toList()) {
            val want = remaining * weights[i] / activeWeightSum
            val room = (caps[i] - alloc[i]).coerceAtLeast(0.0)
            alloc[i] += minOf(want, room)
        }
        remaining = totalBudget - alloc.sum()
        active.removeAll { alloc[it] >= caps[it] - 0.01 }
    }

    return picked
        .mapIndexed { i, c -> AllocatedMaterial(c, alloc[i], buildLadder(c, alloc[i], ladderLevels, ladderStepPct)) }
        .filter { it.allocatedIsk > 1.0 }
}

private fun buildLadder(
    candidate: MaterialCandidate,
    isk: Double,
    levels: Int,
    stepPct: Double,
): List<LadderLevel> {
    if (isk <= 0.0 || levels <= 0) return emptyList()
    val weightSum = levels * (levels + 1) / 2.0
    return (1..levels).map { level ->
        val trigger = candidate.currentPrice * (1.0 - stepPct / 100.0 * (level - 1))
        val share = isk * level / weightSum
        LadderLevel(level, trigger, share, if (trigger > 0) (share / trigger).toLong() else 0L)
    }
}
