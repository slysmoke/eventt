package org.eventt.features.market

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.database.AppState
import org.eventt.core.database.StaticDataDao
import org.eventt.core.model.StaticMarketGroupModel
import org.eventt.core.model.StaticRegionModel

// Past this many candidate types, one paginated all-orders region fetch (a few hundred pages)
// is cheaper than a separate per-type request each — see the bulk cutover in both Analyze paths.
internal const val BULK_ORDER_FETCH_THRESHOLD = 1000

// ─── Data models ──────────────────────────────────────────────────────────

data class StationOpportunity(
    val typeId: Int,
    val typeName: String,
    val bestSell: Double,
    val bestBuy: Double,
    val grossProfit: Double,
    val netProfit: Double,
    // NET margin (after fees) relative to the sell price — matches the Trade Calc overlay.
    val marginPct: Double,
    // The same net profit relative to the buy price — return on the capital tied up.
    val roiPct: Double,
    val dailyVolume: Long,
    val sellOrderCount: Int,
    val buyOrderCount: Int,
    val estimatedDailyProfit: Double,
    val priceChange7d: Double = Double.NaN,
    // True when the item's price history shows a sharp spike (e.g. a one-off buyout) that has
    // since reverted back near its prior baseline — see detectPriceSpikeReverted.
    val spikeDetected: Boolean = false,
)

data class RegionOpportunity(
    val typeId: Int,
    val typeName: String,
    val buyRegionName: String,
    val sellRegionName: String,
    val buyPrice: Double,
    val sellPrice: Double,
    val grossProfit: Double,
    val netProfit: Double,
    // NET margin (after fees and shipping) relative to the sell price — matches Station Trading.
    val marginPct: Double,
    // The same net profit relative to buy price + shipping — return on the capital outlaid.
    val roiPct: Double,
    val itemVolumeM3: Double,
    val shippingCostPerUnit: Double,
    val dailyVolume: Long, // sell region
    val dailyVolumeSrc: Long, // buy region
    // Real quantity actually available/absorbable at a profitable price, walked from the live
    // order book(s) rather than assumed from a single best price — see walkSellLots/walkBuyLots/
    // walkCrossedBook. Not meaningful for BUY_TO_SELL (neither side is a consumable order book),
    // where it's left at 0 and regionFinalVol falls back to the vol/day estimate instead.
    val profitableVolume: Long = 0,
    // Exact accumulated profit for `profitableVolume` units (sum of each walked lot's own margin),
    // not netProfit * profitableVolume — later lots in the walk have thinner margins than the best
    // one, so that shortcut would overstate total profit.
    val profitableTotalProfit: Double = 0.0,
    val priceChange7d: Double = Double.NaN,
    // How today's price compares to a typical day this week — different question from
    // priceChange7d (which is "is the price trending up/down"), this is "is today's price
    // unusually cheap/expensive right now." Buy compares against the 7-day average of the buy
    // region's daily *lowest* (the cheapest you could realistically have bought at recently);
    // sell compares against the 7-day average of the sell region's daily *average* (a fill price,
    // not just the best ask). See compute7dAvgDeviation.
    val buyVsAvg7dPct: Double = Double.NaN,
    val sellVsAvg7dPct: Double = Double.NaN,
    // True when either region's price history shows a sharp spike that has since reverted back
    // near its prior baseline — see detectPriceSpikeReverted.
    val spikeDetected: Boolean = false,
)

// ─── Sort / trade-type enums ───────────────────────────────────────────────

internal enum class StationSortCol { NAME, BUY_PRICE, SELL_PRICE, MARGIN, ROI, NET_PROFIT, VOLUME, DAILY_PROFIT, TREND_7D }

internal enum class RegionSortCol {
    NAME,
    BUY_PRICE,
    SELL_PRICE,
    MARGIN,
    ROI,
    ITEM_VOL,
    SHIPPING,
    NET_PROFIT,
    VOLUME,
    TREND_7D,
    NET_VOL,
    QTY_TO_BUY,
}

