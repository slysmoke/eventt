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
    // Live lowest sell order -- what a held position would realistically sell at right now (by
    // undercutting it), used for the "can I sell yet" profit figure. Null when nobody's selling.
    val bestAsk: Double? = null,
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
    // Actual physical quantity in your assets right now (all locations), not net(buys - sells) --
    // materials commonly arrive without ever going through a wallet transaction at all (mining +
    // reprocessing being the obvious one for this exact market group), so a transaction-only count
    // silently reads as "not held" for stock you can see sitting in your hangar.
    val qtyHeld: Long,
    // Average cost of tracked market buys only -- reprocessed/manufactured/contracted stock has no
    // real purchase price to average in, so when none of the held quantity came from a tracked buy
    // this is null (the ladder then anchors to the live price instead, same as not holding at all).
    val avgBuyPrice: Double?,
)

internal fun computeMaterialPosition(
    transactions: List<WalletDao.RawTxRecord>,
    assetQty: Long,
): MaterialPosition {
    val bought = transactions.filter { it.isBuy }
    val boughtQty = bought.sumOf { it.quantity }
    val avgBuy = if (boughtQty > 0) bought.sumOf { it.unitPrice * it.quantity } / boughtQty else null
    return MaterialPosition(qtyHeld = assetQty, avgBuyPrice = avgBuy)
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
                compareBy { it.toBuyIsk }
            }
        }
    return if (asc) list.sortedWith(cmp) else list.sortedWith(cmp.reversed())
}

/**
 * Builds a dip-buying candidate for one type, or null if it fails a filter or lacks enough
 * history to judge. `currentPrice` is the live top buy order (the price to match/beat with your
 * own standing buy order), not a historical row -- history alone always lags real time by about a
 * day, and not the ask -- this tab is built around placing buy orders, not instant-buying.
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
    // Same one-read-per-scan deal, keyed by typeId -> total quantity currently held across every
    // location -- the real "am I holding this" signal, since materials routinely arrive via mining
    // + reprocessing without ever creating a wallet transaction at all.
    myAssetQtyByType: Map<Int, Long>? = null,
): MaterialCandidate? {
    // Buy-order-oriented, not instant-buy: this tab's whole point is placing standing buy orders
    // at the ladder's target prices rather than paying the ask, so `currentPrice` is the top
    // competing bid -- what you'd need to match/beat -- not the best sell price.
    val buys = orders.filter { (it["is_buy_order"] as? Boolean) == true }
    if (buys.isEmpty()) return null
    val currentPrice = buys.maxOf { (it["price"] as? Number)?.toDouble() ?: 0.0 }
    if (currentPrice <= 0.0) return null
    val bestAsk =
        orders
            .filter { (it["is_buy_order"] as? Boolean) == false }
            .mapNotNull { (it["price"] as? Number)?.toDouble()?.takeIf { p -> p > 0 } }
            .minOrNull()

    val position =
        if (myTransactionsByType != null || myAssetQtyByType != null) {
            computeMaterialPosition(myTransactionsByType?.get(typeId).orEmpty(), myAssetQtyByType?.get(typeId) ?: 0L)
        } else {
            null
        }
    // A position you actually bought into stays on the list regardless of the entry filters below
    // -- those decide whether to *start* buying, and once the price recovers (the whole point)
    // they'd otherwise drop the item exactly when it's time to sell it.
    val holding = position != null && position.qtyHeld > 0 && position.avgBuyPrice != null

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
    if (!holding && maxVolatilityPct > 0.0 && volatilityPct > maxVolatilityPct) return null

    val dailyVolume = medianDailyVolume(fullHistory, lookbackDays)
    if (!holding && dailyVolume < minDailyVol) return null

    val spikeDetected = detectPriceSpike(fullHistory, spikePriceMultiplier, spikeVolumeMultiplier, lookbackDays, currentPrice)
    if (!holding && spikeFilter == SpikeFilter.EXCLUDE && spikeDetected) return null
    if (!holding && spikeFilter == SpikeFilter.ONLY && !spikeDetected) return null

    val vsAvgPct = (currentPrice - avgPrice) / avgPrice * 100.0
    if (!holding && vsAvgPct > -minDiscountPct) return null // not currently cheap enough vs its own history

    // A genuine break to a new multi-month low is a different animal from a routine dip inside a
    // stable range -- could be a balance-patch obsolescence, a permanent supply shift, etc. -- so
    // by default it's excluded rather than fed straight into the ladder as "just a deeper buy."
    val structuralFloor = fullHistory.minOf { it.lowest.takeIf { l -> l > 0 } ?: it.average }
    if (!holding && excludeStructuralBreak && structuralFloor > 0.0 && currentPrice <= structuralFloor) return null

    val type = StaticDataDao.getTypeById(typeId) ?: return null
    return MaterialCandidate(
        typeId = typeId,
        typeName = type.name,
        currentPrice = currentPrice,
        bestAsk = bestAsk,
        avgPrice = avgPrice,
        highPrice = highPrice,
        lowPrice = lowPrice,
        vsAvgPct = vsAvgPct,
        drawdownFromHighPct = if (highPrice > 0) (currentPrice - highPrice) / highPrice * 100.0 else 0.0,
        trendPct = compute7dChange(fullHistory),
        volatilityPct = volatilityPct,
        dailyVolume = dailyVolume,
        spikeDetected = spikeDetected,
        position = position,
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

// What to do with an item right now. SELL wins over BUY: a position that has reached its take-profit
// target is the actionable thing, not topping it up.
internal enum class MaterialAction { BUY, WAIT, SELL }

// Take-profit plan for a held position: sell the whole stack in one order (EVE can't import a
// multi-rung sell list, so a split ladder only meant more manual order edits).
internal data class SellTarget(
    val qty: Long,
    // Lowest sell price that clears your average cost + take-profit % after sales tax and broker fee.
    val targetPrice: Double,
    // Net profit (after fees) selling the whole stack at the current lowest ask, and as % of cost.
    // Null when nobody is selling to price against.
    val profitNow: Double?,
    val profitNowPct: Double?,
)

internal data class AllocatedMaterial(
    val candidate: MaterialCandidate,
    // Target exposure for this item from the budget split.
    val allocatedIsk: Double,
    // Allocation minus what you already hold (at cost) -- what's actually left to buy. Zero once
    // the position is full, which is what stops a position you're just waiting to sell from
    // being recommended as a buy on every re-scan.
    val toBuyIsk: Double,
    val ladder: List<LadderLevel>,
    val sellTarget: SellTarget?,
    val action: MaterialAction?,
)

/**
 * Spreads `totalBudget` across the most-discounted candidates, weighted by how far below their
 * own average price they currently sit, and capped per item both by `maxPerItemPct` of the budget
 * and by a liquidity ceiling (`liquidityDays` worth of the item's own median daily trading value --
 * don't park more ISK in one material than the market could plausibly absorb). Items you hold are
 * always returned (with their sell plan), even when they no longer rank for a share of the budget.
 */
