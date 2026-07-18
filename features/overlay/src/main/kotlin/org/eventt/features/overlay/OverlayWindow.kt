package org.eventt.features.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import org.eventt.core.esi.EsiClient
import org.eventt.core.marketlogs.MarketLogEvent
import org.eventt.core.marketlogs.MarketLogWatcher
import org.eventt.core.model.AppLog
import org.eventt.core.model.PLEX_MARKET_REGION_ID
import org.eventt.core.model.PLEX_TYPE_ID
import org.eventt.core.model.eveOutbidPrice
import org.eventt.core.model.eveUndercutPrice
import org.eventt.core.model.formatEveSigFigPrice
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
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
            width = 290.dp,
            // Grown from the original 290 to fit the Buy out/Sell out section added later — this
            // is an undecorated, non-resizable window, so anything taller than this is otherwise
            // silently clipped rather than scrolled. The body below is also independently
            // scrollable as a safety net against any further growth (long station names, etc.).
            height = 460.dp,
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
        title = "EVE Trade Overlay",
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = typography) {
            OverlayContent(onClose = onClose, prefs = prefs)
        }
    }
}

private enum class PriceSource { CLIPBOARD, FILE, LOOKUP }

private const val THE_FORGE_REGION_ID = 10000002
private const val JITA_44_STATION_ID = 60003760L

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
    }.onFailure { AppLog.warn("Overlay", "price lookup: ${it.message}") }.getOrNull()

// Which beat price to auto-copy to the clipboard when an order-book export is imported:
// one tick under the best sell (to undercut) or one tick over the best buy (to outbid).
private enum class AutoCopy { OFF, SELL, BUY }

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

