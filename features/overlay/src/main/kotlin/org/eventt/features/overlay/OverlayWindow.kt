package org.eventt.features.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.eventt.core.database.AppState
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.ViewContext
import org.eventt.core.esi.EsiClient
import org.eventt.core.marketlogs.MarketLogEvent
import org.eventt.core.marketlogs.MarketLogWatcher
import org.eventt.core.model.AppLog
import org.eventt.core.model.PLEX_MARKET_REGION_ID
import org.eventt.core.model.PLEX_TYPE_ID
import org.eventt.core.model.eveOutbidPrice
import org.eventt.core.model.eveUndercutPrice
import org.eventt.core.model.formatEveSigFigPrice
import org.eventt.features.orders.CostBasisService
import org.eventt.overlay.generated.resources.*
import org.eventt.ui.common.AXIS_COLOR
import org.eventt.ui.common.KV
import org.eventt.ui.common.Mono
import org.eventt.ui.common.Panel
import org.eventt.ui.common.Segment
import org.eventt.ui.common.TerminalField
import org.eventt.ui.common.Tip
import org.eventt.ui.common.formatIsk
import org.eventt.ui.common.formatPriceAbbr
import org.eventt.ui.common.formatVolume
import org.eventt.ui.common.outline
import org.eventt.ui.common.pct
import org.eventt.ui.common.pnlColor
import org.eventt.ui.common.signed
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.Locale
import java.util.prefs.Preferences

@Composable
fun OverlayWindow(
    onClose: () -> Unit,
    colorScheme: ColorScheme,
    typography: Typography,
) {
    val prefs = remember { Preferences.userRoot().node("org/eve/trader/overlay") }

    // Opened via the Ctrl+M hotkey: place it at the cursor instead of wherever it was last
    // dragged to — that's the whole point of that hotkey. Opened via the top-bar button (no
    // pending position): keep the normal drag-persisted spot.
    val pendingPosition = remember { OverlayController.consumePendingOpenPosition() }
    val windowState =
        rememberWindowState(
            // Undecorated and non-resizable, so the body below scrolls rather than clipping
            // whatever doesn't fit (long station names, the position and depth panels together).
            width = 420.dp,
            height = 620.dp,
            position =
                if (pendingPosition != null) {
                    WindowPosition(x = pendingPosition.first.dp, y = pendingPosition.second.dp)
                } else {
                    WindowPosition(
                        x = prefs.getInt("x", 120).dp,
                        y = prefs.getInt("y", 120).dp,
                    )
                },
        )

    Window(
        onCloseRequest = onClose,
        state = windowState,
        undecorated = true,
        alwaysOnTop = true,
        resizable = false,
        title = stringResource(Res.string.overlay_window_title),
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = typography) {
            // Surface supplies the theme's content colour: a separate window has no parent to
            // inherit it from, and plain Text would fall back to black.
            Surface(color = MaterialTheme.colorScheme.background) {
                OverlayContent(onClose = onClose, prefs = prefs)
            }
        }
    }
}

internal enum class PriceSource { CLIPBOARD, FILE, LOOKUP }

/** One side of the book: its best price, where it sits, and every known order (price to volume). */
internal data class Quote(
    val price: Double,
    val location: String,
    val source: PriceSource,
    // One entry from a manual clipboard copy (just that row), the whole side from a Marketlogs
    // export or a name lookup. Buy out/Sell out walk these, each order at its own price and volume.
    val book: List<Pair<Double, Long>>,
)

/** A pasted inventory list priced off each item's best Jita sell. */
internal data class Manifest(
    val total: Double,
    val resolved: Int,
    val unresolved: List<String>,
)

/** Stock of the current item in the FIFO ledger. */
internal data class Holding(
    val qty: Int,
    val avgCost: Double,
)

// Which beat price to auto-copy to the clipboard when an order-book export is imported:
// one tick under the best sell (to undercut) or one tick over the best buy (to outbid).
internal enum class AutoCopy { OFF, SELL, BUY }

// Which orders of a Marketlogs export count: those in your system (0 jumps, falling back to the
// whole region when there are none) or the whole exported region.
internal enum class BookScope { SYSTEM, REGION }

