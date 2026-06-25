package org.eve.trader.features.overlay

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
import kotlinx.coroutines.delay
import org.eve.trader.ui.theme.DarkColorScheme
import org.eve.trader.ui.theme.EveTypography
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.util.prefs.Preferences

private val OVERLAY_BG     = Color(0xEE0D1117)
private val OVERLAY_BORDER = Color(0xFF2A3A50)
private val OVERLAY_HEADER = Color(0xFF131C26)
private val DIM_TEXT       = Color(0xFF6A7D8E)
private val SELL_COLOR     = Color(0xFFFF6B6B)
private val BUY_COLOR      = Color(0xFF69DB7C)
private val ACCENT         = Color(0xFF4A90D9)

@Composable
fun OverlayWindow(onClose: () -> Unit) {
    val prefs        = remember { Preferences.userRoot().node("org/eve/trader/overlay") }
    var showSettings by remember { mutableStateOf(false) }

    val windowState = rememberWindowState(
        width  = 290.dp,
        height = 290.dp,
        position = WindowPosition(
            x = prefs.getInt("x", 120).dp,
            y = prefs.getInt("y", 120).dp,
        ),
    )

    LaunchedEffect(showSettings) {
        windowState.size = DpSize(290.dp, if (showSettings) 370.dp else 290.dp)
    }

    Window(
        onCloseRequest = onClose,
        state          = windowState,
        undecorated    = true,
        alwaysOnTop    = true,
        resizable      = false,
        title          = "EVE Trade Overlay",
    ) {
        MaterialTheme(colorScheme = DarkColorScheme, typography = EveTypography) {
            OverlayContent(
                showSettings     = showSettings,
                onToggleSettings = { showSettings = !showSettings },
                onClose          = onClose,
                prefs            = prefs,
            )
        }
    }
}

