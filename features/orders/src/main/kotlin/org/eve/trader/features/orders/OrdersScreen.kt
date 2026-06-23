package org.eve.trader.features.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eve.trader.core.database.StaticDataDao
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.ui.common.EmptyState
import org.eve.trader.ui.common.LoadingOverlay
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private val SELL_COLOR = Color(0xFFFF6B6B)
private val BUY_COLOR  = Color(0xFF69DB7C)
private val VOL_SELL   = Color(0xFFFF8C00)   // orange bar like Evernus
private val VOL_BUY    = Color(0xFF2E7D32)

private data class CharacterOrder(
    val orderId: Long,
    val typeId: Int,
    val typeName: String,
    val groupName: String,
    val locationId: Long,
    val stationName: String,
    val price: Double,
    val volumeTotal: Int,
    val volumeRemaining: Int,
    val isBuyOrder: Boolean,
    val duration: Int,
    val issued: String,
    val range: String,
    val minVolume: Int,
    val state: String,
) {
    val total: Double get() = price * volumeRemaining
    val timeLeftSeconds: Long get() {
        return try {
            val exp = OffsetDateTime.parse(issued).withOffsetSameInstant(ZoneOffset.UTC).plusDays(duration.toLong())
            ChronoUnit.SECONDS.between(OffsetDateTime.now(ZoneOffset.UTC), exp).coerceAtLeast(0)
        } catch (_: Exception) { 0L }
    }
    val orderAgeSeconds: Long get() {
        return try {
            val start = OffsetDateTime.parse(issued).withOffsetSameInstant(ZoneOffset.UTC)
            ChronoUnit.SECONDS.between(start, OffsetDateTime.now(ZoneOffset.UTC)).coerceAtLeast(0)
        } catch (_: Exception) { 0L }
    }
    val issuedFormatted: String get() = issued.take(16).replace("T", " ")
}

private enum class SortCol { NAME, GROUP, PRICE, VOLUME, TOTAL, TIME_LEFT, ORDER_AGE, STATION }
private enum class SortDir { ASC, DESC }

@Composable
fun OrdersScreen(charId: Int?) {
    val scope = rememberCoroutineScope()
    var orders by remember { mutableStateOf<List<CharacterOrder>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(0) }
    var sortCol by remember { mutableStateOf(SortCol.NAME) }
    var sortDir by remember { mutableStateOf(SortDir.ASC) }

    fun loadOrders(charId: Int) {
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true }
            try {
                val raw = EsiClient.getCharacterOrders(charId)
                val parsed = raw.map { m ->
                    val typeId     = (m["type_id"]     as? Number)?.toInt()  ?: 0
                    val locationId = (m["location_id"] as? Number)?.toLong() ?: 0L
                    CharacterOrder(
                        orderId         = (m["order_id"]      as? Number)?.toLong()  ?: 0L,
                        typeId          = typeId,
                        typeName        = StaticDataDao.getTypeName(typeId)           ?: "Unknown ($typeId)",
                        groupName       = StaticDataDao.getGroupNameForType(typeId)   ?: "",
                        locationId      = locationId,
                        stationName     = StaticDataDao.getStationById(locationId)?.name ?: locationId.toString(),
                        price           = (m["price"]          as? Number)?.toDouble() ?: 0.0,
                        volumeTotal     = (m["volume_total"]   as? Number)?.toInt()   ?: 0,
                        volumeRemaining = (m["volume_remain"]  as? Number)?.toInt()   ?: 0,
                        isBuyOrder      = (m["is_buy_order"]   as? Boolean)           ?: false,
                        duration        = (m["duration"]       as? Number)?.toInt()   ?: 0,
                        issued          = (m["issued"]         as? String)            ?: "",
                        range           = (m["range"]          as? String)            ?: "",
                        minVolume       = (m["min_volume"]     as? Number)?.toInt()   ?: 1,
                        state           = (m["state"]          as? String)            ?: "active",
                    )
                }
                withContext(Dispatchers.Main) { orders = parsed }
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    LaunchedEffect(charId) { charId?.let { loadOrders(it) } }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Top bar ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Orders", style = MaterialTheme.typography.headlineMedium)
            charId?.let { id ->
                IconButton(onClick = { loadOrders(id) }, enabled = !isLoading) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        }

        val sellOrders = orders.filter { !it.isBuyOrder }
        val buyOrders  = orders.filter {  it.isBuyOrder }

        TabRow(selectedTabIndex = activeTab, modifier = Modifier.fillMaxWidth()) {
            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                Text("Sell (${sellOrders.size})", modifier = Modifier.padding(8.dp))
            }
            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                Text("Buy (${buyOrders.size})", modifier = Modifier.padding(8.dp))
            }
        }

        val filtered = if (activeTab == 0) sellOrders else buyOrders
        val sorted   = applySort(filtered, sortCol, sortDir)

        fun onSort(col: SortCol) {
            if (sortCol == col) sortDir = if (sortDir == SortDir.ASC) SortDir.DESC else SortDir.ASC
            else { sortCol = col; sortDir = SortDir.ASC }
        }

        // ── Table ─────────────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (sorted.isEmpty() && !isLoading) {
                EmptyState(
                    icon = Icons.Default.Receipt,
                    title = if (activeTab == 0) "No Sell Orders" else "No Buy Orders",
                    description = if (charId == null) "Add a character to view orders." else "No active orders.",
                )
            } else if (activeTab == 0) {
                SellOrdersTable(sorted, sortCol, sortDir, ::onSort)
            } else {
                BuyOrdersTable(sorted, sortCol, sortDir, ::onSort)
            }
        }

        // ── Summary bar ───────────────────────────────────────────────────
        if (filtered.isNotEmpty()) {
            OrdersSummaryBar(filtered)
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Loading orders…")
}

