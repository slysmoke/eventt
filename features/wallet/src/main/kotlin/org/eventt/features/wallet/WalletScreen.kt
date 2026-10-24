package org.eventt.features.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.AppState
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun WalletScreen(context: ViewContext?) {
    val scope = rememberCoroutineScope()
    val charId = (context as? ViewContext.Character)?.charId
    val corpId = (context as? ViewContext.Corporation)?.corporationId
    val actingCharId = context?.actingCharId
    var balance by remember { mutableStateOf(0.0) }
    var pnlBreakdown by remember { mutableStateOf<List<DailyWalletEntry>>(emptyList()) }
    var fifoResult by remember { mutableStateOf<CostBasisService.FifoResult?>(null) }
    var transactions by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var journal by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var refreshAvailableAt by remember { mutableStateOf<Long?>(null) }
    val pendingSearch by AppState.pendingWalletSearch.collectAsState()

    suspend fun load(acting: Int) {
        isLoading = true
        loadWalletData(
            charId,
            corpId,
            acting,
            balanceCallback = { balance = it },
            pnlCallback = { pnlBreakdown = it },
            fifoCallback = { fifoResult = it },
            transactionsCallback = { transactions = it },
            journalCallback = { journal = it },
            expiryCallback = { refreshAvailableAt = it },
        )
        isLoading = false
    }

    LaunchedEffect(context) { actingCharId?.let { load(it) } }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.wallet), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            EsiRefreshButton(
                isLoading = isLoading,
                expiresAtMs = refreshAvailableAt,
                enabled = actingCharId != null,
                onClick = { actingCharId?.let { scope.launch { load(it) } } },
            )
        }
        WalletBody(
            balance = balance,
            tx = remember(transactions) { transactions.map { it.toTx() } },
            journal = remember(journal) { journal.map { it.toEntry() } },
            daily = pnlBreakdown,
            fifo = fifoResult,
            pendingSearch = pendingSearch,
            modifier = Modifier.weight(1f),
        )
    }

    LoadingOverlay(isLoading = isLoading, message = stringResource(Res.string.loading_wallet))
}

private enum class WalletTab { TRANSACTIONS, JOURNAL, PNL }

private enum class Side { ALL, BUY, SELL }

/** Everything under the header for already-loaded rows — no DB or ESI here. */
@Composable
internal fun WalletBody(
    balance: Double,
    tx: List<Tx>,
    journal: List<Entry>,
    daily: List<DailyWalletEntry>,
    fifo: CostBasisService.FifoResult?,
    pendingSearch: String?,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    var tab by remember { mutableStateOf(WalletTab.TRANSACTIONS) }
    var period by remember { mutableStateOf(Period.D30) }
    var txQuery by remember { mutableStateOf("") }
    // A counterparty clicked on the Dashboard: everything ever traded with them, not just this month.
    LaunchedEffect(pendingSearch) {
        if (pendingSearch != null) {
            tab = WalletTab.TRANSACTIONS
            period = Period.ALL
            txQuery = pendingSearch
            AppState.clearPendingWalletSearch()
        }
    }
    val txIn = remember(tx, period) { tx.inPeriod(period, today) { it.day } }
    val journalIn = remember(journal, period) { journal.inPeriod(period, today) { it.day } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabBar(
                listOf(
                    TabItem(stringResource(Res.string.transactions), txIn.size),
                    TabItem(stringResource(Res.string.journal), journalIn.size),
                    TabItem("P&L"),
                ),
                tab.ordinal,
                { tab = WalletTab.entries[it] },
                Modifier.weight(1f),
            )
            if (tab != WalletTab.PNL) {
                Period.entries.forEach { p ->
                    Segment(if (p == Period.ALL) stringResource(Res.string.period_all) else "${p.days}d", period == p) { period = p }
                }
            }
        }
        when (tab) {
            WalletTab.TRANSACTIONS, WalletTab.JOURNAL -> {
                TotalsStrip(balance, remember(tx, journal, fifo, period) { walletTotals(tx, journal, fifo, period, today) })
                if (tab == WalletTab.TRANSACTIONS) {
                    TransactionsTab(txIn, remember(fifo, period) { realizedByType(fifo, period, today) }, txQuery) { txQuery = it }
                } else {
                    JournalTab(journalIn)
                }
            }

            WalletTab.PNL -> {
                PnlTab(daily, fifo, today)
            }
        }
    }
}

