package org.eventt.features.market

import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.WalletDao
import kotlin.math.sqrt

// How far back a structural-break check and the strategy backtest look, independent of the live
// discount window (`lookbackDays`, typically far shorter) -- ESI keeps roughly 13 months of daily
// history, so this is close to the practical ceiling.
private const val LONG_HISTORY_DAYS = 365

// backtestDipStrategy needs at least this many days *beyond* the lookback window to actually
// simulate anything (the window itself has no day left to compare a price against). Fetching only
// ever up to a flat LONG_HISTORY_DAYS left zero such runway once lookbackDays approached it --
// e.g. lookbackDays = 360 needs 365 days of data just to run one simulated day, which real trading
// history (ESI omits no-trade days entirely) almost never fully has -- so the backtest silently
// went blank for any lookback set anywhere near the fetch ceiling. Shared with the check below so
// the two can't drift apart again.
internal const val BACKTEST_MIN_RUNWAY_DAYS = 5

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
    // Your own current holding in this item (average-cost, not FIFO), or null when no character
    // is selected. Used to anchor the buy ladder to your real cost basis instead of resetting to
    // "current price" on every re-scan, and to generate a take-profit sell ladder.
    val position: MaterialPosition?,
    // Replaying this same discount rule over up to a year of this item's own history, or null when
    // there isn't enough history to do that meaningfully. Illustrative, not a guarantee.
    val backtest: BacktestResult?,
)

// Your current holding in one item -- qtyHeld is net of sells (can be 0), avgBuyPrice is the
// average-cost of every buy transaction on record (not FIFO -- a quick reference, not the exact
// cost basis the Orders tab's FIFO ledger computes).
internal data class MaterialPosition(
    val qtyHeld: Long,
    val avgBuyPrice: Double?,
)

internal fun computeMaterialPosition(transactions: List<WalletDao.RawTxRecord>): MaterialPosition {
    val bought = transactions.filter { it.isBuy }
    val sold = transactions.filter { !it.isBuy }
    val boughtQty = bought.sumOf { it.quantity }
    val soldQty = sold.sumOf { it.quantity }
    val avgBuy = if (boughtQty > 0) bought.sumOf { it.unitPrice * it.quantity } / boughtQty else null
    return MaterialPosition(qtyHeld = (boughtQty - soldQty).toLong(), avgBuyPrice = avgBuy)
}

internal enum class MaterialSortCol { NAME, CURRENT, AVG, DRAWDOWN, VS_AVG, TREND, VOLATILITY, VOLUME, HELD, BACKTEST, ALLOCATED }