@Composable
private fun OverlayContent(
    showSettings:     Boolean,
    onToggleSettings: () -> Unit,
    onClose:          () -> Unit,
    prefs:            Preferences,
) {
    var sellPrice by remember { mutableStateOf<Double?>(null) }
    var buyPrice  by remember { mutableStateOf<Double?>(null) }
    var sellVol   by remember { mutableStateOf(0L) }
    var buyVol    by remember { mutableStateOf(0L) }
    var sellLoc   by remember { mutableStateOf("") }
    var buyLoc    by remember { mutableStateOf("") }

    var brokerFee by remember { mutableStateOf(prefs.getFloat("broker_fee", 3.0f)) }
    var salesTax  by remember { mutableStateOf(prefs.getFloat("sales_tax",  8.0f)) }

    // Clipboard polling — auto-assign sell/buy by EVE row format
    var lastClipboard by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            val text = ClipboardParser.readClipboard()
            if (text != null && text != lastClipboard) {
                lastClipboard = text
                val p = ClipboardParser.parse(text) ?: continue
                if (p.isBuy) {
                    buyPrice  = p.price; buyVol  = p.volume; buyLoc  = p.location
                } else {
                    sellPrice = p.price; sellVol = p.volume; sellLoc = p.location
                }
            }
        }
    }

    val shape = RoundedCornerShape(10.dp)

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(shape)
            .background(OVERLAY_BG)
            .border(1.dp, OVERLAY_BORDER, shape),
    ) {
        Column {
            // ─── Header / drag handle ─────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OVERLAY_HEADER)
                    .pointerInput(Unit) {
                        // Screen-coordinate drag: capture AWT window at drag start
                        var dragWin: java.awt.Window? = null
                        var startMouseX = 0
                        var startMouseY = 0
                        var startWinX   = 0
                        var startWinY   = 0

                        detectDragGestures(
                            onDragStart = { _ ->
                                dragWin = KeyboardFocusManager
                                    .getCurrentKeyboardFocusManager().focusedWindow
                                val mouse = MouseInfo.getPointerInfo().location
                                startMouseX = mouse.x
                                startMouseY = mouse.y
                                startWinX   = dragWin?.x ?: 0
                                startWinY   = dragWin?.y ?: 0
                            },
                            onDrag = { _, _ ->
                                val mouse = MouseInfo.getPointerInfo().location
                                val newX = startWinX + (mouse.x - startMouseX)
                                val newY = startWinY + (mouse.y - startMouseY)
                                dragWin?.setLocation(newX, newY)
                            },
                            onDragEnd = {
                                dragWin?.let { prefs.putInt("x", it.x); prefs.putInt("y", it.y) }
                                dragWin = null
                            },
                            onDragCancel = { dragWin = null },
                        )
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.DragIndicator, null, tint = ACCENT, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "EVE Trade Calc",
                    style      = MaterialTheme.typography.labelMedium,
                    color      = ACCENT,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleSettings, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (showSettings) Icons.Default.ExpandLess else Icons.Default.Settings,
                        null, tint = DIM_TEXT, modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(2.dp))
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, null, tint = DIM_TEXT, modifier = Modifier.size(14.dp))
                }
            }

            // ─── Fee settings (collapsible) ───────────────────────────
            if (showSettings) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    FeeRow("Broker fee", brokerFee, 0f..5f) {
                        brokerFee = it; prefs.putFloat("broker_fee", it)
                    }
                    Spacer(Modifier.height(2.dp))
                    FeeRow("Sales tax", salesTax, 0f..10f) {
                        salesTax = it; prefs.putFloat("sales_tax", it)
                    }
                }
                HorizontalDivider(color = OVERLAY_BORDER)
            }

            // ─── Prices ───────────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                PriceRow(
                    label    = "SELL",
                    price    = sellPrice,
                    location = sellLoc,
                    color    = SELL_COLOR,
                    onSet    = {
                        val p = ClipboardParser.parse(ClipboardParser.readClipboard()) ?: return@PriceRow
                        sellPrice = p.price; sellVol = p.volume; sellLoc = p.location
                    },
                )
                Spacer(Modifier.height(6.dp))
                PriceRow(
                    label    = "BUY ",
                    price    = buyPrice,
                    location = buyLoc,
                    color    = BUY_COLOR,
                    onSet    = {
                        val p = ClipboardParser.parse(ClipboardParser.readClipboard()) ?: return@PriceRow
                        buyPrice = p.price; buyVol = p.volume; buyLoc = p.location
                    },
                )
            }

            // ─── Profit / margin ──────────────────────────────────────
            if (sellPrice != null && buyPrice != null) {
                HorizontalDivider(color = OVERLAY_BORDER)
                val sp = sellPrice!!
                val bp = buyPrice!!
                val bf = brokerFee / 100.0
                val st = salesTax  / 100.0

                // Station-trading formulas:
                // Post a buy order → pay broker fee on buy
                // Post a sell order → pay broker fee + sales tax on sell
                val costPerUnit    = bp * (1.0 + bf)
                val revenuePerUnit = sp * (1.0 - bf - st)
                val profitPerUnit  = revenuePerUnit - costPerUnit
                val margin         = if (sp > 0) profitPerUnit / sp * 100.0 else 0.0
                val profitColor    = if (profitPerUnit > 0) BUY_COLOR else SELL_COLOR

                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    CalcRow("Broker (buy)",  fmtIsk(bp * bf), DIM_TEXT)
                    CalcRow("Broker (sell)", fmtIsk(sp * bf), DIM_TEXT)
                    CalcRow("Sales tax",     fmtIsk(sp * st), DIM_TEXT)
                    Spacer(Modifier.height(4.dp))
                    CalcRow("Profit/unit", fmtIsk(profitPerUnit),       profitColor, bold = true)
                    CalcRow("Margin",      "%.1f%%".format(margin),     profitColor, bold = true)

                    val tradableVol = minOf(sellVol, buyVol).takeIf { it > 0 }
                    if (tradableVol != null) {
                        Spacer(Modifier.height(2.dp))
                        CalcRow(
                            label      = "× ${fmtVol(tradableVol)} units",
                            value      = fmtIsk(profitPerUnit * tradableVol),
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
private fun PriceRow(label: String, price: Double?, location: String, color: Color, onSet: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = color, style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
            Spacer(Modifier.width(8.dp))
            if (price != null) {
                Text(fmtIsk(price), color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold)
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
                color    = DIM_TEXT,
                style    = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 38.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CalcRow(label: String, value: String, valueColor: Color, bold: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = DIM_TEXT, style = MaterialTheme.typography.labelSmall)
        Text(value, color = valueColor, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun FeeRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = DIM_TEXT, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(70.dp))
        Slider(
            value         = value,
            onValueChange = onChange,
            valueRange    = range,
            modifier      = Modifier.weight(1f).height(28.dp),
            colors        = SliderDefaults.colors(thumbColor = ACCENT, activeTrackColor = ACCENT),
        )
        Text("%.1f%%".format(value), color = Color.White, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(38.dp).padding(start = 4.dp))
    }
}

// ─── Formatters ───────────────────────────────────────────────────────────

private fun fmtIsk(v: Double): String = when {
    kotlin.math.abs(v) >= 1_000_000_000 -> "%.2fB ISK".format(v / 1_000_000_000)
    kotlin.math.abs(v) >= 1_000_000     -> "%.2fM ISK".format(v / 1_000_000)
    kotlin.math.abs(v) >= 1_000         -> "%.2fK ISK".format(v / 1_000)
    else                                 -> "%.2f ISK".format(v)
}

private fun fmtVol(v: Long): String = when {
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
    v >= 1_000     -> "%.0fK".format(v / 1_000.0)
    else           -> v.toString()
}
