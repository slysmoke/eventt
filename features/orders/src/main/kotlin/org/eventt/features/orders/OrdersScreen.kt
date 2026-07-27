package org.eventt.features.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.ActiveOrderDao
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.MarketTopSnapshotDao
import org.eventt.core.database.OrderHistoryDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.ViewContext
import org.eventt.core.database.WalletDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.HotkeyBindings
import org.eventt.core.model.eveSigFigStep
import org.eventt.core.model.formatEveSigFigPrice
import org.eventt.ui.common.EmptyState
import org.eventt.ui.common.EsiRefreshButton
import org.eventt.ui.common.LoadingOverlay
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal val SELL_COLOR: Color @Composable get() = negativeColor
internal val BUY_COLOR: Color @Composable get() = positiveColor
internal val VOL_SELL: Color @Composable get() = warningColor
internal val VOL_BUY = Color(0xFF2E7D32)
internal val PROFIT_COLOR: Color @Composable get() = positiveColor
internal val LOSS_COLOR: Color @Composable get() = negativeColor
internal val UNDERCUT_COLOR: Color @Composable get() = warningColor // order has been beaten
internal val ACTIVE_IN_GAME = Color(0xFF4A90D9) // blue — currently open in EVE client

private const val DEFAULT_REGION_ID = 10000002 // The Forge (Jita) — fallback for inventory items with no active order/region context
private const val SHOW_BEATEN_ONLY_SETTING = "orders.show_beaten_only"
private const val BUYOUT_HINT_ENABLED_SETTING = "orders.buyout_hint"

// Jita IV - Moon 4 - Caldari Navy Assembly Plant -- verified against the real static_stations
// table, not trusted from memory. The buyout hint's reference floor: a same-station competitor
// only gets recommended for buyout when their price also undercuts Jita, not just my own order.
private const val JITA_STATION_ID = 60003760L

private val beatenFilterIcon: @Composable () -> Unit = {
    Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
}
private val overbidFilterIcon: @Composable () -> Unit = {
    Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
}

internal data class CharacterOrder(
    val orderId: Long,
    val typeId: Int,
    val typeName: String,
    val locationId: Long,
    val regionId: Int,
    val stationName: String,
    val price: Double,
    val volumeTotal: Int,
    val volumeRemaining: Int,
    val isBuyOrder: Boolean,
    val duration: Int,
    val issued: String,
    val state: String,
    // Only populated in corporation view — ESI's corp orders endpoint reports which member
    // character placed each order (the character-scope endpoint has no such field, since it's
    // always "me"). Lets a corp's trading activity across characters show up in one table.
    val issuedByCharId: Int? = null,
    // Self-computed relist tracking (see OrderFeeService) -- carried forward across refreshes via
    // ActiveOrderDao rather than re-derived, since it depends on price history we don't re-fetch.
    val relistCount: Int = 0,
    val relistFeesPaid: Double = 0.0,
    // Server "as of" time behind relistCount/relistFeesPaid — see ActiveOrderDao.bumpRelistStats.
    val priceUpdatedAt: Long = 0,
) {
    val total: Double get() = price * volumeRemaining
    val timeLeftSeconds: Long get() {
        return try {
            val exp = OffsetDateTime.parse(issued).withOffsetSameInstant(ZoneOffset.UTC).plusDays(duration.toLong())
            ChronoUnit.SECONDS.between(OffsetDateTime.now(ZoneOffset.UTC), exp).coerceAtLeast(0)
        } catch (_: Exception) {
            0L
        }
    }
    val orderAgeSeconds: Long get() {
        return try {
            val start = OffsetDateTime.parse(issued).withOffsetSameInstant(ZoneOffset.UTC)
            ChronoUnit.SECONDS.between(start, OffsetDateTime.now(ZoneOffset.UTC)).coerceAtLeast(0)
        } catch (_: Exception) {
            0L
        }
    }
    val issuedFormatted: String get() = issued.take(16).replace("T", " ")
}

// Mirrors CharacterOrder into the local active-orders cache so the screen has something to show
// instantly on the next launch, instead of an empty table until ESI's live fetch completes.
private fun CharacterOrder.toActiveOrderRecord(
    characterId: Int?,
    corporationId: Int?,
): ActiveOrderDao.ActiveOrderRecord =
    ActiveOrderDao.ActiveOrderRecord(
        orderId = orderId,
        typeId = typeId,
        typeName = typeName,
        locationId = locationId,
        regionId = regionId,
        stationName = stationName,
        price = price,
        volumeTotal = volumeTotal,
        volumeRemaining = volumeRemaining,
        isBuyOrder = isBuyOrder,
        duration = duration,
        issued = issued,
        state = state,
        issuedByCharId = issuedByCharId,
        characterId = characterId,
        corporationId = corporationId,
        relistCount = relistCount,
        relistFeesPaid = relistFeesPaid,
        priceUpdatedAt = priceUpdatedAt,
    )

private fun ActiveOrderDao.ActiveOrderRecord.toCharacterOrder(): CharacterOrder =
    CharacterOrder(
        orderId = orderId,
        typeId = typeId,
        typeName = typeName,
        locationId = locationId,
        regionId = regionId,
        stationName = stationName,
        price = price,
        volumeTotal = volumeTotal,
        volumeRemaining = volumeRemaining,
        isBuyOrder = isBuyOrder,
        duration = duration,
        issued = issued,
        state = state,
        issuedByCharId = issuedByCharId,
        relistCount = relistCount,
        relistFeesPaid = relistFeesPaid,
        priceUpdatedAt = priceUpdatedAt,
    )

// Carries relist stats forward from the last-known snapshot onto the freshly-fetched orders —
// relist detection itself happens only in fetchMarketComparisons's own-order diff (against the
// live public order book, ~5min ESI cache) — not here. /characters/{id}/orders/ and
// /corporations/{corp}/orders/ cache for ~25-30min (see fetchMarketComparisons below), so a price
// diff against *this* endpoint's response is as likely to be stale cache catching up as a real
// relist: comparing here double-counted relists that fetchMarketComparisons had already caught
// sooner, or miscounted a cache-lag revert as a brand-new one on every character/corp switch.
//
// The same staleness also means this fetch's *price* can lag a relist that bumpRelistStats already
// applied (priceUpdatedAt = the server time it was detected at). Accepting that older price would
// revert the row, and the fresher public book would then re-detect the same relist and charge the
// fee again — on every restart/screen-open until the endpoint's cache caught up. So when the
// fetch's own Last-Modified ([fetchAsOf], null = unknown) isn't newer than the applied update,
// keep the already-known price; fresh volume/state are taken either way.
internal fun mergeRelistStats(
    previous: Map<Long, ActiveOrderDao.ActiveOrderRecord>,
    fresh: List<CharacterOrder>,
    fetchAsOf: Long?,
): List<CharacterOrder> =
    fresh.map { order ->
        val prior = previous[order.orderId] ?: return@map order
        val fetchIsOlder =
            prior.priceUpdatedAt > 0 && (fetchAsOf == null || fetchAsOf <= prior.priceUpdatedAt)
        order.copy(
            price = if (fetchIsOlder) prior.price else order.price,
            relistCount = prior.relistCount,
            relistFeesPaid = prior.relistFeesPaid,
            priceUpdatedAt = prior.priceUpdatedAt,
        )
    }