internal fun sortMaterials(
    list: List<AllocatedMaterial>,
    col: MaterialSortCol,
    asc: Boolean,
): List<AllocatedMaterial> {
    val cmp: Comparator<AllocatedMaterial> =
        when (col) {
            MaterialSortCol.NAME -> {
                compareBy { it.candidate.typeName }
            }

            MaterialSortCol.CURRENT -> {
                compareBy { it.candidate.currentPrice }
            }

            MaterialSortCol.AVG -> {
                compareBy { it.candidate.avgPrice }
            }

            MaterialSortCol.DRAWDOWN -> {
                compareBy { it.candidate.drawdownFromHighPct }
            }

            MaterialSortCol.VS_AVG -> {
                compareBy { it.candidate.vsAvgPct }
            }

            MaterialSortCol.TREND -> {
                compareBy { if (it.candidate.trendPct.isNaN()) Double.MIN_VALUE else it.candidate.trendPct }
            }

            MaterialSortCol.VOLATILITY -> {
                compareBy { it.candidate.volatilityPct }
            }

            MaterialSortCol.VOLUME -> {
                compareBy { it.candidate.dailyVolume }
            }

            MaterialSortCol.HELD -> {
                compareBy { it.candidate.position?.qtyHeld ?: 0L }
            }

            MaterialSortCol.BACKTEST -> {
                compareBy { it.candidate.backtest?.unrealizedPnlPct ?: Double.NEGATIVE_INFINITY }
            }

            MaterialSortCol.ALLOCATED -> {
                compareBy { it.allocatedIsk }
            }
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
    excludeStructuralBreak: Boolean = true,
    // Pre-fetched and grouped by typeId once per Analyze run (one wallet-transactions read for the
    // whole scan), not looked up per-item -- see the Analyze coroutine in MaterialsInvestmentTab.
    myTransactionsByType: Map<Int, List<WalletDao.RawTxRecord>>? = null,
): MaterialCandidate? {
    val sells = orders.filter { (it["is_buy_order"] as? Boolean) == false }
    if (sells.isEmpty()) return null
    val currentPrice = sells.minOf { (it["price"] as? Number)?.toDouble() ?: Double.MAX_VALUE }
    if (currentPrice <= 0.0 || currentPrice == Double.MAX_VALUE) return null

    // Fetched once at whichever is longer -- the live discount window still only looks at its own
    // `lookbackDays` prefix of this, while the structural-break check and the backtest use the
    // full thing.
    val fullHistory =
        fetchHistory(typeId, regionId, historySource, days = maxOf(lookbackDays + BACKTEST_MIN_RUNWAY_DAYS, LONG_HISTORY_DAYS))
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

    // A genuine break to a new multi-month low is a different animal from a routine dip inside a
    // stable range -- could be a balance-patch obsolescence, a permanent supply shift, etc. -- so
    // by default it's excluded rather than fed straight into the ladder as "just a deeper buy."
    val structuralFloor = fullHistory.minOf { it.lowest.takeIf { l -> l > 0 } ?: it.average }
    if (excludeStructuralBreak && structuralFloor > 0.0 && currentPrice <= structuralFloor) return null

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
        position = myTransactionsByType?.let { computeMaterialPosition(it[typeId].orEmpty()) },
        backtest = backtestDipStrategy(fullHistory, lookbackDays, minDiscountPct),
    )
}

// ─── Backtest: replay the same "buy when below the trailing N-day average by X%" rule over this
// item's own history, to show what it would actually have returned rather than asking for trust.
// ────────────────────────────────────────────────────────────────────────────────────────────

internal data class BacktestResult(
    val buySignals: Int,
    val totalInvested: Double,
    val currentValue: Double,
    val unrealizedPnlPct: Double,
    // The worst unrealized P&L% the simulated position ever sat at along the way -- how much red
    // you'd have had to stomach, not just where it ended up.
    val worstDrawdownPct: Double,
)

// `iskPerSignal` is an arbitrary fixed notional deployed on every qualifying day -- it cancels out
// of every ratio this returns (pnl%, drawdown%), so its exact value doesn't matter; it's not a
// setting because there's nothing meaningful to tune it to.
private const val BACKTEST_ISK_PER_SIGNAL = 1_000_000.0