// Where "my cost" comes from: the FIFO ledger of the selected character/corp, a typed-in price, or nowhere.
internal enum class CostMode { FIFO, MANUAL, OFF }

internal val DEPTH_STEPS = listOf(1, 5, 10, 25)

internal data class CalcSettings(
    val autoCopy: AutoCopy = AutoCopy.OFF,
    val autoCopyManifest: Boolean = false,
    val scope: BookScope = BookScope.SYSTEM,
    val depthPct: Int = 5,
    // Kept as typed (digits only); blank = per-unit numbers only.
    val units: String = "",
    val costMode: CostMode = CostMode.FIFO,
    val manualCost: String = "",
) {
    val unitsQty get() = units.toLongOrNull()?.takeIf { it > 0 }
    val manualCostValue get() = manualCost.toDoubleOrNull()?.takeIf { it > 0 }

    fun save(prefs: Preferences) {
        prefs.put("autoCopy", autoCopy.name)
        prefs.putBoolean("autoCopyManifest", autoCopyManifest)
        prefs.put("scope", scope.name)
        prefs.putInt("depthPct", depthPct)
        prefs.put("units", units)
        prefs.put("costMode", costMode.name)
        prefs.put("manualCost", manualCost)
    }

    companion object {
        fun load(prefs: Preferences) =
            CalcSettings(
                autoCopy = runCatching { AutoCopy.valueOf(prefs.get("autoCopy", "OFF")) }.getOrDefault(AutoCopy.OFF),
                autoCopyManifest = prefs.getBoolean("autoCopyManifest", false),
                scope = runCatching { BookScope.valueOf(prefs.get("scope", "SYSTEM")) }.getOrDefault(BookScope.SYSTEM),
                depthPct = prefs.getInt("depthPct", 5).takeIf { it in DEPTH_STEPS } ?: 5,
                units = prefs.get("units", ""),
                costMode = runCatching { CostMode.valueOf(prefs.get("costMode", "FIFO")) }.getOrDefault(CostMode.FIFO),
                manualCost = prefs.get("manualCost", ""),
            )
    }
}

private const val THE_FORGE_REGION_ID = 10000002
private const val JITA_44_STATION_ID = 60003760L
private const val FIFO_REFRESH_MS = 5 * 60_000L

// Copied-name price check: the full Jita 4-4 order book for a type (PLEX: the global market),
// as (price, volume) rows — sell book first, buy book second. Null on any ESI failure.
private fun fetchTypeBook(typeId: Int): Pair<List<Pair<Double, Long>>, List<Pair<Double, Long>>>? =
    runCatching {
        val regionId = if (typeId == PLEX_TYPE_ID) PLEX_MARKET_REGION_ID else THE_FORGE_REGION_ID
        val orders = EsiClient.getMarketRegionOrders(regionId, typeId = typeId)
        val local =
            if (typeId == PLEX_TYPE_ID) {
                orders
            } else {
                orders.filter { (it["location_id"] as? Number)?.toLong() == JITA_44_STATION_ID }
            }

        fun side(isBuy: Boolean) =
            local
                .filter { (it["is_buy_order"] as? Boolean) == isBuy }
                .map { ((it["price"] as? Number)?.toDouble() ?: 0.0) to ((it["volume_remain"] as? Number)?.toLong() ?: 0L) }
        side(false) to side(true)
    }.onFailure { AppLog.warn("Overlay", "price lookup", it) }.getOrNull()

// Writes the beat price to the clipboard in EVE's own 4-sigfig format, ready to paste into the
// order dialog. Returns the written string so the poll loop can skip its own write.
private fun copyBeatPrice(price: Double): String {
    val text = formatEveSigFigPrice(price)
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    return text
}

// Plain ISK total for a pasted inventory list — unlike copyBeatPrice this isn't a price tick to
// paste into an order dialog, just the raw sum, so no EVE sigfig rounding.
private fun copyManifestTotal(total: Double): String {
    val text = "%.2f".format(total)
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    return text
}

private fun clipboardQuote(): ClipboardParser.ParsedOrder? = ClipboardParser.parse(ClipboardParser.readClipboard())

private fun ClipboardParser.ParsedOrder.toQuote() = Quote(price, location, PriceSource.CLIPBOARD, listOf(price to volume))