internal fun allocateBudget(
    candidates: List<MaterialCandidate>,
    totalBudget: Double,
    maxItems: Int,
    maxPerItemPct: Double,
    liquidityDays: Double,
    ladderLevels: Int,
    ladderStepPct: Double,
    // Sales tax + broker fee, % of sale value, for the take-profit math.
    sellFeePct: Double = 0.0,
    takeProfitPct: Double = ladderStepPct,
): List<AllocatedMaterial> {
    if (candidates.isEmpty()) return emptyList()

    // Rank by total ISK opportunity -- discount depth times how much of the item actually trades
    // per day -- not by discount % alone. A thin item down 40% on 2 units/day is a smaller real
    // opportunity than a liquid material down 8% that moves billions/day in volume, but ranking on
    // vsAvgPct alone put the thin one first and let maxItems cut the liquid, actually-profitable
    // one before it ever got a share of the budget.
    fun opportunity(c: MaterialCandidate) = (-c.vsAvgPct).coerceAtLeast(0.0) * c.dailyVolume * c.currentPrice

    val picked =
        if (totalBudget > 0.0 && maxItems > 0) {
            candidates.filter { opportunity(it) > 0.0 }.sortedByDescending { opportunity(it) }.take(maxItems)
        } else {
            emptyList()
        }
    val weights = picked.map { opportunity(it) }

    val caps =
        picked.map { c ->
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

    val allocById = picked.indices.associate { picked[it].typeId to alloc[it] }
    val held = candidates.filter { it.typeId !in allocById && (it.position?.qtyHeld ?: 0L) > 0 && it.position?.avgBuyPrice != null }
    // ponytail: ISK freed by an already-full position isn't redistributed to other items -- the
    // budget reads as "target exposure", and a re-scan after selling frees it up again anyway.
    return (picked + held).mapNotNull { c ->
        val allocated = allocById[c.typeId] ?: 0.0
        val position = c.position
        val heldCost = if (position != null && position.qtyHeld > 0) position.qtyHeld * (position.avgBuyPrice ?: c.currentPrice) else 0.0
        val toBuy = (allocated - heldCost).coerceAtLeast(0.0)
        val sell = buildSellTarget(c, sellFeePct, takeProfitPct)
        val action =
            when {
                sell != null && c.bestAsk != null && c.bestAsk >= sell.targetPrice -> MaterialAction.SELL
                toBuy > 1.0 -> MaterialAction.BUY
                heldCost > 0.0 -> MaterialAction.WAIT
                else -> null
            }
        if (action == null) return@mapNotNull null
        AllocatedMaterial(
            candidate = c,
            allocatedIsk = allocated,
            // SELL outranks a remaining budget share -- showing both read as "sell it and buy more".
            toBuyIsk = if (action == MaterialAction.BUY) toBuy else 0.0,
            ladder = if (action == MaterialAction.BUY) buildLadder(c, toBuy, ladderLevels, ladderStepPct) else emptyList(),
            sellTarget = sell,
            action = action,
        )
    }
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

internal fun buildSellTarget(
    candidate: MaterialCandidate,
    sellFeePct: Double,
    takeProfitPct: Double,
): SellTarget? {
    val position = candidate.position ?: return null
    val avgCost = position.avgBuyPrice
    if (position.qtyHeld <= 0 || avgCost == null || avgCost <= 0.0) return null
    val keep = (1.0 - sellFeePct / 100.0).coerceAtLeast(0.01)
    val target = avgCost * (1.0 + takeProfitPct / 100.0) / keep
    val profitNow = candidate.bestAsk?.let { ask -> (ask * keep - avgCost) * position.qtyHeld }
    return SellTarget(
        qty = position.qtyHeld,
        targetPrice = target,
        profitNow = profitNow,
        profitNowPct = profitNow?.let { it / (avgCost * position.qtyHeld) * 100.0 },
    )
}
