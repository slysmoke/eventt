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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eve.trader.core.database.CharacterDao
import org.eve.trader.core.database.StaticDataDao
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.ui.common.EmptyState
import org.eve.trader.ui.common.LoadingOverlay
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private data class CharacterOrder(
    val orderId: Long,
    val typeId: Int,
    val typeName: String,
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
            val issuedTime = OffsetDateTime.parse(issued).withOffsetSameInstant(ZoneOffset.UTC)
            val expiresAt = issuedTime.plusDays(duration.toLong())
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            ChronoUnit.SECONDS.between(now, expiresAt).coerceAtLeast(0)
        } catch (_: Exception) { 0L }
    }
}

private enum class SortCol { NAME, PRICE, VOLUME, TOTAL, TIME_LEFT, STATION }
private enum class SortDir { ASC, DESC }

@Composable
fun OrdersScreen() {
    val scope = rememberCoroutineScope()
    val characters = remember { try { CharacterDao.getAll() } catch (_: Exception) { emptyList() } }
    var selectedCharId by remember { mutableStateOf(characters.firstOrNull()?.id) }
    var orders by remember { mutableStateOf<List<CharacterOrder>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(0) }  // 0 = sell, 1 = buy
    var sortCol by remember { mutableStateOf(SortCol.NAME) }
    var sortDir by remember { mutableStateOf(SortDir.ASC) }

    fun loadOrders(charId: Int) {
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true }
            try {
                val raw = EsiClient.getCharacterOrders(charId)
                val parsed = raw.map { m ->
                    val typeId = (m["type_id"] as? Number)?.toInt() ?: 0
                    val locationId = (m["location_id"] as? Number)?.toLong() ?: 0L
                    CharacterOrder(
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
                        range = (m["range"] as? String) ?: "",
                        minVolume = (m["min_volume"] as? Number)?.toInt() ?: 1,
                        state = (m["state"] as? String) ?: "active",
                    )
                }
                withContext(Dispatchers.Main) { orders = parsed }
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    LaunchedEffect(selectedCharId) {
        selectedCharId?.let { loadOrders(it) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Orders", style = MaterialTheme.typography.headlineMedium)
            selectedCharId?.let { charId ->
                IconButton(onClick = { loadOrders(charId) }, enabled = !isLoading) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (characters.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = characters.indexOfFirst { it.id == selectedCharId }.coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
            ) {
                characters.forEach { char ->
                    Tab(
                        selected = char.id == selectedCharId,
                        onClick = { selectedCharId = char.id },
                        text = { Text(char.name) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        val sellCount = orders.count { !it.isBuyOrder }
        val buyCount = orders.count { it.isBuyOrder }
        TabRow(selectedTabIndex = activeTab) {
            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                Text("Sell ($sellCount)", modifier = Modifier.padding(8.dp))
            }
            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                Text("Buy ($buyCount)", modifier = Modifier.padding(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val filtered = orders.filter { if (activeTab == 0) !it.isBuyOrder else it.isBuyOrder }
        val sorted = applySort(filtered, sortCol, sortDir)

        fun onHeaderClick(col: SortCol) {
            if (sortCol == col) sortDir = if (sortDir == SortDir.ASC) SortDir.DESC else SortDir.ASC
            else { sortCol = col; sortDir = SortDir.ASC }
        }

        if (sorted.isEmpty() && !isLoading) {
            EmptyState(
                icon = Icons.Default.Receipt,
                title = if (activeTab == 0) "No Sell Orders" else "No Buy Orders",
                description = if (characters.isEmpty()) "Add a character to view orders." else "No active orders for this character.",
            )
        } else if (activeTab == 0) {
            SellOrdersTable(sorted, sortCol, sortDir, ::onHeaderClick)
        } else {
            BuyOrdersTable(sorted, sortCol, sortDir, ::onHeaderClick)
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Loading orders...")
}

private fun applySort(list: List<CharacterOrder>, col: SortCol, dir: SortDir): List<CharacterOrder> {
    val sorted = when (col) {
        SortCol.NAME      -> list.sortedBy { it.typeName }
        SortCol.PRICE     -> list.sortedBy { it.price }
        SortCol.VOLUME    -> list.sortedBy { it.volumeRemaining }
        SortCol.TOTAL     -> list.sortedBy { it.total }
        SortCol.TIME_LEFT -> list.sortedBy { it.timeLeftSeconds }
        SortCol.STATION   -> list.sortedBy { it.stationName }
    }
    return if (dir == SortDir.DESC) sorted.reversed() else sorted
}

@Composable
private fun SellOrdersTable(
    orders: List<CharacterOrder>,
    sortCol: SortCol,
    sortDir: SortDir,
    onSort: (SortCol) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortHeader("Name", SortCol.NAME, sortCol, sortDir, onSort, Modifier.weight(3f))
            SortHeader("Price", SortCol.PRICE, sortCol, sortDir, onSort, Modifier.weight(2f), rightAlign = true)
            SortHeader("Volume", SortCol.VOLUME, sortCol, sortDir, onSort, Modifier.weight(2f), rightAlign = true)
            SortHeader("Total", SortCol.TOTAL, sortCol, sortDir, onSort, Modifier.weight(2f), rightAlign = true)
            SortHeader("Time Left", SortCol.TIME_LEFT, sortCol, sortDir, onSort, Modifier.weight(1.5f), rightAlign = true)
            SortHeader("Station", SortCol.STATION, sortCol, sortDir, onSort, Modifier.weight(3f))
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
private fun BuyOrdersTable(
    orders: List<CharacterOrder>,
    sortCol: SortCol,
    sortDir: SortDir,
    onSort: (SortCol) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortHeader("Name", SortCol.NAME, sortCol, sortDir, onSort, Modifier.weight(3f))
            SortHeader("Price", SortCol.PRICE, sortCol, sortDir, onSort, Modifier.weight(2f), rightAlign = true)
            SortHeader("Volume", SortCol.VOLUME, sortCol, sortDir, onSort, Modifier.weight(2f), rightAlign = true)
            SortHeader("Total", SortCol.TOTAL, sortCol, sortDir, onSort, Modifier.weight(2f), rightAlign = true)
            StaticHeader("Range", Modifier.weight(1.5f))
            StaticHeader("Min Qty", Modifier.weight(1f), rightAlign = true)
            SortHeader("Time Left", SortCol.TIME_LEFT, sortCol, sortDir, onSort, Modifier.weight(1.5f), rightAlign = true)
            SortHeader("Station", SortCol.STATION, sortCol, sortDir, onSort, Modifier.weight(3f))
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

@Composable
private fun SellOrderRow(order: CharacterOrder) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            order.typeName,
            modifier = Modifier.weight(3f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            formatIsk(order.price),
            modifier = Modifier.weight(2f),
            textAlign = TextAlign.End,
            color = Color(0xFFFF6B6B),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${formatNumber(order.volumeRemaining)}/${formatNumber(order.volumeTotal)}",
            modifier = Modifier.weight(2f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            formatIsk(order.total),
            modifier = Modifier.weight(2f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            formatTimeLeft(order.timeLeftSeconds),
            modifier = Modifier.weight(1.5f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
            color = timeLeftColor(order.timeLeftSeconds),
        )
        Text(
            order.stationName,
            modifier = Modifier.weight(3f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BuyOrderRow(order: CharacterOrder) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            order.typeName,
            modifier = Modifier.weight(3f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            formatIsk(order.price),
            modifier = Modifier.weight(2f),
            textAlign = TextAlign.End,
            color = Color(0xFF69DB7C),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${formatNumber(order.volumeRemaining)}/${formatNumber(order.volumeTotal)}",
            modifier = Modifier.weight(2f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            formatIsk(order.total),
            modifier = Modifier.weight(2f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            formatRange(order.range),
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            formatNumber(order.minVolume),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            formatTimeLeft(order.timeLeftSeconds),
            modifier = Modifier.weight(1.5f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
            color = timeLeftColor(order.timeLeftSeconds),
        )
        Text(
            order.stationName,
            modifier = Modifier.weight(3f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
                imageVector = if (dir == SortDir.ASC) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = labelColor,
            )
            Spacer(modifier = Modifier.width(2.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = labelColor,
        )
        if (isActive && !rightAlign) {
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = if (dir == SortDir.ASC) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = labelColor,
            )
        }
    }
}

@Composable
private fun StaticHeader(label: String, modifier: Modifier, rightAlign: Boolean = false) {
    Text(
        label,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (rightAlign) TextAlign.End else TextAlign.Start,
    )
}

private fun formatRange(range: String): String = when (range) {
    "station"     -> "Station"
    "solarsystem" -> "System"
    "region"      -> "Region"
    else          -> range.toIntOrNull()?.let { "$it jumps" } ?: range
}

private fun formatTimeLeft(seconds: Long): String {
    if (seconds <= 0) return "Expired"
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
    seconds <= 0       -> Color(0xFFFF6B6B)
    seconds < 86400    -> Color(0xFFFFD43B)
    else               -> Color.Unspecified
}

private fun formatNumber(value: Int): String = "%,d".format(value)

private fun formatIsk(value: Double): String = when {
    kotlin.math.abs(value) >= 1_000_000_000_000 -> "%.2fT".format(value / 1_000_000_000_000)
    kotlin.math.abs(value) >= 1_000_000_000     -> "%.2fB".format(value / 1_000_000_000)
    kotlin.math.abs(value) >= 1_000_000         -> "%.2fM".format(value / 1_000_000)
    kotlin.math.abs(value) >= 1_000             -> "%.2fK".format(value / 1_000)
    else                                         -> "%,.2f".format(value)
}
