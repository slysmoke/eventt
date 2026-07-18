package org.eventt.features.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.*
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.AppLog
import org.eventt.core.model.toPnlWindow
import org.eventt.features.orders.CostBasisService
import org.eventt.features.orders.realizedPnlWindow
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import java.time.LocalDate

private const val COMBINE_ALL_SETTING = "dashboard.combine_all"

private val ACCENT = Color(0xFF4A90D9)

@Composable
fun DashboardScreen(
    context: ViewContext?,
    refreshTrigger: Int = 0,
) {
    val charId = (context as? ViewContext.Character)?.charId
    val corpId = (context as? ViewContext.Corporation)?.corporationId
    val corpName = (context as? ViewContext.Corporation)?.corporationName
    val actingCharId = context?.actingCharId
    // Combined mode aggregates the local DB across every character and corporation (DAO calls
    // with no character/corporation filter) instead of the selected context. ESI sync is skipped
    // there — it authorizes as one character; each context refreshes its own data when viewed.
    var combined by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        combined = withContext(Dispatchers.IO) { StaticDataDao.getSetting(COMBINE_ALL_SETTING) == "true" }
    }
    var walletBalance by remember { mutableStateOf(0.0) }
    var txBreakdown by remember { mutableStateOf<List<org.eventt.core.model.DailyWalletEntry>>(emptyList()) }
    var fifoResult by remember { mutableStateOf<CostBasisService.FifoResult?>(null) }
    var assetValue by remember { mutableStateOf(0.0) }
    var recentTx by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var topWinners by remember { mutableStateOf<List<ItemPnl>>(emptyList()) }
    var topLosers by remember { mutableStateOf<List<ItemPnl>>(emptyList()) }
    var topSellers by remember { mutableStateOf<List<CounterpartyStat>>(emptyList()) }
    var topBuyers by remember { mutableStateOf<List<CounterpartyStat>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(context, refreshTrigger, combined) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val isCorp = corpId != null
            val since90 = LocalDate.now().minusDays(90).toString()
            val qCharId = if (combined) null else charId
            val qCorpId = if (combined) null else corpId
            try {
                walletBalance =
                    if (combined) {
                        // The unfiltered journal's latest row is one wallet's balance, not a
                        // total — sum each character's and each corporation's own latest balance.
                        val chars = CharacterDao.getAll()
                        val corpIds = chars.mapNotNull { it.corporationId }.distinct()
                        chars.sumOf { WalletDao.getWalletSummary(characterId = it.id).balance } +
                            corpIds.sumOf { WalletDao.getWalletSummary(corporationId = it).balance }
                    } else {
                        WalletDao.getWalletSummary(characterId = charId, corporationId = corpId).balance
                    }
                txBreakdown = WalletDao.getTradingPnlBreakdown(characterId = qCharId, corporationId = qCorpId, since = since90)
                assetValue = if (context != null) AssetDao.getTotalValue(characterId = qCharId, corporationId = qCorpId) else 0.0
                val txWindow = WalletDao.getTransactions(characterId = qCharId, corporationId = qCorpId, limit = 2000)
                recentTx = txWindow.take(12)
                val (sellers, buyers) = computeCounterpartyStats(txWindow)
                topSellers = sellers
                topBuyers = buyers
            } catch (e: Exception) {
                AppLog.warn("Dashboard", e)
            }

            val acting = actingCharId
            if (acting != null && !combined) {
                try {
                    walletBalance =
                        if (isCorp) EsiClient.getCorporationWallet(corpId, acting).values.sum() else EsiClient.getCharacterWallet(acting)
                } catch (e: Exception) {
                    AppLog.warn("Dashboard", "wallet ESI: ${e.message}")
                }

                try {
                    val journalEntries =
                        if (isCorp) {
                            EsiClient.getCorporationJournal(
                                corpId,
                                acting,
                            )
                        } else {
                            EsiClient.getCharacterJournal(acting)
                        }
                    journalEntries.forEach { entry ->
                        try {
                            WalletDao.insertJournalEntry(
                                entryId = (entry["id"] as? Number)?.toLong() ?: 0,
                                date = entry["date"] as? String ?: "",
                                amount = (entry["amount"] as? Number)?.toDouble() ?: 0.0,
                                balance = (entry["balance"] as? Number)?.toDouble() ?: 0.0,
                                reason = entry["reason"] as? String ?: "",
                                refType = entry["ref_type"] as? String ?: "",
                                firstPartyId = (entry["first_party_id"] as? Number)?.toInt() ?: 0,
                                firstPartyName = "",
                                secondPartyId = (entry["second_party_id"] as? Number)?.toInt() ?: 0,
                                secondPartyName = "",
                                taxAmount = (entry["tax"] as? Number)?.toDouble(),
                                isCorp = isCorp,
                                characterId = if (isCorp) null else charId,
                                corporationId = corpId,
                                divisionId = if (isCorp) (entry["division"] as? Number)?.toInt() else null,
                            )
                        } catch (_: Exception) {
                        }
                    }
                    walletBalance = WalletDao.getWalletSummary(characterId = charId, corporationId = corpId).balance
                    txBreakdown = WalletDao.getTradingPnlBreakdown(characterId = charId, corporationId = corpId, since = since90)
                } catch (e: Exception) {
                    AppLog.warn("Dashboard", "journal ESI: ${e.message}")
                }

                try {
                    val txList =
                        if (isCorp) EsiClient.getCorporationTransactions(corpId, acting) else EsiClient.getCharacterTransactions(acting)
                    val typeNames =
                        txList
                            .mapNotNull { (it["type_id"] as? Number)?.toInt() }
                            .toSet()
                            .associateWith { id -> StaticDataDao.getTypeName(id) ?: "" }
                    txList.forEach { tx ->
                        val typeId = (tx["type_id"] as? Number)?.toInt() ?: 0
                        val unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0
                        val quantity = (tx["quantity"] as? Number)?.toInt() ?: 0
                        try {
                            WalletDao.insertTransaction(
                                transactionId = (tx["transaction_id"] as? Number)?.toLong() ?: 0,
                                date = tx["date"] as? String ?: "",
                                typeId = typeId,
                                typeName = typeNames[typeId] ?: "",
                                quantity = quantity,
                                unitPrice = unitPrice,
                                total = unitPrice * quantity,
                                isBuy = (tx["is_buy"] as? Boolean) ?: false,
                                clientId = (tx["client_id"] as? Number)?.toInt() ?: 0,
                                clientName = "",
                                locationId = (tx["location_id"] as? Number)?.toLong() ?: 0L,
                                locationName = "",
                                isCorp = isCorp,
                                characterId = if (isCorp) null else charId,
                                corporationId = corpId,
                            )
                        } catch (_: Exception) {
                        }
                    }
                    val txWindow = WalletDao.getTransactions(characterId = charId, corporationId = corpId, limit = 2000)
                    recentTx = txWindow.take(12)
                    val (sellers, buyers) = computeCounterpartyStats(txWindow)
                    topSellers = sellers
                    topBuyers = buyers
                    txBreakdown = WalletDao.getTradingPnlBreakdown(characterId = charId, corporationId = corpId, since = since90)
                } catch (e: Exception) {
                    AppLog.warn("Dashboard", "transactions ESI: ${e.message}")
                }
            }

            try {
                val taxConfig =
                    if (acting != null) {
                        CostBasisService.TaxConfig(
                            salesTaxPct = StaticDataDao.getCharSalesTax(acting),
                            brokerFeePct = StaticDataDao.getCharBrokersFee(acting),
                        )
                    } else {
                        CostBasisService.TaxConfig()
                    }
                val fifo =
                    CostBasisService.compute(
                        characterId = if (combined) null else charId,
                        corporationId = if (combined) null else corpId,
                        taxConfig = taxConfig,
                    )
                fifoResult = fifo

                // Realized P&L per item over the last 30 days, for the winners/losers lists.
                val since30 = LocalDate.now().minusDays(30).toString()
                val byType =
                    fifo.realizedSells
                        .filter { it.date.take(10) >= since30 }
                        .groupBy { it.typeId }
                        .map { (typeId, sells) -> Triple(typeId, sells.sumOf { it.profit }, sells.sumOf { it.qty }) }

                fun toItemPnl(entry: Triple<Int, Double, Int>) =
                    ItemPnl(
                        typeId = entry.first,
                        name =
                            fifo.inventory[entry.first]?.typeName?.takeIf { it.isNotEmpty() }
                                ?: StaticDataDao.getTypeName(entry.first)
                                ?: "Type ${entry.first}",
                        profit = entry.second,
                        qty = entry.third,
                    )
                topWinners =
                    byType
                        .filter { it.second > 0 }
                        .sortedByDescending { it.second }
                        .take(5)
                        .map(::toItemPnl)
                topLosers =
                    byType
                        .filter { it.second < 0 }
                        .sortedBy { it.second }
                        .take(5)
                        .map(::toItemPnl)
            } catch (e: Exception) {
                AppLog.warn("Dashboard", e)
            }
        }
        isLoading = false
    }

    if (context == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No character selected", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        return
    }

    val pnlWindow = txBreakdown.toPnlWindow()
    // Wallet in minus wallet out — cash flow, deliberately NOT labeled P&L anywhere in the UI:
    // buying stock shows up as a huge negative here even when the trade will be profitable.
    // Real profit is the Realized (FIFO cost-basis) section below.
    val todayCashFlow = pnlWindow.todayNet
    val week7CashFlow = pnlWindow.net7d
    val month30CashFlow = pnlWindow.net30d
    val income30d = pnlWindow.income30d
    val spend30d = pnlWindow.expenses30d

    val fifoWindow = fifoResult?.realizedPnlWindow()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        combined -> "Dashboard — All characters & corps"
                        corpName != null -> "Dashboard — $corpName"
                        else -> "Dashboard"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "Combine all",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Switch(
                        checked = combined,
                        onCheckedChange = {
                            combined = it
                            scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(COMBINE_ALL_SETTING, it.toString()) }
                        },
                        modifier = Modifier.height(24.dp),
                    )
                }
            }
        }

        // Top KPI row
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KpiCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.AccountBalance,
                    label = "Wallet Balance",
                    value = formatIsk(walletBalance),
                    color = ACCENT,
                )
                KpiCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Storage,
                    label = "Asset Value",
                    value = formatIsk(assetValue),
                    color = Color(0xFF66D9E8),
                )
                KpiCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    label = "Cash Flow 30d",
                    value = formatIsk(month30CashFlow, showSign = true),
                    color = if (month30CashFlow >= 0) positiveColor else negativeColor,
                    valueColor = if (month30CashFlow >= 0) positiveColor else negativeColor,
                )
            }
        }

        // P&L mini cards — cash flow (gross buy/sell + tax/fees actually paid, from the wallet)
        item {
            Text(
                "Cash Flow",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PnlMiniCard(modifier = Modifier.weight(1f), label = "Cash Flow Today", value = todayCashFlow, showSign = true)
                PnlMiniCard(modifier = Modifier.weight(1f), label = "Cash Flow 7 days", value = week7CashFlow, showSign = true)
                PnlMiniCard(modifier = Modifier.weight(1f), label = "Income 30d", value = income30d, forceColor = positiveColor)
                PnlMiniCard(modifier = Modifier.weight(1f), label = "Expenses 30d", value = spend30d, forceColor = negativeColor)
            }
        }

        // P&L mini cards — FIFO cost-basis (profit only counted once a lot is actually sold)
        if (fifoWindow != null) {
            item {
                Text(
                    "Realized P&L (FIFO cost-basis)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PnlMiniCard(modifier = Modifier.weight(1f), label = "Realized Today", value = fifoWindow.todayPnl, showSign = true)
                    PnlMiniCard(modifier = Modifier.weight(1f), label = "Realized 7 days", value = fifoWindow.pnl7d, showSign = true)
                    PnlMiniCard(modifier = Modifier.weight(1f), label = "Realized 30 days", value = fifoWindow.pnl30d, showSign = true)
                    PnlMiniCard(modifier = Modifier.weight(1f), label = "Realized All-time", value = fifoWindow.pnlAll, showSign = true)
                }
            }
            // Daily realized-P&L bars — profit per calendar day from the same FIFO matches as
            // the cards above (each sale's profit lands on its sell date, not its buy date).
            item {
                val dailyRealized =
                    remember(fifoResult) {
                        fifoResult
                            ?.realizedSells
                            .orEmpty()
                            .groupBy { it.date.substring(0, 10) }
                            .mapValues { (_, sells) -> sells.sumOf { it.profit } }
                    }
                PnlBarChart(
                    dailyValues = dailyRealized,
                    title = "Daily Realized P&L — 30d",
                    emptyHint = "No realized sales in the last 30 days",
                )
            }
        }

        // Transactions + Alerts
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionHeader("Recent Transactions", count = recentTx.size.takeIf { it > 0 })
                        Spacer(Modifier.height(8.dp))
                        if (recentTx.isEmpty()) {
                            EmptyHint("No transactions recorded")
                        } else {
                            recentTx.forEachIndexed { i, tx ->
                                TransactionRow(tx)
                                if (i < recentTx.lastIndex) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    )
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionHeader("Top Items — Realized 30d")
                        Spacer(Modifier.height(8.dp))
                        if (topWinners.isEmpty() && topLosers.isEmpty()) {
                            EmptyHint("No realized sells in the last 30 days")
                        } else {
                            topWinners.forEach { ItemPnlRow(it) }
                            if (topWinners.isNotEmpty() && topLosers.isNotEmpty()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                )
                            }
                            topLosers.forEach { ItemPnlRow(it) }
                        }
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionHeader("Top Buyers & Sellers")
                        Spacer(Modifier.height(8.dp))
                        if (topSellers.isEmpty() && topBuyers.isEmpty()) {
                            EmptyHint("No transactions recorded")
                        } else {
                            Text(
                                "Sellers",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                            if (topSellers.isEmpty()) {
                                EmptyHint("No purchases recorded")
                            } else {
                                topSellers.forEach { CounterpartyRow(it) }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            )
                            Text(
                                "Buyers",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                            if (topBuyers.isEmpty()) {
                                EmptyHint("No sales recorded")
                            } else {
                                topBuyers.forEach { CounterpartyRow(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Sub-composables ──────────────────────────────────────────────────────

@Composable
private fun SectionHeader(
    title: String,
    count: Int? = null,
    badge: Int? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        when {
            badge != null -> {
                Badge { Text("$badge") }
            }

            count != null -> {
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    )
}

@Composable
private fun KpiCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    color: Color,
    valueColor: Color = Color.Unspecified,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Surface(
                    color = color.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxSize(),
                ) {}
                Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            }
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Text(
                    value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor,
                )
            }
        }
    }
}

@Composable
private fun PnlMiniCard(
    modifier: Modifier = Modifier,
    label: String,
    value: Double,
    showSign: Boolean = false,
    forceColor: Color? = null,
) {
    val color =
        forceColor ?: when {
            value > 0 -> positiveColor
            value < 0 -> negativeColor
            else -> Color.Gray
        }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                formatIsk(value, showSign = showSign),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

// Diverging daily bars around a zero baseline. Scale is asymmetric (top = best day,
// bottom = worst day) so an all-profit month uses the full height instead of half of it.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PnlBarChart(
    dailyValues: Map<String, Double>,
    title: String,
    emptyHint: String,
) {
    // Zero-fill the last 30 calendar days — the data is sparse (active days only),
    // and missing bars would silently compress time.
    val days =
        remember(dailyValues) {
            val today = LocalDate.now()
            (29 downTo 0).map { off ->
                val d = today.minusDays(off.toLong()).toString()
                d to (dailyValues[d] ?: 0.0)
            }
        }
    var hovered by remember { mutableStateOf<Int?>(null) }
    val positive = positiveColor
    val negative = negativeColor

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val h = hovered
                Text(
                    if (h != null) "${days[h].first}   ${formatIsk(days[h].second, showSign = true)}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        when {
                            h == null -> Color.Unspecified
                            days[h].second >= 0 -> positive
                            else -> negative
                        },
                )
            }
            Spacer(Modifier.height(8.dp))
            if (days.all { it.second == 0.0 }) {
                EmptyHint(emptyHint)
            } else {
                val baseline = MaterialTheme.colorScheme.outlineVariant
                val maxPos = days.maxOf { it.second }.coerceAtLeast(0.0)
                val maxNeg = -days.minOf { it.second }.coerceAtLeast(0.0)
                val range = (maxPos + maxNeg).takeIf { it > 0 } ?: 1.0
                Canvas(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .onPointerEvent(PointerEventType.Move) { event ->
                                val x =
                                    event.changes
                                        .first()
                                        .position.x
                                hovered = (x / (size.width / days.size.toFloat())).toInt().coerceIn(0, days.lastIndex)
                            }.onPointerEvent(PointerEventType.Exit) { hovered = null },
                ) {
                    val slot = size.width / days.size
                    val barWidth = (slot - 2f).coerceAtLeast(1f)
                    val zeroY = (size.height * (maxPos / range)).toFloat()
                    days.forEachIndexed { i, (_, net) ->
                        if (net == 0.0) return@forEachIndexed
                        val h = (kotlin.math.abs(net) / range * size.height).toFloat()
                        drawRoundRect(
                            color = (if (net > 0) positive else negative).copy(alpha = if (hovered == i) 1f else 0.75f),
                            topLeft = Offset(i * slot + 1f, if (net > 0) zeroY - h else zeroY),
                            size = Size(barWidth, h),
                            cornerRadius = CornerRadius(2f, 2f),
                        )
                    }
                    drawLine(baseline, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1f)
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(tx: Map<String, Any?>) {
    val isBuy = tx["is_buy"] as? Boolean ?: false
    val typeName = tx["type_name"] as? String ?: "Unknown"
    val qty = (tx["quantity"] as? Number)?.toInt() ?: 0
    val unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0
    val total = (tx["total"] as? Number)?.toDouble() ?: 0.0
    val date = (tx["date"] as? String)?.take(10) ?: ""
    val color = if (isBuy) negativeColor else positiveColor

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.extraSmall) {
            Text(
                if (isBuy) "BUY" else "SELL",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(typeName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${qty}x @ ${formatIsk(unitPrice)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatIsk(total),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = color,
            )
            Text(
                date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

// One item's realized FIFO P&L over the dashboard's 30-day window.
private data class ItemPnl(
    val typeId: Int,
    val name: String,
    val profit: Double,
    val qty: Int,
)

// A trading counterparty ranked by ISK moved. A transaction's client is whoever was on the other
// side of it: is_buy=true means we bought, so the client sold to us (a seller); is_buy=false means
// we sold, so the client bought from us (a buyer).
private data class CounterpartyStat(
    val name: String,
    val total: Double,
    val count: Int,
)

private fun txTotal(tx: Map<String, Any?>): Double {
    val unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0
    val quantity = (tx["quantity"] as? Number)?.toInt() ?: 0
    return (tx["total"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: (unitPrice * quantity)
}

// Top 5 sellers (transactions where we bought) and top 5 buyers (where we sold), ranked by total
// ISK traded with that client over whatever transaction window is passed in.
private fun computeCounterpartyStats(transactions: List<Map<String, Any?>>): Pair<List<CounterpartyStat>, List<CounterpartyStat>> {
    fun rank(isBuy: Boolean) =
        transactions
            .filter { (it["is_buy"] as? Boolean) == isBuy }
            .groupBy { (it["client_name"] as? String)?.ifEmpty { null } ?: "Unknown" }
            .map { (name, txs) -> CounterpartyStat(name, txs.sumOf(::txTotal), txs.size) }
            .sortedByDescending { it.total }
            .take(5)
    return rank(isBuy = true) to rank(isBuy = false)
}

@Composable
private fun ItemPnlRow(item: ItemPnl) {
    val color = if (item.profit >= 0) positiveColor else negativeColor
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${item.qty} sold",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        Text(
            formatIsk(item.profit, showSign = true),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

@Composable
private fun CounterpartyRow(stat: CounterpartyStat) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stat.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${stat.count} trade${if (stat.count == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        Text(
            formatIsk(stat.total),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ─── Formatting ───────────────────────────────────────────────────────────

private fun formatIsk(
    value: Double,
    showSign: Boolean = false,
): String =
    (if (showSign && value > 0) "+" else "") +
        org.eventt.ui.common
            .formatIsk(value)
