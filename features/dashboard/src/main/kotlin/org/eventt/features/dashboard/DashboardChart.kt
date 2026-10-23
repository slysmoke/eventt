package org.eventt.features.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eventt.dashboard.generated.resources.*
import org.eventt.ui.common.AXIS_COLOR
import org.eventt.ui.common.Mono
import org.eventt.ui.common.formatIsk
import org.eventt.ui.common.signed
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

internal val WALLET_COLOR = Color(0xFF4A90D9)
internal val ESCROW_COLOR = Color(0xFFFFB74D)
internal val STOCK_COLOR = Color(0xFF4DB6AC)
internal val ITEMS_COLOR = Color(0xFF81C784)
internal val NET_COLOR = Color(0xFFB39DDB)

internal enum class Range(
    val label: StringResource,
    val days: Int,
) {
    W1(Res.string.range_1w, 7),
    M1(Res.string.range_1m, 30),
    M3(Res.string.range_3m, 90),
    Y1(Res.string.range_1y, 365),
    ALL(Res.string.range_all, Int.MAX_VALUE),
}

// Visible window in fractional day indices — same zoom/pan model as the item price chart.
private data class View(
    val start: Float,
    val end: Float,
) {
    val span get() = end - start

    private fun clamped(n: Int): View {
        val last = (n - 1).toFloat()
        val w = span.coerceAtMost(last)
        val s = start.coerceIn(0f, (last - w).coerceAtLeast(0f))
        return View(s, s + w)
    }

    fun panned(
        byDays: Float,
        n: Int,
    ) = View(start + byDays, end + byDays).clamped(n)

    fun zoomed(
        factor: Float,
        anchor: Float,
        n: Int,
    ): View {
        val w = (span * factor).coerceIn(MIN_VIEW_DAYS.coerceAtMost((n - 1).toFloat()), (n - 1).toFloat())
        val pivot = start + anchor * span
        return View(pivot - anchor * w, pivot - anchor * w + w).clamped(n)
    }

    companion object {
        fun preset(
            n: Int,
            days: Int,
        ) = View((n - days).coerceAtLeast(0).toFloat(), (n - 1).coerceAtLeast(1).toFloat())
    }
}

private const val MIN_VIEW_DAYS = 5f

