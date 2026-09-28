package org.eventt.features.market

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.ActiveOrderDao
import org.eventt.core.database.AssetDao
import org.eventt.core.database.MarketDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.WalletDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.MarketHistoryModel
import org.eventt.core.model.PLEX_MARKET_REGION_ID
import org.eventt.core.model.PLEX_TYPE_ID
import org.eventt.ui.common.ContentCard
import org.eventt.ui.common.EmptyState
import org.eventt.ui.common.TypeIcon
import org.eventt.ui.common.formatIsk
import org.eventt.ui.common.formatPriceAbbr
import org.eventt.ui.common.formatVolume
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import java.time.LocalDate
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Fixed chart palette rather than theme roles: the EVE theme's primary/warning/tertiary render as
// near-identical oranges, which made overlaid series indistinguishable.
private val SMA_FAST_COLOR = Color(0xFF4FC3F7)
private val SMA_SLOW_COLOR = Color(0xFFBA68C8)
private val BAND_COLOR = Color(0xFF90A4AE)
private val SECONDARY_COLOR = Color(0xFF81C784)
private val COST_COLOR = Color(0xFFFFD54F)
private val RSI_COLOR = Color(0xFFFFB74D)
private val WICK_COLOR = Color(0xFFE0E0E0)
private val AXIS_COLOR = Color(0xFF777777)
private val GRID_COLOR = Color(0x22FFFFFF)

private const val THE_FORGE = 10000002
private const val JITA_44 = 60003760L

private enum class ChartRange(
    val label: String,
    val days: Int,
) {
    M1("1M", 30),
    M3("3M", 90),
    M6("6M", 180),
    Y1("1Y", 365),
    ALL("All", 450),
}