// Whether a competitor currently beats this order — a cheaper sell at the same station, or a
// higher buy region-wide. Single source for the hotkey queue, the header badge, and notifications.
internal fun CharacterOrder.isBeatenBy(comp: MarketComparison?): Boolean =
    if (isBuyOrder) {
        comp?.bestBuy?.let { it > price } ?: false
    } else {
        comp?.bestSell?.let { it < price } ?: false
    }

/**
 * Best competing prices for a (typeId, locationId) pair, excluding the character's own orders.
 * bestSell is scoped to that exact station — buyers browsing a station's sell listings never see
 * ones sitting elsewhere in the region, so only same-station supply actually competes with a sell
 * order. bestBuy stays region-wide (same value across every station in the region): buy orders
 * carry a range and compete across the whole region regardless of where they're placed.
 */
internal data class MarketComparison(
    val bestSell: Double?, // lowest sell from others at the same station — null means no sell competition there
    val bestBuy: Double?, // highest region-wide buy from others — null means no buy competition
)

/** Net margin (%) of buying at [buyPrice] (cost includes buy broker fee) and reselling at [sellPrice] (revenue net of sales tax + sell broker fee). */
internal fun computeMarginPct(
    buyPrice: Double?,
    sellPrice: Double?,
    taxConfig: CostBasisService.TaxConfig,
): Double? {
    val buy = buyPrice ?: return null
    val sell = sellPrice ?: return null
    val cost = buy * taxConfig.buyMultiplier
    if (cost <= 0) return null
    val revenue = sell * taxConfig.sellMultiplier
    return (revenue - cost) / cost * 100
}

/**
 * Net margin (%) of flipping at the market's current best prices: acquire via a buy order at
 * [MarketComparison.bestBuy], resell via a sell order at [MarketComparison.bestSell]. Used as-is
 * for buy orders' Best Margin; sell orders compute their own Best Margin against cost basis and
 * the relist fee instead (see [computeSellMetrics]), since a sell order already owns the item and
 * matching a competing price means relisting, not buying again.
 */
internal fun computeBestMarginPct(
    comparison: MarketComparison?,
    taxConfig: CostBasisService.TaxConfig,
): Double? = computeMarginPct(comparison?.bestBuy, comparison?.bestSell, taxConfig)

// COST/RELIST/PROFIT/MARGIN/BEST_MARGIN are Sell-only (sorted in sortSellMetrics, since they're
// derived values, not fields on CharacterOrder); TOTAL/ORDER_AGE are Buy-only (applySort).
internal enum class SortCol { NAME, PRICE, VOLUME, TOTAL, TIME_LEFT, ORDER_AGE, COST, RELIST, PROFIT, MARGIN, BEST_MARGIN, COMPETITION }

internal enum class SortDir { ASC, DESC }

