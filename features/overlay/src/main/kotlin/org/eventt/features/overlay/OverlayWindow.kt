package org.eventt.features.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import org.eventt.core.marketlogs.MarketLogEvent
import org.eventt.core.marketlogs.MarketLogWatcher
import org.eventt.ui.theme.DarkColorScheme
import org.eventt.ui.theme.EveTypography
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.util.prefs.Preferences

private val OVERLAY_BG = Color(0xEE0D1117)
private val OVERLAY_BORDER = Color(0xFF2A3A50)
private val OVERLAY_HEADER = Color(0xFF131C26)
private val DIM_TEXT = Color(0xFF6A7D8E)
private val SELL_COLOR = Color(0xFFFF6B6B)
private val BUY_COLOR = Color(0xFF69DB7C)
private val ACCENT = Color(0xFF4A90D9)

@Composable
fun OverlayWindow(onClose: () -> Unit) {
    val prefs = remember { Preferences.userRoot().node("org/eve/trader/overlay") }

    // Opened via the Ctrl+M hotkey: place it at the cursor instead of wherever it was last
    // dragged to — that's the whole point of that hotkey. Opened via the top-bar button (no
    // pending position): keep the normal drag-persisted spot.
    val pendingPosition = remember { OverlayController.consumePendingOpenPosition() }
    val windowState =
        rememberWindowState(
            width = 290.dp,
            height = 290.dp,
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
        MaterialTheme(colorScheme = DarkColorScheme, typography = EveTypography) {
            OverlayContent(onClose = onClose, prefs = prefs)
        }
    }
}

private enum class PriceSource { CLIPBOARD, FILE }