@Composable
private fun OverlayContent(
    onClose: () -> Unit,
    prefs: Preferences,
) {
    val accent = MaterialTheme.colorScheme.primary
    val dimText = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val overlayBg = MaterialTheme.colorScheme.surface
    val overlayHeader = MaterialTheme.colorScheme.surfaceVariant
    val overlayBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val sellColor = negativeColor
    val buyColor = positiveColor

    var sellPrice by remember { mutableStateOf<Double?>(null) }
    var buyPrice by remember { mutableStateOf<Double?>(null) }
    var sellLoc by remember { mutableStateOf("") }
    var buyLoc by remember { mutableStateOf("") }
    var sellSource by remember { mutableStateOf<PriceSource?>(null) }
    var buySource by remember { mutableStateOf<PriceSource?>(null) }
    var bookItemName by remember { mutableStateOf<String?>(null) }

    // Every known order on each side (price to volume) — one entry from a manual clipboard copy
    // (just that row), the whole book from a Marketlogs order-book import. Buy out/Sell out below
    // sum over these instead of guessing off a single price, since a real buyout has to walk
    // every order's own price and volume, not just the best one.
    var sellBook by remember { mutableStateOf<List<Pair<Double, Long>>>(emptyList()) }
    var buyBook by remember { mutableStateOf<List<Pair<Double, Long>>>(emptyList()) }

    // Pasted inventory/cargo list (name+quantity per line) → total liquidation value, priced off
    // each item's best Jita buy order (what you'd actually get selling into the book right now).
    var manifestTotal by remember { mutableStateOf<Double?>(null) }
    var manifestResolved by remember { mutableStateOf(0) }
    var manifestUnresolved by remember { mutableStateOf<List<String>>(emptyList()) }
    var autoCopyManifest by remember { mutableStateOf(prefs.getBoolean("autoCopyManifest", false)) }

    // Tax rates come from the currently active character's own configured fees (Settings ›
    // Character Fees) instead of a manual slider here — one less place to keep in sync.
    val selectedContext by AppState.selectedContext.collectAsState()
    val actingCharId = selectedContext?.actingCharId
    var brokerFeePct by remember { mutableStateOf(3.0) }
    var salesTaxPct by remember { mutableStateOf(8.0) }
    LaunchedEffect(actingCharId) {
        val id = actingCharId
        withContext(Dispatchers.IO) {
            val bf = id?.let { StaticDataDao.getCharBrokersFee(it) } ?: 3.0
            val st = id?.let { StaticDataDao.getCharSalesTax(it) } ?: 8.0
            withContext(Dispatchers.Main) {
                brokerFeePct = bf
                salesTaxPct = st
            }
        }
    }

    // Clipboard polling — auto-assign sell/buy by EVE row format (manual fallback: copy one
    // order row in-game, this overlay picks it up within ~400ms). The parsed price is written
    // straight back to the clipboard as its beat price (one tick under a sell, one tick over a
    // buy), so the next paste into EVE's order dialog already beats the copied order.
    var lastClipboard by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            val text = ClipboardParser.readClipboard()
            if (text != null && text != lastClipboard) {
                lastClipboard = text
                val manifest = ClipboardParser.parseManifest(text)
                if (manifest != null) {
                    withContext(Dispatchers.IO) {
                        var total = 0.0
                        var resolved = 0
                        val unresolved = mutableListOf<String>()
                        for (line in manifest) {
                            val type =
                                runCatching { StaticDataDao.searchMarketTypes(line.name, limit = 1).firstOrNull() }
                                    .getOrNull()
                                    ?.takeIf { it.name.equals(line.name, ignoreCase = true) }
                            val bestBuy =
                                type
                                    ?.let { fetchTypeBook(it.typeId) }
                                    ?.second
                                    ?.maxByOrNull { it.first }
                                    ?.first
                            if (bestBuy != null) {
                                total += bestBuy * line.quantity
                                resolved++
                            } else {
                                unresolved += line.name
                            }
                        }
                        withContext(Dispatchers.Main) {
                            manifestTotal = total
                            manifestResolved = resolved
                            manifestUnresolved = unresolved
                            if (autoCopyManifest) {
                                lastClipboard = copyManifestTotal(total)
                            }
                        }
                    }
                    continue
                }
                val p = ClipboardParser.parse(text)
                if (p == null) {
                    // Not an order row — maybe a copied item name: exact market-type match pulls
                    // the Jita 4-4 book (PLEX: global) so any item can be price-checked from
                    // anywhere a name can be copied (chat, contracts, inventory).
                    val name = text.trim()
                    if (name.length in 3..60 && name.none { it == '\t' || it == '\n' } && name.any { it.isLetter() }) {
                        withContext(Dispatchers.IO) {
                            val type =
                                runCatching { StaticDataDao.searchMarketTypes(name, limit = 1).firstOrNull() }
                                    .getOrNull()
                                    ?.takeIf { it.name.equals(name, ignoreCase = true) }
                            val book = type?.let { fetchTypeBook(it.typeId) }
                            if (type != null && book != null) {
                                val (sells, buys) = book
                                val loc = if (type.typeId == PLEX_TYPE_ID) "Global market" else "Jita IV-4"
                                withContext(Dispatchers.Main) {
                                    bookItemName = type.name
                                    sells.minByOrNull { it.first }?.let {
                                        sellPrice = it.first
                                        sellLoc = loc
                                        sellSource = PriceSource.LOOKUP
                                        sellBook = sells
                                    }
                                    buys.maxByOrNull { it.first }?.let {
                                        buyPrice = it.first
                                        buyLoc = loc
                                        buySource = PriceSource.LOOKUP
                                        buyBook = buys
                                    }
                                }
                            }
                        }
                    }
                    continue
                }
                if (p.isBuy) {
                    buyPrice = p.price
                    buyLoc = p.location
                    buySource = PriceSource.CLIPBOARD
                    buyBook = listOf(p.price to p.volume)
                    lastClipboard = copyBeatPrice(eveOutbidPrice(p.price))
                } else {
                    sellPrice = p.price
                    sellLoc = p.location
                    sellSource = PriceSource.CLIPBOARD
                    sellBook = listOf(p.price to p.volume)
                    lastClipboard = copyBeatPrice(eveUndercutPrice(p.price))
                }
            }
        }
    }

    var autoCopy by remember {
        mutableStateOf(runCatching { AutoCopy.valueOf(prefs.get("autoCopy", "OFF")) }.getOrDefault(AutoCopy.OFF))
    }

    // Richer, automatic source: EVE's own item order-book export (Settings › Marketlogs Folder)
    // — the whole book for the item you have open in-game, not just one copied row.
    LaunchedEffect(Unit) {
        MarketLogWatcher.events.collect { event ->
            if (event is MarketLogEvent.OrderBookImported) {
                // Prefer orders actually in this system (0 jumps); only fall back to the whole
                // region if none are local — same scoping for the best price as for the buyout/
                // sellout totals below, so they describe the same population of orders.
                // PLEX is the exception: it trades in one global virtual market, so station or
                // jumps scoping is meaningless and the whole exported book is used as-is.
                val isGlobalMarket = event.typeId == PLEX_TYPE_ID
                val sellRowsUsed =
                    if (isGlobalMarket) event.sellRows else event.sellRows.filter { it.jumps == 0 }.ifEmpty { event.sellRows }
                val buyRowsUsed =
                    if (isGlobalMarket) event.buyRows else event.buyRows.filter { it.jumps == 0 }.ifEmpty { event.buyRows }
                val bestSell = sellRowsUsed.minByOrNull { it.price }
                val bestBuy = buyRowsUsed.maxByOrNull { it.price }
                bestSell?.let {
                    sellPrice = it.price
                    sellLoc =
                        if (isGlobalMarket) {
                            "Global market"
                        } else {
                            StaticDataDao.getStationById(it.stationId)?.name ?: it.stationId.toString()
                        }
                    sellSource = PriceSource.FILE
                    sellBook = sellRowsUsed.map { row -> row.price to row.volRemaining.toLong() }
                }
                bestBuy?.let {
                    buyPrice = it.price
                    buyLoc =
                        if (isGlobalMarket) {
                            "Global market"
                        } else {
                            StaticDataDao.getStationById(it.stationId)?.name ?: it.stationId.toString()
                        }
                    buySource = PriceSource.FILE
                    buyBook = buyRowsUsed.map { row -> row.price to row.volRemaining.toLong() }
                }
                bookItemName = StaticDataDao.getTypeName(event.typeId) ?: "Unknown (${event.typeId})"
                when (autoCopy) {
                    AutoCopy.SELL -> {
                        bestSell?.let { lastClipboard = copyBeatPrice(eveUndercutPrice(it.price)) }
                    }

                    AutoCopy.BUY -> {
                        bestBuy?.let { lastClipboard = copyBeatPrice(eveOutbidPrice(it.price)) }
                    }

                    AutoCopy.OFF -> {}
                }
            }
        }
    }

    val shape = RoundedCornerShape(10.dp)

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(overlayBg)
                    .border(1.dp, overlayBorder, shape),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ─── Header / drag handle — stays fixed, never scrolls ─────
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(overlayHeader)
                            .pointerInput(Unit) {
                                // Screen-coordinate drag: capture AWT window at drag start
                                var dragWin: java.awt.Window? = null
                                var startMouseX = 0
                                var startMouseY = 0
                                var startWinX = 0
                                var startWinY = 0

                                detectDragGestures(
                                    onDragStart = { _ ->
                                        dragWin =
                                            KeyboardFocusManager
                                                .getCurrentKeyboardFocusManager()
                                                .focusedWindow
                                        val mouse = MouseInfo.getPointerInfo().location
                                        startMouseX = mouse.x
                                        startMouseY = mouse.y
                                        startWinX = dragWin?.x ?: 0
                                        startWinY = dragWin?.y ?: 0
                                    },
                                    onDrag = { _, _ ->
                                        val mouse = MouseInfo.getPointerInfo().location
                                        val newX = startWinX + (mouse.x - startMouseX)
                                        val newY = startWinY + (mouse.y - startMouseY)
                                        dragWin?.setLocation(newX, newY)
                                    },
                                    onDragEnd = {
                                        dragWin?.let {
                                            prefs.putInt("x", it.x)
                                            prefs.putInt("y", it.y)
                                        }
                                        dragWin = null
                                    },
                                    onDragCancel = { dragWin = null },
                                )
                            }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.DragIndicator, null, tint = accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "EVE Trade Calc",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, null, tint = dimText, modifier = Modifier.size(14.dp))
                    }
                }

                // ─── Body — scrolls independently of the header above, so content taller than
                // the (fixed, non-resizable) window is reachable instead of silently clipped ────
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    bookItemName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }

                    // ─── Prices ───────────────────────────────────────────
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        PriceRow(
                            label = "SELL",
                            price = sellPrice,
                            beatPrice = sellPrice?.let { eveUndercutPrice(it) },
                            location = sellLoc,
                            color = sellColor,
                            source = sellSource,
                            onSet = {
                                val p = ClipboardParser.parse(ClipboardParser.readClipboard()) ?: return@PriceRow
                                sellPrice = p.price
                                sellLoc = p.location
                                sellSource = PriceSource.CLIPBOARD
                                sellBook = listOf(p.price to p.volume)
                            },
                            onCopyBeat = { lastClipboard = copyBeatPrice(it) },
                        )
                        Spacer(Modifier.height(6.dp))
                        PriceRow(
                            label = "BUY ",
                            price = buyPrice,
                            beatPrice = buyPrice?.let { eveOutbidPrice(it) },
                            location = buyLoc,
                            color = buyColor,
                            source = buySource,
                            onSet = {
                                val p = ClipboardParser.parse(ClipboardParser.readClipboard()) ?: return@PriceRow
                                buyPrice = p.price
                                buyLoc = p.location
                                buySource = PriceSource.CLIPBOARD
                                buyBook = listOf(p.price to p.volume)
                            },
                            onCopyBeat = { lastClipboard = copyBeatPrice(it) },
                        )
                        Spacer(Modifier.height(6.dp))
                        // Which beat price a Marketlogs order-book import auto-copies to the clipboard.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Auto-copy", color = dimText, style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.weight(1f))
                            AutoCopy.entries.forEach { mode ->
                                val label =
                                    when (mode) {
                                        AutoCopy.OFF -> "OFF"
                                        AutoCopy.SELL -> "SELL−"
                                        AutoCopy.BUY -> "BUY+"
                                    }
                                TextButton(
                                    onClick = {
                                        autoCopy = mode
                                        prefs.put("autoCopy", mode.name)
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(22.dp),
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (autoCopy == mode) accent else dimText,
                                        fontWeight = if (autoCopy == mode) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }

                    // ─── Profit / margin ──────────────────────────────────
                    if (sellPrice != null && buyPrice != null) {
                        HorizontalDivider(color = overlayBorder)
                        val sp = sellPrice!!
                        val bp = buyPrice!!
                        val bf = brokerFeePct / 100.0
                        val st = salesTaxPct / 100.0

                        // Station-trading formulas:
                        // Post a buy order → pay broker fee on buy
                        // Post a sell order → pay broker fee + sales tax on sell
                        val costPerUnit = bp * (1.0 + bf)
                        val revenuePerUnit = sp * (1.0 - bf - st)
                        val profitPerUnit = revenuePerUnit - costPerUnit
                        val margin = if (sp > 0) profitPerUnit / sp * 100.0 else 0.0
                        val profitColor = if (profitPerUnit > 0) buyColor else sellColor

                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            CalcRow("Broker (buy)", fmtIsk(bp * bf), dimText)
                            CalcRow("Broker (sell)", fmtIsk(sp * bf), dimText)
                            CalcRow("Sales tax", fmtIsk(sp * st), dimText)
                            Spacer(Modifier.height(4.dp))
                            CalcRow("Profit/unit", fmtIsk(profitPerUnit), profitColor, bold = true)
                            CalcRow("Margin", "%.1f%%".format(margin), profitColor, bold = true)
                        }
                    }

                    // ─── Sell wall / Buy wall ──────────────────────────────
                    // What it actually costs to clear the sell wall (Buy out — you're buying),
                    // or actually pays to clear the buy wall (Sell out — you're selling) — walked
                    // order-by-order (each at its own price and volume) rather than a flat markup
                    // off the single best price. Grouped and labeled by which book the numbers
                    // come from (sell wall, then buy wall — same order as the SELL/BUY rows
                    // above), not by which action they name, so adjacent rows read consistently.
                    if (sellBook.isNotEmpty() || buyBook.isNotEmpty()) {
                        HorizontalDivider(color = overlayBorder)
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            if (sellBook.isNotEmpty()) {
                                val vol = sellBook.sumOf { it.second }
                                val cost = sellBook.sumOf { it.first * it.second }
                                CalcRow("Sell wall: buy out (${fmtVol(vol)} units)", fmtIsk(cost), dimText)
                                avgTopPrice(sellBook, cheapestFirst = true)?.let {
                                    CalcRow("Sell wall: avg (top 5%)", fmtIsk(it), dimText)
                                }
                            }
                            if (buyBook.isNotEmpty()) {
                                val vol = buyBook.sumOf { it.second }
                                val revenue = buyBook.sumOf { it.first * it.second }
                                CalcRow("Buy wall: sell out (${fmtVol(vol)} units)", fmtIsk(revenue), dimText)
                                avgTopPrice(buyBook, cheapestFirst = false)?.let {
                                    CalcRow("Buy wall: avg (top 5%)", fmtIsk(it), dimText)
                                }
                            }
                        }
                    }

                    // ─── Inventory value — pasted name+quantity list from a cargo/inventory window
                    if (manifestTotal != null) {
                        HorizontalDivider(color = overlayBorder)
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Inventory value", color = dimText, style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        autoCopyManifest = !autoCopyManifest
                                        prefs.putBoolean("autoCopyManifest", autoCopyManifest)
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(22.dp),
                                ) {
                                    Text(
                                        "AUTO-COPY",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (autoCopyManifest) accent else dimText,
                                        fontWeight = if (autoCopyManifest) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                            CalcRow("Total ($manifestResolved items)", fmtIsk(manifestTotal!!), buyColor, bold = true)
                            if (manifestUnresolved.isNotEmpty()) {
                                Text(
                                    "Unresolved: ${manifestUnresolved.joinToString(", ")}",
                                    color = dimText,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } // scrollable body Column
            }
        }
    } // outer fillMaxSize Box
}

