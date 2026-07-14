package org.eventt.features.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.ActiveOrderDao
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.OrderHistoryDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.ViewContext
import org.eventt.core.database.WalletDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.eveSigFigStep
import org.eventt.core.model.formatEveSigFigPrice
import org.eventt.ui.common.EmptyState
import org.eventt.ui.common.EsiRefreshButton
import org.eventt.ui.common.LoadingOverlay
import org.eventt.ui.common.ensureVisible
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale

private val SELL_COLOR = Color(0xFFFF6B6B)
private val BUY_COLOR = Color(0xFF69DB7C)
private val VOL_SELL = Color(0xFFFF8C00)
private val VOL_BUY = Color(0xFF2E7D32)
private val PROFIT_COLOR = Color(0xFF69DB7C)
private val LOSS_COLOR = Color(0xFFFF6B6B)
private val UNDERCUT_COLOR = Color(0xFFFF9800) // orange — order has been beaten
private val ACTIVE_IN_GAME = Color(0xFF4A90D9) // blue — currently open in EVE client

private const val DEFAULT_REGION_ID = 10000002 // The Forge (Jita) — fallback for inventory items with no active order/region context

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
 * for buy orders' Best Margin; sell orders use [sellMarginPct] against their own cost basis instead
 * (see below) since a sell order already owns the item — buying it again isn't the hypothetical.
 */
internal fun computeBestMarginPct(
    comparison: MarketComparison?,
    taxConfig: CostBasisService.TaxConfig,
): Double? = computeMarginPct(comparison?.bestBuy, comparison?.bestSell, taxConfig)

/** Net margin (%) of selling at [price] (net of sales tax + broker fee) against an already-owned [costBasis]. */
internal fun sellMarginPct(
    price: Double?,
    costBasis: Double?,
    taxConfig: CostBasisService.TaxConfig,
): Double? {
    val p = price ?: return null
    val cost = costBasis ?: return null
    if (cost <= 0) return null
    return (p * taxConfig.sellMultiplier - cost) / cost * 100
}

// COST/RELIST/PROFIT/MARGIN/BEST_MARGIN are Sell-only (sorted in sortSellMetrics, since they're
// derived values, not fields on CharacterOrder); TOTAL/ORDER_AGE are Buy-only (applySort).
private enum class SortCol { NAME, PRICE, VOLUME, TOTAL, TIME_LEFT, ORDER_AGE, COST, RELIST, PROFIT, MARGIN, BEST_MARGIN }