@Composable
private fun OverlayContent(
    onClose: () -> Unit,
    prefs: Preferences,
) {
    var sell by remember { mutableStateOf<Quote?>(null) }
    var buy by remember { mutableStateOf<Quote?>(null) }
    var itemName by remember { mutableStateOf<String?>(null) }
    // Known only from a Marketlogs export or a name lookup — a copied order row doesn't name its
    // item, so it keeps whichever item was last identified.
    var typeId by remember { mutableStateOf<Int?>(null) }
    var manifest by remember { mutableStateOf<Manifest?>(null) }
    var settings by remember { mutableStateOf(CalcSettings.load(prefs)) }
    var showSettings by remember { mutableStateOf(false) }

    // Tax rates come from the currently active character's own configured fees (Settings ›
    // Character Fees) instead of a manual slider here — one less place to keep in sync.
    val selectedContext by AppState.selectedContext.collectAsState()
    var fees by remember { mutableStateOf(Fees(0.03, 0.08)) }
    LaunchedEffect(selectedContext?.actingCharId) {
        val id = selectedContext?.actingCharId
        fees =
            withContext(Dispatchers.IO) {
                Fees(
                    broker = (id?.let { StaticDataDao.getCharBrokersFee(it) } ?: 3.0) / 100,
                    tax = (id?.let { StaticDataDao.getCharSalesTax(it) } ?: 8.0) / 100,
                )
            }
    }

    // FIFO stock of the selected character/corp — never the null/null "everyone pooled" ledger,
    // which would let one character's sells consume another's lots. Refreshed every few minutes
    // so fresh wallet syncs show up while the overlay stays open.
    var inventory by remember { mutableStateOf<Map<Int, CostBasisService.InventoryItem>>(emptyMap()) }
    LaunchedEffect(selectedContext, settings.costMode, fees) {
        val ctx = selectedContext
        if (settings.costMode != CostMode.FIFO || ctx == null) {
            inventory = emptyMap()
            return@LaunchedEffect
        }
        while (true) {
            inventory =
                withContext(Dispatchers.IO) {
                    runCatching {
                        CostBasisService
                            .compute(
                                characterId = (ctx as? ViewContext.Character)?.charId,
                                corporationId = (ctx as? ViewContext.Corporation)?.corporationId,
                                taxConfig = CostBasisService.TaxConfig(salesTaxPct = fees.tax * 100, brokerFeePct = fees.broker * 100),
                            ).inventory
                    }.onFailure { AppLog.warn("Overlay", "cost basis", it) }.getOrDefault(emptyMap())
                }
            delay(FIFO_REFRESH_MS)
        }
    }
    val holding =
        typeId
            ?.let { inventory[it] }
            ?.takeIf { it.remainingQty > 0 }
            ?.let { Holding(it.remainingQty, it.avgCostBasis) }

    // Clipboard polling — auto-assign sell/buy by EVE row format (manual fallback: copy one
    // order row in-game, this overlay picks it up within ~400ms). The parsed price is written
    // straight back to the clipboard as its beat price (one tick under a sell, one tick over a
    // buy), so the next paste into EVE's order dialog already beats the copied order.
    var lastClipboard by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            // Off the UI thread: an X11 clipboard read waits on the owner (EVE under Wine) to answer.
            val text = withContext(Dispatchers.IO) { ClipboardParser.readClipboard() }
            if (text == null || text == lastClipboard) continue
            lastClipboard = text
            val lines = ClipboardParser.parseManifest(text)
            if (lines != null) {
                val m =
                    withContext(Dispatchers.IO) {
                        var total = 0.0
                        val unresolved = mutableListOf<String>()
                        for (line in lines) {
                            val type =
                                runCatching { StaticDataDao.searchMarketTypes(line.name, limit = 1).firstOrNull() }
                                    .getOrNull()
                                    ?.takeIf { it.name.equals(line.name, ignoreCase = true) }
                            val bestSell = type?.let { fetchTypeBook(it.typeId) }?.first?.minOfOrNull { it.first }
                            if (bestSell != null) total += bestSell * line.quantity else unresolved += line.name
                        }
                        Manifest(total, lines.size - unresolved.size, unresolved)
                    }
                manifest = m
                if (settings.autoCopyManifest) lastClipboard = copyManifestTotal(m.total)
                continue
            }
            val p = ClipboardParser.parse(text)
            if (p != null) {
                if (p.isBuy) {
                    buy = p.toQuote()
                    lastClipboard = copyBeatPrice(eveOutbidPrice(p.price))
                } else {
                    sell = p.toQuote()
                    lastClipboard = copyBeatPrice(eveUndercutPrice(p.price))
                }
                continue
            }
            // Not an order row — maybe a copied item name: exact market-type match pulls the
            // Jita 4-4 book (PLEX: global) so any item can be price-checked from anywhere a name
            // can be copied (chat, contracts, inventory).
            val name = text.trim()
            if (name.length !in 3..60 || name.any { it == '\t' || it == '\n' } || name.none { it.isLetter() }) continue
            val found =
                withContext(Dispatchers.IO) {
                    val type =
                        runCatching { StaticDataDao.searchMarketTypes(name, limit = 1).firstOrNull() }
                            .getOrNull()
                            ?.takeIf { it.name.equals(name, ignoreCase = true) }
                    type?.let { t -> fetchTypeBook(t.typeId)?.let { t to it } }
                } ?: continue
            val (type, book) = found
            val loc = if (type.typeId == PLEX_TYPE_ID) getString(Res.string.global_market) else "Jita IV-4"
            itemName = type.name
            typeId = type.typeId
            book.first.minByOrNull { it.first }?.let { sell = Quote(it.first, loc, PriceSource.LOOKUP, book.first) }
            book.second.maxByOrNull { it.first }?.let { buy = Quote(it.first, loc, PriceSource.LOOKUP, book.second) }
        }
    }

    // Richer, automatic source: EVE's own item order-book export (Settings › Marketlogs Folder)
    // — the whole book for the item you have open in-game, not just one copied row.
    LaunchedEffect(Unit) {
        MarketLogWatcher.events.collect { event ->
            if (event !is MarketLogEvent.OrderBookImported) return@collect
            // PLEX trades in one global virtual market, so station or jumps scoping is
            // meaningless there and the whole exported book is used as-is.
            val isGlobalMarket = event.typeId == PLEX_TYPE_ID
            val current = settings

            fun <R : Any> scoped(
                rows: List<R>,
                jumps: (R) -> Int,
            ) = if (isGlobalMarket || current.scope == BookScope.REGION) rows else rows.filter { jumps(it) == 0 }.ifEmpty { rows }
            val sellRows = scoped(event.sellRows) { it.jumps }
            val buyRows = scoped(event.buyRows) { it.jumps }

            val bestSell = sellRows.minByOrNull { it.price }
            val bestBuy = buyRows.maxByOrNull { it.price }
            // Prices and the beat price first, with nothing from the DB: the single DB lock can
            // be held by a background sync for a while, and this is what gets pasted into EVE.
            sell = bestSell?.let { Quote(it.price, "", PriceSource.FILE, sellRows.map { r -> r.price to r.volRemaining.toLong() }) }
            buy = bestBuy?.let { Quote(it.price, "", PriceSource.FILE, buyRows.map { r -> r.price to r.volRemaining.toLong() }) }
            typeId = event.typeId
            itemName = null
            when (current.autoCopy) {
                AutoCopy.SELL -> {
                    bestSell?.let { lastClipboard = copyBeatPrice(eveUndercutPrice(it.price)) }
                }

                AutoCopy.BUY -> {
                    bestBuy?.let { lastClipboard = copyBeatPrice(eveOutbidPrice(it.price)) }
                }

                AutoCopy.OFF -> {}
            }
            val global = getString(Res.string.global_market)
            val (sellLoc, buyLoc, name) =
                withContext(Dispatchers.IO) {
                    fun locationOf(stationId: Long?) =
                        when {
                            stationId == null -> ""
                            isGlobalMarket -> global
                            else -> StaticDataDao.getStationById(stationId)?.name ?: stationId.toString()
                        }
                    Triple(locationOf(bestSell?.stationId), locationOf(bestBuy?.stationId), StaticDataDao.getTypeName(event.typeId))
                }
            // A copied item name may have replaced this item meanwhile.
            if (typeId != event.typeId) return@collect
            sell = sell?.copy(location = sellLoc)
            buy = buy?.copy(location = buyLoc)
            itemName = name ?: getString(Res.string.unknown_type_id, event.typeId)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .border(1.dp, outline()),
    ) {
        CalcHeader(
            itemName = itemName,
            settingsOpen = showSettings,
            onToggleSettings = { showSettings = !showSettings },
            onClose = onClose,
            onDragEnd = { x, y ->
                prefs.putInt("x", x)
                prefs.putInt("y", y)
            },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp)) {
            if (showSettings) {
                CalcSettingsPanel(settings, fees) {
                    settings = it
                    it.save(prefs)
                }
            } else {
                TradeCalcBody(
                    sell = sell,
                    buy = buy,
                    fees = fees,
                    holding = holding,
                    itemKnown = typeId != null,
                    manifest = manifest,
                    settings = settings,
                    onSettings = {
                        settings = it
                        it.save(prefs)
                    },
                    onSetSell = { clipboardQuote()?.takeIf { !it.isBuy }?.let { sell = it.toQuote() } },
                    onSetBuy = { clipboardQuote()?.takeIf { it.isBuy }?.let { buy = it.toQuote() } },
                    onCopy = { lastClipboard = copyBeatPrice(it) },
                )
            }
        }
    }
}