internal fun backtestDipStrategy(
    history: List<org.eventt.core.model.MarketHistoryModel>,
    lookbackDays: Int,
    minDiscountPct: Double,
): BacktestResult? {
    val asc = history.sortedBy { it.date }
    if (asc.size < lookbackDays + BACKTEST_MIN_RUNWAY_DAYS) return null // not enough history to replay the rule meaningfully

    var qty = 0.0
    var invested = 0.0
    var worstPnlPct = 0.0
    var signals = 0
    for (i in lookbackDays until asc.size) {
        val price = asc[i].average
        if (price <= 0.0) continue
        val windowAvg = asc.subList(i - lookbackDays, i).map { it.average }.average()
        if (windowAvg > 0.0 && price <= windowAvg * (1.0 - minDiscountPct / 100.0)) {
            qty += BACKTEST_ISK_PER_SIGNAL / price
            invested += BACKTEST_ISK_PER_SIGNAL
            signals++
        }
        if (invested > 0.0) {
            val pnlPct = (qty * price - invested) / invested * 100.0
            if (pnlPct < worstPnlPct) worstPnlPct = pnlPct
        }
    }
    if (invested <= 0.0) return null

    val lastPrice = asc.last().average
    val currentValue = qty * lastPrice
    return BacktestResult(
        buySignals = signals,
        totalInvested = invested,
        currentValue = currentValue,
        unrealizedPnlPct = (currentValue - invested) / invested * 100.0,
        worstDrawdownPct = worstPnlPct,
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
    // True when the current price has already reached/passed this trigger -- happens when the
    // ladder is anchored to a real (held) cost basis that's above where the price sits right now,
    // meaning this rung should already have been bought rather than being a future target.
    val alreadyTriggered: Boolean,
)

// One rung of the take-profit sell ladder -- the mirror of LadderLevel, only generated for an item
// you already hold. Later levels (bigger price recoveries) sell a bigger share of the position --
// lock in more as the rebound goes further, the reverse logic of buying more as it falls further.
internal data class SellLevel(
    val level: Int,
    val targetPrice: Double,
    val qty: Long,
)

internal data class AllocatedMaterial(
    val candidate: MaterialCandidate,
    val allocatedIsk: Double,
    val ladder: List<LadderLevel>,
    val sellLadder: List<SellLevel>,
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

    // Rank by total ISK opportunity -- discount depth times how much of the item actually trades
    // per day -- not by discount % alone. A thin item down 40% on 2 units/day is a smaller real
    // opportunity than a liquid material down 8% that moves billions/day in volume, but ranking on
    // vsAvgPct alone put the thin one first and let maxItems cut the liquid, actually-profitable
    // one before it ever got a share of the budget.
    fun opportunity(c: MaterialCandidate) = (-c.vsAvgPct).coerceAtLeast(0.0) * c.dailyVolume * c.currentPrice

    val picked = candidates.sortedByDescending { opportunity(it) }.take(maxItems)
    val weights = picked.map { opportunity(it) }
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
        .mapIndexed { i, c ->
            AllocatedMaterial(
                candidate = c,
                allocatedIsk = alloc[i],
                ladder = buildLadder(c, alloc[i], ladderLevels, ladderStepPct),
                sellLadder = buildSellLadder(c, ladderLevels, ladderStepPct),
            )
        }.filter { it.allocatedIsk > 1.0 || it.sellLadder.isNotEmpty() }
}

private fun buildLadder(
    candidate: MaterialCandidate,
    isk: Double,
    levels: Int,
    stepPct: Double,
): List<LadderLevel> {
    if (isk <= 0.0 || levels <= 0) return emptyList()
    // Anchor to your real average cost when you're already holding a position underwater (below
    // your own cost basis) -- otherwise every re-scan resets the ladder to "current price and
    // down," which drifts the trigger prices with every tick instead of measuring further downside
    // from where you actually bought in. Not anchored when you're not holding, or already above
    // cost, since there's no real entry to measure a *further* drop from in that case.
    val position = candidate.position
    val anchor =
        if (position != null && position.qtyHeld > 0 && position.avgBuyPrice != null && position.avgBuyPrice > candidate.currentPrice) {
            position.avgBuyPrice
        } else {
            candidate.currentPrice
        }
    val weightSum = levels * (levels + 1) / 2.0
    return (1..levels).map { level ->
        val trigger = anchor * (1.0 - stepPct / 100.0 * (level - 1))
        val share = isk * level / weightSum
        LadderLevel(
            level = level,
            triggerPrice = trigger,
            iskAmount = share,
            qty = if (trigger > 0) (share / trigger).toLong() else 0L,
            alreadyTriggered = trigger >= candidate.currentPrice,
        )
    }
}

private fun buildSellLadder(
    candidate: MaterialCandidate,
    levels: Int,
    stepPct: Double,
): List<SellLevel> {
    val position = candidate.position ?: return emptyList()
    val avgCost = position.avgBuyPrice
    if (position.qtyHeld <= 0 || avgCost == null || avgCost <= 0.0 || levels <= 0) return emptyList()
    val weightSum = levels * (levels + 1) / 2.0
    return (1..levels).map { level ->
        val target = avgCost * (1.0 + stepPct / 100.0 * level)
        val qty = (position.qtyHeld * level / weightSum).toLong()
        SellLevel(level, target, qty)
    }
}