@Composable
private fun TotalsStrip(
    balance: Double,
    t: WalletTotals,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.dp, outline(), MaterialTheme.shapes.extraSmall)) {
        Kpi(stringResource(Res.string.balance), formatIsk(balance), null, null, null)
        Kpi(
            stringResource(Res.string.kpi_realized),
            t.realized?.let(::signed) ?: "—",
            null,
            null,
            null,
            Res.string.tip_realized_pnl,
            valueColor = pnlColor(t.realized),
        )
        Kpi(
            stringResource(Res.string.kpi_sales),
            formatIsk(t.sales),
            stringResource(Res.string.n_trades, t.sells.toString()),
            null,
            SELL_DOT,
        )
        Kpi(
            stringResource(Res.string.kpi_purchases),
            formatIsk(t.purchases),
            stringResource(Res.string.n_trades, t.buys.toString()),
            null,
            BUY_DOT,
        )
        Kpi(
            stringResource(Res.string.kpi_fees),
            formatIsk(t.fees),
            if (t.sales > 0) stringResource(Res.string.pct_of_sales, String.format(Locale.US, "%.2f%%", t.fees / t.sales * 100)) else null,
            null,
            null,
            Res.string.tip_fees,
        )
        Kpi(
            stringResource(Res.string.cash_flow),
            signed(t.cashFlow),
            null,
            null,
            null,
            Res.string.tip_cash_flow,
            valueColor = pnlColor(t.cashFlow),
        )
        Kpi(stringResource(Res.string.kpi_items), t.items.toString(), null, null, null, last = true)
    }
}

// Same marks as the Orders tabs: sell red, buy green.
private val SELL_DOT = Color(0xFFE57373)
private val BUY_DOT = Color(0xFF81C784)

private fun shortStation(name: String) = name.split(" - ").take(2).joinToString(" - ")