// ─── Header / drag handle — stays fixed, never scrolls ─────────────────────

@Composable
private fun CalcHeader(
    itemName: String?,
    settingsOpen: Boolean,
    onToggleSettings: () -> Unit,
    onClose: () -> Unit,
    onDragEnd: (Int, Int) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                // Screen-coordinate drag: capture the AWT window at drag start.
                var dragWin: java.awt.Window? = null
                var startMouseX = 0
                var startMouseY = 0
                var startWinX = 0
                var startWinY = 0
                detectDragGestures(
                    onDragStart = { _ ->
                        dragWin = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                        val mouse = MouseInfo.getPointerInfo().location
                        startMouseX = mouse.x
                        startMouseY = mouse.y
                        startWinX = dragWin?.x ?: 0
                        startWinY = dragWin?.y ?: 0
                    },
                    onDrag = { _, _ ->
                        val mouse = MouseInfo.getPointerInfo().location
                        dragWin?.setLocation(startWinX + (mouse.x - startMouseX), startWinY + (mouse.y - startMouseY))
                    },
                    onDragEnd = {
                        dragWin?.let { onDragEnd(it.x, it.y) }
                        dragWin = null
                    },
                    onDragCancel = { dragWin = null },
                )
            }.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.DragIndicator, null, tint = AXIS_COLOR, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("TRADE CALC", style = MaterialTheme.typography.labelMedium, fontFamily = Mono, color = accent, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(
            itemName ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleSettings, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Default.Settings,
                stringResource(Res.string.tc_settings),
                tint = if (settingsOpen) accent else AXIS_COLOR,
                modifier = Modifier.size(15.dp),
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Close, null, tint = AXIS_COLOR, modifier = Modifier.size(15.dp))
        }
    }
}