/**
 * Net worth over time (wallet + escrow + items, or stock at cost before items were recorded) above realized P&L — daily bars plus the
 * running total since the left edge of the view. One crosshair, wheel zoom, drag to pan,
 * double-click back to [range].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NetWorthChart(
    days: List<Day>,
    range: Range,
    stacked: Boolean,
    modifier: Modifier = Modifier,
    // A click (not a drag) on a day.
    onDayClick: (String) -> Unit = {},
) {
    val n = days.size
    var view by remember(days, range) { mutableStateOf(View.preset(n, range.days.coerceAtLeast(MIN_VIEW_DAYS.toInt()).coerceAtMost(n))) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var dragX by remember { mutableStateOf<Float?>(null) }
    var pressX by remember { mutableStateOf<Float?>(null) }
    var lastPress by remember { mutableStateOf(0L) }
    var canvasWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val tm = rememberTextMeasurer()
    val up = positiveColor
    val down = negativeColor
    val ink = MaterialTheme.colorScheme.onSurface
    val grid = ink.copy(alpha = 0.07f)
    val lPadDp = 4.dp
    val rPadDp = 64.dp

    fun plotWidth() = canvasWidth - with(density) { (lPadDp + rPadDp).toPx() }

    fun anchorOf(x: Float) = ((x - with(density) { lPadDp.toPx() }) / plotWidth()).coerceIn(0f, 1f)
    val hoverIndex =
        hoverX?.let { x ->
            val w = plotWidth()
            if (w <= 0 || n < 2) null else (view.start + anchorOf(x) * view.span).roundToInt().coerceIn(0, n - 1)
        }
    val iFrom = floor(view.start).toInt().coerceIn(0, (n - 1).coerceAtLeast(0))
    val iTo = ceil(view.end).toInt().coerceIn(0, (n - 1).coerceAtLeast(0))
    // Running realized P&L, rebased to zero just before the first visible day.
    val cum =
        remember(days, iFrom) {
            var acc = 0.0
            days.mapIndexed { i, d ->
                if (i >= iFrom) acc += d.realized
                if (i >= iFrom) acc else null
            }
        }

    Column(modifier) {
        Legend(days, cum, hoverIndex ?: (n - 1).takeIf { it >= 0 })
        Spacer(Modifier.height(2.dp))
        val pnlWord = stringResource(Res.string.chart_pnl_period)
        Canvas(
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
                }.onPointerEvent(PointerEventType.Press) {
                    val now = System.currentTimeMillis()
                    if (now - lastPress < 350) view = View.preset(n, range.days.coerceAtLeast(MIN_VIEW_DAYS.toInt()).coerceAtMost(n))
                    lastPress = now
                    dragX =
                        it.changes
                            .first()
                            .position.x
                    pressX = dragX
                }.onPointerEvent(PointerEventType.Release) {
                    val px = pressX
                    val x =
                        it.changes
                            .first()
                            .position.x
                    // From the release point itself: hoverIndex is only as fresh as the last frame.
                    if (px != null && kotlin.math.abs(x - px) < 4f && plotWidth() > 0 && n >= 2) {
                        onDayClick(days[(view.start + anchorOf(x) * view.span).roundToInt().coerceIn(0, n - 1)].day)
                    }
                    dragX = null
                    pressX = null
                }.onPointerEvent(PointerEventType.Scroll) {
                    val c = it.changes.first()
                    view = view.zoomed(1.15f.pow(c.scrollDelta.y), anchorOf(c.position.x), n)
                }.onPointerEvent(PointerEventType.Exit) {
                    hoverX = null
                    dragX = null
                },
        ) {
            val lPad = lPadDp.toPx()
            val chartW = size.width - lPad - rPadDp.toPx()
            if (chartW <= 0 || n < 2) return@Canvas
            val gap = 10.dp.toPx()
            val bottomAxis = 14.dp.toPx()
            val usable = size.height - bottomAxis - gap
            val topH = usable * 0.62f
            val pnlTop = topH + gap
            val pnlH = usable - topH

            fun xFor(i: Int) = lPad + (i - view.start) / view.span * chartW

            fun clip(block: DrawScope.() -> Unit) = clipRect(lPad, 0f, lPad + chartW, size.height, block = block)
            val visible = days.subList(iFrom, iTo + 1)

            // ── Net worth pane. Stacked mode starts at zero so layer heights compare; the plain
            // line auto-scales so a few percent of change is actually visible.
            val hi = visible.maxOf { it.netWorth }
            val lo = if (stacked) 0.0 else visible.minOf { it.netWorth }
            val pad = ((hi - lo) * 0.06).coerceAtLeast(hi * 0.001).coerceAtLeast(1.0)
            val vMin = if (stacked) 0.0 else lo - pad
            val vMax = hi + pad

            fun yN(v: Double) = (topH * (1 - (v - vMin) / (vMax - vMin))).toFloat()
            for (g in 0..4) {
                val v = vMin + (vMax - vMin) * g / 4
                drawLine(grid, Offset(lPad, yN(v)), Offset(lPad + chartW, yN(v)), 1f)
                label(tm, formatIsk(v), Offset(lPad + chartW + 6.dp.toPx(), yN(v) - 6.dp.toPx()), AXIS_COLOR)
            }

            fun area(
                top: (Day) -> Double,
                bottom: (Day) -> Double,
                color: Color,
            ) {
                val path =
                    Path().apply {
                        moveTo(xFor(0), yN(top(days[0])))
                        for (i in 1 until n) lineTo(xFor(i), yN(top(days[i])))
                        for (i in n - 1 downTo 0) lineTo(xFor(i), yN(bottom(days[i])))
                        close()
                    }
                drawPath(path, color.copy(alpha = 0.55f))
            }
            clip {
                if (stacked) {
                    area({ it.wallet }, { 0.0 }, WALLET_COLOR)
                    area({ it.wallet + it.escrow }, { it.wallet }, ESCROW_COLOR)
                    area({ it.netWorth }, { it.wallet + it.escrow }, ITEMS_COLOR)
                } else {
                    val fill =
                        Path().apply {
                            moveTo(xFor(0), topH)
                            for (i in 0 until n) lineTo(xFor(i), yN(days[i].netWorth))
                            lineTo(xFor(n - 1), topH)
                            close()
                        }
                    drawPath(fill, Brush.verticalGradient(listOf(NET_COLOR.copy(alpha = 0.35f), Color.Transparent), 0f, topH))
                }
                series(days.map { it.netWorth }, NET_COLOR, 1.6.dp.toPx(), ::xFor, ::yN)
            }

            // ── P&L pane: cumulative line on the right-hand scale, daily bars in the lower third
            // on their own scale — a strong day shouldn't flatten the running total.
            val cumVis = cum.subList(iFrom, iTo + 1).filterNotNull()
            val cMin = minOf(0.0, cumVis.minOrNull() ?: 0.0)
            val cMax = maxOf(0.0, cumVis.maxOrNull() ?: 0.0)
            val cPad = ((cMax - cMin) * 0.08).coerceAtLeast(1.0)

            fun yC(v: Double) = (pnlTop + pnlH * (1 - (v - (cMin - cPad)) / (cMax - cMin + 2 * cPad))).toFloat()
            drawLine(grid, Offset(lPad, pnlTop - gap / 2), Offset(lPad + chartW, pnlTop - gap / 2), 1f)
            // Zero first, so a min/max sitting right next to it is the label that gets dropped.
            val labelled = mutableListOf<Float>()
            listOf(0.0, cMin, cMax).distinct().forEach { v ->
                drawLine(
                    if (v == 0.0) ink.copy(alpha = 0.25f) else grid,
                    Offset(lPad, yC(v)),
                    Offset(lPad + chartW, yC(v)),
                    1f,
                    pathEffect = if (v == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                )
                if (labelled.none { y -> kotlin.math.abs(y - yC(v)) < 12.dp.toPx() }) {
                    label(tm, formatIsk(v), Offset(lPad + chartW + 6.dp.toPx(), yC(v) - 6.dp.toPx()), AXIS_COLOR)
                    labelled += yC(v)
                }
            }
            val barMax = visible.maxOf { kotlin.math.abs(it.realized) }.coerceAtLeast(1.0)
            val barZero = yC(0.0)
            val barH = pnlH * 0.32f
            val barW = (chartW / (view.span + 1) * 0.7f).coerceIn(1f, 10.dp.toPx())
            clip {
                for (i in iFrom..iTo) {
                    val r = days[i].realized
                    if (r == 0.0) continue
                    val h = (kotlin.math.abs(r) / barMax * barH).toFloat()
                    val top = if (r > 0) barZero - h else barZero
                    val c = (if (r > 0) up else down).copy(alpha = if (i == hoverIndex) 0.95f else 0.55f)
                    drawRect(c, Offset(xFor(i) - barW / 2, top), Size(barW, h))
                }
                series(cum, if ((cumVis.lastOrNull() ?: 0.0) >= 0) up else down, 1.6.dp.toPx(), ::xFor, ::yC)
            }
            label(tm, pnlWord, Offset(lPad + 2.dp.toPx(), pnlTop), AXIS_COLOR)

            // ── Date axis.
            val ticks = 6
            for (t in 0..ticks) {
                val i = iFrom + (iTo - iFrom) * t / ticks
                val lm = tm.measure(days[i].day.drop(5), TextStyle(fontSize = 9.sp, color = AXIS_COLOR))
                val x = (xFor(i) - lm.size.width / 2f).coerceIn(lPad, lPad + chartW - lm.size.width)
                drawText(lm, topLeft = Offset(x, size.height - bottomAxis + 2.dp.toPx()))
            }

            // ── Crosshair, with the hovered values tagged on the right axis.
            hoverIndex?.let { i ->
                val x = xFor(i)
                drawLine(ink.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, pnlTop + pnlH), 1f)
                drawCircle(NET_COLOR, 3.5.dp.toPx(), Offset(x, yN(days[i].netWorth)))
                tag(tm, formatIsk(days[i].netWorth), yN(days[i].netWorth), lPad + chartW, NET_COLOR)
                cum[i]?.let {
                    drawCircle(if (it >= 0) up else down, 3.5.dp.toPx(), Offset(x, yC(it)))
                    tag(tm, formatIsk(it), yC(it), lPad + chartW, if (it >= 0) up else down)
                }
            }
        }
    }
}

// Day under the crosshair (or the latest): every component plus the day's and period's P&L.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(
    days: List<Day>,
    cum: List<Double?>,
    index: Int?,
) {
    val style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono)
    // Fixed two-line height so hovering doesn't make the chart jump when the row wraps.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().height(34.dp)) {
        if (index == null) return@FlowRow
        val d = days[index]
        val prev = days.getOrNull(index - 1)
        Text(d.day, style = style, color = AXIS_COLOR)
        LegendValue(stringResource(Res.string.net_worth), formatIsk(d.netWorth), NET_COLOR, style)
        prev?.let {
            val ch = d.netWorth - it.netWorth
            Text(signed(ch), style = style, color = if (ch >= 0) positiveColor else negativeColor)
        }
        LegendValue(stringResource(Res.string.wallet), formatIsk(d.wallet), WALLET_COLOR, style)
        LegendValue(stringResource(Res.string.escrow), formatIsk(d.escrow), ESCROW_COLOR, style)
        d.items?.let { LegendValue(stringResource(Res.string.items), formatIsk(it), ITEMS_COLOR, style) }
        LegendValue(stringResource(Res.string.stock_cost), formatIsk(d.stock), STOCK_COLOR, style)
        LegendValue(
            stringResource(Res.string.pnl_day),
            signed(d.realized),
            if (d.realized >= 0) positiveColor else negativeColor,
            style,
        )
        cum.getOrNull(index)?.let {
            LegendValue(stringResource(Res.string.chart_pnl_period), signed(it), if (it >= 0) positiveColor else negativeColor, style)
        }
    }
}

@Composable
private fun LegendValue(
    name: String,
    value: String,
    color: Color,
    style: TextStyle,
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(name, style = style, color = AXIS_COLOR)
        Text(value, style = style, color = color)
    }
}

private fun DrawScope.label(
    tm: TextMeasurer,
    text: String,
    topLeft: Offset,
    color: Color,
) {
    drawText(tm.measure(text, TextStyle(fontSize = 9.sp, color = color, fontFamily = Mono)), topLeft = topLeft)
}

// Value box on the right axis at y.
private fun DrawScope.tag(
    tm: TextMeasurer,
    text: String,
    y: Float,
    axisX: Float,
    color: Color,
) {
    val lm = tm.measure(text, TextStyle(fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.SemiBold, fontFamily = Mono))
    val tl = Offset(axisX + 2.dp.toPx(), y - lm.size.height / 2f)
    drawRect(color, topLeft = tl, size = Size(lm.size.width + 6.dp.toPx(), lm.size.height.toFloat()))
    drawText(lm, topLeft = tl + Offset(3.dp.toPx(), 0f))
}

private fun DrawScope.series(
    values: List<Double?>,
    color: Color,
    width: Float,
    xOf: (Int) -> Float,
    yOf: (Double) -> Float,
) {
    val pts = values.mapIndexedNotNull { i, v -> v?.let { Offset(xOf(i), yOf(it)) } }
    if (pts.size < 2) return
    val path =
        Path().apply {
            moveTo(pts.first().x, pts.first().y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
