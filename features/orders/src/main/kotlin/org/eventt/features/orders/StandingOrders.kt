package org.eventt.features.orders

import org.eventt.core.database.StaticDataDao
import java.util.Locale
import kotlin.math.abs

/**
 * Buy orders priced deliberately far under the market — Materials Investment's dip ladders — are
 * "standing": they wait for the price to come to them, so being outbid is their normal state, not
 * something to react to. A buy order at least [thresholdPct] under the best competing bid counts
 * as standing and is left out of everything that chases outbid orders: the overbid flag and
 * filter, the Ctrl+Z queue's beaten list, the beaten tray notification and the Dashboard's
 * "needs attention". 0 turns the rule off.
 */
object StandingOrders {
    private const val SETTING = "orders.standing_buy_pct"
    private const val DEFAULT_PCT = 0.0

    // Read from settings once, then kept in memory — every beaten check in the app goes through here.
    @Volatile
    private var cached: Double? = null

    var thresholdPct: Double
        get() = cached ?: (StaticDataDao.getSetting(SETTING)?.toDoubleOrNull() ?: DEFAULT_PCT).also { cached = it }
        set(value) {
            cached = value
            StaticDataDao.setSetting(SETTING, value.toString())
        }

    fun isStanding(
        ownPrice: Double,
        bestBuy: Double?,
        threshold: Double = thresholdPct,
    ): Boolean = threshold > 0 && bestBuy != null && bestBuy > 0 && (bestBuy - ownPrice) / bestBuy * 100 >= threshold
}

/**
 * How far behind the best competing price an order sits, percent of that price: positive = behind
 * (beaten / outbid by that much), zero or negative = on top. Null without competition.
 */
internal fun distancePct(
    price: Double,
    isBuy: Boolean,
    comp: MarketComparison?,
): Double? {
    val best = (if (isBuy) comp?.bestBuy else comp?.bestSell)?.takeIf { it > 0 } ?: return null
    return if (isBuy) (best - price) / best * 100 else (price - best) / best * 100
}

// "+2.4%", or two decimals under 1% — a one-tick undercut must not read as "+0.0%".
internal fun formatDistance(
    pct: Double,
    signed: Boolean,
): String {
    val pattern = if (abs(pct) < 1) "%s%.2f%%" else "%s%.1f%%"
    return String.format(Locale.US, pattern, if (signed && pct > 0) "+" else "", pct)
}