// ─── Body ─────────────────────────────────────────────────────────────────

@Composable
internal fun TradeCalcBody(
    sell: Quote?,
    buy: Quote?,
    fees: Fees,
    holding: Holding?,
    itemKnown: Boolean,
    manifest: Manifest?,
    settings: CalcSettings,
    onSettings: (CalcSettings) -> Unit,
    onSetSell: () -> Unit,
    onSetBuy: () -> Unit,
    onCopy: (Double) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            // Auto-copy is one side or neither: clicking the lit side turns it off.
            fun toggle(side: AutoCopy) = onSettings(settings.copy(autoCopy = if (settings.autoCopy == side) AutoCopy.OFF else side))
            QuoteBox(
                stringResource(Res.string.price_sell),
                sell,
                sell?.let { eveUndercutPrice(it.price) },
                negativeColor,
                autoCopy = settings.autoCopy == AutoCopy.SELL,
                onAutoCopy = { toggle(AutoCopy.SELL) },
                onSetSell,
                onCopy,
            )
            QuoteBox(
                stringResource(Res.string.price_buy),
                buy,
                buy?.let { eveOutbidPrice(it.price) },
                positiveColor,
                autoCopy = settings.autoCopy == AutoCopy.BUY,
                onAutoCopy = { toggle(AutoCopy.BUY) },
                onSetBuy,
                onCopy,
            )
        }
        if (sell != null && buy != null) FlipPanel(sell.price, buy.price, fees, settings.unitsQty)
        PositionPanel(sell?.price, buy?.price, fees, holding, itemKnown, settings)
        if (sell != null || buy != null) DepthPanel(sell?.book.orEmpty(), buy?.book.orEmpty(), settings.depthPct)
        manifest?.let { ManifestPanel(it) }
    }
}