internal enum class InterRegionTradeType(
    val label: String,
) {
    SELL_TO_BUY("Sell → Buy (instant)"),
    SELL_TO_SELL("Sell → Sell Order"),
    BUY_TO_BUY("Buy Order → Buy"),
    BUY_TO_SELL("Buy → Sell (orders)"),

    // Places a buy order at the source like BUY_TO_SELL, but doesn't price it by outbidding the
    // current best buy order — instead it's priced at the source's lowest sell price minus the
    // round-trip cost (sales tax + broker fee) a local station trader would pay to sell via their
    // own sell order. That price is unattractive to outbid (there's no local margin left to
    // squeeze), which is fine for us — we're hauling the stock to sellRegion to sell anyway, so we
    // don't care about capturing margin at the source station, just about a cheap, low-competition
    // fill. Sell leg is a placed sell order at the destination, same as BUY_TO_SELL.
    SAFE_BUY_TO_SELL("Safe Buy → Sell (orders)"),
}

// Tri-state: don't care either way, cut items that look like a spike-and-reverted event, or
// show only those — see detectPriceSpikeReverted for what qualifies.
internal enum class SpikeFilter(
    val label: String,
) {
    ANY("Any"),
    EXCLUDE("Exclude spikes"),
    ONLY("Only spikes"),
}

// The effective daily volume for a station opportunity once the volume modifier is applied —
// shared by sorting, row display, clipboard copy, and the hotkey queue so they never disagree.
fun stationEffVol(
    opp: StationOpportunity,
    volCapEnabled: Boolean,
    volCapPct: Double,
): Long =
    if (volCapEnabled && opp.dailyVolume > 0) {
        (opp.dailyVolume * volCapPct / 100.0).toLong().coerceAtLeast(1)
    } else {
        opp.dailyVolume
    }

internal fun sortStation(
    list: List<StationOpportunity>,
    col: StationSortCol,
    asc: Boolean,
    volCapEnabled: Boolean = false,
    volCapPct: Double = 100.0,
): List<StationOpportunity> {
    fun effVol(opp: StationOpportunity) = stationEffVol(opp, volCapEnabled, volCapPct)
    val cmp: Comparator<StationOpportunity> =
        when (col) {
            StationSortCol.NAME -> compareBy { it.typeName }
            StationSortCol.BUY_PRICE -> compareBy { it.bestBuy }
            StationSortCol.SELL_PRICE -> compareBy { it.bestSell }
            StationSortCol.MARGIN -> compareBy { it.marginPct }
            StationSortCol.ROI -> compareBy { it.roiPct }
            StationSortCol.NET_PROFIT -> compareBy { it.netProfit }
            StationSortCol.VOLUME -> compareBy { effVol(it) }
            StationSortCol.DAILY_PROFIT -> compareBy { it.netProfit * effVol(it) }
            StationSortCol.TREND_7D -> compareBy { if (it.priceChange7d.isNaN()) Double.MIN_VALUE else it.priceChange7d }
        }
    return if (asc) list.sortedWith(cmp) else list.sortedWith(cmp.reversed())
}

// The effective daily volume for an inter-region opportunity once the volume modifier is
// applied. `volCapEnabled` picks which side's volume is the base — the source (buy) region's
// when on, the destination (sell) region's when off — and the percentage always scales whichever
// one is currently selected, rather than only ever affecting the source side.
fun regionEffVol(
    opp: RegionOpportunity,
    volCapEnabled: Boolean,
    volCapPct: Double,
): Long {
    val base = if (volCapEnabled) opp.dailyVolumeSrc else opp.dailyVolume
    return if (base > 0) (base * volCapPct / 100.0).toLong().coerceAtLeast(1) else 0
}