// ── Sorting ───────────────────────────────────────────────────────────────

private fun applySort(list: List<CharacterOrder>, col: SortCol, dir: SortDir): List<CharacterOrder> {
    val sorted = when (col) {
        SortCol.NAME      -> list.sortedBy { it.typeName }
        SortCol.GROUP     -> list.sortedBy { it.groupName }
        SortCol.PRICE     -> list.sortedBy { it.price }
        SortCol.VOLUME    -> list.sortedBy { it.volumeRemaining }
        SortCol.TOTAL     -> list.sortedBy { it.total }
        SortCol.TIME_LEFT -> list.sortedBy { it.timeLeftSeconds }
        SortCol.ORDER_AGE -> list.sortedBy { it.orderAgeSeconds }
        SortCol.STATION   -> list.sortedBy { it.stationName }
    }
    return if (dir == SortDir.DESC) sorted.reversed() else sorted
}

// ── Tables ────────────────────────────────────────────────────────────────

@Composable
private fun SellOrdersTable(orders: List<CharacterOrder>, sortCol: SortCol, sortDir: SortDir, onSort: (SortCol) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortHeader("Name",       SortCol.NAME,      sortCol, sortDir, onSort, Modifier.weight(3f))
            SortHeader("Group",      SortCol.GROUP,     sortCol, sortDir, onSort, Modifier.weight(2f))
            SortHeader("Price",      SortCol.PRICE,     sortCol, sortDir, onSort, Modifier.weight(2f))
            SortHeader("Volume",     SortCol.VOLUME,    sortCol, sortDir, onSort, Modifier.weight(2.5f))
            SortHeader("Total",      SortCol.TOTAL,     sortCol, sortDir, onSort, Modifier.weight(2f))
            SortHeader("Time Left",  SortCol.TIME_LEFT, sortCol, sortDir, onSort, Modifier.weight(1.5f))
            SortHeader("Order Age",  SortCol.ORDER_AGE, sortCol, sortDir, onSort, Modifier.weight(1.5f))
            SortHeader("Issued",     SortCol.TIME_LEFT, sortCol, sortDir, onSort, Modifier.weight(2f))
            SortHeader("Station",    SortCol.STATION,   sortCol, sortDir, onSort, Modifier.weight(3f))
        }
        HorizontalDivider()
        LazyColumn {
            items(orders, key = { it.orderId }) { order ->
                SellOrderRow(order)
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun BuyOrdersTable(orders: List<CharacterOrder>, sortCol: SortCol, sortDir: SortDir, onSort: (SortCol) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortHeader("Name",      SortCol.NAME,      sortCol, sortDir, onSort, Modifier.weight(3f))
            SortHeader("Group",     SortCol.GROUP,     sortCol, sortDir, onSort, Modifier.weight(2f))
            SortHeader("Price",     SortCol.PRICE,     sortCol, sortDir, onSort, Modifier.weight(2f))
            SortHeader("Volume",    SortCol.VOLUME,    sortCol, sortDir, onSort, Modifier.weight(2.5f))
            SortHeader("Total",     SortCol.TOTAL,     sortCol, sortDir, onSort, Modifier.weight(2f))
            StaticHeader("Range",   Modifier.weight(1.5f))
            StaticHeader("Min Qty", Modifier.weight(1f))
            SortHeader("Time Left", SortCol.TIME_LEFT, sortCol, sortDir, onSort, Modifier.weight(1.5f))
            SortHeader("Order Age", SortCol.ORDER_AGE, sortCol, sortDir, onSort, Modifier.weight(1.5f))
            SortHeader("Station",   SortCol.STATION,   sortCol, sortDir, onSort, Modifier.weight(3f))
        }
        HorizontalDivider()
        LazyColumn {
            items(orders, key = { it.orderId }) { order ->
                BuyOrderRow(order)
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

// ── Rows ──────────────────────────────────────────────────────────────────

@Composable
private fun SellOrderRow(order: CharacterOrder) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name + status dot
        Row(modifier = Modifier.weight(3f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusDot(order.state)
            Text(order.typeName, style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis, maxLines = 1)
        }
        Text(order.groupName, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis, maxLines = 1)
        Text(formatIsk(order.price), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium, color = SELL_COLOR)
        VolumeBar(order.volumeRemaining, order.volumeTotal, isSell = true, modifier = Modifier.weight(2.5f).padding(horizontal = 4.dp))
        Text(formatIsk(order.total), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
        Text(formatDuration(order.timeLeftSeconds), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall, color = timeLeftColor(order.timeLeftSeconds))
        Text(formatDuration(order.orderAgeSeconds), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(order.issuedFormatted, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis, maxLines = 1)
        Text(order.stationName, modifier = Modifier.weight(3f).padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis, maxLines = 1)
    }
}

@Composable
private fun BuyOrderRow(order: CharacterOrder) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(3f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusDot(order.state)
            Text(order.typeName, style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis, maxLines = 1)
        }
        Text(order.groupName, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis, maxLines = 1)
        Text(formatIsk(order.price), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium, color = BUY_COLOR)
        VolumeBar(order.volumeRemaining, order.volumeTotal, isSell = false, modifier = Modifier.weight(2.5f).padding(horizontal = 4.dp))
        Text(formatIsk(order.total), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
        Text(formatRange(order.range), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
        Text(formatNumber(order.minVolume), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(formatDuration(order.timeLeftSeconds), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall, color = timeLeftColor(order.timeLeftSeconds))
        Text(formatDuration(order.orderAgeSeconds), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(order.stationName, modifier = Modifier.weight(3f).padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis, maxLines = 1)
    }
}

// ── Sub-components ────────────────────────────────────────────────────────

@Composable
private fun VolumeBar(remaining: Int, total: Int, isSell: Boolean, modifier: Modifier) {
    val fraction = if (total > 0) remaining.toFloat() / total.toFloat() else 0f
    val barColor = if (isSell) VOL_SELL else VOL_BUY

    Box(
        modifier = modifier
            .height(18.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // fill bar
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(barColor)
                .align(Alignment.CenterStart),
        )
        // text overlay
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
    val color = when (state) {
        "active"    -> Color(0xFF69DB7C)
        "cancelled" -> Color(0xFFFF6B6B)
        "expired"   -> Color(0xFFFF6B6B)
        "pending"   -> Color(0xFF74C0FC)
        else        -> Color.Gray
    }
    Box(
        modifier = Modifier
            .size(7.dp)
            .background(color, shape = MaterialTheme.shapes.small),
    )
}

@Composable
private fun SortHeader(
    label: String, col: SortCol, currentCol: SortCol, dir: SortDir,
    onSort: (SortCol) -> Unit, modifier: Modifier, rightAlign: Boolean = false,
) {
    val isActive = currentCol == col
    val labelColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.clickable { onSort(col) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (rightAlign) Arrangement.End else Arrangement.Start,
    ) {
        if (isActive && rightAlign) {
            Icon(if (dir == SortDir.ASC) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, Modifier.size(12.dp), tint = labelColor)
            Spacer(Modifier.width(2.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal, color = labelColor)
        if (isActive && !rightAlign) {
            Spacer(Modifier.width(2.dp))
            Icon(if (dir == SortDir.ASC) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, Modifier.size(12.dp), tint = labelColor)
        }
    }
}

@Composable
private fun StaticHeader(label: String, modifier: Modifier, rightAlign: Boolean = false) {
    Text(label, modifier = modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (rightAlign) TextAlign.End else TextAlign.Start)
}

@Composable
private fun OrdersSummaryBar(orders: List<CharacterOrder>) {
    val active       = orders.filter { it.state == "active" }
    val totalRemain  = active.sumOf { it.volumeRemaining }
    val totalEntered = active.sumOf { it.volumeTotal }
    val pct          = if (totalEntered > 0) totalRemain * 100.0 / totalEntered else 0.0
    val totalIsk     = active.sumOf { it.total }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryItem("Active orders", active.size.toString())
            SummaryItem("Volume", "${formatNumber(totalRemain)}/${formatNumber(totalEntered)} (${String.format("%.1f", pct)}%)")
            SummaryItem("Total ISK", "${formatIsk(totalIsk)} ISK")
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label + ":", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

// ── Formatters ────────────────────────────────────────────────────────────

private fun formatRange(range: String): String = when (range) {
    "station"     -> "Station"
    "solarsystem" -> "System"
    "region"      -> "Region"
    else          -> range.toIntOrNull()?.let { "$it jumps" } ?: range
}

private fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "—"
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else  -> "${m}m"
    }
}

private fun timeLeftColor(seconds: Long): Color = when {
    seconds <= 0    -> Color(0xFFFF6B6B)
    seconds < 86400 -> Color(0xFFFFD43B)
    else            -> Color.Unspecified
}

private fun formatNumber(value: Int): String = "%,d".format(value)

private fun formatIsk(value: Double): String = when {
    kotlin.math.abs(value) >= 1_000_000_000_000 -> "%.2fT".format(value / 1_000_000_000_000)
    kotlin.math.abs(value) >= 1_000_000_000     -> "%.2fB".format(value / 1_000_000_000)
    kotlin.math.abs(value) >= 1_000_000         -> "%.2fM".format(value / 1_000_000)
    kotlin.math.abs(value) >= 1_000             -> "%.2fK".format(value / 1_000)
    else                                         -> "%,.2f".format(value)
}