private enum class SortDir { ASC, DESC }

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
    // competing sell order at the same station. Off (show all) by default.
    var showBeatenOnly by remember { mutableStateOf(false) }
    // Buy tab only: when on, hides every order that isn't currently outbid by a higher
    // competing buy order region-wide. Off (show all) by default.
    var showOverbidOnly by remember { mutableStateOf(false) }

    // Market comparison data — loaded after orders, shown as overbid indicators.
    // Refreshed on order load and via the manual "Refresh Prices" button (no auto-refresh:
    // that used to reset the Ctrl+Z hotkey cycle position every 60s — see PendingOrdersQueue.update).
    var marketComparisons by remember { mutableStateOf<Map<Pair<Int, Long>, MarketComparison>>(emptyMap()) }
    var isLoadingMarket by remember { mutableStateOf(false) }
    var marketComparisonsExpiresAt by remember { mutableStateOf<Long?>(null) }

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
                                val bestSell =
                                    sellersElsewhere
                                        .filter { (it["location_id"] as? Number)?.toLong() == locationId }
                                        .mapNotNull { (it["price"] as? Number)?.toDouble() }
                                        .minOrNull()
                                result[typeId to locationId] = MarketComparison(bestSell, bestBuy)
                            }
                            val pairParams = mapOf("order_type" to "all", "type_id" to typeId.toString())
                            // The public order book's own "as of" time for this (typeId, regionId)
                            // pair — used below to reject a price diff that's actually this
                            // specific response lagging behind data we already know is newer,
                            // rather than a genuine relist (e.g. right after a character switch,
                            // where different pairs' cached responses can be minutes apart in age).
                            val pairLastModified = EsiClient.getEndpointLastModifiedMillis("/markets/$regionId/orders/", pairParams)
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
                    withContext(Dispatchers.Main) {
                        marketComparisons = result
                        marketComparisonsExpiresAt = latestExpiry
                        if (detectedChanges.isNotEmpty()) orders = orders.map { detectedChanges[it.orderId] ?: it }
                    }
                } finally {
                    withContext(Dispatchers.Main) { isLoadingMarket = false }
                }
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
                            OrderHistoryDao.OrderHistoryRecord(
                                orderId = (m["order_id"] as? Number)?.toLong() ?: 0L,
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
            // table while the ESI fetch below is in flight.
            val cachedOrders =
                withContext(Dispatchers.IO) { ActiveOrderDao.getAll(characterId = charId, corporationId = corpId) }
            if (cachedOrders.isNotEmpty()) orders = cachedOrders.map { it.toCharacterOrder() }

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
                    val isBeaten =
                        if (order.isBuyOrder) {
                            comp?.bestBuy != null && comp.bestBuy > order.price
                        } else {
                            comp?.bestSell != null && comp.bestSell < order.price
                        }
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
                val beatenCount =
                    tabActive.count { o ->
                        val comp = marketComparisons[o.typeId to o.locationId]
                        if (o.isBuyOrder) {
                            comp?.bestBuy?.let { it > o.price } ?: false
                        } else {
                            comp?.bestSell?.let { it < o.price } ?: false
                        }
                    }
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
                            Text(
                                "Ctrl+Z",
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
                    TextButton(onClick = { recalculateFifo() }) {
                        Text("Recalculate P&L")
                    }
                    EsiRefreshButton(
                        isLoading = isLoadingMarket,
                        enabled = !isLoading,
                        expiresAtMs = marketComparisonsExpiresAt,
                        onClick = { fetchMarketComparisons(orders) },
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
                            ) {
                                FilterChip(
                                    selected = showBeatenOnly,
                                    onClick = { showBeatenOnly = !showBeatenOnly },
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
                                relistDiscountPct = relistDiscountPct,
                                showBeatenOnly = showBeatenOnly,
                                selectedOrderId = selectedOrderId,
                                activeOrderId = activeOrderId,
                                onSelect = { id -> selectedOrderId = if (selectedOrderId == id) null else id },
                                onAction = { order -> triggerOrderAction(order) },
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
            3 -> fifoResult?.let { InventorySummaryBar(it, inventory) }
            2 -> if (historyOrders.isNotEmpty()) HistorySummaryBar(historyOrders, fifoResult)
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

// ── Sorting ───────────────────────────────────────────────────────────────

// Buy-only: sorts by a value derived from market comparison, which doesn't live on CharacterOrder
// itself -- computed once per row in computeBuyMetrics, sorted here.
private fun sortBuyMetrics(
    list: List<BuyOrderMetrics>,
    col: SortCol,
    dir: SortDir,
): List<BuyOrderMetrics> {
    val sorted =
        when (col) {
            SortCol.NAME -> list.sortedBy { it.order.typeName }
            SortCol.PRICE -> list.sortedBy { it.order.price }
            SortCol.RELIST -> list.sortedBy { it.order.relistFeesPaid }
            SortCol.MARGIN -> list.sortedBy { it.marginPct ?: Double.NEGATIVE_INFINITY }
            SortCol.BEST_MARGIN -> list.sortedBy { it.bestMarginPct ?: Double.NEGATIVE_INFINITY }
            SortCol.VOLUME -> list.sortedBy { it.order.volumeRemaining }
            SortCol.TOTAL -> list.sortedBy { it.order.total }
            SortCol.TIME_LEFT -> list.sortedBy { it.order.timeLeftSeconds }
            SortCol.ORDER_AGE -> list.sortedBy { it.order.orderAgeSeconds }
            SortCol.COST, SortCol.PROFIT -> list // Sell-only columns, not shown here
        }
    return if (dir == SortDir.DESC) sorted.reversed() else sorted
}

// Sell-only: sorts by a value derived from cost basis / market comparison, neither of which lives
// on CharacterOrder itself -- computed once per row in computeSellMetrics, sorted here.
private fun sortSellMetrics(
    list: List<SellOrderMetrics>,
    col: SortCol,
    dir: SortDir,
): List<SellOrderMetrics> {
    val sorted =
        when (col) {
            SortCol.NAME -> list.sortedBy { it.order.typeName }
            SortCol.COST -> list.sortedBy { it.costBasis ?: Double.NEGATIVE_INFINITY }
            SortCol.PRICE -> list.sortedBy { it.order.price }
            SortCol.RELIST -> list.sortedBy { it.order.relistFeesPaid }
            SortCol.PROFIT -> list.sortedBy { it.totalProfit ?: Double.NEGATIVE_INFINITY }
            SortCol.MARGIN -> list.sortedBy { it.marginPct ?: Double.NEGATIVE_INFINITY }
            SortCol.BEST_MARGIN -> list.sortedBy { it.bestMarginPct ?: Double.NEGATIVE_INFINITY }
            SortCol.VOLUME -> list.sortedBy { it.order.volumeRemaining }
            SortCol.TIME_LEFT -> list.sortedBy { it.order.timeLeftSeconds }
            SortCol.TOTAL, SortCol.ORDER_AGE -> list // Buy-only columns, not shown here
        }
    return if (dir == SortDir.DESC) sorted.reversed() else sorted
}

// ── Tables ────────────────────────────────────────────────────────────────

// Every per-row derived value the Sell table shows or sorts by, computed once so sorting doesn't
// have to re-derive it and rows don't duplicate the math.
private data class SellOrderMetrics(
    val order: CharacterOrder,
    val comparison: MarketComparison?,
    val costBasis: Double?,
    val isEstimated: Boolean,
    val totalProfit: Double?,
    val marginPct: Double?,
    val bestMarginPct: Double?,
    val updatesRemaining: Int?,
    // Beaten: another sell order at the same station is currently cheaper than ours.
    val isBeaten: Boolean,
)

private fun computeSellMetrics(
    order: CharacterOrder,
    inventory: Map<Int, CostBasisService.InventoryItem>,
    historyCostBasis: Map<Int, Double>,
    taxConfig: CostBasisService.TaxConfig,
    comparisons: Map<Pair<Int, Long>, MarketComparison>,
    relistDiscountPct: Double,
): SellOrderMetrics {
    val comparison = comparisons[order.typeId to order.locationId]
    val costBasis = inventory[order.typeId]?.avgCostBasis ?: historyCostBasis[order.typeId]
    val isEstimated = inventory[order.typeId] == null && costBasis != null
    val netSellPrice = order.price * taxConfig.sellMultiplier
    // Whole remaining position, not per-unit: profit/margin should reflect what this order
    // actually nets after the relist fees already sunk into it, not just its current listed price.
    val totalProfit = costBasis?.let { order.volumeRemaining * (netSellPrice - it) - order.relistFeesPaid }
    val marginPct =
        costBasis?.let { cb ->
            totalProfit?.let { p -> if (cb > 0 && order.volumeRemaining > 0) p / (cb * order.volumeRemaining) * 100 else null }
        }
    // "If I matched the top competing sell price instead" against my own cost basis -- not the
    // region market-flip metric BuyOrderRow uses, since a sell order already owns the item.
    val bestMarginPct = sellMarginPct(comparison?.bestSell, costBasis, taxConfig)
    val updatesRemaining =
        OrderFeeService.estimateUpdatesRemaining(
            volumeRemaining = order.volumeRemaining,
            price = order.price,
            netSellPricePerUnit = netSellPrice,
            costBasis = costBasis,
            brokerFeePct = taxConfig.brokerFeePct,
            relistCount = order.relistCount,
            relistFeesPaid = order.relistFeesPaid,
            relistDiscountPct = relistDiscountPct,
        )
    val isBeaten = comparison?.bestSell != null && comparison.bestSell < order.price
    return SellOrderMetrics(order, comparison, costBasis, isEstimated, totalProfit, marginPct, bestMarginPct, updatesRemaining, isBeaten)
}

@Composable
private fun SellOrdersTable(
    orders: List<CharacterOrder>,
    sortCol: SortCol,
    sortDir: SortDir,
    onSort: (SortCol) -> Unit,
    inventory: Map<Int, CostBasisService.InventoryItem>,
    taxConfig: CostBasisService.TaxConfig,
    historyCostBasis: Map<Int, Double>,
    comparisons: Map<Pair<Int, Long>, MarketComparison>,
    relistDiscountPct: Double,
    showBeatenOnly: Boolean,
    selectedOrderId: Long?,
    activeOrderId: Long?,
    onSelect: (Long) -> Unit,
    onAction: (CharacterOrder) -> Unit,
) {
    val metrics =
        remember(orders, inventory, historyCostBasis, taxConfig, comparisons, relistDiscountPct) {
            orders.map { computeSellMetrics(it, inventory, historyCostBasis, taxConfig, comparisons, relistDiscountPct) }
        }
    val visible = if (showBeatenOnly) metrics.filter { it.isBeaten } else metrics
    val sorted = sortSellMetrics(visible, sortCol, sortDir)

    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                    ).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortHeader("Name", SortCol.NAME, sortCol, sortDir, onSort, Modifier.weight(3f))
            SortHeader("Cost", SortCol.COST, sortCol, sortDir, onSort, Modifier.weight(1.8f))
            SortHeader("Price / Best", SortCol.PRICE, sortCol, sortDir, onSort, Modifier.weight(2.4f))
            SortHeader("Relist", SortCol.RELIST, sortCol, sortDir, onSort, Modifier.weight(1.8f))
            SortHeader("Profit", SortCol.PROFIT, sortCol, sortDir, onSort, Modifier.weight(1.8f))
            SortHeader("Margin", SortCol.MARGIN, sortCol, sortDir, onSort, Modifier.weight(1.2f))
            SortHeader("Best Margin", SortCol.BEST_MARGIN, sortCol, sortDir, onSort, Modifier.weight(1.4f))
            SortHeader("Volume", SortCol.VOLUME, sortCol, sortDir, onSort, Modifier.weight(2.5f))
            SortHeader("Time Left", SortCol.TIME_LEFT, sortCol, sortDir, onSort, Modifier.weight(1.5f))
            StaticHeader("", Modifier.width(36.dp))
        }
        HorizontalDivider()
        val listState = rememberLazyListState()
        // The hotkey cycles activeOrderId through the list, but the highlighted row moves
        // independently of scroll position — without this, cycling can walk the active row
        // off-screen with no visual indication of where it went.
        LaunchedEffect(activeOrderId, sorted) {
            val idx = sorted.indexOfFirst { it.order.orderId == activeOrderId }
            if (idx >= 0) listState.ensureVisible(idx)
        }
        LazyColumn(state = listState) {
            items(sorted, key = { it.order.orderId }) { m ->
                SellOrderRow(
                    metrics = m,
                    isSelected = selectedOrderId == m.order.orderId,
                    isActiveInGame = activeOrderId == m.order.orderId,
                    onSelect = { onSelect(m.order.orderId) },
                    onAction = { onAction(m.order) },
                )
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

// Every per-row derived value the Buy table shows or sorts by, computed once so sorting doesn't
// have to re-derive it and rows don't duplicate the math.
private data class BuyOrderMetrics(
    val order: CharacterOrder,
    val comparison: MarketComparison?,
    val marginPct: Double?,
    val bestMarginPct: Double?,
    // Overbid: another buy order region-wide currently pays more than ours.
    val isOverbid: Boolean,
)

private fun computeBuyMetrics(
    order: CharacterOrder,
    taxConfig: CostBasisService.TaxConfig,
    comparisons: Map<Pair<Int, Long>, MarketComparison>,
): BuyOrderMetrics {
    val comparison = comparisons[order.typeId to order.locationId]
    // Margin if this order fills and the item is resold at the current best sell price.
    val marginPct = computeMarginPct(order.price, comparison?.bestSell, taxConfig)
    val bestMarginPct = computeBestMarginPct(comparison, taxConfig)
    val isOverbid = comparison?.bestBuy != null && comparison.bestBuy > order.price
    return BuyOrderMetrics(order, comparison, marginPct, bestMarginPct, isOverbid)
}

@Composable
private fun BuyOrdersTable(
    orders: List<CharacterOrder>,
    sortCol: SortCol,
    sortDir: SortDir,
    onSort: (SortCol) -> Unit,
    taxConfig: CostBasisService.TaxConfig,
    comparisons: Map<Pair<Int, Long>, MarketComparison>,
    showOverbidOnly: Boolean,
    selectedOrderId: Long?,
    activeOrderId: Long?,
    onSelect: (Long) -> Unit,
    onAction: (CharacterOrder) -> Unit,
) {
    val metrics =
        remember(orders, taxConfig, comparisons) {
            orders.map { computeBuyMetrics(it, taxConfig, comparisons) }
        }
    val visible = if (showOverbidOnly) metrics.filter { it.isOverbid } else metrics
    val sorted = sortBuyMetrics(visible, sortCol, sortDir)

    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                    ).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortHeader("Name", SortCol.NAME, sortCol, sortDir, onSort, Modifier.weight(3f))
            SortHeader("Price / Best", SortCol.PRICE, sortCol, sortDir, onSort, Modifier.weight(2.4f))
            SortHeader("Relist", SortCol.RELIST, sortCol, sortDir, onSort, Modifier.weight(1.6f))
            SortHeader("Margin", SortCol.MARGIN, sortCol, sortDir, onSort, Modifier.weight(1.2f))
            SortHeader("Best Margin", SortCol.BEST_MARGIN, sortCol, sortDir, onSort, Modifier.weight(1.4f))
            SortHeader("Volume", SortCol.VOLUME, sortCol, sortDir, onSort, Modifier.weight(2.5f))
            SortHeader("Total", SortCol.TOTAL, sortCol, sortDir, onSort, Modifier.weight(2f))
            SortHeader("Time Left", SortCol.TIME_LEFT, sortCol, sortDir, onSort, Modifier.weight(1.5f))
            SortHeader("Order Age", SortCol.ORDER_AGE, sortCol, sortDir, onSort, Modifier.weight(1.5f))
            StaticHeader("", Modifier.width(36.dp))
        }
        HorizontalDivider()
        val listState = rememberLazyListState()
        // The hotkey cycles activeOrderId through the list, but the highlighted row moves
        // independently of scroll position — without this, cycling can walk the active row
        // off-screen with no visual indication of where it went.
        LaunchedEffect(activeOrderId, sorted) {
            val idx = sorted.indexOfFirst { it.order.orderId == activeOrderId }
            if (idx >= 0) listState.ensureVisible(idx)
        }
        LazyColumn(state = listState) {
            items(sorted, key = { it.order.orderId }) { m ->
                BuyOrderRow(
                    metrics = m,
                    isSelected = selectedOrderId == m.order.orderId,
                    isActiveInGame = activeOrderId == m.order.orderId,
                    onSelect = { onSelect(m.order.orderId) },
                    onAction = { onAction(m.order) },
                )
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun OrderHistoryTable(
    orders: List<OrderHistoryDao.OrderHistoryRecord>,
    fifoResult: CostBasisService.FifoResult?,
) {
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                    ).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaticHeader("Name", Modifier.weight(3f))
            StaticHeader("Type", Modifier.weight(1f))
            StaticHeader("State", Modifier.weight(1.5f))
            StaticHeader("Price", Modifier.weight(2f))
            StaticHeader("Profit", Modifier.weight(2f))
            StaticHeader("Margin", Modifier.weight(1.2f))
            StaticHeader("Volume", Modifier.weight(2f))
            StaticHeader("Issued", Modifier.weight(2f))
            StaticHeader("Station", Modifier.weight(2.5f))
        }
        HorizontalDivider()
        LazyColumn {
            items(orders, key = { it.orderId }) { order ->
                val (pnl, margin) = historyPnl(order, fifoResult)
                OrderHistoryRow(order, pnl, margin)
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun InventoryTable(
    inventory: Map<Int, CostBasisService.InventoryItem>,
    sellOrders: List<CharacterOrder>,
    fifoResult: CostBasisService.FifoResult?,
    marketPrices: Map<Int, Double>,
) {
    val sellByType = sellOrders.filter { !it.isBuyOrder && it.state == "active" }.groupBy { it.typeId }
    val realizedByType = fifoResult?.realizedByType ?: emptyMap()
    val items = inventory.values.sortedBy { it.typeName }

    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                    ).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaticHeader("Name", Modifier.weight(3f))
            StaticHeader("Qty", Modifier.weight(1.5f))
            StaticHeader("Avg Cost", Modifier.weight(2f))
            StaticHeader("Total Cost", Modifier.weight(2f))
            StaticHeader("Sell Price", Modifier.weight(2f))
            StaticHeader("Profit/unit", Modifier.weight(2f))
            StaticHeader("Margin", Modifier.weight(1.2f))
            StaticHeader("Realized P&L", Modifier.weight(2f))
        }
        HorizontalDivider()
        val tax = fifoResult?.taxConfig ?: CostBasisService.TaxConfig()
        LazyColumn {
            items(items, key = { it.typeId }) { item ->
                val activeOrder = sellByType[item.typeId]?.maxByOrNull { it.price }
                val realized = realizedByType[item.typeId]?.sumOf { it.profit }
                val sellPrice = activeOrder?.price ?: marketPrices[item.typeId]
                val isOwnListing = activeOrder != null
                InventoryRow(item, sellPrice, isOwnListing, realized, tax)
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

// ── Rows ──────────────────────────────────────────────────────────────────

@Composable
private fun SellOrderRow(
    metrics: SellOrderMetrics,
    isSelected: Boolean,
    isActiveInGame: Boolean,
    onSelect: () -> Unit,
    onAction: () -> Unit,
) {
    val order = metrics.order
    val comparison = metrics.comparison
    val costBasis = metrics.costBasis
    val isEstimated = metrics.isEstimated
    val totalProfit = metrics.totalProfit
    val marginPct = metrics.marginPct
    val bestMarginPct = metrics.bestMarginPct
    val isBeaten = metrics.isBeaten
    val profitColor = totalProfit?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val bestMarginColor = bestMarginPct?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val rowBg =
        when {
            isActiveInGame -> ACTIVE_IN_GAME.copy(alpha = 0.15f)
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else -> Color.Transparent
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(rowBg)
                .clickable { onSelect() }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name + status dot
        Row(
            modifier = Modifier.weight(3f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatusDot(order.state)
            if (isBeaten) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Undercut", modifier = Modifier.size(11.dp), tint = UNDERCUT_COLOR)
            }
            Text(order.typeName, style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis, maxLines = 1)
        }

        Text(
            costBasis?.let { if (isEstimated) "~${formatIsk(it)}" else formatIsk(it) } ?: "—",
            modifier = Modifier.weight(1.8f),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (isEstimated) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )

        // Price column: order price + competing price below if beaten
        Column(modifier = Modifier.weight(2.4f)) {
            Text(
                formatIsk(order.price),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isBeaten) UNDERCUT_COLOR else SELL_COLOR,
            )
            val bestSell = comparison?.bestSell
            if (isBeaten && bestSell != null) {
                Text(
                    "Best: ${formatIsk(bestSell)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = UNDERCUT_COLOR.copy(alpha = 0.8f),
                )
            }
        }

        RelistCell(order.relistCount, order.relistFeesPaid, metrics.updatesRemaining, modifier = Modifier.weight(1.8f))

        Text(
            totalProfit?.let { (if (isEstimated) "~" else "") + formatIsk(it) } ?: "—",
            modifier = Modifier.weight(1.8f),
            style = MaterialTheme.typography.bodySmall,
            color = if (isEstimated) profitColor.copy(alpha = 0.7f) else profitColor,
            fontWeight = if (totalProfit != null) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            marginPct?.let { (if (isEstimated) "~" else "") + "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall,
            color = if (isEstimated) profitColor.copy(alpha = 0.7f) else profitColor,
        )
        Text(
            bestMarginPct?.let { "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodySmall,
            color = bestMarginColor,
        )
        VolumeBar(order.volumeRemaining, order.volumeTotal, isSell = true, modifier = Modifier.weight(2.5f).padding(horizontal = 4.dp))
        Text(
            formatDuration(order.timeLeftSeconds),
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.bodySmall,
            color = timeLeftColor(order.timeLeftSeconds),
        )

        // Action button: open market in-game + copy overbid price
        IconButton(modifier = Modifier.size(36.dp), onClick = onAction) {
            Icon(
                Icons.Default.OpenInBrowser,
                contentDescription = "Open in game & copy price",
                modifier = Modifier.size(16.dp),
                tint =
                    when {
                        isActiveInGame -> ACTIVE_IN_GAME
                        isBeaten -> UNDERCUT_COLOR
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

// Modification fees paid so far ("N× · total") plus an estimated countdown to zero margin
// ("~N left"), stacked like the Price column's undercut sub-line. updatesRemaining is omitted
// (buy orders, or no cost basis to estimate against) rather than shown as a misleading "—".
@Composable
private fun RelistCell(
    relistCount: Int,
    relistFeesPaid: Double,
    updatesRemaining: Int?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            if (relistCount > 0) "$relistCount× · ${formatIsk(relistFeesPaid)}" else "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (updatesRemaining != null) {
            Text(
                "~$updatesRemaining left",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun BuyOrderRow(
    metrics: BuyOrderMetrics,
    isSelected: Boolean,
    isActiveInGame: Boolean,
    onSelect: () -> Unit,
    onAction: () -> Unit,
) {
    val order = metrics.order
    val comparison = metrics.comparison
    val isOverbid = metrics.isOverbid
    val marginPct = metrics.marginPct
    val marginColor = marginPct?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val bestMarginPct = metrics.bestMarginPct
    val bestMarginColor = bestMarginPct?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val rowBg =
        when {
            isActiveInGame -> ACTIVE_IN_GAME.copy(alpha = 0.15f)
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else -> Color.Transparent
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(rowBg)
                .clickable { onSelect() }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(3f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatusDot(order.state)
            if (isOverbid) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Overbid", modifier = Modifier.size(11.dp), tint = UNDERCUT_COLOR)
            }
            Text(order.typeName, style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis, maxLines = 1)
        }

        // Price column: order price + competing price below if overbid
        Column(modifier = Modifier.weight(2.4f)) {
            Text(formatIsk(order.price), style = MaterialTheme.typography.bodyMedium, color = if (isOverbid) UNDERCUT_COLOR else BUY_COLOR)
            val bestBuy = comparison?.bestBuy
            if (isOverbid && bestBuy != null) {
                Text(
                    "Best: ${formatIsk(bestBuy)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = UNDERCUT_COLOR.copy(alpha = 0.8f),
                )
            }
        }

        // No "~N left" estimate here: unlike a sell order, a buy order has no committed cost
        // basis yet to measure remaining margin against, only a speculative future resale price.
        RelistCell(order.relistCount, order.relistFeesPaid, updatesRemaining = null, modifier = Modifier.weight(1.6f))

        Text(
            marginPct?.let { "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall,
            color = marginColor,
        )
        Text(
            bestMarginPct?.let { "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodySmall,
            color = bestMarginColor,
        )

        VolumeBar(order.volumeRemaining, order.volumeTotal, isSell = false, modifier = Modifier.weight(2.5f).padding(horizontal = 4.dp))
        Text(formatIsk(order.total), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
        Text(
            formatDuration(order.timeLeftSeconds),
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.bodySmall,
            color = timeLeftColor(order.timeLeftSeconds),
        )
        Text(
            formatDuration(order.orderAgeSeconds),
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        IconButton(modifier = Modifier.size(36.dp), onClick = onAction) {
            Icon(
                Icons.Default.OpenInBrowser,
                contentDescription = "Open in game & copy price",
                modifier = Modifier.size(16.dp),
                tint =
                    when {
                        isActiveInGame -> ACTIVE_IN_GAME
                        isOverbid -> UNDERCUT_COLOR
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@Composable
private fun OrderHistoryRow(
    order: OrderHistoryDao.OrderHistoryRecord,
    pnl: Double?,
    marginPct: Double?,
) {
    val effectiveState = effectiveOrderState(order)
    val stateColor =
        when (effectiveState) {
            "fulfilled" -> Color(0xFF69DB7C)
            "partially_filled" -> Color(0xFF74C0FC)
            "cancelled" -> Color(0xFFFF6B6B)
            else -> Color(0xFFFFD43B)
        }
    val profitColor = pnl?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            order.typeName,
            modifier = Modifier.weight(3f),
            style = MaterialTheme.typography.bodyMedium,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Text(
            if (order.isBuyOrder) "Buy" else "Sell",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (order.isBuyOrder) BUY_COLOR else SELL_COLOR,
        )
        Text(
            effectiveState.split("_").joinToString(" ") { it.replaceFirstChar(Char::uppercase) },
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.bodySmall,
            color = stateColor,
        )
        Text(formatIsk(order.price), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
        Text(
            pnl?.let { formatIsk(it) } ?: "—",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = profitColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (pnl != null) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            marginPct?.let { "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall,
            color = profitColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${formatNumber(order.volumeRemaining)}/${formatNumber(order.volumeTotal)}",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            order.issued.take(16).replace("T", " "),
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            order.stationName,
            modifier = Modifier.weight(2.5f).padding(start = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}

@Composable
private fun InventoryRow(
    item: CostBasisService.InventoryItem,
    sellPrice: Double?,
    isOwnListing: Boolean,
    realizedPnl: Double?,
    taxConfig: CostBasisService.TaxConfig,
) {
    val netSellPrice = sellPrice?.let { it * taxConfig.sellMultiplier }
    val profitPerUnit = netSellPrice?.let { it - item.avgCostBasis }
    val marginPct = profitPerUnit?.let { if (item.avgCostBasis > 0) it / item.avgCostBasis * 100 else null }
    val profitColor = profitPerUnit?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val realizedColor = realizedPnl?.let { if (it >= 0) PROFIT_COLOR else LOSS_COLOR } ?: MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.typeName,
            modifier = Modifier.weight(3f),
            style = MaterialTheme.typography.bodyMedium,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Text(formatNumber(item.remainingQty), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodyMedium)
        Text(
            formatIsk(item.avgCostBasis),
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(formatIsk(item.totalCostBasis), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
        Text(
            sellPrice?.let { formatIsk(it) } ?: "—",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = if (isOwnListing) SELL_COLOR else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            profitPerUnit?.let { formatIsk(it) } ?: "—",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = profitColor,
            fontWeight = if (profitPerUnit != null) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            marginPct?.let { "%.1f%%".format(it) } ?: "—",
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall,
            color = profitColor,
        )
        Text(
            realizedPnl?.let { formatIsk(it) } ?: "—",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.bodySmall,
            color = realizedColor,
            fontWeight = if (realizedPnl != null) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ── Sub-components ────────────────────────────────────────────────────────

// Corp view: narrows the Sell/Buy tables (and the Ctrl+Z queue) down to one member's orders.
@Composable
private fun IssuerFilterChip(
    issuerNames: Map<Int, String>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val sortedIssuers = remember(issuerNames) { issuerNames.entries.sortedBy { it.value } }

    Box {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            onClick = { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(
                    selected?.let { issuerNames[it] } ?: "All characters",
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (selected == null) Icon(Icons.Default.Check, null, Modifier.size(14.dp)) else Spacer(Modifier.size(14.dp))
                        Text("All characters")
                    }
                },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            sortedIssuers.forEach { (charId, name) ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (selected == charId) Icon(Icons.Default.Check, null, Modifier.size(14.dp)) else Spacer(Modifier.size(14.dp))
                            Text(name)
                        }
                    },
                    onClick = {
                        onSelect(charId)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun VolumeBar(
    remaining: Int,
    total: Int,
    isSell: Boolean,
    modifier: Modifier,
) {
    val fraction = if (total > 0) remaining.toFloat() / total.toFloat() else 0f
    val barColor = if (isSell) VOL_SELL else VOL_BUY

    Box(
        modifier =
            modifier
                .height(18.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(barColor)
                    .align(Alignment.CenterStart),
        )
        Text(
            "${formatNumber(remaining)}/${formatNumber(total)}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatusDot(state: String) {
    val color =
        when (state) {
            "active" -> Color(0xFF69DB7C)
            "cancelled" -> Color(0xFFFF6B6B)
            "expired" -> Color(0xFFFF6B6B)
            "pending" -> Color(0xFF74C0FC)
            else -> Color.Gray
        }
    Box(modifier = Modifier.size(7.dp).background(color, shape = MaterialTheme.shapes.small))
}

@Composable
private fun SortHeader(
    label: String,
    col: SortCol,
    currentCol: SortCol,
    dir: SortDir,
    onSort: (SortCol) -> Unit,
    modifier: Modifier,
    rightAlign: Boolean = false,
) {
    val isActive = currentCol == col
    val labelColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.clickable { onSort(col) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (rightAlign) Arrangement.End else Arrangement.Start,
    ) {
        if (isActive && rightAlign) {
            Icon(
                if (dir ==
                    SortDir.ASC
                ) {
                    Icons.Default.ArrowUpward
                } else {
                    Icons.Default.ArrowDownward
                },
                null,
                Modifier.size(12.dp),
                tint = labelColor,
            )
            Spacer(Modifier.width(2.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = labelColor,
        )
        if (isActive && !rightAlign) {
            Spacer(Modifier.width(2.dp))
            Icon(
                if (dir ==
                    SortDir.ASC
                ) {
                    Icons.Default.ArrowUpward
                } else {
                    Icons.Default.ArrowDownward
                },
                null,
                Modifier.size(12.dp),
                tint = labelColor,
            )
        }
    }
}

@Composable
private fun StaticHeader(
    label: String,
    modifier: Modifier,
    rightAlign: Boolean = false,
) {
    Text(
        label,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (rightAlign) TextAlign.End else TextAlign.Start,
    )
}

// ── Summary bars ──────────────────────────────────────────────────────────

@Composable
private fun OrdersSummaryBar(
    orders: List<CharacterOrder>,
    inventory: Map<Int, CostBasisService.InventoryItem>?,
    taxConfig: CostBasisService.TaxConfig = CostBasisService.TaxConfig(),
) {
    val active = orders.filter { it.state == "active" }
    val totalRemain = active.sumOf { it.volumeRemaining }
    val totalEntered = active.sumOf { it.volumeTotal }
    val pct = if (totalEntered > 0) totalRemain * 100.0 / totalEntered else 0.0
    val totalIsk = active.sumOf { it.total }
    val totalProfit =
        inventory?.let {
            active
                .sumOf { o ->
                    val cb = it[o.typeId]?.avgCostBasis ?: return@sumOf 0.0
                    (o.price * taxConfig.sellMultiplier - cb) * o.volumeRemaining
                }.takeIf { it != 0.0 }
        }

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryItem("Active orders", active.size.toString())
            SummaryItem("Volume", "${formatNumber(totalRemain)}/${formatNumber(totalEntered)} (${String.format(Locale.US, "%.1f", pct)}%)")
            SummaryItem("Total ISK", "${formatIsk(totalIsk)} ISK")
            if (totalProfit != null) {
                val color = if (totalProfit >= 0) PROFIT_COLOR else LOSS_COLOR
                SummaryItem("Expected profit", "${formatIsk(totalProfit)} ISK", color)
            }
            val totalRelistFees = active.sumOf { it.relistFeesPaid }
            if (totalRelistFees > 0) {
                SummaryItem("Relist fees", "${formatIsk(totalRelistFees)} ISK", LOSS_COLOR)
            }
        }
    }
}

@Composable
private fun HistorySummaryBar(
    orders: List<OrderHistoryDao.OrderHistoryRecord>,
    fifoResult: CostBasisService.FifoResult?,
) {
    val effectiveStates = orders.associateWith { effectiveOrderState(it) }
    val fulfilled = effectiveStates.count { it.value == "fulfilled" }
    val partiallyFilled = effectiveStates.count { it.value == "partially_filled" }
    val cancelled = effectiveStates.count { it.value == "cancelled" }
    val expired = effectiveStates.count { it.value == "expired" }
    val totalSold =
        orders
            .filter { !it.isBuyOrder && effectiveStates[it] in setOf("fulfilled", "partially_filled") }
            .sumOf { it.price * (it.volumeTotal - it.volumeRemaining) }
    val totalPnl = fifoResult?.totalRealizedPnl

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryItem("Total", orders.size.toString())
            SummaryItem("Fulfilled", fulfilled.toString())
            if (partiallyFilled > 0) SummaryItem("Partially filled", partiallyFilled.toString())
            SummaryItem("Cancelled", cancelled.toString())
            SummaryItem("Expired", expired.toString())
            if (totalSold > 0) SummaryItem("Sell volume", "${formatIsk(totalSold)} ISK")
            if (totalPnl != null) {
                val color = if (totalPnl >= 0) PROFIT_COLOR else LOSS_COLOR
                SummaryItem("Total realized P&L", "${formatIsk(totalPnl)} ISK", color)
            }
        }
    }
}

@Composable
private fun InventorySummaryBar(
    fifoResult: CostBasisService.FifoResult,
    inventory: Map<Int, CostBasisService.InventoryItem>,
) {
    val totalCost = inventory.values.sumOf { it.totalCostBasis }
    val totalPnl = fifoResult.totalRealizedPnl
    val pnlColor = if (totalPnl >= 0) PROFIT_COLOR else LOSS_COLOR

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryItem("Items", inventory.size.toString())
            SummaryItem("Inventory cost", "${formatIsk(totalCost)} ISK")
            SummaryItem("All-time realized P&L", "${formatIsk(totalPnl)} ISK", pnlColor)
        }
    }
}

@Composable
private fun SummaryItem(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label + ":", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

// ── History P&L helper ────────────────────────────────────────────────────

internal fun historyPnl(
    order: OrderHistoryDao.OrderHistoryRecord,
    fifoResult: CostBasisService.FifoResult?,
): Pair<Double?, Double?> {
    if (order.isBuyOrder || fifoResult == null) return null to null
    val filled = order.volumeTotal - order.volumeRemaining
    if (filled <= 0) return null to null // nothing actually sold (cancelled/expired with no fills)

    val taxConfig = fifoResult.taxConfig
    val netSellPrice = order.price * taxConfig.sellMultiplier

    val fifoProfit = CostBasisService.pnlForOrder(fifoResult, order.typeId, order.issued, filled)
    if (fifoProfit != null) {
        val cb = netSellPrice - fifoProfit / filled
        val margin = if (cb > 0) (netSellPrice - cb) / cb * 100 else 0.0
        return fifoProfit to margin
    }

    val cb = fifoResult.avgCostBasisForType(order.typeId) ?: return null to null
    val profit = (netSellPrice - cb) * filled
    val margin = if (cb > 0) (netSellPrice - cb) / cb * 100 else 0.0
    return profit to margin
}

// ESI's order-history `state` is only ever "expired" or "cancelled" — CCP never reports
// "fulfilled", even when an order sold out completely before its duration ran out. The only
// reliable signal for that is volume_remain vs volume_total, so this derives a more useful
// four-way status ("fulfilled" / "partially_filled" / the raw "cancelled" / the raw "expired")
// that the raw ESI field alone can't distinguish.
internal fun effectiveOrderState(order: OrderHistoryDao.OrderHistoryRecord): String =
    when {
        order.volumeRemaining <= 0 -> "fulfilled"
        order.volumeRemaining < order.volumeTotal -> "partially_filled"
        else -> order.state // nothing filled at all: the raw "cancelled" or "expired" stands
    }

// ── Formatters ────────────────────────────────────────────────────────────

private fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "—"
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

private fun timeLeftColor(seconds: Long): Color =
    when {
        seconds <= 0 -> Color(0xFFFF6B6B)
        seconds < 86400 -> Color(0xFFFFD43B)
        else -> Color.Unspecified
    }

private fun formatNumber(value: Int): String = "%,d".format(value)

private fun formatIsk(value: Double): String =
    when {
        kotlin.math.abs(value) >= 1_000_000_000_000 -> "%.2fT".format(value / 1_000_000_000_000)
        kotlin.math.abs(value) >= 1_000_000_000 -> "%.2fB".format(value / 1_000_000_000)
        kotlin.math.abs(value) >= 1_000_000 -> "%.2fM".format(value / 1_000_000)
        kotlin.math.abs(value) >= 1_000 -> "%.2fK".format(value / 1_000)
        else -> "%,.2f".format(value)
    }