@Composable
fun OrdersScreen(context: ViewContext?) {
    val scope = rememberCoroutineScope()
    // Character-mode: charId is set, corpId is null. Corp-mode: corpId is set, charId is null.
    // actingCharId is whichever character's token authorizes ESI calls either way (open-market-
    // window hotkey, corp API auth, tax-rate lookups — corp doesn't have its own tax rate).
    val charId = (context as? ViewContext.Character)?.charId
    val corpId = (context as? ViewContext.Corporation)?.corporationId
    val actingCharId = context?.actingCharId
    var orders by remember { mutableStateOf<List<CharacterOrder>>(emptyList()) }
    var historyOrders by remember { mutableStateOf<List<OrderHistoryDao.OrderHistoryRecord>>(emptyList()) }
    var fifoResult by remember { mutableStateOf<CostBasisService.FifoResult?>(null) }
    var relistDiscountPct by remember { mutableStateOf(OrderFeeService.relistDiscountPct(0)) }
    var isLoading by remember { mutableStateOf(false) }
    // Superseded on every new loadOrders()/fetchMarketComparisons() call so switching characters
    // (or clicking refresh again) cancels a still-running fetch instead of letting it complete
    // later against a snapshot from a character the user has since switched away from — which
    // used to surface as a phantom relist bump landing well after the fact.
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var marketJob by remember { mutableStateOf<Job?>(null) }
    var refreshAvailableAt by remember { mutableStateOf<Long?>(null) }
    var activeTab by remember { mutableStateOf(0) }
    var sortCol by remember { mutableStateOf(SortCol.NAME) }
    var sortDir by remember { mutableStateOf(SortDir.ASC) }
    // Sell tab only: when on, hides every order that isn't currently beaten by a cheaper
    // competing sell order at the same station. Off (show all) by default; persisted.
    var showBeatenOnly by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showBeatenOnly = withContext(Dispatchers.IO) { StaticDataDao.getSetting(SHOW_BEATEN_ONLY_SETTING) == "true" }
    }
    // Buy tab only: when on, hides every order that isn't currently outbid by a higher
    // competing buy order region-wide. Off (show all) by default.
    var showOverbidOnly by remember { mutableStateOf(false) }

    // Market comparison data — loaded after orders, shown as overbid indicators. Refreshed on
    // order load, via the manual "Refresh Prices" button, and automatically once the ESI cache
    // expires (safe since the Ctrl+Z cycle keeps its place across updates, keyed by orderId).
    var marketComparisons by remember { mutableStateOf<Map<Pair<Int, Long>, MarketComparison>>(emptyMap()) }
    // Competition stats per order book — keyed (typeId, station) for sells and (typeId, region)
    // for buys, matching how MarketTopSnapshotDao scopes each side. Station and region id spaces
    // don't overlap, so one map serves both tables.
    var competitionStats by remember { mutableStateOf<Map<Pair<Int, Long>, CompetitionService.Stats>>(emptyMap()) }

    // Ctrl+Z cycles only beaten orders when on (mirrors PendingOrdersQueue.onlyBeaten).
    var onlyBeaten by remember { mutableStateOf(PendingOrdersQueue.onlyBeaten) }

    // Tray notification when an order newly becomes beaten. Persisted; default on.
    var notifyBeaten by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        notifyBeaten = withContext(Dispatchers.IO) { StaticDataDao.getSetting(NOTIFY_BEATEN_SETTING) != "false" }
    }
    var isLoadingMarket by remember { mutableStateOf(false) }
    var marketComparisonsExpiresAt by remember { mutableStateOf<Long?>(null) }

    // Opt-in "buy out the competition" hint (Sell tab only): off by default, since it's an extra
    // column/button most people won't want, and the Jita fetch below (see fetchJitaPrices) is an
    // extra ESI call per distinct sold type.
    var buyoutHintEnabled by remember { mutableStateOf(false) }
    // (typeId, locationId) -> every other seller's (price, volumeRemain) at that exact station,
    // sorted or not (computeBuyoutPlan sorts) -- the same filtered list fetchMarketComparisons
    // already builds to take bestSell's minOrNull() from, just not discarded here. Free byproduct
    // of that fetch, unlike jitaSellPrices below.
    var competingSellBooks by remember { mutableStateOf<Map<Pair<Int, Long>, List<Pair<Double, Long>>>>(emptyMap()) }
    // typeId -> best sell price at Jita right now -- the reference floor a same-station
    // competitor's price must also undercut before buyoutPlan recommends buying them out.
    var jitaSellPrices by remember { mutableStateOf<Map<Int, Double?>>(emptyMap()) }
    LaunchedEffect(Unit) {
        buyoutHintEnabled = withContext(Dispatchers.IO) { StaticDataDao.getSetting(BUYOUT_HINT_ENABLED_SETTING) == "true" }
    }

    // Best market sell price per type — used as an estimated Sell Price for inventory items
    // that don't have a matching active sell order of their own.
    var inventoryMarketPrices by remember { mutableStateOf<Map<Int, Double>>(emptyMap()) }

    // Corp view only: issuedByCharId -> character name, for the "Character" column.
    var issuerNames by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }

    // Corp view only: narrows the Sell/Buy tabs (and the Ctrl+Z queue) down to orders placed by
    // one specific member — null means show every member's orders. Resets whenever the selected
    // corp/character changes so a stale filter doesn't silently hide orders after switching.
    var issuerFilter by remember(corpId) { mutableStateOf<Int?>(null) }

    fun applyIssuerFilter(list: List<CharacterOrder>): List<CharacterOrder> =
        if (issuerFilter != null) list.filter { it.issuedByCharId == issuerFilter } else list

    // Selected order for hotkey action
    var selectedOrderId by remember { mutableStateOf<Long?>(null) }

    // Selecting a row also repositions the Ctrl+Z cycle: the next press acts on that order.
    LaunchedEffect(selectedOrderId) {
        selectedOrderId?.let { PendingOrdersQueue.startFrom(it) }
    }

    // Order currently open in the EVE client via the global hotkey
    val activeOrderId by PendingOrdersQueue.currentOrderId.collectAsState()

    val focusRequester = remember { FocusRequester() }

    fun fetchMarketComparisons(activeOrders: List<CharacterOrder>) {
        if (activeOrders.isEmpty()) return
        marketJob?.cancel()
        marketJob =
            scope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) { isLoadingMarket = true }
                try {
                    val brokerFeePct = (fifoResult?.taxConfig ?: CostBasisService.TaxConfig()).brokerFeePct
                    val activeById = activeOrders.associateBy { it.orderId }

                    // One ESI call per (typeId, regionId) still covers every station in that region —
                    // sell competition is then split out per station below, entirely from that same
                    // response, so this doesn't cost any extra requests.
                    val uniqueRegionPairs =
                        activeOrders
                            .filter { it.regionId > 0 && it.state == "active" }
                            .map { it.typeId to it.regionId }
                            .toSet()

                    val result = mutableMapOf<Pair<Int, Long>, MarketComparison>()
                    val competingBooks = mutableMapOf<Pair<Int, Long>, List<Pair<Double, Long>>>()
                    // Relists detected from the live public order book -- ~5min fresh, versus
                    // ~25-30min for /characters/{id}/orders/, so this catches a price change well
                    // before the next full loadOrders() poll would.
                    val detectedChanges = mutableMapOf<Long, CharacterOrder>()
                    // Latest of the per-pair ESI cache expiries — waiting for every pair to have
                    // actually gone stale (rather than just the first one) means each click of
                    // "Refresh Prices" does real work for every pair instead of mostly re-checking
                    // ones that are still cached, which is what made frequent clicks feel like a
                    // burst of requests.
                    var latestExpiry: Long? = null
                    for ((typeId, regionId) in uniqueRegionPairs) {
                        try {
                            val ownOrdersForPair = activeOrders.filter { it.typeId == typeId && it.regionId == regionId }
                            val ownIds = ownOrdersForPair.map { it.orderId }.toSet()
                            val all = EsiClient.getMarketRegionOrders(regionId, "all", typeId)
                            val sellersElsewhere =
                                all.filter {
                                    !(it["is_buy_order"] as? Boolean ?: false) && (it["order_id"] as? Number)?.toLong() !in ownIds
                                }
                            val bestBuy =
                                all
                                    .filter {
                                        (it["is_buy_order"] as? Boolean ?: false) && (it["order_id"] as? Number)?.toLong() !in ownIds
                                    }.mapNotNull { (it["price"] as? Number)?.toDouble() }
                                    .maxOrNull()
                            // One entry per station this character actually has an active order at —
                            // bestSell only counts other sellers at that same station.
                            ownOrdersForPair.map { it.locationId }.toSet().forEach { locationId ->
                                val ordersHere = sellersElsewhere.filter { (it["location_id"] as? Number)?.toLong() == locationId }
                                val bestSell = ordersHere.mapNotNull { (it["price"] as? Number)?.toDouble() }.minOrNull()
                                result[typeId to locationId] = MarketComparison(bestSell, bestBuy)
                                // Buyout hint's raw material -- the same filtered list bestSell's
                                // minOrNull() above comes from, just not collapsed to one number.
                                competingBooks[typeId to locationId] =
                                    ordersHere.mapNotNull { o ->
                                        val price = (o["price"] as? Number)?.toDouble() ?: return@mapNotNull null
                                        val vol = (o["volume_remain"] as? Number)?.toLong() ?: return@mapNotNull null
                                        price to vol
                                    }
                            }
                            val pairParams = mapOf("order_type" to "all", "type_id" to typeId.toString())
                            // The public order book's own "as of" time for this (typeId, regionId)
                            // pair — used below to reject a price diff that's actually this
                            // specific response lagging behind data we already know is newer,
                            // rather than a genuine relist (e.g. right after a character switch,
                            // where different pairs' cached responses can be minutes apart in age).
                            val pairLastModified = EsiClient.getEndpointLastModifiedMillis("/markets/$regionId/orders/", pairParams)
                            // Top-of-book snapshot per ESI tick — the raw history behind the
                            // Competition column. Unlike the comparison values above, our own
                            // orders are INCLUDED here: "am I on top right now" is the point.
                            // ts = the response's own Last-Modified, so re-reading a still-cached
                            // response dedups on the primary key instead of minting fake ticks.
                            if (pairLastModified != null) {
                                CompetitionService.recordTopSnapshots(
                                    all,
                                    ownIds,
                                    sellStationIds = ownOrdersForPair.filter { !it.isBuyOrder }.map { it.locationId }.toSet(),
                                    hasBuyOrders = ownOrdersForPair.any { it.isBuyOrder },
                                    typeId = typeId,
                                    regionId = regionId,
                                    ts = pairLastModified,
                                )
                            }
                            // Same response also carries this character's own orders (excluded above
                            // for competition purposes) — compare against the last-known price to
                            // detect a relist that happened since the last poll.
                            all
                                .filter { (it["order_id"] as? Number)?.toLong() in ownIds }
                                .forEach { o ->
                                    val orderId = (o["order_id"] as? Number)?.toLong() ?: return@forEach
                                    val price = (o["price"] as? Number)?.toDouble() ?: return@forEach
                                    val volRemain = (o["volume_remain"] as? Number)?.toInt() ?: return@forEach
                                    val known = activeById[orderId] ?: return@forEach
                                    // Own orders are stored under the placing character's
                                    // character_id even in corp mode (see replaceCorpOrders) —
                                    // charId is null there, so fall back to the order's own issuer.
                                    val ownerCharId = charId ?: known.issuedByCharId ?: return@forEach
                                    val stale = pairLastModified != null && pairLastModified < known.priceUpdatedAt
                                    if (known.price != price && !stale) {
                                        val fee =
                                            OrderFeeService.computeModificationFee(
                                                known.price,
                                                price,
                                                volRemain,
                                                brokerFeePct,
                                                relistDiscountPct,
                                            )
                                        val asOf = pairLastModified ?: System.currentTimeMillis()
                                        ActiveOrderDao.bumpRelistStats(orderId, ownerCharId, price, fee, asOf)
                                        detectedChanges[orderId] =
                                            known.copy(
                                                price = price,
                                                volumeRemaining = volRemain,
                                                relistCount = known.relistCount + 1,
                                                relistFeesPaid = known.relistFeesPaid + fee,
                                                priceUpdatedAt = asOf,
                                            )
                                    } else if (known.volumeRemaining != volRemain) {
                                        detectedChanges[orderId] = known.copy(volumeRemaining = volRemain)
                                    }
                                }
                            val expiry = EsiClient.getEndpointExpiry("/markets/$regionId/orders/", pairParams)
                            if (expiry != null && (latestExpiry == null || expiry > latestExpiry)) {
                                latestExpiry = expiry
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                    val statsSince = System.currentTimeMillis() - CompetitionService.WINDOW_MILLIS
                    val stats = mutableMapOf<Pair<Int, Long>, CompetitionService.Stats>()
                    activeOrders
                        .filter { it.state == "active" }
                        .forEach { o ->
                            val scopeId = if (o.isBuyOrder) o.regionId.toLong() else o.locationId
                            stats.getOrPut(o.typeId to scopeId) {
                                CompetitionService.compute(MarketTopSnapshotDao.getWindow(o.typeId, scopeId, o.isBuyOrder, statsSince))
                            }
                        }

                    withContext(Dispatchers.Main) {
                        marketComparisons = result
                        marketComparisonsExpiresAt = latestExpiry
                        competitionStats = stats
                        competingSellBooks = competingBooks
                        if (detectedChanges.isNotEmpty()) orders = orders.map { detectedChanges[it.orderId] ?: it }
                    }
                } finally {
                    withContext(Dispatchers.Main) { isLoadingMarket = false }
                }
            }
    }

    // Buyout hint's reference floor (see JITA_STATION_ID) -- sell orders only, since the buyout
    // hint itself only applies to sells. A separate fetch from fetchMarketComparisons above:
    // Jita is a different region from wherever these orders actually are, so it can't ride along
    // on that same per-order-region loop.
    fun fetchJitaPrices(sellOrders: List<CharacterOrder>) {
        if (!buyoutHintEnabled || sellOrders.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val typeIds = sellOrders.filter { it.state == "active" }.map { it.typeId }.toSet()
            val result = mutableMapOf<Int, Double?>()
            for (typeId in typeIds) {
                try {
                    val sellersAtJita =
                        EsiClient
                            .getMarketRegionOrders(DEFAULT_REGION_ID, "sell", typeId)
                            .filter { (it["location_id"] as? Number)?.toLong() == JITA_STATION_ID }
                    result[typeId] = sellersAtJita.mapNotNull { (it["price"] as? Number)?.toDouble() }.minOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            withContext(Dispatchers.Main) { jitaSellPrices = result }
        }
    }

    fun fetchInventoryMarketPrices(inventory: Map<Int, CostBasisService.InventoryItem>) {
        if (inventory.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val result = mutableMapOf<Int, Double>()
            for (typeId in inventory.keys) {
                try {
                    val sellOrders = EsiClient.getMarketRegionOrders(DEFAULT_REGION_ID, "sell", typeId)
                    sellOrders.mapNotNull { (it["price"] as? Number)?.toDouble() }.minOrNull()?.let { result[typeId] = it }
                } catch (_: Exception) {
                }
            }
            withContext(Dispatchers.Main) { inventoryMarketPrices = result }
        }
    }

    fun parseOrder(
        m: Map<String, Any?>,
        issuedByCharId: Int?,
    ): CharacterOrder {
        val typeId = (m["type_id"] as? Number)?.toInt() ?: 0
        val locationId = (m["location_id"] as? Number)?.toLong() ?: 0L
        // Corp orders report region_id directly; character orders don't, so it's derived from
        // the station. Prefer the direct value when present — it also covers citadels/structures
        // (locationId > 10^12) that the station lookup can't resolve.
        val directRegionId = (m["region_id"] as? Number)?.toInt()
        return CharacterOrder(
            orderId = (m["order_id"] as? Number)?.toLong() ?: 0L,
            typeId = typeId,
            typeName = StaticDataDao.getTypeName(typeId) ?: "Unknown ($typeId)",
            locationId = locationId,
            regionId =
                directRegionId
                    ?: if (locationId < 1_000_000_000_000L) StaticDataDao.getStationById(locationId)?.regionId ?: 0 else 0,
            stationName = StaticDataDao.getStationById(locationId)?.name ?: locationId.toString(),
            price = (m["price"] as? Number)?.toDouble() ?: 0.0,
            volumeTotal = (m["volume_total"] as? Number)?.toInt() ?: 0,
            volumeRemaining = (m["volume_remain"] as? Number)?.toInt() ?: 0,
            isBuyOrder = (m["is_buy_order"] as? Boolean) ?: false,
            duration = (m["duration"] as? Number)?.toInt() ?: 0,
            issued = (m["issued"] as? String) ?: "",
            state = (m["state"] as? String) ?: "active",
            issuedByCharId = issuedByCharId,
        )
    }

    fun resolveIssuerNames(ids: Set<Int>) {
        if (ids.isEmpty()) {
            issuerNames = emptyMap()
            return
        }
        scope.launch(Dispatchers.IO) {
            val local = ids.mapNotNull { id -> CharacterDao.getById(id)?.let { id to it.name } }.toMap()
            val missing = ids - local.keys
            val resolved =
                if (missing.isNotEmpty()) {
                    try {
                        EsiClient.resolveNames(missing.toList())
                    } catch (_: Exception) {
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }
            withContext(Dispatchers.Main) { issuerNames = local + resolved }
        }
    }

    fun loadOrders() {
        val cid = charId
        val corp = corpId
        val acting = actingCharId ?: return
        loadJob?.cancel()
        marketJob?.cancel()
        loadJob =
            scope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) { isLoading = true }
                try {
                    val taxConfig =
                        CostBasisService.TaxConfig(
                            salesTaxPct = StaticDataDao.getCharSalesTax(acting),
                            brokerFeePct = StaticDataDao.getCharBrokersFee(acting),
                        )
                    val relistDiscount = OrderFeeService.relistDiscountPct(StaticDataDao.getCharRelistSkillLevel(acting))

                    // Character mode must drop is_corporation orders: /characters/{id}/orders/
                    // returns the character's corp-wallet orders too, and letting them through
                    // used to INSERT OR REPLACE the corp-scoped active_orders rows (order_id is
                    // the table's PK) with zero-relist personal rows on every view switch —
                    // wiping relist history in corp view and re-counting it from a zero baseline.
                    val raw =
                        if (corp != null) {
                            EsiClient.getCorporationOrders(corp, acting)
                        } else {
                            EsiClient.getCharacterOrders(cid!!).filter { (it["is_corporation"] as? Boolean) != true }
                        }
                    val freshlyParsed = raw.map { m -> parseOrder(m, if (corp != null) (m["issued_by"] as? Number)?.toInt() else null) }
                    // Carry the last-known relist stats forward onto the fresh snapshot (see
                    // mergeRelistStats — detection itself happens elsewhere), keeping the newer
                    // price when this endpoint's response is older than an already-applied relist.
                    val ordersEndpoint = if (corp != null) "/corporations/$corp/orders/" else "/characters/$cid/orders/"
                    val fetchAsOf = EsiClient.getEndpointLastModifiedMillis(ordersEndpoint)
                    val previousSnapshot = ActiveOrderDao.getAll(characterId = cid, corporationId = corp).associateBy { it.orderId }
                    val parsed = mergeRelistStats(previousSnapshot, freshlyParsed, fetchAsOf)
                    withContext(Dispatchers.Main) { orders = parsed }
                    if (corp != null) {
                        resolveIssuerNames(parsed.mapNotNull { it.issuedByCharId }.toSet())
                        // Stored per placing character (not a flat corp-wide scope) — see
                        // ActiveOrderDao.replaceCorpOrders for why.
                        val byPlacer =
                            parsed.mapNotNull { o -> o.issuedByCharId?.let { placer -> o.toActiveOrderRecord(placer, corp) } }
                        ActiveOrderDao.replaceCorpOrders(corp, byPlacer)
                    } else {
                        ActiveOrderDao.replaceAll(cid!!, parsed.map { it.toActiveOrderRecord(cid, null) })
                    }

                    // Same is_corporation filter as the active fetch above: order_history's PK is
                    // also order_id, so unfiltered corp rows would flip scope on every switch.
                    val rawHistory =
                        if (corp != null) {
                            EsiClient.getCorporationOrdersHistory(corp, acting)
                        } else {
                            EsiClient.getCharacterOrdersHistory(cid!!).filter { (it["is_corporation"] as? Boolean) != true }
                        }
                    val historyRecords =
                        rawHistory.map { m ->
                            val typeId = (m["type_id"] as? Number)?.toInt() ?: 0
                            val locationId = (m["location_id"] as? Number)?.toLong() ?: 0L
                            val orderId = (m["order_id"] as? Number)?.toLong() ?: 0L
                            OrderHistoryDao.OrderHistoryRecord(
                                orderId = orderId,
                                typeId = typeId,
                                typeName = StaticDataDao.getTypeName(typeId) ?: "Unknown ($typeId)",
                                locationId = locationId,
                                stationName = StaticDataDao.getStationById(locationId)?.name ?: locationId.toString(),
                                price = (m["price"] as? Number)?.toDouble() ?: 0.0,
                                volumeTotal = (m["volume_total"] as? Number)?.toInt() ?: 0,
                                volumeRemaining = (m["volume_remain"] as? Number)?.toInt() ?: 0,
                                isBuyOrder = (m["is_buy_order"] as? Boolean) ?: false,
                                duration = (m["duration"] as? Number)?.toInt() ?: 0,
                                issued = (m["issued"] as? String) ?: "",
                                range = (m["range"] as? String) ?: "station",
                                minVolume = (m["min_volume"] as? Number)?.toInt() ?: 1,
                                state = (m["state"] as? String) ?: "expired",
                                characterId = if (corp != null) null else cid,
                                corporationId = corp,
                                isCorp = corp != null,
                                // The pre-replace active snapshot still holds the order's final
                                // relist total; upsertAll keeps the max, so later syncs (where
                                // the active row is already gone) can't zero it back out.
                                relistFeesPaid = previousSnapshot[orderId]?.relistFeesPaid ?: 0.0,
                            )
                        }
                    OrderHistoryDao.upsertAll(historyRecords)
                    val stored = OrderHistoryDao.getAll(characterId = cid, corporationId = corp)
                    withContext(Dispatchers.Main) { historyOrders = stored }

                    // Sync wallet transactions to DB before recomputing FIFO.
                    // EsiClient serves from the ESI cache during cooldown, so no extra HTTP call is made.
                    // This ensures newly fulfilled orders get correct P&L once the wallet cache expires.
                    try {
                        val txList =
                            if (corp !=
                                null
                            ) {
                                EsiClient.getCorporationTransactions(corp, acting)
                            } else {
                                EsiClient.getCharacterTransactions(cid!!)
                            }
                        txList.forEach { tx ->
                            val typeId = (tx["type_id"] as? Number)?.toInt() ?: 0
                            val qty = (tx["quantity"] as? Number)?.toInt() ?: 0
                            val unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0
                            runCatching {
                                WalletDao.insertTransaction(
                                    transactionId = (tx["transaction_id"] as? Number)?.toLong() ?: 0L,
                                    date = tx["date"] as? String ?: "",
                                    typeId = typeId,
                                    typeName = StaticDataDao.getTypeName(typeId) ?: "",
                                    quantity = qty,
                                    unitPrice = unitPrice,
                                    total = unitPrice * qty,
                                    isBuy = (tx["is_buy"] as? Boolean) ?: false,
                                    clientId = (tx["client_id"] as? Number)?.toInt() ?: 0,
                                    clientName = "",
                                    locationId = (tx["location_id"] as? Number)?.toLong() ?: 0L,
                                    locationName = "",
                                    isCorp = corp != null,
                                    characterId = if (corp != null) null else cid,
                                    corporationId = corp,
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }

                    val fifo = CostBasisService.compute(characterId = cid, corporationId = corp, taxConfig = taxConfig)
                    val expiry =
                        if (corp != null) {
                            EsiClient.getEndpointExpiry("/corporations/$corp/orders/")
                        } else {
                            EsiClient.getEndpointExpiry("/characters/$cid/orders/")
                        }
                    withContext(Dispatchers.Main) {
                        fifoResult = fifo
                        relistDiscountPct = relistDiscount
                        refreshAvailableAt = expiry
                    }

                    // Load market comparison data after orders are parsed
                    fetchMarketComparisons(parsed)
                    fetchJitaPrices(parsed.filter { !it.isBuyOrder })
                    fetchInventoryMarketPrices(fifo.inventory)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    withContext(Dispatchers.Main) { isLoading = false }
                }
            }
    }

    // Hotkey action: open market window in-game + copy overbid price to clipboard
    fun triggerOrderAction(order: CharacterOrder) {
        // Table is frozen while a refresh is in flight — orders/marketComparisons are mid-update,
        // so acting on them now could open the market window for a price that's about to change.
        if (isLoading || isLoadingMarket) return
        val comp = marketComparisons[order.typeId to order.locationId]
        val overbidPrice =
            if (order.isBuyOrder) {
                comp?.bestBuy?.let { base ->
                    val step = eveSigFigStep(base)
                    kotlin.math.round(base / step) * step + step
                } ?: order.price
            } else {
                comp?.bestSell?.let { base ->
                    val step = eveSigFigStep(base)
                    kotlin.math.round(base / step) * step - step
                } ?: order.price
            }
        scope.launch(Dispatchers.IO) {
            try {
                actingCharId?.let { EsiClient.openMarketWindow(it, order.typeId) }
            } catch (_: Exception) {
            }
            withContext(Dispatchers.Main) {
                val text = formatEveSigFigPrice(overbidPrice)
                val sel = StringSelection(text)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            }
        }
    }

    // Buyout action: open market window in-game + copy the recommended quantity to clipboard.
    // Unlike triggerOrderAction above (which prepares a limit-order price to paste), this is an
    // instant market buy against existing sell orders -- it fills cheapest-first automatically up
    // to whatever quantity you enter, so quantity is the useful paste target here, not a price.
    fun triggerBuyoutAction(
        order: CharacterOrder,
        plan: BuyoutPlan,
    ) {
        if (isLoading || isLoadingMarket) return
        scope.launch(Dispatchers.IO) {
            try {
                actingCharId?.let { EsiClient.openMarketWindow(it, order.typeId) }
            } catch (_: Exception) {
            }
            withContext(Dispatchers.Main) {
                val text = plan.volume.toString()
                val sel = StringSelection(text)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            }
        }
    }

    fun recalculateFifo() {
        val acting = actingCharId ?: return
        scope.launch(Dispatchers.IO) {
            val taxConfig =
                CostBasisService.TaxConfig(
                    salesTaxPct = StaticDataDao.getCharSalesTax(acting),
                    brokerFeePct = StaticDataDao.getCharBrokersFee(acting),
                )
            val fifo = CostBasisService.compute(characterId = charId, corporationId = corpId, taxConfig = taxConfig)
            withContext(Dispatchers.Main) { fifoResult = fifo }
            fetchInventoryMarketPrices(fifo.inventory)
        }
    }

    LaunchedEffect(context) {
        val acting = actingCharId
        if (acting != null) {
            // Show the last-known snapshot immediately — instant on launch instead of an empty
            // table while the ESI fetch below is in flight. Always assigned (even when empty):
            // this effect also re-fires on every character/corp switch, and a stale non-empty
            // guard here used to leave the *previous* context's orders on screen whenever the
            // newly-selected one had no cached snapshot yet.
            val cachedOrders =
                withContext(Dispatchers.IO) { ActiveOrderDao.getAll(characterId = charId, corporationId = corpId) }
            orders = cachedOrders.map { it.toCharacterOrder() }

            val stored = withContext(Dispatchers.IO) { OrderHistoryDao.getAll(characterId = charId, corporationId = corpId) }
            historyOrders = stored
            val taxConfig =
                withContext(Dispatchers.IO) {
                    CostBasisService.TaxConfig(
                        salesTaxPct = StaticDataDao.getCharSalesTax(acting),
                        brokerFeePct = StaticDataDao.getCharBrokersFee(acting),
                    )
                }
            val fifo =
                withContext(Dispatchers.IO) {
                    CostBasisService.compute(characterId = charId, corporationId = corpId, taxConfig = taxConfig)
                }
            fifoResult = fifo
            relistDiscountPct =
                withContext(Dispatchers.IO) { OrderFeeService.relistDiscountPct(StaticDataDao.getCharRelistSkillLevel(acting)) }
            loadOrders()
        } else {
            PendingOrdersQueue.clear()
        }
    }

    // Keep the global hotkey queue in sync with the active tab + market comparison data.
    // Tab 0 = sell orders only, Tab 1 = buy orders only, other tabs = all.
    // Beaten orders sort first so the most urgent ones cycle first.
    LaunchedEffect(context, orders, marketComparisons, activeTab, issuerFilter) {
        val cid = actingCharId ?: return@LaunchedEffect
        val tabFiltered =
            applyIssuerFilter(
                when (activeTab) {
                    0 -> orders.filter { !it.isBuyOrder }
                    1 -> orders.filter { it.isBuyOrder }
                    else -> orders
                },
            )
        val pending =
            tabFiltered
                .filter { it.state == "active" }
                .map { order ->
                    val comp = marketComparisons[order.typeId to order.locationId]
                    val isBeaten = order.isBeatenBy(comp)
                    PendingOrder(
                        charId = cid,
                        orderId = order.orderId,
                        typeId = order.typeId,
                        typeName = order.typeName,
                        isBuyOrder = order.isBuyOrder,
                        regionId = order.regionId,
                        ownPrice = order.price,
                        bestCompetingPrice = if (order.isBuyOrder) comp?.bestBuy else comp?.bestSell,
                        isBeaten = isBeaten,
                    )
                }
        PendingOrdersQueue.update(pending)
    }

    // Beaten-order tray notifications live in MarketWatchService now — it watches every
    // character's cached orders app-wide, so they fire even with another tab or character active.

    // Auto-refresh market comparisons once the ESI cache for every pair has expired, so beaten
    // detection (and the notification above) stays current without manual clicks.
    LaunchedEffect(context) {
        while (true) {
            delay(30_000)
            val expiry = marketComparisonsExpiresAt ?: continue
            if (System.currentTimeMillis() >= expiry && !isLoadingMarket && !isLoading && orders.isNotEmpty()) {
                fetchMarketComparisons(orders)
                fetchJitaPrices(orders.filter { !it.isBuyOrder })
            }
        }
    }

    // Flipping the toggle fetches immediately instead of waiting for the next scheduled refresh --
    // fetchJitaPrices itself is a no-op while disabled.
    LaunchedEffect(buyoutHintEnabled) {
        fetchJitaPrices(orders.filter { !it.isBuyOrder })
    }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        val sel = selectedOrderId
                        if (sel != null) {
                            orders.find { it.orderId == sel }?.let { triggerOrderAction(it) }
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Orders", style = MaterialTheme.typography.headlineMedium)
                if (isLoadingMarket) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    Text("loading market…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Global hotkey queue indicator — scoped to the visible tab
                val tabActive =
                    applyIssuerFilter(
                        when (activeTab) {
                            0 -> orders.filter { !it.isBuyOrder && it.state == "active" }
                            1 -> orders.filter { it.isBuyOrder && it.state == "active" }
                            else -> orders.filter { it.state == "active" }
                        },
                    )
                val queueSize = tabActive.size
                val beatenCount = tabActive.count { it.isBeatenBy(marketComparisons[it.typeId to it.locationId]) }
                if (queueSize > 0 && actingCharId != null) {
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.Keyboard,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val hotkeyLabel by HotkeyBindings.queueLabel.collectAsState()
                            Text(
                                hotkeyLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "$queueSize orders",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (beatenCount > 0) {
                                Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "$beatenCount beaten",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = UNDERCUT_COLOR,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "only beaten",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (onlyBeaten) UNDERCUT_COLOR else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                fontWeight = if (onlyBeaten) FontWeight.SemiBold else FontWeight.Normal,
                                modifier =
                                    Modifier.clickable {
                                        onlyBeaten = !onlyBeaten
                                        PendingOrdersQueue.onlyBeaten = onlyBeaten
                                    },
                            )
                        }
                    }
                }
            }
            if (actingCharId != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (corpId != null && issuerNames.isNotEmpty()) {
                        IssuerFilterChip(
                            issuerNames = issuerNames,
                            selected = issuerFilter,
                            onSelect = { issuerFilter = it },
                        )
                    }
                    IconButton(onClick = {
                        notifyBeaten = !notifyBeaten
                        scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(NOTIFY_BEATEN_SETTING, notifyBeaten.toString()) }
                    }) {
                        Icon(
                            if (notifyBeaten) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                            contentDescription = "Toggle beaten-order notifications",
                            tint = if (notifyBeaten) UNDERCUT_COLOR else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    TextButton(onClick = { recalculateFifo() }) {
                        Text("Recalculate P&L")
                    }
                    EsiRefreshButton(
                        isLoading = isLoadingMarket,
                        enabled = !isLoading,
                        expiresAtMs = marketComparisonsExpiresAt,
                        onClick = {
                            fetchMarketComparisons(orders)
                            fetchJitaPrices(orders.filter { !it.isBuyOrder })
                        },
                        label = "Refresh Prices",
                    )
                    EsiRefreshButton(
                        isLoading = isLoading,
                        enabled = !isLoadingMarket,
                        expiresAtMs = refreshAvailableAt,
                        onClick = { loadOrders() },
                        label = if (corpId != null) "Refresh Corp Orders" else "Refresh Orders",
                    )
                }
            }
        }

        val sellOrders = applyIssuerFilter(orders.filter { !it.isBuyOrder })
        val buyOrders = applyIssuerFilter(orders.filter { it.isBuyOrder })
        val inventory = fifoResult?.inventory ?: emptyMap()

        // Fallback cost map from completed buy orders in history.
        // Used when wallet transactions haven't been synced yet (ESI cooldown).
        // Weighted average order price × buyMultiplier to match the FIFO cost convention.
        val tax = fifoResult?.taxConfig ?: CostBasisService.TaxConfig()
        val historyCostBasis: Map<Int, Double> =
            remember(historyOrders, tax) {
                historyOrders
                    .filter { it.isBuyOrder && (it.volumeTotal - it.volumeRemaining) > 0 }
                    .groupBy { it.typeId }
                    .mapValues { (_, orders) ->
                        val totalFilled = orders.sumOf { it.volumeTotal - it.volumeRemaining }.toDouble()
                        val weightedAvg = orders.sumOf { (it.volumeTotal - it.volumeRemaining) * it.price } / totalFilled
                        weightedAvg * tax.buyMultiplier
                    }
            }

        PrimaryTabRow(selectedTabIndex = activeTab, modifier = Modifier.fillMaxWidth()) {
            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                Text("Sell (${sellOrders.size})", modifier = Modifier.padding(8.dp))
            }
            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                Text("Buy (${buyOrders.size})", modifier = Modifier.padding(8.dp))
            }
            Tab(selected = activeTab == 2, onClick = { activeTab = 2 }) {
                Text("History (${historyOrders.size})", modifier = Modifier.padding(8.dp))
            }
            Tab(selected = activeTab == 3, onClick = { activeTab = 3 }) {
                Text("Inventory (${inventory.size})", modifier = Modifier.padding(8.dp))
            }
        }

        fun onSort(col: SortCol) {
            if (sortCol == col) {
                sortDir = if (sortDir == SortDir.ASC) SortDir.DESC else SortDir.ASC
            } else {
                sortCol = col
                sortDir = SortDir.ASC
            }
        }

        // Dimmed + non-interactive (see triggerOrderAction's own guard) while a refresh is in
        // flight — orders/marketComparisons are mid-update, so acting on a row now could target
        // stale data.
        Box(modifier = Modifier.weight(1f).fillMaxWidth().alpha(if (isLoading || isLoadingMarket) 0.5f else 1f)) {
            when (activeTab) {
                2 -> {
                    if (historyOrders.isEmpty() && !isLoading) {
                        EmptyState(
                            icon = Icons.Default.History,
                            title = "No Order History",
                            description = if (context == null) "Add a character to view order history." else "No completed orders found.",
                        )
                    } else {
                        OrderHistoryTable(historyOrders, fifoResult)
                    }
                }

                3 -> {
                    if (inventory.isEmpty() && !isLoading) {
                        EmptyState(
                            icon = Icons.Default.Inventory2,
                            title = "No Inventory",
                            description = if (context == null) "Add a character to view inventory." else "No items in FIFO inventory.",
                        )
                    } else {
                        InventoryTable(inventory, sellOrders, fifoResult, inventoryMarketPrices)
                    }
                }

                else -> {
                    val filtered = if (activeTab == 0) sellOrders else buyOrders
                    if (filtered.isEmpty() && !isLoading) {
                        EmptyState(
                            icon = Icons.Default.Receipt,
                            title = if (activeTab == 0) "No Sell Orders" else "No Buy Orders",
                            description = if (context == null) "Add a character to view orders." else "No active orders.",
                        )
                    } else if (activeTab == 0) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilterChip(
                                    selected = buyoutHintEnabled,
                                    onClick = {
                                        buyoutHintEnabled = !buyoutHintEnabled
                                        scope.launch(Dispatchers.IO) {
                                            StaticDataDao.setSetting(BUYOUT_HINT_ENABLED_SETTING, buyoutHintEnabled.toString())
                                        }
                                    },
                                    label = { Text("Buyout hint") },
                                )
                                Spacer(Modifier.width(8.dp))
                                FilterChip(
                                    selected = showBeatenOnly,
                                    onClick = {
                                        showBeatenOnly = !showBeatenOnly
                                        scope.launch(Dispatchers.IO) {
                                            StaticDataDao.setSetting(SHOW_BEATEN_ONLY_SETTING, showBeatenOnly.toString())
                                        }
                                    },
                                    label = { Text(if (showBeatenOnly) "Beaten only" else "All orders") },
                                    leadingIcon = if (showBeatenOnly) beatenFilterIcon else null,
                                )
                            }
                            SellOrdersTable(
                                orders = filtered,
                                sortCol = sortCol,
                                sortDir = sortDir,
                                onSort = ::onSort,
                                inventory = inventory,
                                taxConfig = tax,
                                historyCostBasis = historyCostBasis,
                                comparisons = marketComparisons,
                                competition = competitionStats,
                                relistDiscountPct = relistDiscountPct,
                                showBeatenOnly = showBeatenOnly,
                                selectedOrderId = selectedOrderId,
                                activeOrderId = activeOrderId,
                                onSelect = { id -> selectedOrderId = if (selectedOrderId == id) null else id },
                                onAction = { order -> triggerOrderAction(order) },
                                buyoutHintEnabled = buyoutHintEnabled,
                                competingSellBooks = competingSellBooks,
                                jitaSellPrices = jitaSellPrices,
                                onBuyout = { order, plan -> triggerBuyoutAction(order, plan) },
                            )
                        }
                    } else {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                FilterChip(
                                    selected = showOverbidOnly,
                                    onClick = { showOverbidOnly = !showOverbidOnly },
                                    label = { Text(if (showOverbidOnly) "Overbid only" else "All orders") },
                                    leadingIcon = if (showOverbidOnly) overbidFilterIcon else null,
                                )
                            }
                            BuyOrdersTable(
                                orders = filtered,
                                sortCol = sortCol,
                                sortDir = sortDir,
                                onSort = ::onSort,
                                taxConfig = tax,
                                comparisons = marketComparisons,
                                competition = competitionStats,
                                showOverbidOnly = showOverbidOnly,
                                selectedOrderId = selectedOrderId,
                                activeOrderId = activeOrderId,
                                onSelect = { id -> selectedOrderId = if (selectedOrderId == id) null else id },
                                onAction = { order -> triggerOrderAction(order) },
                            )
                        }
                    }
                }
            }
        }

        when (activeTab) {
            3 -> {
                fifoResult?.let { InventorySummaryBar(it, inventory) }
            }

            2 -> {
                if (historyOrders.isNotEmpty()) HistorySummaryBar(historyOrders, fifoResult)
            }

            else -> {
                val filtered = if (activeTab == 0) sellOrders else buyOrders
                if (filtered.isNotEmpty()) {
                    val tax = fifoResult?.taxConfig ?: CostBasisService.TaxConfig()
                    OrdersSummaryBar(filtered, if (activeTab == 0) inventory else null, tax)
                }
            }
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Loading orders…")
}