@Composable
private fun RowScope.QuoteBox(
    label: String,
    quote: Quote?,
    beat: Double?,
    color: Color,
    autoCopy: Boolean,
    onAutoCopy: () -> Unit,
    onSet: () -> Unit,
    onCopyBeat: (Double) -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .border(1.dp, outline(), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Text(label.trim(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.weight(1f))
            Tip(stringResource(Res.string.tip_auto_copy)) { Segment(stringResource(Res.string.tc_auto_copy), autoCopy, onAutoCopy) }
            Tip(stringResource(Res.string.tip_set)) {
                Text(
                    stringResource(Res.string.set),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = Mono,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onSet).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        if (quote == null) {
            Text(
                stringResource(Res.string.copy_order_row),
                style = MaterialTheme.typography.bodySmall,
                color = AXIS_COLOR,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            return@Column
        }
        Text(
            formatPriceAbbr(quote.price),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = Mono,
            fontWeight = FontWeight.SemiBold,
        )
        // The beat price already sits in the clipboard (written by the poll loop / book import);
        // clicking here re-copies it after something else overwrote the clipboard.
        if (beat != null) {
            Tip(stringResource(Res.string.tip_beat)) {
                Text(
                    stringResource(Res.string.beat_price, formatEveSigFigPrice(beat)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = Mono,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onCopyBeat(beat) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceBadge(quote.source)
            Text(
                quote.location,
                style = MaterialTheme.typography.labelSmall,
                color = AXIS_COLOR,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Where a price came from: the automatic order-book file import ("auto"), a manual clipboard copy
// ("manual") or a copied item name looked up in Jita ("jita").
@Composable
private fun SourceBadge(source: PriceSource?) {
    if (source == null) return
    val (icon, label) =
        when (source) {
            PriceSource.FILE -> Icons.Default.Bolt to stringResource(Res.string.src_auto)
            PriceSource.CLIPBOARD -> Icons.Default.ContentPaste to stringResource(Res.string.src_manual)
            PriceSource.LOOKUP -> Icons.Default.Search to stringResource(Res.string.src_jita)
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = AXIS_COLOR, modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = AXIS_COLOR)
    }
}

@Composable
private fun PanelNote(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = AXIS_COLOR, maxLines = 1)
}

private fun pctOf(
    v: Double,
    of: Double,
) = if (of > 0) " (${pct(v / of * 100)})" else ""

// Post a buy order, then a sell order: broker fee on both, sales tax on the sell.
@Composable
private fun FlipPanel(
    sell: Double,
    buy: Double,
    fees: Fees,
    units: Long?,
) {
    val f = flip(sell, buy, fees)
    val color = pnlColor(f.profit)
    Panel(
        stringResource(Res.string.tc_flip),
        Modifier.fillMaxWidth(),
        header = {
            Spacer(Modifier.weight(1f))
            PanelNote(stringResource(Res.string.tc_fees, feePct(fees.broker), feePct(fees.tax)))
        },
    ) {
        KV(stringResource(Res.string.tc_spread), formatIsk(sell - buy) + pctOf(sell - buy, sell))
        KV(stringResource(Res.string.calc_broker_buy), formatIsk(f.brokerBuy))
        KV(stringResource(Res.string.calc_broker_sell), formatIsk(f.brokerSell))
        KV(stringResource(Res.string.calc_sales_tax), formatIsk(f.salesTax))
        HorizontalDivider(color = outline(), modifier = Modifier.padding(vertical = 2.dp))
        KV(stringResource(Res.string.calc_profit_unit), signed(f.profit), color)
        KV(stringResource(Res.string.calc_margin), pct(f.marginPct), color)
        KV(stringResource(Res.string.tc_roi), pct(f.roiPct), color)
        if (units != null) {
            HorizontalDivider(color = outline(), modifier = Modifier.padding(vertical = 2.dp))
            KV(stringResource(Res.string.tc_capital, formatVolume(units)), formatIsk(buy * (1 + fees.broker) * units))
            KV(stringResource(Res.string.tc_profit_total, formatVolume(units)), signed(f.profit * units), color)
        }
    }
}

private fun feePct(fraction: Double) = String.format(Locale.US, "%.2f%%", fraction * 100)

// The stock already bought: what it cost, where it breaks even, and what relisting or dumping it
// into the buy wall nets right now.
@Composable
private fun PositionPanel(
    sell: Double?,
    buy: Double?,
    fees: Fees,
    holding: Holding?,
    itemKnown: Boolean,
    settings: CalcSettings,
) {
    if (settings.costMode == CostMode.OFF) return
    val (cost, qty) =
        when (settings.costMode) {
            CostMode.FIFO -> holding?.let { it.avgCost to it.qty.toLong() } ?: (null to null)
            else -> settings.manualCostValue to settings.unitsQty
        }
    Panel(
        stringResource(Res.string.tc_position),
        Modifier.fillMaxWidth(),
        header = {
            Spacer(Modifier.weight(1f))
            PanelNote(
                when {
                    settings.costMode == CostMode.MANUAL -> stringResource(Res.string.tc_cost_manual)
                    holding != null -> stringResource(Res.string.tc_cost_fifo_held, formatVolume(holding.qty.toLong()))
                    else -> stringResource(Res.string.tc_cost_fifo)
                },
            )
        },
    ) {
        if (cost == null) {
            Text(
                stringResource(
                    when {
                        settings.costMode == CostMode.MANUAL -> Res.string.tc_no_manual_cost
                        itemKnown -> Res.string.tc_no_stock
                        else -> Res.string.tc_no_item
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AXIS_COLOR,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            return@Panel
        }
        val p = position(cost, sell, buy, fees)
        KV(stringResource(Res.string.tc_my_cost), formatPriceAbbr(p.cost))
        KV(stringResource(Res.string.tc_break_even_order), formatPriceAbbr(p.breakEvenOrder))
        KV(stringResource(Res.string.tc_break_even_instant), formatPriceAbbr(p.breakEvenInstant))
        HorizontalDivider(color = outline(), modifier = Modifier.padding(vertical = 2.dp))
        p.relist?.let { KV(stringResource(Res.string.tc_relist), signed(it) + pctOf(it, cost), pnlColor(it)) }
        p.dump?.let { KV(stringResource(Res.string.tc_dump), signed(it) + pctOf(it, cost), pnlColor(it)) }
        if (qty != null && (p.relist != null || p.dump != null)) {
            HorizontalDivider(color = outline(), modifier = Modifier.padding(vertical = 2.dp))
            p.relist?.let { KV(stringResource(Res.string.tc_relist_total, formatVolume(qty)), signed(it * qty), pnlColor(it)) }
            p.dump?.let { KV(stringResource(Res.string.tc_dump_total, formatVolume(qty)), signed(it * qty), pnlColor(it)) }
        }
    }
}

// What it actually costs to clear the sell wall (buy out) or pays to clear the buy wall (sell
// out), walked order-by-order at each order's own price and volume.
@Composable
private fun DepthPanel(
    sellBook: List<Pair<Double, Long>>,
    buyBook: List<Pair<Double, Long>>,
    depthPct: Int,
) {
    Panel(
        stringResource(Res.string.tc_depth),
        Modifier.fillMaxWidth(),
        header = {
            Spacer(Modifier.weight(1f))
            PanelNote(stringResource(Res.string.tc_orders, sellBook.size, buyBook.size))
        },
    ) {
        val top = "$depthPct%"
        if (sellBook.isNotEmpty()) {
            KV(
                stringResource(Res.string.sell_wall_buyout, formatVolume(sellBook.sumOf { it.second })),
                formatIsk(
                    sellBook.sumOf {
                        it.first *
                            it.second
                    },
                ),
            )
            avgTopPrice(
                sellBook,
                cheapestFirst = true,
                depthPct,
            )?.let { KV(stringResource(Res.string.sell_wall_avg, top), formatPriceAbbr(it)) }
        }
        if (buyBook.isNotEmpty()) {
            KV(
                stringResource(Res.string.buy_wall_sellout, formatVolume(buyBook.sumOf { it.second })),
                formatIsk(
                    buyBook.sumOf {
                        it.first *
                            it.second
                    },
                ),
            )
            avgTopPrice(
                buyBook,
                cheapestFirst = false,
                depthPct,
            )?.let { KV(stringResource(Res.string.buy_wall_avg, top), formatPriceAbbr(it)) }
        }
    }
}

// A pasted name+quantity list from a cargo/inventory window.
@Composable
private fun ManifestPanel(m: Manifest) {
    Panel(stringResource(Res.string.inventory_value), Modifier.fillMaxWidth()) {
        KV(stringResource(Res.string.manifest_total, m.resolved), formatIsk(m.total), MaterialTheme.colorScheme.primary)
        if (m.unresolved.isNotEmpty()) {
            Text(
                stringResource(Res.string.unresolved, m.unresolved.joinToString(", ")),
                color = AXIS_COLOR,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─── Settings ─────────────────────────────────────────────────────────────

@Composable
internal fun CalcSettingsPanel(
    settings: CalcSettings,
    fees: Fees,
    onChange: (CalcSettings) -> Unit,
) {
    Panel(stringResource(Res.string.tc_settings), Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingRow(stringResource(Res.string.tc_copy_manifest), stringResource(Res.string.tip_copy_manifest)) {
                Segment(stringResource(Res.string.ac_off), !settings.autoCopyManifest) { onChange(settings.copy(autoCopyManifest = false)) }
                Segment(stringResource(Res.string.tc_on), settings.autoCopyManifest) { onChange(settings.copy(autoCopyManifest = true)) }
            }
            SettingRow(stringResource(Res.string.tc_scope), stringResource(Res.string.tip_scope)) {
                Segment(stringResource(Res.string.tc_scope_system), settings.scope == BookScope.SYSTEM) {
                    onChange(settings.copy(scope = BookScope.SYSTEM))
                }
                Segment(stringResource(Res.string.tc_scope_region), settings.scope == BookScope.REGION) {
                    onChange(settings.copy(scope = BookScope.REGION))
                }
            }
            SettingRow(stringResource(Res.string.tc_depth_setting), stringResource(Res.string.tip_depth)) {
                DEPTH_STEPS.forEach { d -> Segment("$d%", settings.depthPct == d) { onChange(settings.copy(depthPct = d)) } }
            }
            SettingRow(stringResource(Res.string.tc_units), stringResource(Res.string.tip_units)) {
                TerminalField(settings.units, number = true, decimals = false) { onChange(settings.copy(units = it)) }
            }
            HorizontalDivider(color = outline())
            SettingRow(stringResource(Res.string.tc_cost_source), stringResource(Res.string.tip_cost_source)) {
                Segment("FIFO", settings.costMode == CostMode.FIFO) { onChange(settings.copy(costMode = CostMode.FIFO)) }
                Segment(
                    stringResource(Res.string.tc_manual),
                    settings.costMode == CostMode.MANUAL,
                ) { onChange(settings.copy(costMode = CostMode.MANUAL)) }
                Segment(
                    stringResource(Res.string.ac_off),
                    settings.costMode == CostMode.OFF,
                ) { onChange(settings.copy(costMode = CostMode.OFF)) }
            }
            if (settings.costMode == CostMode.MANUAL) {
                SettingRow(stringResource(Res.string.tc_manual_cost), stringResource(Res.string.tip_manual_cost)) {
                    TerminalField(settings.manualCost, number = true, decimals = true) { onChange(settings.copy(manualCost = it)) }
                }
            }
            HorizontalDivider(color = outline())
            Text(
                stringResource(Res.string.tc_fees_from_char, feePct(fees.broker), feePct(fees.tax)),
                style = MaterialTheme.typography.labelSmall,
                color = AXIS_COLOR,
            )
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    tip: String,
    content: @Composable RowScope.() -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Tip(tip, Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