// The actual "how many should I buy" figure — this is what the Qty column, the hotkey queue, and
// clipboard copy all use. Only BUY_TO_SELL has neither side backed by a real order book (both legs
// are our own placed orders), so it falls back to the vol/day estimate exactly as before.
// SELL_TO_SELL's own sell leg is *also* a placed order, so its real order-book supply is further
// capped by vol/day — can't sell faster than the destination market actually absorbs, however much
// profitable stock is sitting at the source. BUY_TO_BUY/SELL_TO_BUY's destination leg is an
// instant fill into an existing buy order, so the order-book walk alone is already the true limit.
internal fun regionFinalVol(
    opp: RegionOpportunity,
    tradeType: InterRegionTradeType,
    volCapEnabled: Boolean,
    volCapPct: Double,
): Long =
    when (tradeType) {
        InterRegionTradeType.BUY_TO_SELL, InterRegionTradeType.SAFE_BUY_TO_SELL -> regionEffVol(opp, volCapEnabled, volCapPct)
        InterRegionTradeType.SELL_TO_SELL -> minOf(opp.profitableVolume, regionEffVol(opp, volCapEnabled, volCapPct))
        InterRegionTradeType.BUY_TO_BUY, InterRegionTradeType.SELL_TO_BUY -> opp.profitableVolume
    }

// Estimated total profit at regionFinalVol — the exact walked total when the vol/day cap didn't
// bind, otherwise profitableTotalProfit scaled proportionally (a fair approximation: we don't know
// exactly *which* lots get filled first once capped below the full walked volume).
internal fun regionDailyProfit(
    opp: RegionOpportunity,
    tradeType: InterRegionTradeType,
    volCapEnabled: Boolean,
    volCapPct: Double,
): Double {
    val finalVol = regionFinalVol(opp, tradeType, volCapEnabled, volCapPct)
    if (tradeType == InterRegionTradeType.BUY_TO_SELL || tradeType == InterRegionTradeType.SAFE_BUY_TO_SELL) return opp.netProfit * finalVol
    if (opp.profitableVolume <= 0) return 0.0
    if (finalVol >= opp.profitableVolume) return opp.profitableTotalProfit
    return opp.profitableTotalProfit * finalVol / opp.profitableVolume
}

internal fun sortRegion(
    list: List<RegionOpportunity>,
    tradeType: InterRegionTradeType,
    col: RegionSortCol,
    asc: Boolean,
    volCapEnabled: Boolean = false,
    volCapPct: Double = 100.0,
): List<RegionOpportunity> {
    fun effVol(opp: RegionOpportunity) = regionEffVol(opp, volCapEnabled, volCapPct)

    fun finalVol(opp: RegionOpportunity) = regionFinalVol(opp, tradeType, volCapEnabled, volCapPct)
    val cmp: Comparator<RegionOpportunity> =
        when (col) {
            RegionSortCol.NAME -> compareBy { it.typeName }
            RegionSortCol.BUY_PRICE -> compareBy { it.buyPrice }
            RegionSortCol.SELL_PRICE -> compareBy { it.sellPrice }
            RegionSortCol.MARGIN -> compareBy { it.marginPct }
            RegionSortCol.ROI -> compareBy { it.roiPct }
            RegionSortCol.ITEM_VOL -> compareBy { it.itemVolumeM3 }
            RegionSortCol.SHIPPING -> compareBy { it.shippingCostPerUnit }
            RegionSortCol.NET_PROFIT -> compareBy { it.netProfit }
            RegionSortCol.VOLUME -> compareBy { effVol(it) }
            RegionSortCol.TREND_7D -> compareBy { if (it.priceChange7d.isNaN()) Double.MIN_VALUE else it.priceChange7d }
            RegionSortCol.NET_VOL -> compareBy { regionDailyProfit(it, tradeType, volCapEnabled, volCapPct) }
            RegionSortCol.QTY_TO_BUY -> compareBy { finalVol(it) }
        }
    return if (asc) list.sortedWith(cmp) else list.sortedWith(cmp.reversed())
}

// ─── Settings helpers ─────────────────────────────────────────────────────