// Always hits ESI directly (which has its own response-level cache, so this doesn't spam the
// network) rather than reusing MarketAnalysisCompute's fetchHistory — that one prefers whatever's
// already in the local market_history table over re-fetching, which is right for a bulk region
// scan but wrong here: this dialog makes one history call per region, and preferring stale/partial
// cached rows meant the two regions' series could desync in date coverage. Falls back to whatever's
// cached only if the live call itself fails (e.g. no network).
private fun fetchLiveHistory(
    typeId: Int,
    regionId: Int,
): List<MarketHistoryModel> {
    val effectiveRegionId = if (typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else regionId
    return try {
        EsiClient.getMarketRegionHistory(effectiveRegionId, typeId).mapNotNull { entry ->
            val date = entry["date"] as? String ?: return@mapNotNull null
            MarketHistoryModel(
                typeId = typeId,
                regionId = effectiveRegionId,
                date = date,
                average = (entry["average"] as? Number)?.toDouble() ?: 0.0,
                volume = (entry["volume"] as? Number)?.toLong() ?: 0L,
                orderCount = (entry["order_count"] as? Number)?.toLong() ?: 0L,
                highest = (entry["highest"] as? Number)?.toDouble() ?: 0.0,
                lowest = (entry["lowest"] as? Number)?.toDouble() ?: 0.0,
            )
        }
    } catch (_: Exception) {
        MarketDao.getHistory(typeId, effectiveRegionId, ChartRange.ALL.days)
    }.sortedBy { it.date }
}

// ─── Data ─────────────────────────────────────────────────────────────────────────────────

internal data class MyTrades(
    val qtyHeld: Long,
    val avgBuyPrice: Double?,
    val realizedPnl: Double?,
    val sellFeePct: Double,
    val transactions: List<WalletDao.RawTxRecord>,
    val openOrders: List<ActiveOrderDao.ActiveOrderRecord>,
    // Fills/orders in the charted region. Fills elsewhere (inter-region trading) are still plotted,
    // but hollow — their price is another market's, not a point on this chart's book.
    val regionTransactions: List<WalletDao.RawTxRecord>,
    val regionOrders: List<ActiveOrderDao.ActiveOrderRecord>,
)

// In corp view (corpId set) everything comes from the corporation's wallet, orders and hangars —
// corp transactions are stored without a character_id — while fees stay the acting member's.
internal fun loadMyTrades(
    charId: Int,
    corpId: Int?,
    typeId: Int,
    regionId: Int,
): MyTrades {
    val txs =
        (if (corpId != null) WalletDao.getAllTransactions(corporationId = corpId) else WalletDao.getAllTransactions(characterId = charId))
            .filter { it.typeId == typeId }
    val basis = txCostBasis(txs)
    val openOrders =
        (if (corpId != null) ActiveOrderDao.getAll(corporationId = corpId) else ActiveOrderDao.getAll(characterId = charId))
            .filter { it.typeId == typeId && it.state == "active" }
    // Physical stock, not net(buys - sells): materials often arrive via mining/reprocessing with no
    // transaction at all (same reasoning as MaterialPosition.qtyHeld). Plus whatever's listed in
    // sell orders, which EVE escrows out of the hangar.
    val held =
        (if (corpId != null) AssetDao.getByCorporation(corpId) else AssetDao.getByCharacter(charId))
            .filter { it.typeId == typeId }
            .sumOf { it.quantity.toLong() } +
            openOrders.filter { !it.isBuyOrder }.sumOf { it.volumeRemaining.toLong() }
    return MyTrades(
        qtyHeld = held,
        avgBuyPrice = basis.avgCost,
        realizedPnl = basis.realized.takeIf { txs.any { !it.isBuy } },
        sellFeePct = StaticDataDao.getCharSalesTax(charId) + StaticDataDao.getCharBrokersFee(charId),
        transactions = txs,
        openOrders = openOrders,
        regionTransactions = txs.filter { regionOf(it.locationId).let { r -> r == null || r == regionId } },
        regionOrders = openOrders.filter { it.regionId <= 0 || it.regionId == regionId },
    )
}

// Station -> region from static data; null for player structures, which aren't in it — those
// fills are kept rather than dropped, since their region can't be ruled out.
private val stationRegions = java.util.concurrent.ConcurrentHashMap<Long, Int>()

private fun regionOf(locationId: Long): Int? =
    stationRegions[locationId] ?: StaticDataDao.getStationById(locationId)?.regionId?.also { stationRegions[locationId] = it }

private fun Map<String, Any?>.price() = (get("price") as? Number)?.toDouble() ?: 0.0

private fun Map<String, Any?>.isBuyOrd() = get("is_buy_order") as? Boolean == true

private fun Map<String, Any?>.loc() = (get("location_id") as? Number)?.toLong()

private fun Map<String, Any?>.vol() = (get("volume_remain") as? Number)?.toLong() ?: 0L

private fun Map<String, Any?>.orderId() = (get("order_id") as? Number)?.toLong()

/**
 * Trading-terminal view of one item, shared by Station Trading, Inter-Region and Materials
 * Investment: a price chart over a selectable range with SMA/Bollinger/RSI and volume, your own
 * fills and open orders plotted on it, the live order book, Adam4EVE's per-side fill rate, and
 * your position. [secondaryRegionId] is null for single-region callers; Inter-Region passes both
 * sides of its route and gets the second region overlaid plus a second book.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemDetailDialog(
    typeId: Int,
    typeName: String,
    primaryRegionId: Int,
    primaryRegionName: String,
    primaryStationId: Long?,
    secondaryRegionId: Int? = null,
    secondaryRegionName: String? = null,
    secondaryStationId: Long? = null,
    charId: Int?,
    // Corp view: show the corporation's fills/orders/assets (charId is then the acting member).
    corporationId: Int? = null,
    onDismiss: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(true) }
    var primaryOrders by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var secondaryOrders by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var primaryHistory by remember { mutableStateOf<List<MarketHistoryModel>>(emptyList()) }
    var secondaryHistory by remember { mutableStateOf<List<MarketHistoryModel>>(emptyList()) }
    var myTrades by remember { mutableStateOf<MyTrades?>(null) }
    var flow by remember { mutableStateOf<StationFlow?>(null) }
    var flowLoading by remember { mutableStateOf(true) }

    val effPrimaryRegion = if (typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else primaryRegionId
    // Station/system scoping is meaningless for PLEX (global market) — filtering its book down to
    // some other item's station would just show an empty book.
    val effPrimaryStation = if (typeId == PLEX_TYPE_ID) null else primaryStationId
    val effSecondaryStation = if (typeId == PLEX_TYPE_ID) null else secondaryStationId
    val primaryLabel = if (typeId == PLEX_TYPE_ID) "Global Market (PLEX)" else primaryRegionName
    val secondaryLabel = if (typeId == PLEX_TYPE_ID) "Global Market (PLEX)" else secondaryRegionName

    LaunchedEffect(typeId, primaryRegionId, secondaryRegionId) {
        isLoading = true
        withContext(Dispatchers.IO) {
            primaryOrders = runCatching { EsiClient.getMarketRegionOrders(effPrimaryRegion, typeId = typeId) }.getOrDefault(emptyList())
            primaryHistory = fetchLiveHistory(typeId, primaryRegionId)
            if (secondaryRegionId != null) {
                val effSecondary = if (typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else secondaryRegionId
                secondaryOrders = runCatching { EsiClient.getMarketRegionOrders(effSecondary, typeId = typeId) }.getOrDefault(emptyList())
                secondaryHistory = fetchLiveHistory(typeId, secondaryRegionId)
            } else {
                secondaryOrders = emptyList()
                secondaryHistory = emptyList()
            }
            myTrades = charId?.let { runCatching { loadMyTrades(it, corporationId, typeId, effPrimaryRegion) }.getOrNull() }
        }
        isLoading = false
    }
    // Adam4EVE downloads a week of whole-universe CSVs on first use — loaded separately so the
    // chart doesn't wait on it.
    LaunchedEffect(typeId, effPrimaryRegion, effPrimaryStation) {
        flowLoading = true
        flow =
            runCatching {
                if (effPrimaryStation != null) {
                    Adam4EveFlowService.fetchStationFlow(effPrimaryStation, listOf(typeId))[typeId]
                } else {
                    Adam4EveFlowService.fetchRegionFlow(effPrimaryRegion, typeId)
                }
            }.getOrNull()
        flowLoading = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.94f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                val book = remember(primaryOrders, effPrimaryStation) { BookSide.of(primaryOrders, effPrimaryStation) }
                Header(typeId, typeName, primaryLabel, book, primaryHistory, onDismiss)
                Spacer(Modifier.height(8.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }

                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ItemPriceChart(
                        typeId = typeId,
                        regionId = effPrimaryRegion,
                        stationId = effPrimaryStation,
                        history = primaryHistory,
                        secondaryHistory = secondaryHistory,
                        secondaryLabel = secondaryLabel,
                        trades = myTrades,
                        bestBid = book.bestBid,
                        bestAsk = book.bestAsk,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    Column(
                        modifier = Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatsCard(primaryHistory, book, flow, flowLoading, effPrimaryStation != null)
                        myTrades?.let { PositionCard(it, book) }
                        OrderBookCard(primaryLabel, book, myTrades?.openOrders.orEmpty())
                        if (secondaryRegionId != null) {
                            val secondaryBook =
                                remember(secondaryOrders, effSecondaryStation) { BookSide.of(secondaryOrders, effSecondaryStation) }
                            OrderBookCard(secondaryLabel ?: "", secondaryBook, myTrades?.openOrders.orEmpty())
                        }
                        myTrades?.takeIf { it.transactions.isNotEmpty() }?.let { TradesCard(it.transactions, it.regionTransactions) }
                    }
                }
            }
        }
    }
}

/**
 * The chart half of the item view — range/indicator toolbar, candles with SMA/Bollinger/RSI and
 * volume, your fills and levels, Adam4EVE overlay — shared by ItemDetailDialog and the Market
 * Browser's history tab. Toolbar choices persist in settings (itemChart.*) across both.
 * [regionId]/[stationId] are the effective (PLEX-redirected) market the Adam4EVE data is read for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ItemPriceChart(
    typeId: Int,
    regionId: Int,
    stationId: Long?,
    history: List<MarketHistoryModel>,
    secondaryHistory: List<MarketHistoryModel> = emptyList(),
    secondaryLabel: String? = null,
    trades: MyTrades?,
    bestBid: Double?,
    bestAsk: Double?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf(ChartRange.M6) }
    var showAvgLine by remember { mutableStateOf(false) }
    var showSmaFast by remember { mutableStateOf(true) }
    var showSmaSlow by remember { mutableStateOf(true) }
    var showBands by remember { mutableStateOf(false) }
    var showTrades by remember { mutableStateOf(true) }
    var showA4e by remember { mutableStateOf(true) }
    var a4e by remember { mutableStateOf<Map<String, A4eDay>>(emptyMap()) }
    val a4eSync by A4eHistorySync.state.collectAsState()
    // ponytail: test override — region-wide Adam4EVE in The Forge sums ~350 stations/structures,
    // and the outliers make the wicks pure noise; Jita 4-4 only. Generalise (per-region hub pick,
    // or a toggle) if it proves out.
    val a4eStation = stationId ?: JITA_44.takeIf { regionId == THE_FORGE }

    fun save(
        key: String,
        value: Any,
    ) {
        scope.launch(Dispatchers.IO) { S.set("itemChart.$key", value.toString()) }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            fun flag(key: String) = S.get("itemChart.$key")?.toBooleanStrictOrNull()
            S.get("itemChart.range")?.let { name -> ChartRange.entries.find { it.name == name } }?.let { range = it }
            flag("avg")?.let { showAvgLine = it }
            flag("sma20")?.let { showSmaFast = it }
            flag("sma50")?.let { showSmaSlow = it }
            flag("bollinger")?.let { showBands = it }
            flag("trades")?.let { showTrades = it }
            flag("a4e")?.let { showA4e = it }
        }
    }

    // Local Adam4EVE history — re-read as the background sync lands more files.
    LaunchedEffect(typeId, regionId, a4eStation, a4eSync.revision) {
        a4e =
            withContext(Dispatchers.IO) {
                runCatching {
                    val today = LocalDate.now().toEpochDay().toInt()
                    aggregateA4e(
                        A4eHistorySync.store.query(typeId, today - ChartRange.ALL.days, today),
                        a4eStation,
                        regionId,
                    )
                }.getOrDefault(emptyMap())
            }
    }

    Column(modifier = modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChartRange.entries.forEach { r ->
                FilterChip(selected = range == r, onClick = {
                    range = r
                    save("range", r.name)
                }, label = { Text(r.label) })
            }
            Spacer(Modifier.width(12.dp))
            ToggleChip("Avg line", MaterialTheme.colorScheme.primary, showAvgLine) {
                showAvgLine = it
                save("avg", it)
            }
            ToggleChip("SMA 20", SMA_FAST_COLOR, showSmaFast) {
                showSmaFast = it
                save("sma20", it)
            }
            ToggleChip("SMA 50", SMA_SLOW_COLOR, showSmaSlow) {
                showSmaSlow = it
                save("sma50", it)
            }
            ToggleChip("Bollinger", BAND_COLOR, showBands) {
                showBands = it
                save("bollinger", it)
            }
            if (trades != null) {
                ToggleChip("My trades", COST_COLOR, showTrades) {
                    showTrades = it
                    save("trades", it)
                }
            }
            ToggleChip("Adam4EVE", WICK_COLOR, showA4e) {
                showA4e = it
                save("a4e", it)
            }
            Text(
                when {
                    a4eSync.running -> "Adam4EVE syncing ${a4eSync.filesDone}/${a4eSync.filesTotal}…"
                    a4e.isEmpty() -> "Adam4EVE: no fills tracked here"
                    stationId != null -> "Adam4EVE: this station"
                    a4eStation == JITA_44 -> "Adam4EVE: Jita 4-4 only"
                    else -> "Adam4EVE: all stations in region"
                },
                style = MaterialTheme.typography.labelSmall,
                color = AXIS_COLOR,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        Spacer(Modifier.height(6.dp))
        TradingChart(
            history = history,
            secondaryHistory = secondaryHistory,
            secondaryLabel = secondaryLabel,
            range = range,
            showAvgLine = showAvgLine,
            showSmaFast = showSmaFast,
            showSmaSlow = showSmaSlow,
            showBands = showBands,
            trades = trades.takeIf { showTrades },
            a4e = a4e.takeIf { showA4e }.orEmpty(),
            bestBid = bestBid,
            bestAsk = bestAsk,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

// Both sides of one order book, best price first, optionally scoped to a single station.
private data class BookSide(
    val sells: List<Map<String, Any?>>,
    val buys: List<Map<String, Any?>>,
) {
    val bestAsk get() = sells.firstOrNull()?.price()
    val bestBid get() = buys.firstOrNull()?.price()

    companion object {
        fun of(
            orders: List<Map<String, Any?>>,
            stationId: Long?,
        ): BookSide {
            val scoped = if (stationId != null) orders.filter { it.loc() == stationId } else orders
            return BookSide(
                sells = scoped.filter { !it.isBuyOrd() }.sortedBy { it.price() },
                buys = scoped.filter { it.isBuyOrd() }.sortedByDescending { it.price() },
            )
        }
    }
}

// ─── Header ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun Header(
    typeId: Int,
    typeName: String,
    regionLabel: String,
    book: BookSide,
    history: List<MarketHistoryModel>,
    onDismiss: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TypeIcon(typeId, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(typeName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(regionLabel, style = MaterialTheme.typography.labelSmall, color = AXIS_COLOR)
        }
        Spacer(Modifier.width(28.dp))
        HeaderQuote("Bid", book.bestBid?.let { formatIsk(it) } ?: "—", positiveColor)
        HeaderQuote("Ask", book.bestAsk?.let { formatIsk(it) } ?: "—", negativeColor)
        val bid = book.bestBid
        val ask = book.bestAsk
        HeaderQuote("Spread", if (bid != null && ask != null && bid > 0) pct((ask - bid) / bid * 100) else "—", null)
        changeOver(history, 1)?.let { HeaderQuote("1d", signedPct(it), if (it >= 0) positiveColor else negativeColor) }
        changeOver(history, 7)?.let { HeaderQuote("7d", signedPct(it), if (it >= 0) positiveColor else negativeColor) }
        changeOver(history, 30)?.let { HeaderQuote("30d", signedPct(it), if (it >= 0) positiveColor else negativeColor) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
    }
}

@Composable
private fun HeaderQuote(
    label: String,
    value: String,
    color: Color?,
) {
    Column(modifier = Modifier.padding(end = 20.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AXIS_COLOR)
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

// % change of the daily average price between the latest trading day and the one `days` back.
private fun changeOver(
    history: List<MarketHistoryModel>,
    days: Int,
): Double? {
    val last = history.lastOrNull() ?: return null
    val cutoff = LocalDate.parse(last.date.take(10)).minusDays(days.toLong()).toString()
    val base = history.lastOrNull { it.date.take(10) <= cutoff }?.average ?: return null
    return if (base > 0) (last.average - base) / base * 100.0 else null
}

private fun pct(v: Double) = String.format(Locale.US, "%.1f%%", v)

private fun signedPct(v: Double) = (if (v >= 0) "+" else "") + pct(v)

@Composable
private fun ToggleChip(
    label: String,
    color: Color,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text(label) },
        leadingIcon = { Box(Modifier.size(8.dp).background(color, CircleShape)) },
    )
}

// ─── Chart ────────────────────────────────────────────────────────────────────────────────

// Per-calendar-day view of the selected range; null fields = no trades that day.
private class ChartData(
    val days: List<String>,
    val rows: List<MarketHistoryModel?>,
    // Previous trading day's average — the candle's "open".
    val open: List<Double?>,
    val smaFast: List<Double?>,
    val smaSlow: List<Double?>,
    val bands: List<Band?>,
    val rsi: List<Double?>,
    val volSma: List<Double?>,
    val secondary: List<Double?>,
)

private fun buildChartData(
    history: List<MarketHistoryModel>,
    secondary: List<MarketHistoryModel>,
): ChartData {
    // Indicators run over the full history, so any zoomed window has warmed-up SMA/RSI values at
    // its left edge.
    val prices = history.map { it.average }
    val byDate = history.indices.associateBy { history[it].date.take(10) }
    val fast = sma(prices, 20)
    val slow = sma(prices, 50)
    val bands = bollinger(prices, 20)
    val rsiAll = rsi(prices, 14)
    val volSma = sma(history.map { it.volume.toDouble() }, 20)
    val secondaryByDate = secondary.associate { it.date.take(10) to it.average }

    val end =
        history
            .lastOrNull()
            ?.date
            ?.take(10)
            ?.let(LocalDate::parse) ?: LocalDate.now()
    // Always the whole history: the range buttons and mouse zoom/pan only move the viewport.
    val span =
        history.firstOrNull()?.let {
            java.time.temporal.ChronoUnit.DAYS
                .between(LocalDate.parse(it.date.take(10)), end)
                .toInt() + 1
        } ?: 1
    val days = (span - 1 downTo 0).map { end.minusDays(it.toLong()).toString() }
    val idx = days.map { byDate[it] }
    return ChartData(
        days = days,
        rows = idx.map { i -> i?.let { history[it] } },
        open = idx.map { i -> i?.takeIf { it > 0 }?.let { prices[it - 1] } },
        smaFast = idx.map { i -> i?.let { fast[it] } },
        smaSlow = idx.map { i -> i?.let { slow[it] } },
        bands = idx.map { i -> i?.let { bands[it] } },
        rsi = idx.map { i -> i?.let { rsiAll[it] } },
        volSma = idx.map { i -> i?.let { volSma[it] } },
        secondary = days.map { secondaryByDate[it] },
    )
}

// Visible chart window, in fractional day indices of ChartData.days.
internal data class ChartView(
    val start: Float,
    val end: Float,
) {
    val span get() = end - start

    // Keeps the window inside [0, n-1] without changing its width (as far as it fits).
    private fun clamped(n: Int): ChartView {
        val last = (n - 1).toFloat()
        val w = span.coerceAtMost(last)
        val s = start.coerceIn(0f, (last - w).coerceAtLeast(0f))
        return ChartView(s, s + w)
    }

    fun panned(
        byDays: Float,
        n: Int,
    ) = ChartView(start + byDays, end + byDays).clamped(n)

    // factor > 1 zooms out. The day at `anchor` (0..1 across the plot) stays under the cursor.
    fun zoomed(
        factor: Float,
        anchor: Float,
        n: Int,
    ): ChartView {
        val w = (span * factor).coerceIn(MIN_VIEW_DAYS.coerceAtMost((n - 1).toFloat()), (n - 1).toFloat())
        val pivot = start + anchor * span
        return ChartView(pivot - anchor * w, pivot - anchor * w + w).clamped(n)
    }
}

private const val MIN_VIEW_DAYS = 7f

internal fun presetView(
    n: Int,
    days: Int,
) = ChartView((n - days).coerceAtLeast(0).toFloat(), (n - 1).coerceAtLeast(1).toFloat())

// One day's own fills on one side: volume-weighted price and total quantity.
private data class DayFill(
    val price: Double,
    val qty: Long,
)

private fun fillsByDay(
    txs: List<WalletDao.RawTxRecord>,
    buy: Boolean,
): Map<String, DayFill> =
    txs
        .filter { it.isBuy == buy }
        .groupBy { it.date.take(10) }
        .mapValues { (_, list) ->
            val qty = list.sumOf { it.quantity.toLong() }
            DayFill(list.sumOf { it.unitPrice * it.quantity } / qty.coerceAtLeast(1), qty)
        }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TradingChart(
    history: List<MarketHistoryModel>,
    secondaryHistory: List<MarketHistoryModel>,
    secondaryLabel: String?,
    range: ChartRange,
    showAvgLine: Boolean,
    showSmaFast: Boolean,
    showSmaSlow: Boolean,
    showBands: Boolean,
    trades: MyTrades?,
    a4e: Map<String, A4eDay>,
    bestBid: Double?,
    bestAsk: Double?,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.ShowChart,
            title = "No Price History",
            description = "No market history yet for this item.",
        )
        return
    }
    val data = remember(history, secondaryHistory) { buildChartData(history, secondaryHistory) }
    val buyFills = remember(trades) { trades?.let { fillsByDay(it.regionTransactions, buy = true) }.orEmpty() }
    val sellFills = remember(trades) { trades?.let { fillsByDay(it.regionTransactions, buy = false) }.orEmpty() }
    val elsewhere = remember(trades) { trades?.let { t -> t.transactions - t.regionTransactions.toSet() }.orEmpty() }
    val otherBuyFills = remember(elsewhere) { fillsByDay(elsewhere, buy = true) }
    val otherSellFills = remember(elsewhere) { fillsByDay(elsewhere, buy = false) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var hoverY by remember { mutableStateOf<Float?>(null) }
    val priceColor = MaterialTheme.colorScheme.primary
    val upColor = positiveColor
    val downColor = negativeColor
    val textMeasurer = rememberTextMeasurer()
    val n = data.days.size
    // Visible window as fractional day indices; the range buttons set it, wheel/drag move it.
    var view by remember(data, range) { mutableStateOf(presetView(n, range.days)) }
    var dragX by remember { mutableStateOf<Float?>(null) }
    var lastPress by remember { mutableStateOf(0L) }

    // Layout constants shared by drawing and hit-testing.
    val lPadDp = 8.dp
    val rPadDp = 72.dp

    var canvasWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val hoverIndex =
        hoverX?.let { x ->
            val lPad = with(density) { lPadDp.toPx() }
            val chartW = canvasWidth - lPad - with(density) { rPadDp.toPx() }
            if (chartW <= 0 || n < 2) null else (view.start + (x - lPad) / chartW * view.span).roundToInt().coerceIn(0, n - 1)
        }

    fun plotWidth() = canvasWidth - with(density) { (lPadDp + rPadDp).toPx() }

    fun anchorOf(x: Float) = ((x - with(density) { lPadDp.toPx() }) / plotWidth()).coerceIn(0f, 1f)

    Column(modifier = modifier) {
        // Legend strip: follows the crosshair, or shows the latest day when not hovering.
        HoverLegend(
            data,
            hoverIndex ?: data.rows.indexOfLast { it != null }.takeIf { it >= 0 },
            buyFills,
            sellFills,
            otherBuyFills.takeIf { trades != null }.orEmpty(),
            otherSellFills.takeIf { trades != null }.orEmpty(),
            a4e,
            secondaryLabel,
        )
        Spacer(Modifier.height(4.dp))

        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { canvasWidth = it.width }
                    .onPointerEvent(PointerEventType.Move) {
                        val x =
                            it.changes
                                .first()
                                .position.x
                        val from = dragX
                        if (from != null && it.buttons.isPrimaryPressed && plotWidth() > 0) {
                            view = view.panned(-(x - from) / plotWidth() * view.span, n)
                            dragX = x
                        }
                        hoverX = x
                        hoverY =
                            it.changes
                                .first()
                                .position.y
                    }.onPointerEvent(PointerEventType.Press) {
                        val now = System.currentTimeMillis()
                        // Double-click: back to the selected range preset.
                        if (now - lastPress < 350) view = presetView(n, range.days)
                        lastPress = now
                        dragX =
                            it.changes
                                .first()
                                .position.x
                    }.onPointerEvent(PointerEventType.Release) { dragX = null }
                    .onPointerEvent(PointerEventType.Scroll) {
                        val c = it.changes.first()
                        // Wheel down zooms out, up zooms in, anchored on the day under the cursor.
                        view = view.zoomed(1.15f.pow(c.scrollDelta.y), anchorOf(c.position.x), n)
                    }.onPointerEvent(PointerEventType.Exit) {
                        hoverX = null
                        hoverY = null
                        dragX = null
                    },
        ) {
            val lPad = lPadDp.toPx()
            val rPad = rPadDp.toPx()
            val chartW = size.width - lPad - rPad
            if (chartW <= 0 || n < 2) return@Canvas
            val gap = 10.dp.toPx()
            val bottomAxis = 16.dp.toPx()
            val usable = size.height - bottomAxis - 2 * gap
            val priceTop = 4.dp.toPx()
            val priceH = usable * 0.62f
            val volTop = priceTop + priceH + gap
            val volH = usable * 0.18f
            val rsiTop = volTop + volH + gap
            val rsiH = usable * 0.20f - priceTop

            fun xFor(i: Int) = lPad + (i - view.start) / view.span * chartW
            val iFrom = floor(view.start).toInt().coerceIn(0, n - 1)
            val iTo = ceil(view.end).toInt().coerceIn(0, n - 1)

            fun visible(i: Int) = i in iFrom..iTo

            fun clipPlot(block: DrawScope.() -> Unit) = clipRect(lPad, 0f, lPad + chartW, size.height, block = block)

            // ── Price pane range: daily highs/lows plus any overlay that's within reason, so a
            // far-off stale order or cost line doesn't squash the actual price action flat.
            val core =
                data.rows.subList(iFrom, iTo + 1).filterNotNull().flatMap {
                    listOf(it.average, it.highest.takeIf { h -> h > 0 } ?: it.average, it.lowest.takeIf { l -> l > 0 } ?: it.average)
                } + data.secondary.subList(iFrom, iTo + 1).filterNotNull()
            if (core.isEmpty()) return@Canvas
            val coreMin = core.min()
            val coreMax = core.max()
            val extras =
                buildList {
                    bestBid?.let { add(it) }
                    bestAsk?.let { add(it) }
                    trades?.avgBuyPrice?.let { add(it) }
                    trades?.regionOrders?.forEach { add(it.price) }
                    data.days.subList(iFrom, iTo + 1).forEach { d ->
                        a4e[d]?.let { day ->
                            day.low?.let { add(it) }
                            day.high?.let { add(it) }
                        }
                    }
                    if (showBands) {
                        data.bands.subList(iFrom, iTo + 1).filterNotNull().forEach {
                            add(it.upper)
                            add(it.lower)
                        }
                    }
                }.filter { it in coreMin * 0.75..coreMax * 1.25 }
            val pMin = minOf(coreMin, extras.minOrNull() ?: coreMin) * 0.985
            val pMax = maxOf(coreMax, extras.maxOrNull() ?: coreMax) * 1.015
            val pRange = (pMax - pMin).coerceAtLeast(1e-9)

            fun yP(v: Double) = priceTop + (1f - ((v - pMin) / pRange).toFloat()) * priceH

            // Grid + right-hand price axis.
            for (g in 0..4) {
                val v = pMin + pRange * g / 4
                val y = yP(v)
                drawLine(GRID_COLOR, Offset(lPad, y), Offset(lPad + chartW, y), 1f)
                axisLabel(textMeasurer, formatPriceAbbr(v), Offset(lPad + chartW + 6.dp.toPx(), y - 6.dp.toPx()), AXIS_COLOR)
            }

            // Bollinger band fill.
            if (showBands) {
                val pts = data.bands.mapIndexedNotNull { i, b -> b?.let { i to it } }
                if (pts.size > 1) {
                    val path =
                        Path().apply {
                            moveTo(xFor(pts.first().first), yP(pts.first().second.upper))
                            pts.drop(1).forEach { (i, b) -> lineTo(xFor(i), yP(b.upper)) }
                            pts.reversed().forEach { (i, b) -> lineTo(xFor(i), yP(b.lower)) }
                            close()
                        }
                    clipPlot { drawPath(path, BAND_COLOR.copy(alpha = 0.12f)) }
                }
            }

            // Candles. ESI history has no open/close, so the body runs from the previous trading
            // day's average to this day's (green up, red down) and the wick is CCP's H/L. Adam4EVE's
            // range is drawn first as a thin light line underneath, so only where real fills went
            // beyond CCP's outlier-trimmed H/L does it show — as tails past the candle.
            val barW = (chartW / (view.span + 1) * 0.6f).coerceIn(1f, 12.dp.toPx())
            val tick = barW / 2 + 3.dp.toPx()

            fun yClamped(v: Double) = yP(v).coerceIn(priceTop, priceTop + priceH)
            data.days.forEachIndexed { i, d ->
                if (!visible(i)) return@forEachIndexed
                val day = a4e[d] ?: return@forEachIndexed
                val lo = day.low ?: return@forEachIndexed
                val hi = day.high ?: return@forEachIndexed
                drawLine(
                    WICK_COLOR.copy(alpha = 0.6f),
                    Offset(xFor(i), yClamped(hi)),
                    Offset(xFor(i), yClamped(lo)),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            data.rows.forEachIndexed { i, r ->
                if (r == null || !visible(i)) return@forEachIndexed
                val x = xFor(i)
                val open = data.open[i] ?: r.average
                val color = if (r.average >= open) upColor else downColor
                if (r.highest > 0 && r.lowest > 0) {
                    drawLine(color, Offset(x, yClamped(r.highest)), Offset(x, yClamped(r.lowest)), strokeWidth = 1.5.dp.toPx())
                }
                val top = yClamped(maxOf(open, r.average))
                val bottom = maxOf(yClamped(minOf(open, r.average)), top + 1.dp.toPx()) // flat day still visible
                drawRect(color, topLeft = Offset(x - barW / 2, top), size = Size(barW, bottom - top))
            }
            // Adam4EVE side VWAP ticks on top: bid fills left, ask fills right.
            data.days.forEachIndexed { i, d ->
                if (!visible(i)) return@forEachIndexed
                val day = a4e[d] ?: return@forEachIndexed
                val x = xFor(i)
                day.bid?.let {
                    drawLine(
                        WICK_COLOR,
                        Offset(x - tick, yClamped(it.vwap)),
                        Offset(x - barW / 2, yClamped(it.vwap)),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                day.ask?.let {
                    drawLine(
                        WICK_COLOR,
                        Offset(x + barW / 2, yClamped(it.vwap)),
                        Offset(x + tick, yClamped(it.vwap)),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }

            clipPlot { if (secondaryLabel != null) drawSeries(data.secondary, SECONDARY_COLOR, 1.5.dp.toPx(), ::xFor, ::yP) }
            clipPlot { if (showAvgLine) drawSeries(data.rows.map { it?.average }, priceColor, 2.dp.toPx(), ::xFor, ::yP) }
            clipPlot { if (showSmaFast) drawSeries(data.smaFast, SMA_FAST_COLOR, 1.2.dp.toPx(), ::xFor, ::yP) }
            clipPlot { if (showSmaSlow) drawSeries(data.smaSlow, SMA_SLOW_COLOR, 1.2.dp.toPx(), ::xFor, ::yP) }

            // Horizontal levels: live bid/ask, your average cost, your open orders.
            fun level(
                price: Double,
                color: Color,
                label: String,
                dashed: Boolean,
            ) {
                if (price < pMin || price > pMax) return
                val y = yP(price)
                drawLine(
                    color.copy(alpha = 0.8f),
                    Offset(lPad, y),
                    Offset(lPad + chartW, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
                )
                val lm = textMeasurer.measure(label, TextStyle(fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.SemiBold))
                val tl = Offset(lPad + chartW + 2.dp.toPx(), y - lm.size.height / 2f)
                drawRect(color, topLeft = tl, size = Size(lm.size.width + 6.dp.toPx(), lm.size.height.toFloat()))
                drawText(lm, topLeft = tl + Offset(3.dp.toPx(), 0f))
            }
            bestBid?.let { level(it, upColor, formatPriceAbbr(it), dashed = false) }
            bestAsk?.let { level(it, downColor, formatPriceAbbr(it), dashed = false) }
            trades?.avgBuyPrice?.takeIf { trades.qtyHeld > 0 }?.let { level(it, COST_COLOR, "cost ${formatPriceAbbr(it)}", dashed = true) }
            trades?.regionOrders?.forEach { o ->
                level(o.price, if (o.isBuyOrder) upColor else downColor, "my ${if (o.isBuyOrder) "B" else "S"}", dashed = true)
            }

            // Your fills: ▲ buys / ▼ sells at the day's VWAP, sized by quantity — solid in this
            // region, hollow for fills in another region (their price is that market's).
            if (trades != null) {
                val maxQty =
                    (buyFills.values + sellFills.values + otherBuyFills.values + otherSellFills.values)
                        .maxOfOrNull { it.qty }
                        ?.toDouble() ?: 1.0
                val dayIndex = data.days.withIndex().associate { (i, d) -> d to i }

                fun marker(
                    fills: Map<String, DayFill>,
                    up: Boolean,
                    hollow: Boolean = false,
                ) = fills.forEach { (day, f) ->
                    val i = dayIndex[day]?.takeIf { visible(it) } ?: return@forEach
                    val r = (4.dp.toPx() + 6.dp.toPx() * sqrt(f.qty / maxQty)).toFloat()
                    val c = Offset(xFor(i), yClamped(f.price))
                    val path =
                        Path().apply {
                            if (up) {
                                moveTo(c.x, c.y - r)
                                lineTo(c.x + r, c.y + r)
                                lineTo(c.x - r, c.y + r)
                            } else {
                                moveTo(c.x, c.y + r)
                                lineTo(c.x + r, c.y - r)
                                lineTo(c.x - r, c.y - r)
                            }
                            close()
                        }
                    // Same red/green as the candles underneath, so contrast comes from the outline: a
                    // dark halo first, then white edge (solid) or dark fill + coloured edge (hollow).
                    val color = if (up) upColor else downColor
                    drawPath(path, Color.Black.copy(alpha = 0.85f), style = Stroke(4.dp.toPx(), join = StrokeJoin.Round))
                    if (hollow) {
                        drawPath(path, Color.Black.copy(alpha = 0.75f))
                        drawPath(path, color, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
                    } else {
                        drawPath(path, color)
                        drawPath(path, Color.White, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
                    }
                }
                marker(otherBuyFills, up = true, hollow = true)
                marker(otherSellFills, up = false, hollow = true)
                marker(buyFills, up = true)
                marker(sellFills, up = false)
            }

            // ── Volume pane.
            val maxVol =
                data.rows
                    .subList(iFrom, iTo + 1)
                    .maxOfOrNull { it?.volume ?: 0L }
                    ?.toFloat()
                    ?.coerceAtLeast(1f) ?: 1f

            fun yV(v: Double) = volTop + volH - (v / maxVol).toFloat() * volH
            var prevAvg: Double? = null
            data.rows.forEachIndexed { i, r ->
                if (r == null) return@forEachIndexed
                val up = prevAvg?.let { r.average >= it } ?: true
                if (!visible(i)) {
                    prevAvg = r.average
                    return@forEachIndexed
                }
                prevAvg = r.average
                val x = xFor(i)
                val bottom = volTop + volH
                val top = yV(r.volume.toDouble())
                val day = a4e[data.days[i]]
                val bidAmt = day?.bid?.amount ?: 0L
                val askAmt = day?.ask?.amount ?: 0L
                if (a4e.isNotEmpty() && bidAmt + askAmt > 0) {
                    // ESI's total volume, split by Adam4EVE's side ratio: green = sold into bids,
                    // red = bought from asks — who was the aggressor that day.
                    val split = bottom - (bottom - top) * bidAmt / (bidAmt + askAmt)
                    drawLine(upColor.copy(alpha = 0.6f), Offset(x, bottom), Offset(x, split), strokeWidth = barW)
                    drawLine(downColor.copy(alpha = 0.6f), Offset(x, split), Offset(x, top), strokeWidth = barW)
                } else {
                    val c =
                        if (a4e.isNotEmpty()) {
                            AXIS_COLOR
                        } else if (up) {
                            upColor
                        } else {
                            downColor
                        }
                    drawLine(c.copy(alpha = 0.55f), Offset(x, bottom), Offset(x, top), strokeWidth = barW)
                }
            }
            clipPlot { drawSeries(data.volSma, AXIS_COLOR, 1.dp.toPx(), ::xFor, ::yV) }
            axisLabel(textMeasurer, "Vol " + formatVolume(maxVol.toLong()), Offset(lPad + chartW + 6.dp.toPx(), volTop), AXIS_COLOR)

            // ── RSI pane with 30/70 guides.
            fun yR(v: Double) = rsiTop + (1f - (v / 100.0).toFloat()) * rsiH
            drawRect(RSI_COLOR.copy(alpha = 0.05f), topLeft = Offset(lPad, yR(70.0)), size = Size(chartW, yR(30.0) - yR(70.0)))
            listOf(30.0, 50.0, 70.0).forEach { lv ->
                drawLine(
                    GRID_COLOR,
                    Offset(lPad, yR(lv)),
                    Offset(lPad + chartW, yR(lv)),
                    1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                )
                axisLabel(textMeasurer, lv.toInt().toString(), Offset(lPad + chartW + 6.dp.toPx(), yR(lv) - 6.dp.toPx()), AXIS_COLOR)
            }
            clipPlot { drawSeries(data.rsi, RSI_COLOR, 1.2.dp.toPx(), ::xFor, ::yR) }
            axisLabel(textMeasurer, "RSI 14", Offset(lPad + 2.dp.toPx(), rsiTop), AXIS_COLOR)

            // ── Date axis.
            val ticks = 6
            for (t in 0..ticks) {
                val i = iFrom + (iTo - iFrom) * t / ticks
                val lm = textMeasurer.measure(data.days[i].drop(5), TextStyle(fontSize = 9.sp, color = AXIS_COLOR))
                val x = (xFor(i) - lm.size.width / 2f).coerceIn(lPad, lPad + chartW - lm.size.width)
                drawText(lm, topLeft = Offset(x, size.height - bottomAxis + 3.dp.toPx()))
            }

            // ── Crosshair.
            hoverIndex?.let { i ->
                val x = xFor(i)
                drawLine(Color.White.copy(alpha = 0.35f), Offset(x, priceTop), Offset(x, rsiTop + rsiH), 1f)
                data.rows[i]?.let { r -> drawCircle(priceColor, 4.dp.toPx(), Offset(x, yP(r.average))) }
            }
            // Horizontal line at the cursor, with the value it points at on the right axis —
            // price in the price pane, volume and RSI in theirs.
            hoverY?.let { y ->
                val label =
                    when (y) {
                        in priceTop..priceTop + priceH -> formatPriceAbbr(pMin + (1 - (y - priceTop) / priceH) * pRange)
                        in volTop..volTop + volH -> formatVolume(((volTop + volH - y) / volH * maxVol).toLong())
                        in rsiTop..rsiTop + rsiH -> ((1 - (y - rsiTop) / rsiH) * 100).roundToInt().toString()
                        else -> return@let
                    }
                drawLine(Color.White.copy(alpha = 0.35f), Offset(lPad, y), Offset(lPad + chartW, y), 1f)
                val lm = textMeasurer.measure(label, TextStyle(fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.SemiBold))
                val tl = Offset(lPad + chartW + 2.dp.toPx(), y - lm.size.height / 2f)
                drawRect(Color.White.copy(alpha = 0.85f), topLeft = tl, size = Size(lm.size.width + 6.dp.toPx(), lm.size.height.toFloat()))
                drawText(lm, topLeft = tl + Offset(3.dp.toPx(), 0f))
            }
        }
    }
}

private fun DrawScope.axisLabel(
    tm: TextMeasurer,
    text: String,
    topLeft: Offset,
    color: Color,
) {
    drawText(tm.measure(text, TextStyle(fontSize = 9.sp, color = color)), topLeft = topLeft)
}

// Straight segments connecting every non-null point — gaps (no-trade days) are bridged rather
// than breaking the line, so a thin item still reads as a trend instead of scattered dots.
private fun DrawScope.drawSeries(
    series: List<Double?>,
    color: Color,
    width: Float,
    xOf: (Int) -> Float,
    yOf: (Double) -> Float,
) {
    val pts = series.mapIndexedNotNull { i, v -> v?.let { Offset(xOf(i), yOf(it)) } }
    if (pts.size < 2) return
    val path =
        Path().apply {
            moveTo(pts.first().x, pts.first().y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HoverLegend(
    data: ChartData,
    index: Int?,
    buyFills: Map<String, DayFill>,
    sellFills: Map<String, DayFill>,
    otherBuyFills: Map<String, DayFill>,
    otherSellFills: Map<String, DayFill>,
    a4e: Map<String, A4eDay>,
    secondaryLabel: String?,
) {
    val style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
    // Fixed two-line height so hovering days with more/less detail doesn't make the chart jump.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(34.dp)) {
        if (index == null) return@FlowRow
        val day = data.days[index]
        val r = data.rows[index]
        Text(day, style = style, color = AXIS_COLOR)
        if (r == null) {
            Text("no trades", style = style, color = AXIS_COLOR)
        } else {
            Text("avg ${formatIsk(r.average)}", style = style)
            Text("CCP H ${formatPriceAbbr(r.highest)}  L ${formatPriceAbbr(r.lowest)}", style = style, color = AXIS_COLOR)
            Text("vol ${formatVolume(r.volume)}", style = style, color = AXIS_COLOR)
        }
        data.smaFast[index]?.let { Text("SMA20 ${formatPriceAbbr(it)}", style = style, color = SMA_FAST_COLOR) }
        data.smaSlow[index]?.let { Text("SMA50 ${formatPriceAbbr(it)}", style = style, color = SMA_SLOW_COLOR) }
        data.rsi[index]?.let { Text("RSI ${it.roundToInt()}", style = style, color = RSI_COLOR) }
        if (secondaryLabel !=
            null
        ) {
            data.secondary[index]?.let { Text("$secondaryLabel ${formatPriceAbbr(it)}", style = style, color = SECONDARY_COLOR) }
        }
        buyFills[day]?.let { Text("▲ bought ${formatVolume(it.qty)} @ ${formatPriceAbbr(it.price)}", style = style, color = positiveColor) }
        sellFills[day]?.let { Text("▼ sold ${formatVolume(it.qty)} @ ${formatPriceAbbr(it.price)}", style = style, color = negativeColor) }
        otherBuyFills[day]?.let {
            Text("△ bought ${formatVolume(it.qty)} @ ${formatPriceAbbr(it.price)} (other region)", style = style, color = positiveColor)
        }
        otherSellFills[day]?.let {
            Text("▽ sold ${formatVolume(it.qty)} @ ${formatPriceAbbr(it.price)} (other region)", style = style, color = negativeColor)
        }
        a4e[day]?.let { d ->
            d.bid?.let {
                Text(
                    "A4E bid ${formatVolume(
                        it.amount,
                    )} @ ${formatPriceAbbr(it.vwap)} (${formatPriceAbbr(it.low)}–${formatPriceAbbr(it.high)})",
                    style = style,
                    color = positiveColor,
                )
            }
            d.ask?.let {
                Text(
                    "ask ${formatVolume(it.amount)} @ ${formatPriceAbbr(it.vwap)} (${formatPriceAbbr(it.low)}–${formatPriceAbbr(it.high)})",
                    style = style,
                    color = negativeColor,
                )
            }
        }
    }
}

// ─── Side panel ───────────────────────────────────────────────────────────────────────────

@Composable
private fun KV(
    label: String,
    value: String,
    color: Color = Color.Unspecified,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = color)
    }
}

@Composable
private fun StatsCard(
    history: List<MarketHistoryModel>,
    book: BookSide,
    flow: StationFlow?,
    flowLoading: Boolean,
    isStation: Boolean,
) {
    val lastYear = history.takeLast(365)
    ContentCard("Market") {
        KV("Median vol/day 7d", formatVolume(medianDailyVolume(history, windowDays = 7)))
        KV("Median vol/day 30d", formatVolume(medianDailyVolume(history, windowDays = 30)))
        if (lastYear.isNotEmpty()) {
            KV("1y high", formatIsk(lastYear.maxOf { it.highest.takeIf { h -> h > 0 } ?: it.average }))
            KV("1y low", formatIsk(lastYear.minOf { it.lowest.takeIf { l -> l > 0 } ?: it.average }))
        }
        val last30 = history.takeLast(30).map { it.average }
        if (last30.size > 1) {
            val mean = last30.average()
            val sd = sqrt(last30.sumOf { (it - mean) * (it - mean) } / last30.size)
            KV("Volatility 30d", pct(sd / mean * 100))
        }
        KV("Orders sell / buy", "${book.sells.size} / ${book.buys.size}")
        KV("Depth sell / buy", "${formatVolume(book.sells.sumOf { it.vol() })} / ${formatVolume(book.buys.sumOf { it.vol() })}")
        Spacer(Modifier.height(6.dp))
        Text(
            "Adam4EVE fills/day (7d, ${if (isStation) "station" else "region"})",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            flowLoading -> {
                KV("", "loading…", AXIS_COLOR)
            }

            flow == null -> {
                KV("", "no data", AXIS_COLOR)
            }

            else -> {
                KV("Sell orders filled", formatVolume(flow.sellAmount.toLong()), negativeColor)
                KV("Buy orders filled", formatVolume(flow.buyAmount.toLong()), positiveColor)
                val total = flow.buyAmount + flow.sellAmount
                if (total > 0) KV("Buy-side share", pct(flow.buyAmount / total * 100))
            }
        }
    }
}

@Composable
private fun PositionCard(
    t: MyTrades,
    book: BookSide,
) {
    ContentCard("My Position") {
        KV("Held", formatVolume(t.qtyHeld))
        KV("Avg buy", t.avgBuyPrice?.let { formatIsk(it) } ?: "—")
        val cost = t.avgBuyPrice
        if (t.qtyHeld > 0 && cost != null) {
            val keep = 1.0 - t.sellFeePct / 100.0
            // Selling via a sell order just under the ask, net of sales tax + broker fee.
            book.bestAsk?.let { ask ->
                val pnl = (ask * keep - cost) * t.qtyHeld
                KV(
                    "P&L at ask (net)",
                    "${formatIsk(pnl)} (${signedPct(pnl / (cost * t.qtyHeld) * 100)})",
                    if (pnl >=
                        0
                    ) {
                        positiveColor
                    } else {
                        negativeColor
                    },
                )
            }
            KV("Break-even ask", formatIsk(cost / keep))
        }
        t.realizedPnl?.let { KV("Realized, all time*", formatIsk(it), if (it >= 0) positiveColor else negativeColor) }
        if (t.openOrders.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            t.openOrders.forEach { o ->
                KV(
                    "${if (o.isBuyOrder) "Buy" else "Sell"} order ${formatVolume(
                        o.volumeRemaining.toLong(),
                    )}/${formatVolume(o.volumeTotal.toLong())}",
                    formatIsk(o.price),
                    if (o.isBuyOrder) positiveColor else negativeColor,
                )
            }
        }
        Text(
            "* moving-average cost (resets when the position closes), before fees; the Orders tab has exact FIFO. Fees: ${pct(
                t.sellFeePct,
            )} (tax + broker).",
            style = MaterialTheme.typography.labelSmall,
            color = AXIS_COLOR,
        )
    }
}

@Composable
private fun OrderBookCard(
    label: String,
    book: BookSide,
    myOrders: List<ActiveOrderDao.ActiveOrderRecord>,
) {
    val mine = myOrders.map { it.orderId }.toSet()
    val sells = book.sells.take(8)
    val buys = book.buys.take(8)
    val maxVol = (sells + buys).maxOfOrNull { it.vol() }?.toFloat()?.coerceAtLeast(1f) ?: 1f
    ContentCard("Book — $label") {
        // Asks top-down to the spread, then bids — the usual ladder layout, with a depth bar.
        sells.reversed().forEach { BookRow(it, negativeColor, maxVol, it.orderId() in mine) }
        val bid = book.bestBid
        val ask = book.bestAsk
        if (bid != null && ask != null) {
            Text(
                "spread ${formatIsk(ask - bid)} (${pct((ask - bid) / bid * 100)})",
                style = MaterialTheme.typography.labelSmall,
                color = AXIS_COLOR,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        buys.forEach { BookRow(it, positiveColor, maxVol, it.orderId() in mine) }
    }
}

@Composable
private fun BookRow(
    o: Map<String, Any?>,
    color: Color,
    maxVol: Float,
    mine: Boolean,
) {
    Box(modifier = Modifier.fillMaxWidth().height(18.dp)) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((o.vol() / maxVol).coerceIn(0.02f, 1f))
                    .align(Alignment.CenterEnd)
                    .background(color.copy(alpha = 0.12f)),
        )
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatIsk(o.price()) + if (mine) "  ★" else "",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (mine) FontWeight.Bold else null,
                color = color,
                modifier = Modifier.weight(1f),
            )
            Text(formatVolume(o.vol()), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun TradesCard(
    txs: List<WalletDao.RawTxRecord>,
    here: List<WalletDao.RawTxRecord>,
) {
    val inRegion = here.toSet()
    ContentCard("My Trades") {
        txs.takeLast(15).reversed().forEach { t ->
            // Fills in another region: dimmed, hollow marker — matches the chart.
            val other = t !in inRegion
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp).alpha(if (other) 0.55f else 1f)) {
                Text(
                    t.date.take(16).replace('T', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = AXIS_COLOR,
                    modifier = Modifier.width(110.dp),
                )
                Text(
                    (
                        if (t.isBuy) {
                            (if (other) "△ BUY" else "▲ BUY")
                        } else if (other) {
                            "▽ SELL"
                        } else {
                            "▼ SELL"
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (t.isBuy) positiveColor else negativeColor,
                    modifier = Modifier.width(52.dp),
                )
                Text(
                    formatVolume(t.quantity.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(formatIsk(t.unitPrice), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
