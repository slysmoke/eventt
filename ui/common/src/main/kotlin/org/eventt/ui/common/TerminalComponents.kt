package org.eventt.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

// Dense "trading terminal" building blocks shared by the Dashboard and the Leaderboard: thin
// bordered panels, monospace numbers, sortable tables.

// Secondary text, axis labels, table headers.
val AXIS_COLOR = Color(0xFF808080)

val Mono = FontFamily.Monospace

fun signed(v: Double) = (if (v > 0) "+" else "") + formatIsk(v)

fun pct(v: Double) = String.format(Locale.US, "%+.1f%%", v)

// Compact toggle button, terminal style.
@Composable
fun Segment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = Mono,
        color = if (selected) primary else AXIS_COLOR,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier =
            Modifier
                .border(1.dp, if (selected) primary else outline(), MaterialTheme.shapes.extraSmall)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun outline() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

// Bordered box with a thin title bar — the terminal screens' one container.
@Composable
fun Panel(
    title: String?,
    modifier: Modifier = Modifier,
    header: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .border(1.dp, outline(), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AXIS_COLOR,
                )
            }
            header()
        }
        HorizontalDivider(thickness = 1.dp, color = outline())
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
fun TabLabel(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (selected) MaterialTheme.colorScheme.primary else AXIS_COLOR,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 2.dp),
    )
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun pnlColor(v: Double?): Color? =
    when {
        v == null || v == 0.0 -> null
        v > 0 -> positiveColor
        else -> negativeColor
    }

@Composable
fun RowScope.Kpi(
    label: String,
    value: String,
    sub: String?,
    subColor: Color?,
    dot: Color?,
    tip: StringResource? = null,
    valueColor: Color? = null,
    last: Boolean = false,
) {
    Row(Modifier.weight(1f)) {
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
            val title = @Composable {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dot?.let { Box(Modifier.size(6.dp).background(it, CircleShape)) }
                    Text(
                        label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = AXIS_COLOR,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (tip != null) Tip(stringResource(tip)) { title() } else title()
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = Mono,
                fontWeight = FontWeight.SemiBold,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                sub ?: " ",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = Mono,
                color = subColor ?: AXIS_COLOR,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!last) VerticalDivider(color = outline())
    }
}

@Composable
fun KV(
    label: String,
    value: String,
    color: Color? = null,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = Mono, color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

// One column of [Table].
class Col<T>(
    val title: String,
    val weight: Float,
    val end: Boolean = true,
    // Clicking the header sorts by this; null = not sortable.
    val sort: ((T) -> Comparable<*>?)? = null,
)

class Cell(
    val text: String,
    val color: Color? = null,
    // Drawn instead of [text] when set (a portrait, a sparkline); [text] still names the cell.
    val content: (@Composable () -> Unit)? = null,
)

@Composable
fun <T> Table(
    cols: List<Col<T>>,
    rows: List<T>,
    empty: String,
    // Column sorted by on open, newest/biggest first unless [defaultDesc] is off. Not remembered
    // across tab switches.
    defaultSort: Int,
    defaultDesc: Boolean = true,
    key: ((T) -> Any)? = null,
    onClick: ((T) -> Unit)? = null,
    footer: List<Cell>? = null,
    // Rows drawn with a tinted background (e.g. your own).
    highlight: (T) -> Boolean = { false },
    cells: @Composable (T) -> List<Cell>,
) {
    var sortCol by remember { mutableStateOf(defaultSort) }
    var desc by remember { mutableStateOf(defaultDesc) }
    val sorted =
        remember(rows, sortCol, desc) {
            val sel = cols[sortCol].sort ?: return@remember rows
            val cmp = compareBy(sel)
            rows.sortedWith(if (desc) cmp.reversed() else cmp)
        }

    @Composable
    fun line(
        values: List<Cell>,
        header: Boolean = false,
        modifier: Modifier = Modifier,
    ) = Row(
        modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cols.forEachIndexed { i, c ->
            val sortable = header && c.sort != null
            val custom = values[i].content
            if (custom != null) {
                Box(Modifier.weight(c.weight), contentAlignment = if (c.end) Alignment.CenterEnd else Alignment.CenterStart) { custom() }
                return@forEachIndexed
            }
            Text(
                values[i].text + if (header && i == sortCol) (if (desc) " ▼" else " ▲") else "",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (header || !c.end) null else Mono,
                fontWeight = if (header) FontWeight.Bold else null,
                color =
                    values[i].color ?: when {
                        header && i == sortCol -> MaterialTheme.colorScheme.primary
                        header -> AXIS_COLOR
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                textAlign = if (c.end) TextAlign.End else TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.weight(c.weight).then(
                        if (sortable) {
                            Modifier.clickable {
                                // Same column flips the order; a new one starts descending.
                                desc = if (sortCol == i) !desc else true
                                sortCol = i
                            }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
    line(cols.map { Cell(it.title) }, header = true)
    HorizontalDivider(color = outline())
    if (rows.isEmpty()) {
        Hint(empty)
        return
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            items(sorted, key = key) { r ->
                val tint = if (highlight(r)) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) else Modifier
                line(cells(r), modifier = tint.then(if (onClick != null) Modifier.clickable { onClick(r) } else Modifier))
            }
        }
        footer?.let {
            HorizontalDivider(color = outline())
            line(it)
        }
    }
}