@Composable
private fun TransactionsTab(
    tx: List<Tx>,
    realized: Map<Int, Double>?,
    query: String,
    onQuery: (String) -> Unit,
) {
    var side by remember { mutableStateOf(Side.ALL) }
    val sided =
        remember(tx, side) {
            when (side) {
                Side.ALL -> tx
                Side.BUY -> tx.filter { it.isBuy }
                Side.SELL -> tx.filter { !it.isBuy }
            }
        }
    val shown =
        remember(sided, query) { if (query.isBlank()) sided else sided.filter { it.search.contains(query.trim(), ignoreCase = true) } }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Panel(
            null,
            Modifier.weight(0.7f).fillMaxHeight(),
            header = {
                Segment(stringResource(Res.string.side_all), side == Side.ALL) { side = Side.ALL }
                Segment(stringResource(Res.string.buy), side == Side.BUY) { side = Side.BUY }
                Segment(stringResource(Res.string.sell), side == Side.SELL) { side = Side.SELL }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(
                        Res.string.tx_summary,
                        formatIsk(shown.filter { it.isBuy }.sumOf { it.total }),
                        formatIsk(shown.filter { !it.isBuy }.sumOf { it.total }),
                        shown.size.toString(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = Mono,
                    color = AXIS_COLOR,
                )
            },
        ) {
            TerminalSearch(query, onQuery, stringResource(Res.string.search_tx), Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            TxTable(shown)
        }
        Column(Modifier.weight(0.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Panel(stringResource(Res.string.panel_items), Modifier.fillMaxWidth().weight(1f)) {
                FlowTable(remember(sided, realized) { byItem(sided, realized) }, stringResource(Res.string.col_item), realized != null) {
                    onQuery(if (query == it.label) "" else it.label)
                }
            }
            Panel(stringResource(Res.string.panel_clients), Modifier.fillMaxWidth().weight(1f)) {
                FlowTable(remember(sided) { byClient(sided) }, stringResource(Res.string.col_client), false) {
                    onQuery(if (query == it.label) "" else it.label)
                }
            }
        }
    }
}

@Composable
private fun TxTable(tx: List<Tx>) {
    val cols =
        listOf<Col<Tx>>(
            Col(stringResource(Res.string.col_date), 1.2f, end = false) { it.date },
            Col(stringResource(Res.string.col_bs), 0.4f, end = false) { it.isBuy },
            Col(stringResource(Res.string.col_item), 2.6f, end = false) { it.typeName.lowercase() },
            Col(stringResource(Res.string.col_qty), 0.8f) { it.qty },
            Col(stringResource(Res.string.col_unit_price), 1f) { it.unitPrice },
            Col(stringResource(Res.string.col_total), 1f) { it.total },
            Col(stringResource(Res.string.col_client), 1.5f, end = false) { it.client.lowercase() },
            Col(stringResource(Res.string.col_station), 1.6f, end = false) { it.station.lowercase() },
        )
    val b = stringResource(Res.string.b_short)
    val s = stringResource(Res.string.s_short)
    Table(cols = cols, rows = tx, empty = stringResource(Res.string.no_tx_match), defaultSort = 0, key = { it.id }) { t ->
        listOf(
            Cell(t.date.take(16).replace("T", " "), AXIS_COLOR),
            Cell(if (t.isBuy) b else s, if (t.isBuy) positiveColor else negativeColor),
            Cell(t.typeName) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TypeIcon(t.typeId, size = 18.dp)
                    Text(t.typeName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            Cell(formatVolume(t.qty.toLong())),
            Cell(formatPriceAbbr(t.unitPrice)),
            // Money out on a buy, in on a sell.
            Cell(formatIsk(t.total), if (t.isBuy) negativeColor else positiveColor),
            Cell(t.client, AXIS_COLOR),
            Cell(shortStation(t.station), AXIS_COLOR),
        )
    }
}

// Items / clients beside the transactions; a click searches the table for that row.
@Composable
private fun FlowTable(
    rows: List<FlowStat>,
    title: String,
    showRealized: Boolean,
    onClick: (FlowStat) -> Unit,
) {
    val cols =
        buildList<Col<FlowStat>> {
            add(Col(title, 1.7f, end = false) { it.label.lowercase() })
            if (showRealized) {
                add(Col(stringResource(Res.string.col_realized), 1f) { it.realized })
            } else {
                add(Col(stringResource(Res.string.col_bought), 1f) { it.bought })
                add(Col(stringResource(Res.string.col_sold), 1f) { it.sold })
            }
            add(Col(stringResource(Res.string.col_turnover), 1.2f) { it.turnover })
            add(Col("#", 0.5f) { it.trades })
        }
    Table(cols = cols, rows = rows, empty = stringResource(Res.string.no_tx_match), defaultSort = cols.size - 2, key = {
        it.key
    }, onClick = onClick) { f ->
        buildList {
            add(
                Cell(f.label) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        f.typeId?.let { TypeIcon(it, size = 16.dp) }
                        Text(f.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            )
            if (showRealized) {
                add(Cell(f.realized?.let(::signed) ?: "—", pnlColor(f.realized)))
            } else {
                add(Cell(formatIsk(f.bought), AXIS_COLOR))
                add(Cell(formatIsk(f.sold), AXIS_COLOR))
            }
            add(Cell(formatIsk(f.turnover)))
            add(Cell(f.trades.toString(), AXIS_COLOR))
        }
    }
}

@Composable
private fun JournalTab(journal: List<Entry>) {
    var query by remember { mutableStateOf("") }
    var refType by remember { mutableStateOf<String?>(null) }
    val shown =
        remember(journal, query, refType) {
            journal.filter { e ->
                (refType == null || e.refType == refType) &&
                    (query.isBlank() || "${formatRefType(e.refType)} ${e.reason}".contains(query.trim(), ignoreCase = true))
            }
        }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Panel(
            null,
            Modifier.weight(0.7f).fillMaxHeight(),
            header = {
                refType?.let { r -> Segment("${formatRefType(r)}  ✕", true) { refType = null } }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(Res.string.journal_summary, signed(shown.sumOf { it.amount }), shown.size.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = Mono,
                    color = AXIS_COLOR,
                )
            },
        ) {
            TerminalSearch(query, { query = it }, stringResource(Res.string.search_journal), Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            val cols =
                listOf<Col<Entry>>(
                    Col(stringResource(Res.string.col_date), 1.2f, end = false) { it.date },
                    Col(stringResource(Res.string.col_type), 1.6f, end = false) { formatRefType(it.refType) },
                    Col(stringResource(Res.string.col_description), 2.6f, end = false) { it.reason.lowercase() },
                    Col(stringResource(Res.string.col_amount), 1.1f) { it.amount },
                    Col(stringResource(Res.string.col_tax), 0.8f) { it.tax },
                    Col(stringResource(Res.string.balance), 1.1f) { it.balance },
                )
            Table(cols = cols, rows = shown, empty = stringResource(Res.string.no_journal_match), defaultSort = 0, key = { it.id }) { e ->
                listOf(
                    Cell(e.date.take(16).replace("T", " "), AXIS_COLOR),
                    Cell(formatRefType(e.refType)),
                    Cell(e.reason, AXIS_COLOR),
                    Cell(signed(e.amount), pnlColor(e.amount)),
                    Cell(e.tax?.let(::formatIsk) ?: "—", if (e.tax != null) negativeColor else AXIS_COLOR),
                    Cell(formatIsk(e.balance), AXIS_COLOR),
                )
            }
        }
        Panel(stringResource(Res.string.panel_by_type), Modifier.weight(0.3f).fillMaxHeight()) {
            Table(
                cols =
                    listOf(
                        Col(stringResource(Res.string.col_type), 2f, end = false) { formatRefType(it.refType) },
                        Col("#", 0.6f) { it.count },
                        Col(stringResource(Res.string.col_amount), 1.2f) { abs(it.sum) },
                    ),
                rows = remember(journal) { byRefType(journal) },
                empty = stringResource(Res.string.no_journal_match),
                defaultSort = 2,
                key = { it.refType },
                onClick = { refType = if (refType == it.refType) null else it.refType },
                highlight = { it.refType == refType },
            ) { r ->
                listOf(Cell(formatRefType(r.refType)), Cell(r.count.toString(), AXIS_COLOR), Cell(signed(r.sum), pnlColor(r.sum)))
            }
        }
    }
}

@Composable
private fun PnlTab(
    daily: List<DailyWalletEntry>,
    fifo: CostBasisService.FifoResult?,
    today: LocalDate,
) {
    if (daily.isEmpty() && fifo == null) {
        Hint(stringResource(Res.string.no_pnl_desc))
        return
    }
    val window = daily.toPnlWindow()
    val fifoWindow = fifo?.realizedPnlWindow()
    // Days that closed with positive realized FIFO profit, out of the days that had any realized
    // sales at all — a heavy restock day is not a "losing day", and a day without sales is
    // neither won nor lost. Falls back to the cash-flow count when there's no FIFO data yet.
    val realizedByDay =
        remember(fifo) {
            fifo
                ?.realizedSells
                ?.groupBy { it.date.take(10) }
                ?.mapValues { (_, sells) -> sells.sumOf { it.profit } }
                .orEmpty()
        }
    val profitableDays = if (realizedByDay.isNotEmpty()) realizedByDay.count { it.value > 0 } else window.profitableDays
    val tradingDays = if (realizedByDay.isNotEmpty()) realizedByDay.size else daily.size
    // Realized profit (already net of relist fees, see CostBasisService.compute) relative to the
    // capital the sold units cost — not net cash flow / income, which mostly measures restocking.
    val realizedCost = fifo?.realizedSells?.sumOf { it.costBasis * it.qty } ?: 0.0
    val margin = if (fifo != null && realizedCost > 0) fifo.totalRealizedPnl / realizedCost * 100 else null
    val wToday = stringResource(Res.string.w_today)
    val wAll = stringResource(Res.string.w_all)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (fifoWindow != null) {
            Strip {
                PnlKpi(Res.string.kpi_realized_w, wToday, fifoWindow.todayPnl, Res.string.tip_realized_pnl)
                PnlKpi(Res.string.kpi_realized_w, "7d", fifoWindow.pnl7d, Res.string.tip_realized_pnl)
                PnlKpi(Res.string.kpi_realized_w, "30d", fifoWindow.pnl30d, Res.string.tip_realized_pnl)
                PnlKpi(Res.string.kpi_realized_w, wAll, fifoWindow.pnlAll, Res.string.tip_realized_pnl)
                Kpi(
                    stringResource(Res.string.avg_margin_realized),
                    margin?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—",
                    null,
                    null,
                    null,
                    valueColor = pnlColor(margin),
                )
                Kpi(stringResource(Res.string.profitable_days), "$profitableDays / $tradingDays", null, null, null, last = true)
            }
        }
        Strip {
            PnlKpi(Res.string.kpi_cash_w, wToday, window.todayNet, Res.string.tip_cash_flow)
            PnlKpi(Res.string.kpi_cash_w, "7d", window.net7d, Res.string.tip_cash_flow)
            PnlKpi(Res.string.kpi_cash_w, "30d", window.net30d, Res.string.tip_cash_flow)
            PnlKpi(Res.string.kpi_cash_w, wAll, window.netAll, Res.string.tip_cash_flow)
            Kpi(stringResource(Res.string.income), formatIsk(window.incomeAll), null, null, BUY_DOT)
            Kpi(stringResource(Res.string.expenses), formatIsk(window.expensesAll), null, null, SELL_DOT, last = true)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Realized profit per sell date, mirroring the Dashboard chart — the cash-flow series
            // spikes hugely negative on every restock day, which reads as a loss.
            val days = remember(today) { (29 downTo 0).map { today.minusDays(it.toLong()).toString() } }
            Panel(
                stringResource(if (fifo != null) Res.string.daily_realized_pnl_30d else Res.string.daily_cash_flow),
                Modifier.weight(0.6f).fillMaxHeight(),
            ) {
                if (fifo != null) {
                    PnlBarChart(days.map { realizedByDay[it] ?: 0.0 }, days, Modifier.fillMaxSize())
                } else {
                    val chronological = daily.reversed()
                    PnlBarChart(chronological.map { it.net }, chronological.map { it.date }, Modifier.fillMaxSize())
                }
            }
            Panel(stringResource(Res.string.daily_breakdown), Modifier.weight(0.4f).fillMaxHeight()) {
                Table(
                    cols =
                        buildList<Col<DailyWalletEntry>> {
                            add(Col(stringResource(Res.string.col_date), 1f, end = false) { it.date })
                            if (fifo != null) add(Col(stringResource(Res.string.col_realized), 1f) { realizedByDay[it.date] })
                            add(Col(stringResource(Res.string.income), 1f) { it.income })
                            add(Col(stringResource(Res.string.expenses), 1f) { it.expenses })
                            add(Col(stringResource(Res.string.net), 1f) { it.net })
                        },
                    rows = daily,
                    empty = stringResource(Res.string.no_pnl_desc),
                    defaultSort = 0,
                    key = { it.date },
                ) { d ->
                    buildList {
                        add(Cell(d.date, AXIS_COLOR))
                        if (fifo != null) realizedByDay[d.date].let { add(Cell(it?.let(::signed) ?: "—", pnlColor(it))) }
                        add(Cell(formatIsk(d.income), AXIS_COLOR))
                        add(Cell(formatIsk(d.expenses), AXIS_COLOR))
                        add(Cell(signed(d.net), pnlColor(d.net)))
                    }
                }
            }
        }
    }
}

@Composable
private fun Strip(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.dp, outline(), MaterialTheme.shapes.extraSmall), content = content)
}

@Composable
private fun RowScope.PnlKpi(
    label: StringResource,
    window: String,
    v: Double,
    tip: StringResource,
) = Kpi(stringResource(label, window), signed(v), null, null, null, tip, valueColor = pnlColor(v))

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