// ─── Sub-composables ──────────────────────────────────────────────────────

@Composable
private fun PriceRow(
    label: String,
    price: Double?,
    beatPrice: Double?,
    location: String,
    color: Color,
    source: PriceSource?,
    onSet: () -> Unit,
    onCopyBeat: (Double) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val dimText = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(30.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (price != null) {
                Text(
                    fmtIsk(price),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                SourceBadge(source)
            } else {
                Text("—  copy an order row", color = dimText, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onSet,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(22.dp),
            ) {
                Text("SET", style = MaterialTheme.typography.labelSmall, color = accent)
            }
        }
        // The beat price already sits in the clipboard (written by the poll loop / book import);
        // clicking here re-copies it after something else overwrote the clipboard.
        if (beatPrice != null) {
            Text(
                "beat ${formatEveSigFigPrice(beatPrice)}",
                color = accent,
                style = MaterialTheme.typography.labelSmall,
                modifier =
                    Modifier
                        .padding(start = 38.dp)
                        .clickable { onCopyBeat(beatPrice) },
            )
        }
        if (location.isNotEmpty()) {
            Text(
                location,
                color = dimText,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 38.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Shows whether a price came from the automatic order-book file import ("auto") or a manual
// clipboard copy ("manual") — the file source is richer (whole order book, not one copied line)
// and refreshes itself the moment a new export appears.
@Composable
private fun SourceBadge(source: PriceSource?) {
    if (source == null) return
    val dimText = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val (icon, label) =
        when (source) {
            PriceSource.FILE -> Icons.Default.Bolt to "auto"
            PriceSource.CLIPBOARD -> Icons.Default.ContentPaste to "manual"
            PriceSource.LOOKUP -> Icons.Default.Search to "jita"
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = dimText, modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = dimText)
    }
}

@Composable
private fun CalcRow(
    label: String,
    value: String,
    valueColor: Color,
    bold: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
        Text(
            value,
            color = valueColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// Volume-weighted average price across the closest 5% of order rows by count (rounded up, min 1)
// — cheapest rows for sell, priciest for buy. A more realistic "quick fill" reference than either
// the single best price (only ever true for the very first unit) or the full Buy out/Sell out
// total (walks the entire book, unrealistically deep for one trade).
private fun avgTopPrice(
    levels: List<Pair<Double, Long>>,
    cheapestFirst: Boolean,
): Double? {
    if (levels.isEmpty()) return null
    val sorted = if (cheapestFirst) levels.sortedBy { it.first } else levels.sortedByDescending { it.first }
    // Integer ceiling division (5% = 5/100) instead of Math.ceil, to sidestep float rounding.
    val topCount = ((sorted.size * 5 + 99) / 100).coerceAtLeast(1)
    val top = sorted.take(topCount)
    val vol = top.sumOf { it.second }
    return if (vol > 0) top.sumOf { it.first * it.second } / vol else top.map { it.first }.average()
}

// ─── Formatters ───────────────────────────────────────────────────────────

private fun fmtIsk(v: Double): String =
    when {
        kotlin.math.abs(v) >= 1_000_000_000 -> "%.2fB ISK".format(v / 1_000_000_000)
        kotlin.math.abs(v) >= 1_000_000 -> "%.2fM ISK".format(v / 1_000_000)
        kotlin.math.abs(v) >= 1_000 -> "%.2fK ISK".format(v / 1_000)
        else -> "%.2f ISK".format(v)
    }

private fun fmtVol(v: Long): String =
    when {
        v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
        v >= 1_000 -> "%.0fK".format(v / 1_000.0)
        else -> v.toString()
    }
