package org.eventt.features.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import org.eventt.core.database.ViewContext
import org.eventt.core.database.WalletDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.DailyWalletEntry
import org.eventt.core.model.stringBlocking
import org.eventt.core.model.toPnlWindow
import org.eventt.features.orders.CostBasisService
import org.eventt.features.orders.realizedPnlWindow
import org.eventt.ui.common.*
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.wallet.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun WalletScreen(context: ViewContext?) {
    val scope = rememberCoroutineScope()
    val charId = (context as? ViewContext.Character)?.charId
    val corpId = (context as? ViewContext.Corporation)?.corporationId
    val actingCharId = context?.actingCharId
    var balance by remember { mutableStateOf(0.0) }
    var dailyBreakdown by remember { mutableStateOf<List<DailyWalletEntry>>(emptyList()) }
    var pnlBreakdown by remember { mutableStateOf<List<DailyWalletEntry>>(emptyList()) }
    var fifoResult by remember { mutableStateOf<CostBasisService.FifoResult?>(null) }
    var transactions by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var journal by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var refreshAvailableAt by remember { mutableStateOf<Long?>(null) }
    var activeTab by remember { mutableStateOf(0) }

    LaunchedEffect(context) {
        val acting = actingCharId
        if (acting != null) {
            isLoading = true
            loadWalletData(
                charId,
                corpId,
                acting,
                balanceCallback = { balance = it },
                dailyCallback = { dailyBreakdown = it },
                pnlCallback = { pnlBreakdown = it },
                fifoCallback = { fifoResult = it },
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
            Text(stringResource(Res.string.wallet), style = MaterialTheme.typography.headlineMedium)
            actingCharId?.let { acting ->
                EsiRefreshButton(
                    isLoading = isLoading,
                    expiresAtMs = refreshAvailableAt,
                    onClick = {
                        scope.launch {
                            isLoading = true
                            loadWalletData(
                                charId,
                                corpId,
                                acting,
                                balanceCallback = { balance = it },
                                dailyCallback = { dailyBreakdown = it },
                                pnlCallback = { pnlBreakdown = it },
                                fifoCallback = { fifoResult = it },
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
                Text(
                    stringResource(Res.string.balance),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(Res.string.isk_amount, formatIsk(balance)),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val totalEarned = dailyBreakdown.sumOf { it.income }
                    val totalSpent = dailyBreakdown.sumOf { it.expenses }
                    Text(
                        stringResource(Res.string.earned, formatIsk(totalEarned)),
                        style = MaterialTheme.typography.bodySmall,
                        color = positiveColor,
                    )
                    Text(
                        stringResource(Res.string.spent, formatIsk(totalSpent)),
                        style = MaterialTheme.typography.bodySmall,
                        color = negativeColor,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tabs
        PrimaryTabRow(selectedTabIndex = activeTab) {
            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text(stringResource(Res.string.transactions)) })
            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }, text = { Text(stringResource(Res.string.journal)) })
            Tab(selected = activeTab == 2, onClick = { activeTab = 2 }, text = { Text("P&L") })
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tab content
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                0 -> TransactionList(transactions)
                1 -> JournalList(journal)
                2 -> PnlChart(pnlBreakdown, fifoResult)
            }
        }
    }

    LoadingOverlay(isLoading = isLoading, message = stringResource(Res.string.loading_wallet))
}

private enum class SortDirection { ASC, DESC }

/** Clickable column header — click toggles sort on this column, arrow shows direction only when active. */
@Composable
private fun SortHeaderCell(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean,
    direction: SortDirection,
    rightAlign: Boolean = false,
    tooltip: String? = null,
    onClick: () -> Unit,
) {
    Tip(tooltip, modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            horizontalArrangement = if (rightAlign) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            if (active) {
                Icon(
                    if (direction == SortDirection.ASC) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp).padding(start = 2.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PaginationBar(
    page: Int,
    totalPages: Int,
    totalCount: Int,
    onPageChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(Res.plurals.entries_count, totalCount, totalCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { onPageChange(page - 1) }, enabled = page > 0) {
                Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(Res.string.previous_page))
            }
            Text(stringResource(Res.string.page_of, page + 1, totalPages), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = { onPageChange(page + 1) }, enabled = page < totalPages - 1) {
                Icon(Icons.Default.ChevronRight, contentDescription = stringResource(Res.string.next_page))
            }
        }
    }
}

private enum class TxSortColumn { DATE, SIDE, ITEM, QTY, UNIT_PRICE, TOTAL, CLIENT, STATION }

private fun txTotal(tx: Map<String, Any?>): Double {
    val unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0
    val quantity = (tx["quantity"] as? Number)?.toInt() ?: 0
    return (tx["total"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: (unitPrice * quantity)
}

@Composable
private fun TransactionList(transactions: List<Map<String, Any?>>) {
    if (transactions.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Receipt,
            title = stringResource(Res.string.no_transactions),
            description = stringResource(Res.string.select_char_transactions),
        )
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    var sortColumn by remember { mutableStateOf(TxSortColumn.DATE) }
    var sortDirection by remember { mutableStateOf(SortDirection.DESC) }
    var page by remember { mutableStateOf(0) }

    fun toggleSort(column: TxSortColumn) {
        if (sortColumn == column) {
            sortDirection = if (sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
        } else {
            sortColumn = column
            sortDirection = SortDirection.DESC
        }
    }

    val filtered =
        remember(transactions, searchQuery) {
            val q = searchQuery.trim()
            if (q.isEmpty()) {
                transactions
            } else {
                transactions.filter { tx ->
                    listOfNotNull(
                        tx["type_name"]?.toString(),
                        tx["client_name"]?.toString(),
                        tx["location_name"]?.toString(),
                    ).any { it.contains(q, ignoreCase = true) }
                }
            }
        }

    val sorted =
        remember(filtered, sortColumn, sortDirection) {
            val comparator: Comparator<Map<String, Any?>> =
                when (sortColumn) {
                    TxSortColumn.DATE -> compareBy { it["date"]?.toString() ?: "" }
                    TxSortColumn.SIDE -> compareBy { (it["is_buy"] as? Boolean) ?: false }
                    TxSortColumn.ITEM -> compareBy { it["type_name"]?.toString() ?: "" }
                    TxSortColumn.QTY -> compareBy { (it["quantity"] as? Number)?.toInt() ?: 0 }
                    TxSortColumn.UNIT_PRICE -> compareBy { (it["unit_price"] as? Number)?.toDouble() ?: 0.0 }
                    TxSortColumn.TOTAL -> compareBy { txTotal(it) }
                    TxSortColumn.CLIENT -> compareBy { it["client_name"]?.toString() ?: "" }
                    TxSortColumn.STATION -> compareBy { it["location_name"]?.toString() ?: "" }
                }
            filtered.sortedWith(if (sortDirection == SortDirection.DESC) comparator.reversed() else comparator)
        }

    LaunchedEffect(transactions, searchQuery) { page = 0 }

    val pageSize = 100
    val totalPages = maxOf(1, (sorted.size + pageSize - 1) / pageSize)
    val clampedPage = page.coerceIn(0, totalPages - 1)
    val pageItems = sorted.drop(clampedPage * pageSize).take(pageSize)

    Column(modifier = Modifier.fillMaxSize()) {
        SearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = stringResource(Res.string.search_tx),
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (sorted.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Receipt,
                title = stringResource(Res.string.no_matches),
                description = stringResource(Res.string.no_tx_match),
            )
            return
        }

        // Header
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortHeaderCell(stringResource(Res.string.col_date), Modifier.weight(1.8f), sortColumn == TxSortColumn.DATE, sortDirection) {
                toggleSort(TxSortColumn.DATE)
            }
            SortHeaderCell(
                stringResource(Res.string.col_bs),
                Modifier.weight(0.6f),
                sortColumn == TxSortColumn.SIDE,
                sortDirection,
                tooltip = stringResource(Res.string.tip_w_bs),
            ) {
                toggleSort(TxSortColumn.SIDE)
            }
            SortHeaderCell(stringResource(Res.string.col_item), Modifier.weight(3f), sortColumn == TxSortColumn.ITEM, sortDirection) {
                toggleSort(TxSortColumn.ITEM)
            }
            SortHeaderCell(
                stringResource(Res.string.col_qty),
                Modifier.weight(1f),
                sortColumn == TxSortColumn.QTY,
                sortDirection,
                rightAlign = true,
            ) { toggleSort(TxSortColumn.QTY) }
            SortHeaderCell(
                stringResource(Res.string.col_unit_price),
                Modifier.weight(2f),
                sortColumn == TxSortColumn.UNIT_PRICE,
                sortDirection,
                rightAlign = true,
            ) { toggleSort(TxSortColumn.UNIT_PRICE) }
            SortHeaderCell(
                stringResource(Res.string.col_total),
                Modifier.weight(2f),
                sortColumn == TxSortColumn.TOTAL,
                sortDirection,
                rightAlign = true,
            ) { toggleSort(TxSortColumn.TOTAL) }
            SortHeaderCell(
                stringResource(Res.string.col_client),
                Modifier.weight(2f),
                sortColumn == TxSortColumn.CLIENT,
                sortDirection,
                tooltip = stringResource(Res.string.tip_w_client),
            ) { toggleSort(TxSortColumn.CLIENT) }
            SortHeaderCell(
                stringResource(Res.string.col_station),
                Modifier.weight(2.5f),
                sortColumn == TxSortColumn.STATION,
                sortDirection,
            ) { toggleSort(TxSortColumn.STATION) }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(pageItems) { tx ->
                val isBuy = tx["is_buy"] as? Boolean ?: false
                val unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0
                val quantity = (tx["quantity"] as? Number)?.toInt() ?: 0
                val total = txTotal(tx)
                val typeName =
                    tx["type_name"]?.toString()?.ifEmpty { null }
                        ?: stringResource(Res.string.unknown_type_id, tx["type_id"].toString())
                val clientName =
                    tx["client_name"]?.toString()?.ifEmpty { null }
                        ?: tx["client_id"]?.let { "#$it" } ?: ""
                val locationName =
                    tx["location_name"]?.toString()?.ifEmpty { null }
                        ?: tx["location_id"]?.toString() ?: ""
                val dateStr = tx["date"]?.toString()?.take(16)?.replace("T", " ") ?: ""
                val buyColor = positiveColor
                val sellColor = negativeColor

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
                            if (isBuy) stringResource(Res.string.buy) else stringResource(Res.string.sell),
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
        PaginationBar(page = clampedPage, totalPages = totalPages, totalCount = sorted.size, onPageChange = { page = it })
    }
}

@Composable
private fun TxHeader(
    label: String,
    modifier: Modifier,
    rightAlign: Boolean = false,
    tooltip: String? = null,
) {
    Tip(tooltip, modifier) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (rightAlign) TextAlign.End else TextAlign.Start,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private enum class JournalSortColumn { DATE, TYPE, DESCRIPTION, AMOUNT, TAX, BALANCE }

@Composable
private fun JournalList(journal: List<Map<String, Any?>>) {
    if (journal.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.List,
            title = stringResource(Res.string.no_journal),
            description = stringResource(Res.string.select_char_journal),
        )
        return
    }

    val totalTax = journal.filter { it["ref_type"] == "transaction_tax" }.sumOf { (it["amount"] as? Number)?.toDouble() ?: 0.0 }
    val totalBroker = journal.filter { it["ref_type"] == "brokers_fee" }.sumOf { (it["amount"] as? Number)?.toDouble() ?: 0.0 }

    var searchQuery by remember { mutableStateOf("") }
    var sortColumn by remember { mutableStateOf(JournalSortColumn.DATE) }
    var sortDirection by remember { mutableStateOf(SortDirection.DESC) }
    var page by remember { mutableStateOf(0) }

    fun toggleSort(column: JournalSortColumn) {
        if (sortColumn == column) {
            sortDirection = if (sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
        } else {
            sortColumn = column
            sortDirection = SortDirection.DESC
        }
    }

    val filtered =
        remember(journal, searchQuery) {
            val q = searchQuery.trim()
            if (q.isEmpty()) {
                journal
            } else {
                journal.filter { entry ->
                    listOfNotNull(
                        formatRefType(entry["ref_type"]?.toString() ?: ""),
                        entry["reason"]?.toString(),
                    ).any { it.contains(q, ignoreCase = true) }
                }
            }
        }

    val sorted =
        remember(filtered, sortColumn, sortDirection) {
            val comparator: Comparator<Map<String, Any?>> =
                when (sortColumn) {
                    JournalSortColumn.DATE -> compareBy { it["date"]?.toString() ?: "" }
                    JournalSortColumn.TYPE -> compareBy { formatRefType(it["ref_type"]?.toString() ?: "") }
                    JournalSortColumn.DESCRIPTION -> compareBy { it["reason"]?.toString()?.trim() ?: "" }
                    JournalSortColumn.AMOUNT -> compareBy { (it["amount"] as? Number)?.toDouble() ?: 0.0 }
                    JournalSortColumn.TAX -> compareBy { (it["tax_amount"] as? Number)?.toDouble() ?: 0.0 }
                    JournalSortColumn.BALANCE -> compareBy { (it["balance"] as? Number)?.toDouble() ?: 0.0 }
                }
            filtered.sortedWith(if (sortDirection == SortDirection.DESC) comparator.reversed() else comparator)
        }

    LaunchedEffect(journal, searchQuery) { page = 0 }

    val pageSize = 100
    val totalPages = maxOf(1, (sorted.size + pageSize - 1) / pageSize)
    val clampedPage = page.coerceIn(0, totalPages - 1)
    val pageItems = sorted.drop(clampedPage * pageSize).take(pageSize)

    Column(modifier = Modifier.fillMaxSize()) {
        SearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = stringResource(Res.string.search_journal),
            modifier = Modifier.padding(bottom = 8.dp),
        )

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
                        stringResource(Res.string.taxes_paid),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    if (totalTax != 0.0) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(Res.string.sales_tax),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                formatIsk(totalTax),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = negativeColor,
                            )
                        }
                    }
                    if (totalBroker != 0.0) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(Res.string.brokers_fee),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                formatIsk(totalBroker),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = negativeColor,
                            )
                        }
                    }
                    if (totalTax != 0.0 && totalBroker != 0.0) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(Res.string.col_total),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                formatIsk(totalTax + totalBroker),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = negativeColor,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (sorted.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.List,
                title = stringResource(Res.string.no_matches),
                description = stringResource(Res.string.no_journal_match),
            )
            return
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
            SortHeaderCell(
                stringResource(Res.string.col_date),
                Modifier.weight(1.8f),
                sortColumn == JournalSortColumn.DATE,
                sortDirection,
            ) { toggleSort(JournalSortColumn.DATE) }
            SortHeaderCell(
                stringResource(Res.string.col_type),
                Modifier.weight(2.5f),
                sortColumn == JournalSortColumn.TYPE,
                sortDirection,
                tooltip = stringResource(Res.string.tip_w_type),
            ) { toggleSort(JournalSortColumn.TYPE) }
            SortHeaderCell(
                stringResource(Res.string.col_description),
                Modifier.weight(3f),
                sortColumn == JournalSortColumn.DESCRIPTION,
                sortDirection,
            ) { toggleSort(JournalSortColumn.DESCRIPTION) }
            SortHeaderCell(
                stringResource(Res.string.col_amount),
                Modifier.weight(2f),
                sortColumn == JournalSortColumn.AMOUNT,
                sortDirection,
                rightAlign = true,
                tooltip = stringResource(Res.string.tip_w_amount),
            ) { toggleSort(JournalSortColumn.AMOUNT) }
            SortHeaderCell(
                stringResource(Res.string.col_tax),
                Modifier.weight(1.5f),
                sortColumn == JournalSortColumn.TAX,
                sortDirection,
                rightAlign = true,
                tooltip = stringResource(Res.string.tip_w_tax),
            ) { toggleSort(JournalSortColumn.TAX) }
            SortHeaderCell(
                stringResource(Res.string.balance),
                Modifier.weight(2f),
                sortColumn == JournalSortColumn.BALANCE,
                sortDirection,
                rightAlign = true,
                tooltip = stringResource(Res.string.tip_w_balance),
            ) { toggleSort(JournalSortColumn.BALANCE) }
        }
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(pageItems) { entry ->
                val amount = (entry["amount"] as? Number)?.toDouble() ?: 0.0
                val taxAmount = (entry["tax_amount"] as? Number)?.toDouble()
                val balance = (entry["balance"] as? Number)?.toDouble() ?: 0.0
                val refType = entry["ref_type"]?.toString() ?: ""
                val reason = entry["reason"]?.toString()?.trim() ?: ""
                val dateStr = entry["date"]?.toString()?.take(16)?.replace("T", " ") ?: ""
                val amountColor = if (amount >= 0) positiveColor else negativeColor

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
                                negativeColor
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
        PaginationBar(page = clampedPage, totalPages = totalPages, totalCount = sorted.size, onPageChange = { page = it })
    }
}

private fun formatRefType(refType: String): String =
    when (refType) {
        "transaction_tax" -> {
            stringBlocking(Res.string.ref_transaction_tax)
        }

        "brokers_fee" -> {
            stringBlocking(Res.string.ref_brokers_fee)
        }

        "market_transaction" -> {
            stringBlocking(Res.string.ref_market_transaction)
        }

        "market_escrow" -> {
            stringBlocking(Res.string.ref_market_escrow)
        }

        "market_escrow_refund" -> {
            stringBlocking(Res.string.ref_market_escrow_refund)
        }

        "player_trading" -> {
            stringBlocking(Res.string.ref_player_trading)
        }

        "contract_price" -> {
            stringBlocking(Res.string.ref_contract_price)
        }

        "contract_reward" -> {
            stringBlocking(Res.string.ref_contract_reward)
        }

        "contract_deposit" -> {
            stringBlocking(Res.string.ref_contract_deposit)
        }

        "contract_deposit_refund" -> {
            stringBlocking(Res.string.ref_contract_deposit_refund)
        }

        "contract_price_payment_corp" -> {
            stringBlocking(Res.string.ref_contract_price_payment_corp)
        }

        "bounty_prizes" -> {
            stringBlocking(Res.string.ref_bounty_prizes)
        }

        "industry_job_tax" -> {
            stringBlocking(Res.string.ref_industry_job_tax)
        }

        "manufacturing" -> {
            stringBlocking(Res.string.ref_manufacturing)
        }

        "reprocessing_tax" -> {
            stringBlocking(Res.string.ref_reprocessing_tax)
        }

        "jump_clone_installation_fee" -> {
            stringBlocking(Res.string.ref_jump_clone_installation_fee)
        }

        "planetary_import_tax" -> {
            stringBlocking(Res.string.ref_planetary_import_tax)
        }

        "planetary_export_tax" -> {
            stringBlocking(Res.string.ref_planetary_export_tax)
        }

        "corporation_account_withdrawal" -> {
            stringBlocking(Res.string.ref_corporation_account_withdrawal)
        }

        "corporation_dividend_payment" -> {
            stringBlocking(Res.string.ref_corporation_dividend_payment)
        }

        "structure_gate_jump" -> {
            stringBlocking(Res.string.ref_structure_gate_jump)
        }

        "asset_safety_recovery_tax" -> {
            stringBlocking(Res.string.ref_asset_safety_recovery_tax)
        }

        "skill_purchase" -> {
            stringBlocking(Res.string.ref_skill_purchase)
        }

        "agent_mission_reward" -> {
            stringBlocking(Res.string.ref_agent_mission_reward)
        }

        "agent_mission_time_bonus_reward" -> {
            stringBlocking(Res.string.ref_agent_mission_time_bonus_reward)
        }

        else -> {
            refType
                .replace('_', ' ')
                .split(' ')
                .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        }
    }

private fun pnlSignedText(value: Double): String = "${if (value >= 0) "+" else "-"}${formatIsk(abs(value))}"

@Composable
private fun pnlColor(value: Double): Color =
    when {
        value > 0 -> positiveColor
        value < 0 -> negativeColor
        else -> MaterialTheme.colorScheme.onSurface
    }

@Composable
private fun PnlChart(
    dailyBreakdown: List<DailyWalletEntry>,
    fifoResult: CostBasisService.FifoResult?,
) {
    if (dailyBreakdown.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.ShowChart,
            title = stringResource(Res.string.no_pnl),
            description = stringResource(Res.string.no_pnl_desc),
        )
        return
    }

    // dailyBreakdown arrives newest-first; chronological order is needed for the chart.
    val chronological = dailyBreakdown.reversed()

    val window = dailyBreakdown.toPnlWindow()
    val todayNet = window.todayNet
    val net7 = window.net7d
    val net30 = window.net30d
    val netAll = window.netAll
    val incomeAll = window.incomeAll
    val expensesAll = window.expensesAll
    // Days that closed with positive realized FIFO profit, out of the days that had any realized
    // sales at all — a heavy restock day is not a "losing day", and a day without sales is
    // neither won nor lost. Falls back to the cash-flow count when there's no FIFO data yet.
    val dailyRealizedByDay =
        fifoResult
            ?.realizedSells
            ?.groupBy { it.date.substring(0, 10) }
            ?.mapValues { (_, sells) -> sells.sumOf { it.profit } }
            .orEmpty()
    val profitableDays = if (dailyRealizedByDay.isNotEmpty()) dailyRealizedByDay.count { it.value > 0 } else window.profitableDays
    val tradingDays = if (dailyRealizedByDay.isNotEmpty()) dailyRealizedByDay.size else chronological.size
    // Average realized margin over completed FIFO trades — profit (already net of relist fees,
    // see CostBasisService.compute) relative to the capital those sold units cost. The old figure
    // (net cash flow / income) mostly measured how much restocking happened lately, not how
    // profitable the trading was.
    val realizedCost = fifoResult?.realizedSells?.sumOf { it.costBasis * it.qty } ?: 0.0
    val margin =
        if (fifoResult != null && realizedCost > 0) {
            fifoResult.totalRealizedPnl / realizedCost * 100
        } else {
            null
        }
    val fifoWindow = fifoResult?.realizedPnlWindow()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Tip(stringResource(Res.string.tip_cash_flow)) {
            Text(
                stringResource(Res.string.cash_flow),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PnlStatCard(stringResource(Res.string.pnl_today), pnlSignedText(todayNet), pnlColor(todayNet), Modifier.weight(1f))
            PnlStatCard(stringResource(Res.string.pnl_7_days), pnlSignedText(net7), pnlColor(net7), Modifier.weight(1f))
            PnlStatCard(stringResource(Res.string.pnl_30_days), pnlSignedText(net30), pnlColor(net30), Modifier.weight(1f))
            PnlStatCard(
                stringResource(Res.string.pnl_days_total, chronological.size),
                pnlSignedText(netAll),
                pnlColor(netAll),
                Modifier.weight(1f),
            )
        }

        if (fifoWindow != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Tip(stringResource(Res.string.tip_realized_pnl)) {
                Text(
                    stringResource(Res.string.realized_pnl_fifo),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PnlStatCard(
                    stringResource(Res.string.pnl_today),
                    pnlSignedText(fifoWindow.todayPnl),
                    pnlColor(fifoWindow.todayPnl),
                    Modifier.weight(1f),
                )
                PnlStatCard(
                    stringResource(Res.string.pnl_7_days),
                    pnlSignedText(fifoWindow.pnl7d),
                    pnlColor(fifoWindow.pnl7d),
                    Modifier.weight(1f),
                )
                PnlStatCard(
                    stringResource(Res.string.pnl_30_days),
                    pnlSignedText(fifoWindow.pnl30d),
                    pnlColor(fifoWindow.pnl30d),
                    Modifier.weight(1f),
                )
                PnlStatCard(
                    stringResource(Res.string.all_time),
                    pnlSignedText(fifoWindow.pnlAll),
                    pnlColor(fifoWindow.pnlAll),
                    Modifier.weight(1f),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PnlStatCard(stringResource(Res.string.income), "+${formatIsk(incomeAll)}", positiveColor, Modifier.weight(1f))
            PnlStatCard(stringResource(Res.string.expenses), "-${formatIsk(expensesAll)}", negativeColor, Modifier.weight(1f))
            PnlStatCard(
                stringResource(Res.string.avg_margin_realized),
                margin?.let { "%.1f%%".format(it) } ?: "—",
                pnlColor(margin ?: 0.0),
                Modifier.weight(1f),
            )
            PnlStatCard(
                stringResource(Res.string.profitable_days),
                "$profitableDays / $tradingDays",
                MaterialTheme.colorScheme.onSurface,
                Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Realized profit per sell date, mirroring the Dashboard chart — the cash-flow series
        // this used to plot spikes hugely negative on every restock day, which reads as a loss.
        if (fifoResult != null) {
            ContentCard(stringResource(Res.string.daily_realized_pnl_30d)) {
                val today = java.time.LocalDate.now()
                val days = (29 downTo 0).map { today.minusDays(it.toLong()).toString() }
                PnlBarChart(
                    data = days.map { dailyRealizedByDay[it] ?: 0.0 },
                    dates = days,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            }
        } else {
            ContentCard(stringResource(Res.string.daily_cash_flow)) {
                PnlBarChart(
                    data = chronological.map { it.net },
                    dates = chronological.map { it.date },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        ContentCard(stringResource(Res.string.daily_breakdown)) {
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
            TxHeader(stringResource(Res.string.col_date), Modifier.weight(2f))
            TxHeader(stringResource(Res.string.income), Modifier.weight(1.5f), rightAlign = true)
            TxHeader(stringResource(Res.string.expenses), Modifier.weight(1.5f), rightAlign = true)
            TxHeader(stringResource(Res.string.net), Modifier.weight(1.5f), rightAlign = true)
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
                    color = positiveColor,
                )
                Text(
                    "-${formatIsk(entry.expenses)}",
                    modifier = Modifier.weight(1.5f),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall,
                    color = negativeColor,
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
    val positive = positiveColor
    val negative = negativeColor

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
            val color = if (isPositive) positive else negative
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
            val color = if (isPositive) positive else negative
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
    characterId: Int?,
    corporationId: Int?,
    actingCharId: Int,
    balanceCallback: (Double) -> Unit,
    dailyCallback: (List<DailyWalletEntry>) -> Unit,
    pnlCallback: (List<DailyWalletEntry>) -> Unit,
    fifoCallback: (CostBasisService.FifoResult) -> Unit,
    transactionsCallback: (List<Map<String, Any?>>) -> Unit,
    journalCallback: (List<Map<String, Any?>>) -> Unit,
    expiryCallback: (Long?) -> Unit = {},
) {
    withContext(Dispatchers.IO) {
        val isCorp = corporationId != null

        // Load from DB, resolve names (local + ESI fallback for old records)
        val summary = WalletDao.getWalletSummary(characterId = characterId, corporationId = corporationId)
        balanceCallback(summary.balance)
        dailyCallback(summary.dailyBreakdown)
        pnlCallback(WalletDao.getTradingPnlBreakdown(characterId = characterId, corporationId = corporationId))
        transactionsCallback(
            resolveAllNames(WalletDao.getTransactions(characterId = characterId, corporationId = corporationId, limit = -1)),
        )
        journalCallback(WalletDao.getJournalEntries(characterId = characterId, corporationId = corporationId))

        // Fetch from ESI
        try {
            val walletBalance =
                if (isCorp) {
                    EsiClient.getCorporationWallet(corporationId, actingCharId).values.sum()
                } else {
                    EsiClient.getCharacterWallet(characterId!!)
                }
            balanceCallback(walletBalance)
        } catch (e: Exception) {
            println("Error fetching wallet: ${e.message}")
        }

        try {
            val journalEntries =
                if (isCorp) EsiClient.getCorporationJournal(corporationId, actingCharId) else EsiClient.getCharacterJournal(characterId!!)
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
                        characterId = if (isCorp) null else characterId,
                        corporationId = corporationId,
                        divisionId = if (isCorp) (entry["division"] as? Number)?.toInt() else null,
                    )
                } catch (e: Exception) {
                    // skip duplicates or bad entries
                }
            }
            journalCallback(WalletDao.getJournalEntries(characterId = characterId, corporationId = corporationId))
        } catch (e: Exception) {
            println("Error fetching journal: ${e.message}")
        }

        try {
            val txList =
                if (isCorp) {
                    EsiClient.getCorporationTransactions(corporationId, actingCharId)
                } else {
                    EsiClient.getCharacterTransactions(characterId!!)
                }

            // Batch-resolve names before inserting
            val typeIds = txList.mapNotNull { (it["type_id"] as? Number)?.toInt() }.toSet()
            val typeNames = typeIds.associateWith { id -> StaticDataDao.getTypeName(id) ?: "" }

            val locationIds = txList.mapNotNull { (it["location_id"] as? Number)?.toLong() }.toSet()
            val locationNames = locationIds.associateWith { id -> StaticDataDao.getStationById(id)?.name ?: "" }

            val clientIds = txList.mapNotNull { (it["client_id"] as? Number)?.toInt() }.filter { it > 0 }.toSet()
            val knownClientNames = WalletDao.getKnownClientNames(clientIds)
            val missingClientIds = clientIds - knownClientNames.keys
            val freshClientNames = if (missingClientIds.isNotEmpty()) EsiClient.resolveNames(missingClientIds.toList()) else emptyMap()
            val clientNames = knownClientNames + freshClientNames

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
                        isCorp = isCorp,
                        characterId = if (isCorp) null else characterId,
                        corporationId = corporationId,
                    )
                } catch (_: Exception) {
                }
            }
            transactionsCallback(
                resolveAllNames(WalletDao.getTransactions(characterId = characterId, corporationId = corporationId, limit = -1)),
            )
        } catch (e: Exception) {
            println("Error fetching transactions: ${e.message}")
        }

        pnlCallback(WalletDao.getTradingPnlBreakdown(characterId = characterId, corporationId = corporationId))

        try {
            val taxConfig =
                CostBasisService.TaxConfig(
                    salesTaxPct = StaticDataDao.getCharSalesTax(actingCharId),
                    brokerFeePct = StaticDataDao.getCharBrokersFee(actingCharId),
                )
            fifoCallback(CostBasisService.compute(characterId = characterId, corporationId = corporationId, taxConfig = taxConfig))
        } catch (_: Exception) {
        }

        val expiry =
            if (isCorp) {
                val txExpiry = EsiClient.getEndpointExpiry("/corporations/$corporationId/wallets/1/transactions/")
                val journalExpiry = EsiClient.getEndpointExpiry("/corporations/$corporationId/wallets/1/journal/")
                listOfNotNull(txExpiry, journalExpiry).maxOrNull()
            } else {
                val txExpiry = EsiClient.getEndpointExpiry("/characters/$characterId/wallet/transactions/")
                val journalExpiry = EsiClient.getEndpointExpiry("/characters/$characterId/wallet/journal/")
                listOfNotNull(txExpiry, journalExpiry).maxOrNull()
            }
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