internal object S {
    // Station trading keys
    const val ST_REGION = "analysis.s.region"
    const val ST_STATION = "analysis.s.station"
    const val ST_CAT_TOP = "analysis.s.catTop"
    const val ST_CAT_SUB = "analysis.s.catSub"
    const val ST_MARGIN = "analysis.s.margin"
    const val ST_MIN_VOL = "analysis.s.minVol"
    const val ST_MAX_PRICE = "analysis.s.maxPrice"
    const val ST_MIN_PROFIT = "analysis.s.minProfit"
    const val ST_VOL_CAP_ENABLED = "analysis.s.volCapEnabled"
    const val ST_VOL_CAP_PCT = "analysis.s.volCapPct"
    const val ST_COPY_VOLUME = "analysis.s.copyVolume"
    const val ST_SKIP_EXISTING = "analysis.s.skipExisting"
    const val ST_SPIKE_FILTER = "analysis.s.spikeFilter"

    // Inter-region keys
    const val IR_BUY_REGION = "analysis.r.buyRegion"
    const val IR_BUY_STATION = "analysis.r.buyStation"
    const val IR_SELL_REGION = "analysis.r.sellRegion"
    const val IR_SELL_STATION = "analysis.r.sellStation"
    const val IR_TRADE_TYPE = "analysis.r.tradeType"
    const val IR_CAT_TOP = "analysis.r.catTop"
    const val IR_CAT_SUB = "analysis.r.catSub"
    const val IR_MARGIN = "analysis.r.margin"
    const val IR_MARGIN_LIMIT_ENABLED = "analysis.r.marginLimitEnabled"
    const val IR_MARGIN_LIMIT_PCT = "analysis.r.marginLimitPct"
    const val IR_ISK_PER_M3 = "analysis.r.iskPerM3"
    const val IR_SHIP_BY_COST_ENABLED = "analysis.r.shipByCostEnabled"
    const val IR_SHIP_COST_PCT = "analysis.r.shipCostPct"
    const val IR_MAX_CARGO = "analysis.r.maxCargo"
    const val IR_MIN_PROFIT = "analysis.r.minProfit"
    const val IR_VOL_CAP_ENABLED = "analysis.r.volCapEnabled"
    const val IR_VOL_CAP_PCT = "analysis.r.volCapPct"
    const val IR_COPY_VOLUME = "analysis.r.copyVolume"
    const val IR_SKIP_EXISTING = "analysis.r.skipExisting"
    const val IR_SPIKE_FILTER = "analysis.r.spikeFilter"
    const val IR_PRESETS = "analysis.r.presets"

    fun get(key: String): String? = StaticDataDao.getSetting(key)

    fun set(
        key: String,
        value: String,
    ) = StaticDataDao.setSetting(key, value)
}

// ─── Main screen ──────────────────────────────────────────────────────────

@Composable
fun MarketAnalysisScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    val selectedCharId by AppState.selectedCharId.collectAsState()

    LaunchedEffect(selectedTab) { MarketAnalysisRouter.activeTab = selectedTab }

    val allRegions by produceState(initialValue = emptyList<StaticRegionModel>()) {
        value = withContext(Dispatchers.IO) { StaticDataDao.getAllRegions() }
    }
    val topGroups by produceState(initialValue = emptyList<StaticMarketGroupModel>()) {
        value = withContext(Dispatchers.IO) { StaticDataDao.getTopMarketGroups() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Station Trading") },
                icon = { Icon(Icons.Default.Store, null, Modifier.size(16.dp)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Inter-Region") },
                icon = { Icon(Icons.AutoMirrored.Filled.CompareArrows, null, Modifier.size(16.dp)) },
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = if (selectedTab == 0) Modifier.fillMaxSize() else Modifier.requiredSize(0.dp).clipToBounds()) {
                StationTradingTab(allRegions, topGroups, selectedCharId)
            }
            Box(modifier = if (selectedTab == 1) Modifier.fillMaxSize() else Modifier.requiredSize(0.dp).clipToBounds()) {
                InterRegionTab(allRegions, topGroups, selectedCharId)
            }
        }
    }
}