@Composable
private fun OverlayContent(
    onClose: () -> Unit,
    prefs: Preferences,
) {
    var sellPrice by remember { mutableStateOf<Double?>(null) }
    var buyPrice by remember { mutableStateOf<Double?>(null) }
    var sellVol by remember { mutableStateOf(0L) }
    var buyVol by remember { mutableStateOf(0L) }
    var sellLoc by remember { mutableStateOf("") }
    var buyLoc by remember { mutableStateOf("") }
    var sellSource by remember { mutableStateOf<PriceSource?>(null) }
    var buySource by remember { mutableStateOf<PriceSource?>(null) }
    var bookItemName by remember { mutableStateOf<String?>(null) }

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
    // order row in-game, this overlay picks it up within ~400ms).
    var lastClipboard by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            val text = ClipboardParser.readClipboard()
            if (text != null && text != lastClipboard) {
                lastClipboard = text
                val p = ClipboardParser.parse(text) ?: continue
                if (p.isBuy) {
                    buyPrice = p.price
                    buyVol = p.volume
                    buyLoc = p.location
                    buySource = PriceSource.CLIPBOARD
                } else {
                    sellPrice = p.price
                    sellVol = p.volume
                    sellLoc = p.location
                    sellSource = PriceSource.CLIPBOARD
                }
            }
        }
    }

    // Richer, automatic source: EVE's own item order-book export (Settings › Marketlogs Folder)
    // — the whole book for the item you have open in-game, not just one copied row.
    LaunchedEffect(Unit) {
        MarketLogWatcher.events.collect { event ->
            if (event is MarketLogEvent.OrderBookImported) {
                val bestSell = event.sellRows.filter { it.jumps == 0 }.minByOrNull { it.price } ?: event.sellRows.minByOrNull { it.price }
                val bestBuy = event.buyRows.filter { it.jumps == 0 }.maxByOrNull { it.price } ?: event.buyRows.maxByOrNull { it.price }
                bestSell?.let {
                    sellPrice = it.price
                    sellVol = it.volRemaining.toLong()
                    sellLoc = StaticDataDao.getStationById(it.stationId)?.name ?: it.stationId.toString()
                    sellSource = PriceSource.FILE
                }
                bestBuy?.let {
                    buyPrice = it.price
                    buyVol = it.volRemaining.toLong()
                    buyLoc = StaticDataDao.getStationById(it.stationId)?.name ?: it.stationId.toString()
                    buySource = PriceSource.FILE
                }
                bookItemName = StaticDataDao.getTypeName(event.typeId) ?: "Unknown (${event.typeId})"
            }
        }
    }

    val shape = RoundedCornerShape(10.dp)

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .clip(shape)
                    .background(OVERLAY_BG)
                    .border(1.dp, OVERLAY_BORDER, shape),
        ) {
            Column {
                // ─── Header / drag handle ─────────────────────────────────
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(OVERLAY_HEADER)
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
                    Icon(Icons.Default.DragIndicator, null, tint = ACCENT, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "EVE Trade Calc",
                        style = MaterialTheme.typography.labelMedium,
                        color = ACCENT,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, null, tint = DIM_TEXT, modifier = Modifier.size(14.dp))
                    }
                }

                bookItemName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = ACCENT,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }

                // ─── Prices ───────────────────────────────────────────────
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    PriceRow(
                        label = "SELL",
                        price = sellPrice,
                        location = sellLoc,
                        color = SELL_COLOR,
                        source = sellSource,
                        onSet = {
                            val p = ClipboardParser.parse(ClipboardParser.readClipboard()) ?: return@PriceRow
                            sellPrice = p.price
                            sellVol = p.volume
                            sellLoc = p.location
                            sellSource = PriceSource.CLIPBOARD
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    PriceRow(
                        label = "BUY ",
                        price = buyPrice,
                        location = buyLoc,
                        color = BUY_COLOR,
                        source = buySource,
                        onSet = {
                            val p = ClipboardParser.parse(ClipboardParser.readClipboard()) ?: return@PriceRow
                            buyPrice = p.price
                            buyVol = p.volume
                            buyLoc = p.location
                            buySource = PriceSource.CLIPBOARD
                        },
                    )
                }

                // ─── Profit / margin ──────────────────────────────────────
                if (sellPrice != null && buyPrice != null) {
                    HorizontalDivider(color = OVERLAY_BORDER)
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
                    val profitColor = if (profitPerUnit > 0) BUY_COLOR else SELL_COLOR

                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        CalcRow("Broker (buy)", fmtIsk(bp * bf), DIM_TEXT)
                        CalcRow("Broker (sell)", fmtIsk(sp * bf), DIM_TEXT)
                        CalcRow("Sales tax", fmtIsk(sp * st), DIM_TEXT)
                        Spacer(Modifier.height(4.dp))
                        CalcRow("Profit/unit", fmtIsk(profitPerUnit), profitColor, bold = true)
                        CalcRow("Margin", "%.1f%%".format(margin), profitColor, bold = true)
                        Spacer(Modifier.height(4.dp))
                        // Instant-fill reference prices: crossing the spread by 10% guarantees a
                        // fill even if the book moves a tick before the order lands, unlike
                        // matching the going price exactly.
                        CalcRow("Buy out (+10%)", fmtIsk(sp * 1.1), DIM_TEXT)
                        CalcRow("Sell out (−10%)", fmtIsk(bp * 0.9), DIM_TEXT)

                        val tradableVol = minOf(sellVol, buyVol).takeIf { it > 0 }
                        if (tradableVol != null) {
                            Spacer(Modifier.height(2.dp))
                            CalcRow(
                                label = "× ${fmtVol(tradableVol)} units",
                                value = fmtIsk(profitPerUnit * tradableVol),
                                valueColor = profitColor,
                            )
                        }
                    }
                }
            }
        }
    } // outer fillMaxSize Box
}

// ─── Sub-composables ──────────────────────────────────────────────────────

@Composable
private fun PriceRow(
    label: String,
    price: Double?,
    location: String,
    color: Color,
    source: PriceSource?,
    onSet: () -> Unit,
) {
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
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                SourceBadge(source)
            } else {
                Text("—  copy an order row", color = DIM_TEXT, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onSet,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(22.dp),
            ) {
                Text("SET", style = MaterialTheme.typography.labelSmall, color = ACCENT)
            }
        }
        if (location.isNotEmpty()) {
            Text(
                location,
                color = DIM_TEXT,
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
    val (icon, label) =
        when (source) {
            PriceSource.FILE -> Icons.Default.Bolt to "auto"
            PriceSource.CLIPBOARD -> Icons.Default.ContentPaste to "manual"
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = DIM_TEXT, modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = DIM_TEXT)
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
        Text(label, color = DIM_TEXT, style = MaterialTheme.typography.labelSmall)
        Text(
            value,
            color = valueColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
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
