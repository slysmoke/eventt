package org.eventt.features.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.WalletDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.DailyWalletEntry
import org.eventt.ui.common.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun WalletScreen(charId: Int?) {
    val scope = rememberCoroutineScope()
    var balance by remember { mutableStateOf(0.0) }
    var dailyBreakdown by remember { mutableStateOf<List<DailyWalletEntry>>(emptyList()) }
    var transactions by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var journal by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var refreshAvailableAt by remember { mutableStateOf<Long?>(null) }
    var activeTab by remember { mutableStateOf(0) }

    LaunchedEffect(charId) {
        if (charId != null) {
            isLoading = true
            loadWalletData(
                charId,
                balanceCallback = { balance = it },
                dailyCallback = { dailyBreakdown = it },
                transactionsCallback = { transactions = it },
                journalCallback = { journal = it },
                expiryCallback = { refreshAvailableAt = it },
            )
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Wallet", style = MaterialTheme.typography.headlineMedium)
            charId?.let { id ->
                EsiRefreshButton(
                    isLoading = isLoading,
                    expiresAtMs = refreshAvailableAt,
                    onClick = {
                        scope.launch {
                            isLoading = true
                            loadWalletData(
                                id,
                                balanceCallback = { balance = it },
                                dailyCallback = { dailyBreakdown = it },
                                transactionsCallback = { transactions = it },
                                journalCallback = { journal = it },
                                expiryCallback = { refreshAvailableAt = it },
                            )
                            isLoading = false
                        }
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Balance card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Balance", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    text = "${formatIsk(balance)} ISK",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val totalEarned = dailyBreakdown.sumOf { it.income }
                    val totalSpent = dailyBreakdown.sumOf { it.expenses }
                    Text("Earned: ${formatIsk(totalEarned)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF69DB7C))
                    Text("Spent: ${formatIsk(totalSpent)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF6B6B))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tabs
        TabRow(selectedTabIndex = activeTab) {
            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text("Transactions") })
            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }, text = { Text("Journal") })
            Tab(selected = activeTab == 2, onClick = { activeTab = 2 }, text = { Text("P&L") })
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tab content
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                0 -> TransactionList(transactions)
                1 -> JournalList(journal)
                2 -> PnlChart(dailyBreakdown)
            }
        }
    }

    LoadingOverlay(isLoading = isLoading, message = "Loading wallet data...")
}

@Composable
private fun TransactionList(transactions: List<Map<String, Any?>>) {
    if (transactions.isEmpty()) {
        EmptyState(icon = Icons.Default.Receipt, title = "No Transactions", description = "Select a character to load transactions.")
        return
    }

    Column {
        // Header
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TxHeader("Date", Modifier.weight(1.8f))
            TxHeader("B/S", Modifier.weight(0.6f))
            TxHeader("Item", Modifier.weight(3f))
            TxHeader("Qty", Modifier.weight(1f), rightAlign = true)
            TxHeader("Unit Price", Modifier.weight(2f), rightAlign = true)
            TxHeader("Total", Modifier.weight(2f), rightAlign = true)
            TxHeader("Client", Modifier.weight(2f))
            TxHeader("Station", Modifier.weight(2.5f))
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(transactions) { tx ->
                val isBuy = tx["is_buy"] as? Boolean ?: false
                val unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0
                val quantity = (tx["quantity"] as? Number)?.toInt() ?: 0
                val total =
                    (tx["total"] as? Number)
                        ?.toDouble()
                        ?.takeIf { it > 0 } ?: (unitPrice * quantity)
                val typeName =
                    tx["type_name"]?.toString()?.ifEmpty { null }
                        ?: "Unknown (${tx["type_id"]})"
                val clientName =
                    tx["client_name"]?.toString()?.ifEmpty { null }
                        ?: tx["client_id"]?.let { "#$it" } ?: ""
                val locationName =
                    tx["location_name"]?.toString()?.ifEmpty { null }
                        ?: tx["location_id"]?.toString() ?: ""
                val dateStr = tx["date"]?.toString()?.take(16)?.replace("T", " ") ?: ""
                val buyColor = Color(0xFF69DB7C)
                val sellColor = Color(0xFFFF6B6B)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        dateStr,
                        modifier = Modifier.weight(1.8f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        modifier = Modifier.weight(0.6f),
                        color = (if (isBuy) buyColor else sellColor).copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            if (isBuy) "Buy" else "Sell",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isBuy) buyColor else sellColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        typeName,
                        modifier = Modifier.weight(3f).padding(start = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                    Text(
                        "%,d".format(quantity),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        formatIsk(unitPrice),
                        modifier = Modifier.weight(2f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        formatIsk(total),
                        modifier = Modifier.weight(2f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (isBuy) sellColor else buyColor,
                    )
                    Text(
                        clientName,
                        modifier = Modifier.weight(2f).padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        locationName,
                        modifier = Modifier.weight(2.5f).padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun TxHeader(
    label: String,
    modifier: Modifier,
    rightAlign: Boolean = false,
) {
    Text(
        label,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (rightAlign) TextAlign.End else TextAlign.Start,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun JournalList(journal: List<Map<String, Any?>>) {
    if (journal.isEmpty()) {
        EmptyState(icon = Icons.AutoMirrored.Filled.List, title = "No Journal Entries", description = "Select a character to load journal.")
        return
    }

    val totalTax = journal.filter { it["ref_type"] == "transaction_tax" }.sumOf { (it["amount"] as? Number)?.toDouble() ?: 0.0 }
    val totalBroker = journal.filter { it["ref_type"] == "brokers_fee" }.sumOf { (it["amount"] as? Number)?.toDouble() ?: 0.0 }

    Column {
        // Tax summary
        if (totalTax != 0.0 || totalBroker != 0.0) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "Taxes paid (shown entries):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    if (totalTax != 0.0) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Sales Tax",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                formatIsk(totalTax),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF6B6B),
                            )
                        }
                    }
                    if (totalBroker != 0.0) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Broker's Fee",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                formatIsk(totalBroker),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF6B6B),
                            )
                        }
                    }
                    if (totalTax != 0.0 && totalBroker != 0.0) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                formatIsk(totalTax + totalBroker),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF6B6B),
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Table header
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TxHeader("Date", Modifier.weight(1.8f))
            TxHeader("Type", Modifier.weight(2.5f))
            TxHeader("Description", Modifier.weight(3f))
            TxHeader("Amount", Modifier.weight(2f), rightAlign = true)
            TxHeader("Tax", Modifier.weight(1.5f), rightAlign = true)
            TxHeader("Balance", Modifier.weight(2f), rightAlign = true)
        }
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(journal) { entry ->
                val amount = (entry["amount"] as? Number)?.toDouble() ?: 0.0
                val taxAmount = (entry["tax_amount"] as? Number)?.toDouble()
                val balance = (entry["balance"] as? Number)?.toDouble() ?: 0.0
                val refType = entry["ref_type"]?.toString() ?: ""
                val reason = entry["reason"]?.toString()?.trim() ?: ""
                val dateStr = entry["date"]?.toString()?.take(16)?.replace("T", " ") ?: ""
                val amountColor = if (amount >= 0) Color(0xFF69DB7C) else Color(0xFFFF6B6B)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        dateStr,
                        modifier = Modifier.weight(1.8f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatRefType(refType),
                        modifier = Modifier.weight(2.5f),
                        style = MaterialTheme.typography.bodySmall,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                    Text(
                        reason,
                        modifier = Modifier.weight(3f).padding(start = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                    Text(
                        "${if (amount >= 0) "+" else ""}${formatIsk(amount)}",
                        modifier = Modifier.weight(2f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = amountColor,
                    )
                    Text(
                        if (taxAmount != null && taxAmount != 0.0) formatIsk(taxAmount) else "—",
                        modifier = Modifier.weight(1.5f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (taxAmount != null &&
                                taxAmount != 0.0
                            ) {
                                Color(0xFFFF6B6B)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Text(
                        formatIsk(balance),
                        modifier = Modifier.weight(2f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

private fun formatRefType(refType: String): String =
    when (refType) {
        "transaction_tax" -> "Sales Tax"
        "brokers_fee" -> "Broker's Fee"
        "market_transaction" -> "Market Transaction"
        "market_escrow" -> "Buy Order Escrow"
        "market_escrow_refund" -> "Escrow Refund"
        "player_trading" -> "Trade"
        "contract_price" -> "Contract"
        "contract_reward" -> "Contract Reward"
        "contract_deposit" -> "Contract Deposit"
        "contract_deposit_refund" -> "Contract Deposit Refund"
        "contract_price_payment_corp" -> "Corp Contract"
        "bounty_prizes" -> "Bounty"
        "industry_job_tax" -> "Industry Tax"
        "manufacturing" -> "Manufacturing"
        "reprocessing_tax" -> "Reprocessing Tax"
        "jump_clone_installation_fee" -> "Clone Jump Fee"
        "planetary_import_tax" -> "PI Import Tax"
        "planetary_export_tax" -> "PI Export Tax"
        "corporation_account_withdrawal" -> "Corp Withdrawal"
        "corporation_dividend_payment" -> "Dividend"
        "structure_gate_jump" -> "Jump Gate"
        "asset_safety_recovery_tax" -> "Asset Safety Tax"
        "skill_purchase" -> "Skill Purchase"
        "agent_mission_reward" -> "Mission Reward"
        "agent_mission_time_bonus_reward" -> "Mission Bonus"
        else ->
            refType
                .replace('_', ' ')
                .split(' ')
                .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }

private val PNL_POSITIVE = Color(0xFF69DB7C)
private val PNL_NEGATIVE = Color(0xFFFF6B6B)

private fun pnlSignedText(value: Double): String = "${if (value >= 0) "+" else "-"}${formatIsk(abs(value))}"

@Composable
private fun pnlColor(value: Double): Color =
    when {
        value > 0 -> PNL_POSITIVE
        value < 0 -> PNL_NEGATIVE
        else -> MaterialTheme.colorScheme.onSurface
    }

@Composable
private fun PnlChart(dailyBreakdown: List<DailyWalletEntry>) {
    if (dailyBreakdown.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.ShowChart,
            title = "No P&L Data",
            description = "Need journal entries to calculate P&L.",
        )
        return
    }

    // dailyBreakdown arrives newest-first; chronological order is needed for period sums & the chart.
    val chronological = dailyBreakdown.reversed()
    val last7 = chronological.takeLast(7)
    val last30 = chronological.takeLast(30)

    val todayNet = chronological.lastOrNull()?.net ?: 0.0
    val net7 = last7.sumOf { it.net }
    val net30 = last30.sumOf { it.net }
    val netAll = chronological.sumOf { it.net }
    val incomeAll = chronological.sumOf { it.income }
    val expensesAll = chronological.sumOf { it.expenses }
    val profitableDays = chronological.count { it.net > 0 }
    val margin = if (incomeAll > 0) netAll / incomeAll * 100 else 0.0

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PnlStatCard("Today", pnlSignedText(todayNet), pnlColor(todayNet), Modifier.weight(1f))
            PnlStatCard("7 Days", pnlSignedText(net7), pnlColor(net7), Modifier.weight(1f))
            PnlStatCard("30 Days", pnlSignedText(net30), pnlColor(net30), Modifier.weight(1f))
            PnlStatCard("${chronological.size}d Total", pnlSignedText(netAll), pnlColor(netAll), Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PnlStatCard("Income", "+${formatIsk(incomeAll)}", PNL_POSITIVE, Modifier.weight(1f))
            PnlStatCard("Expenses", "-${formatIsk(expensesAll)}", PNL_NEGATIVE, Modifier.weight(1f))
            PnlStatCard("Margin", "%.1f%%".format(margin), pnlColor(margin), Modifier.weight(1f))
            PnlStatCard(
                "Profitable Days",
                "$profitableDays / ${chronological.size}",
                MaterialTheme.colorScheme.onSurface,
                Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        ContentCard("Daily Net P&L") {
            PnlBarChart(
                data = chronological.map { it.net },
                dates = chronological.map { it.date },
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        ContentCard("Daily Breakdown") {
            PnlTable(chronological.reversed())
        }
    }
}

@Composable
private fun PnlStatCard(
    label: String,
    valueText: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(valueText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun PnlTable(entries: List<DailyWalletEntry>) {
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TxHeader("Date", Modifier.weight(2f))
            TxHeader("Income", Modifier.weight(1.5f), rightAlign = true)
            TxHeader("Expenses", Modifier.weight(1.5f), rightAlign = true)
            TxHeader("Net", Modifier.weight(1.5f), rightAlign = true)
        }
        HorizontalDivider()
        entries.forEach { entry ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.date, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                Text(
                    "+${formatIsk(entry.income)}",
                    modifier = Modifier.weight(1.5f),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall,
                    color = PNL_POSITIVE,
                )
                Text(
                    "-${formatIsk(entry.expenses)}",
                    modifier = Modifier.weight(1.5f),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall,
                    color = PNL_NEGATIVE,
                )
                Text(
                    pnlSignedText(entry.net),
                    modifier = Modifier.weight(1.5f),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = pnlColor(entry.net),
                )
            }
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

@Composable
private fun PnlBarChart(
    data: List<Double>,
    dates: List<String>,
    modifier: Modifier = Modifier,
) {
    if (data.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val gridColor = Color(0x14FFFFFF)
    val labelColor = Color(0xFF777777)
    val maxAbs = remember(data) { (data.maxOfOrNull { abs(it) } ?: 0.0).coerceAtLeast(1.0) }
    var hoverIdx by remember { mutableStateOf<Int?>(null) }

    Canvas(
        modifier =
            modifier.pointerInput(data) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Exit) {
                            hoverIdx = null
                            continue
                        }
                        if (event.type != PointerEventType.Move) continue
                        val posX =
                            event.changes
                                .firstOrNull()
                                ?.position
                                ?.x ?: continue
                        val lPad = 56.dp.toPx()
                        val chartW = size.width - lPad - 8.dp.toPx()
                        hoverIdx =
                            if (posX >= lPad && data.isNotEmpty()) {
                                ((posX - lPad) / chartW * data.size).toInt().coerceIn(0, data.size - 1)
                            } else {
                                null
                            }
                    }
                }
            },
    ) {
        val lPad = 56.dp.toPx()
        val rPad = 8.dp.toPx()
        val tPad = 10.dp.toPx()
        val bPad = 18.dp.toPx()
        val chartW = size.width - lPad - rPad
        val chartH = size.height - tPad - bPad
        if (chartW <= 0 || chartH <= 0) return@Canvas
        val centerY = tPad + chartH / 2
        val barW = (chartW / data.size - 1f).coerceAtLeast(1f)

        fun barX(i: Int) = lPad + i.toFloat() / data.size * chartW

        fun barHeightOf(v: Double) = (abs(v) / maxAbs * (chartH / 2)).toFloat().coerceAtLeast(1f)

        // Zero line
        drawLine(gridColor.copy(alpha = 0.6f), Offset(lPad, centerY), Offset(lPad + chartW, centerY), 1f)

        // Y labels: max, 0, -max
        val lmMax = textMeasurer.measure(formatIsk(maxAbs), TextStyle(fontSize = 10.sp, color = labelColor))
        drawText(lmMax, topLeft = Offset(lPad - lmMax.size.width - 5.dp.toPx(), tPad - lmMax.size.height / 2f))
        val lmZero = textMeasurer.measure("0", TextStyle(fontSize = 10.sp, color = labelColor))
        drawText(lmZero, topLeft = Offset(lPad - lmZero.size.width - 5.dp.toPx(), centerY - lmZero.size.height / 2f))
        val lmMin = textMeasurer.measure("-${formatIsk(maxAbs)}", TextStyle(fontSize = 10.sp, color = labelColor))
        drawText(lmMin, topLeft = Offset(lPad - lmMin.size.width - 5.dp.toPx(), tPad + chartH - lmMin.size.height / 2f))

        // Bars
        data.forEachIndexed { i, v ->
            val barHeight = barHeightOf(v)
            val isPositive = v >= 0
            val y = if (isPositive) centerY - barHeight else centerY
            val color = if (isPositive) PNL_POSITIVE else PNL_NEGATIVE
            drawRect(
                if (i == hoverIdx) color else color.copy(alpha = 0.75f),
                Offset(barX(i) + 0.5f, y),
                Size(barW, barHeight),
            )
        }

        // X-axis date labels (up to 6)
        val xN = minOf(6, data.size)
        repeat(xN) { t ->
            val i = if (xN == 1) 0 else (t.toFloat() / (xN - 1) * (data.size - 1)).roundToInt().coerceIn(0, data.size - 1)
            val x = barX(i) + barW / 2
            val lm = textMeasurer.measure(dates.getOrElse(i) { "" }, TextStyle(fontSize = 9.sp, color = labelColor))
            drawText(
                lm,
                topLeft =
                    Offset(
                        (x - lm.size.width / 2f).coerceIn(lPad, maxOf(lPad, lPad + chartW - lm.size.width)),
                        size.height - bPad + 4.dp.toPx(),
                    ),
            )
        }

        // Hover tooltip
        hoverIdx?.let { idx ->
            val cx = barX(idx) + barW / 2
            val v = data[idx]
            val isPositive = v >= 0
            val color = if (isPositive) PNL_POSITIVE else PNL_NEGATIVE
            val barHeight = barHeightOf(v)
            val y = if (isPositive) centerY - barHeight else centerY + barHeight

            val lm1 = textMeasurer.measure(dates.getOrElse(idx) { "" }, TextStyle(fontSize = 10.sp, color = Color(0xFF999999)))
            val lm2 = textMeasurer.measure(pnlSignedText(v), TextStyle(fontSize = 12.sp, color = color, fontWeight = FontWeight.SemiBold))
            val pad = 7.dp.toPx()
            val gap2 = 2.dp.toPx()
            val ttW = maxOf(lm1.size.width, lm2.size.width) + pad * 2
            val ttH = lm1.size.height + lm2.size.height + pad * 2 + gap2

            var ttX = cx + 8.dp.toPx()
            if (ttX + ttW > lPad + chartW) ttX = cx - ttW - 8.dp.toPx()
            val ttY = (y - ttH - 8.dp.toPx()).coerceIn(tPad, maxOf(tPad, tPad + chartH - ttH))

            drawRoundRect(Color(0xEE0D1117), Offset(ttX, ttY), Size(ttW, ttH), CornerRadius(4.dp.toPx()))
            drawText(lm1, topLeft = Offset(ttX + pad, ttY + pad))
            drawText(lm2, topLeft = Offset(ttX + pad, ttY + pad + lm1.size.height + gap2))
        }
    }
}

private suspend fun loadWalletData(
    characterId: Int,
    balanceCallback: (Double) -> Unit,
    dailyCallback: (List<DailyWalletEntry>) -> Unit,
    transactionsCallback: (List<Map<String, Any?>>) -> Unit,
    journalCallback: (List<Map<String, Any?>>) -> Unit,
    expiryCallback: (Long?) -> Unit = {},
) {
    withContext(Dispatchers.IO) {
        // Load from DB, resolve names (local + ESI fallback for old records)
        val summary = WalletDao.getWalletSummary(characterId = characterId)
        balanceCallback(summary.balance)
        dailyCallback(summary.dailyBreakdown)
        transactionsCallback(resolveAllNames(WalletDao.getTransactions(characterId = characterId, limit = 200)))
        journalCallback(WalletDao.getJournalEntries(characterId = characterId))

        // Fetch from ESI
        try {
            val walletBalance = EsiClient.getCharacterWallet(characterId)
            balanceCallback(walletBalance)
        } catch (e: Exception) {
            println("Error fetching wallet: ${e.message}")
        }

        try {
            val journalEntries = EsiClient.getCharacterJournal(characterId)
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
                        isCorp = false,
                        characterId = characterId,
                        corporationId = null,
                        divisionId = null,
                    )
                } catch (e: Exception) {
                    // skip duplicates or bad entries
                }
            }
            journalCallback(WalletDao.getJournalEntries(characterId = characterId))
        } catch (e: Exception) {
            println("Error fetching journal: ${e.message}")
        }

        try {
            val txList = EsiClient.getCharacterTransactions(characterId)

            // Batch-resolve names before inserting
            val typeIds = txList.mapNotNull { (it["type_id"] as? Number)?.toInt() }.toSet()
            val typeNames = typeIds.associateWith { id -> StaticDataDao.getTypeName(id) ?: "" }

            val locationIds = txList.mapNotNull { (it["location_id"] as? Number)?.toLong() }.toSet()
            val locationNames = locationIds.associateWith { id -> StaticDataDao.getStationById(id)?.name ?: "" }

            val clientIds = txList.mapNotNull { (it["client_id"] as? Number)?.toInt() }.filter { it > 0 }.toSet()
            val clientNames = if (clientIds.isNotEmpty()) EsiClient.resolveNames(clientIds.toList()) else emptyMap()

            txList.forEach { tx ->
                val typeId = (tx["type_id"] as? Number)?.toInt() ?: 0
                val locationId = (tx["location_id"] as? Number)?.toLong() ?: 0L
                val clientId = (tx["client_id"] as? Number)?.toInt() ?: 0
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
                        clientId = clientId,
                        clientName = clientNames[clientId] ?: "",
                        locationId = locationId,
                        locationName = locationNames[locationId] ?: "",
                        isCorp = false,
                        characterId = characterId,
                        corporationId = null,
                    )
                } catch (_: Exception) {
                }
            }
            transactionsCallback(resolveAllNames(WalletDao.getTransactions(characterId = characterId, limit = 200)))
        } catch (e: Exception) {
            println("Error fetching transactions: ${e.message}")
        }

        val txExpiry = EsiClient.getEndpointExpiry("/characters/$characterId/wallet/transactions/")
        val journalExpiry = EsiClient.getEndpointExpiry("/characters/$characterId/wallet/journal/")
        val expiry = listOfNotNull(txExpiry, journalExpiry).maxOrNull()
        expiryCallback(expiry)
    }
}

// Resolves type/station names locally, then falls back to ESI /universe/names/ for anything
// still missing (old DB records inserted before name-resolution was in place).
// Persists resolved names back to the DB so subsequent loads don't need ESI.
private fun resolveAllNames(rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
    val afterLocal = resolveLocalNames(rows)

    // Collect IDs that are still unresolved
    val missingClientIds =
        afterLocal
            .filter { (it["client_name"] as? String).isNullOrEmpty() }
            .mapNotNull { (it["client_id"] as? Number)?.toInt() }
            .filter { it > 0 }
            .distinct()

    // Only NPC station IDs fit in Int; citadels (> 10^12) must come from static_stations
    val missingLocationIds =
        afterLocal
            .filter { (it["location_name"] as? String).isNullOrEmpty() }
            .mapNotNull { tx ->
                val id = (tx["location_id"] as? Number)?.toLong() ?: return@mapNotNull null
                if (id > Int.MAX_VALUE.toLong()) null else id.toInt()
            }.filter { it > 0 }
            .distinct()

    val toResolve = (missingClientIds + missingLocationIds).distinct()
    if (toResolve.isEmpty()) return afterLocal

    val esiNames =
        try {
            EsiClient.resolveNames(toResolve)
        } catch (_: Exception) {
            emptyMap()
        }
    if (esiNames.isEmpty()) return afterLocal

    val clientSet = missingClientIds.toSet()
    val locationSet = missingLocationIds.toSet()

    return afterLocal.map { tx ->
        val txId = (tx["transaction_id"] as? Number)?.toLong() ?: return@map tx

        val newClient =
            if ((tx["client_name"] as? String).isNullOrEmpty()) {
                val id = (tx["client_id"] as? Number)?.toInt() ?: 0
                if (id in clientSet) esiNames[id] else null
            } else {
                null
            }

        val newLocation =
            if ((tx["location_name"] as? String).isNullOrEmpty()) {
                val lid = (tx["location_id"] as? Number)?.toLong() ?: 0L
                if (lid <= Int.MAX_VALUE && lid.toInt() in locationSet) esiNames[lid.toInt()] else null
            } else {
                null
            }

        if (newClient == null && newLocation == null) return@map tx

        // Persist so next load skips ESI
        WalletDao.updateTransactionNames(txId, clientName = newClient, locationName = newLocation)

        tx.toMutableMap().apply {
            newClient?.let { put("client_name", it) }
            newLocation?.let { put("location_name", it) }
        }
    }
}

private fun resolveLocalNames(rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
    return rows.map { tx ->
        val needsType = (tx["type_name"] as? String).isNullOrEmpty()
        val needsLocation = (tx["location_name"] as? String).isNullOrEmpty()
        if (!needsType && !needsLocation) return@map tx
        val updated = tx.toMutableMap()
        if (needsType) {
            val typeId = (tx["type_id"] as? Number)?.toInt() ?: 0
            updated["type_name"] = StaticDataDao.getTypeName(typeId) ?: ""
        }
        if (needsLocation) {
            val locationId = (tx["location_id"] as? Number)?.toLong() ?: 0L
            updated["location_name"] = StaticDataDao.getStationById(locationId)?.name ?: ""
        }
        updated
    }
}

private fun formatIsk(value: Double): String =
    when {
        kotlin.math.abs(value) >= 1_000_000_000_000 -> String.format(Locale.US, "%.2fT", value / 1_000_000_000_000)
        kotlin.math.abs(value) >= 1_000_000_000 -> String.format(Locale.US, "%.2fB", value / 1_000_000_000)
        kotlin.math.abs(value) >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000)
        kotlin.math.abs(value) >= 1_000 -> String.format(Locale.US, "%.2fK", value / 1_000)
        else -> String.format(Locale.US, "%,.2f", value)
    }
