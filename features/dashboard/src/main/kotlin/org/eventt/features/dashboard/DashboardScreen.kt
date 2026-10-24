package org.eventt.features.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.*
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.AppLog
import org.eventt.core.model.CorpFeature
import org.eventt.dashboard.generated.resources.*
import org.eventt.features.market.ItemDetailDialog
import org.eventt.features.orders.ActiveOrdersSyncService
import org.eventt.features.orders.CostBasisService
import org.eventt.features.orders.WalletSyncService
import org.eventt.ui.common.*
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.time.Duration
import java.time.LocalDate
import java.util.Locale

private const val COMBINE_ALL_SETTING = "dashboard.combine_all"
private const val RANGE_SETTING = "dashboard.range"
private const val STACKED_SETTING = "dashboard.stacked"
private const val TAB_SETTING = "dashboard.tab"
private const val THE_FORGE = 10000002
private const val JITA_44 = 60003760L

// Orders this close to expiring are flagged.
private val EXPIRY_WARN = Duration.ofHours(48)

// yyyy-MM-dd… → MM-dd.
private fun shortDate(date: String) = date.take(10).drop(5)

internal enum class Tab(
    val label: StringResource,
) {
    POSITIONS(Res.string.tab_positions),
    ALLOCATION(Res.string.tab_allocation),
    ITEMS(Res.string.tab_items),
    COUNTERPARTIES(Res.string.tab_counterparties),
    TRADES(Res.string.tab_trades),
}

private data class ChartTarget(
    val typeId: Int,
    val name: String,
    val regionId: Int,
    val stationId: Long?,
)

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
    var range by remember { mutableStateOf(Range.M1) }
    var stacked by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.POSITIONS) }
    val scope = rememberCoroutineScope()

    fun save(
        key: String,
        value: String,
    ) {
        scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(key, value) }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            combined = StaticDataDao.getSetting(COMBINE_ALL_SETTING) == "true"
            StaticDataDao.getSetting(RANGE_SETTING)?.let { s -> Range.entries.find { it.name == s } }?.let { range = it }
            stacked = StaticDataDao.getSetting(STACKED_SETTING) == "true"
            StaticDataDao.getSetting(TAB_SETTING)?.let { s -> Tab.entries.find { it.name == s } }?.let { tab = it }
        }
    }

    var snap by remember { mutableStateOf<Snapshot?>(null) }
    var prices by remember { mutableStateOf<Map<Int, Double>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(false) }
    var deniedCorpFeatures by remember { mutableStateOf<Set<CorpFeature>>(emptySet()) }
    var chartTarget by remember { mutableStateOf<ChartTarget?>(null) }
    // Bumped by the "Retry" button on CorpAccessNotice after clearing the denied flags, so the
    // sync effect below re-runs and gives ESI another try — otherwise corpGuarded would just
    // keep skipping the call it already knows is denied.
    var retryNonce by remember { mutableStateOf(0) }

    LaunchedEffect(context, refreshTrigger, combined, retryNonce) {
        if (context == null) return@LaunchedEffect
        isLoading = true
        withContext(Dispatchers.IO) {
            val qChar = if (combined) null else charId
            val qCorp = if (combined) null else corpId
            val scopeKey =
                when {
                    combined -> "all"
                    corpId != null -> "corp:$corpId"
                    else -> "char:$charId"
                }

            fun load(walletNow: Double?) = loadSnapshot(qChar, qCorp, scopeKey, actingCharId, walletNow)

            try {
                snap = load(null)
            } catch (e: Exception) {
                AppLog.warn("Dashboard", e)
            }

            val acting = actingCharId
            if (acting != null && !combined) {
                // Journal + transactions + the active order book, the same per-context syncs the
                // Wallet and Orders screens run, then reload with the live wallet balance on top.
                val live =
                    runCatching {
                        if (corpId !=
                            null
                        ) {
                            EsiClient.getCorporationWallet(corpId, acting).values.sum()
                        } else {
                            EsiClient.getCharacterWallet(acting)
                        }
                    }.onFailure { AppLog.warn("Dashboard", "wallet ESI", it) }.getOrNull()
                if (corpId != null) WalletSyncService.syncCorporation(corpId, acting) else WalletSyncService.syncCharacter(acting)
                runCatching {
                    if (corpId !=
                        null
                    ) {
                        ActiveOrdersSyncService.syncCorporation(corpId, acting)
                    } else {
                        ActiveOrdersSyncService.syncCharacter(acting)
                    }
                }.onFailure { AppLog.warn("Dashboard", "orders ESI", it) }
                try {
                    snap = load(live)
                } catch (e: Exception) {
                    AppLog.warn("Dashboard", e)
                }
            }
            deniedCorpFeatures = if (acting != null && corpId != null) CharacterDao.getDeniedCorpFeatures(acting) else emptySet()
            isLoading = false
            snap?.let { prices = jitaSellPrices(it.fifo.inventory.keys) }
        }
        isLoading = false
    }

    if (context == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.no_character_selected), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Header(
            title =
                when {
                    combined -> stringResource(Res.string.dashboard_all)
                    corpName != null -> stringResource(Res.string.dashboard_corp, corpName)
                    else -> stringResource(Res.string.dashboard)
                },
            range = range,
            onRange = {
                range = it
                save(RANGE_SETTING, it.name)
            },
            stacked = stacked,
            onStacked = {
                stacked = it
                save(STACKED_SETTING, it.toString())
            },
            combined = combined,
            onCombined = {
                combined = it
                save(COMBINE_ALL_SETTING, it.toString())
            },
            isLoading = isLoading,
        )

        if (deniedCorpFeatures.isNotEmpty()) {
            CorpAccessNotice(
                deniedCorpFeatures,
                onRetry = {
                    val acting = actingCharId
                    if (acting != null) {
                        scope.launch(Dispatchers.IO) {
                            deniedCorpFeatures.forEach { CharacterDao.setCorpFeatureDenied(acting, it, denied = false) }
                            withContext(Dispatchers.Main) { retryNonce++ }
                        }
                    }
                },
            )
        }

        val s = snap
        if (s == null) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else {
            DashboardBody(
                s,
                prices,
                range,
                stacked,
                tab,
                onTab = {
                    tab = it
                    save(TAB_SETTING, it.name)
                },
                onOpenItem = { typeId, name -> chartTarget = ChartTarget(typeId, name, THE_FORGE, JITA_44) },
                onOpenOrder = { o -> chartTarget = ChartTarget(o.typeId, o.typeName, o.regionId, o.locationId.takeIf { !o.isBuyOrder }) },
            )
        }
    }

    chartTarget?.let { t ->
        val regionName by produceState("", t.regionId) {
            value = withContext(Dispatchers.IO) { StaticDataDao.getRegionById(t.regionId)?.name ?: "" }
        }
        ItemDetailDialog(
            typeId = t.typeId,
            typeName = t.name,
            primaryRegionId = t.regionId,
            primaryRegionName = regionName,
            primaryStationId = t.stationId,
            charId = actingCharId,
            corporationId = corpId.takeIf { !combined },
            onDismiss = { chartTarget = null },
        )
    }
}

/** Everything under the header, drawn from an already-loaded [Snapshot] — no DB or ESI here. */
@Composable
internal fun DashboardBody(
    s: Snapshot,
    prices: Map<Int, Double>,
    range: Range,
    stacked: Boolean,
    tab: Tab,
    onTab: (Tab) -> Unit,
    onOpenItem: (Int, String) -> Unit,
    onOpenOrder: (ActiveOrderDao.ActiveOrderRecord) -> Unit,
) {
    // A day clicked on the chart narrows the Trades tab to that day.
    var tradeDay by remember { mutableStateOf<String?>(null) }
    val rangeDays = s.days.takeLast(range.days.coerceAtMost(s.days.size))
    val from = rangeDays.firstOrNull()?.day ?: LocalDate.now().toString()
    val stats = remember(s, range) { rangeStats(rangeDays, s.fifo.realizedSells, s.txs, s.feesByDay) }
    val pos = remember(s, prices) { positions(s.fifo, prices) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KpiStrip(s, stats, pos)

        Row(Modifier.fillMaxWidth().height(400.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Panel(stringResource(Res.string.panel_chart), Modifier.weight(0.72f).fillMaxHeight()) {
                if (s.days.size < 2) {
                    Hint(stringResource(Res.string.no_history))
                } else {
                    NetWorthChart(s.days, range, stacked, Modifier.fillMaxSize()) { day ->
                        tradeDay = day
                        onTab(Tab.TRADES)
                    }
                }
            }
            Panel(stringResource(Res.string.panel_stats, stringResource(range.label)), Modifier.weight(0.28f).fillMaxHeight()) {
                StatsPanel(stats)
            }
        }

        Row(Modifier.fillMaxWidth().height(340.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Panel(stringResource(Res.string.panel_orders), Modifier.weight(0.36f).fillMaxHeight()) {
                OrdersPanel(s.orders, fills24h(s.txs), onOpenOrder)
            }
            Panel(
                null,
                Modifier.weight(0.64f).fillMaxHeight(),
                header = {
                    Tab.entries.forEach { t -> TabLabel(stringResource(t.label), t == tab) { onTab(t) } }
                    if (tab == Tab.TRADES) {
                        tradeDay?.let { d ->
                            Spacer(Modifier.weight(1f))
                            Segment("${stringResource(Res.string.day_filter, d)}  ✕", selected = true) { tradeDay = null }
                        }
                    }
                },
            ) {
                val unknown = stringResource(Res.string.unknown)
                when (tab) {
                    Tab.POSITIONS -> {
                        PositionsTable(pos, prices.isEmpty() && pos.isNotEmpty(), onOpenItem)
                    }

                    Tab.ALLOCATION -> {
                        val other = stringResource(Res.string.other)
                        AllocationPanel(remember(pos, s) { allocation(pos, s.groups, other) }, top5SharePct(pos))
                    }

                    Tab.ITEMS -> {
                        ItemsTable(remember(s, from) { itemPnl(s.fifo.realizedSells, from) { s.names[it] ?: "#$it" } }, onOpenItem)
                    }

                    Tab.COUNTERPARTIES -> {
                        CounterpartiesTable(remember(s) { counterparties(s.txs, unknown) }, unknown)
                    }

                    Tab.TRADES -> {
                        val day = tradeDay
                        val txs =
                            remember(s, day) {
                                if (day ==
                                    null
                                ) {
                                    s.txs
                                } else {
                                    s.txs.filter { (it["date"] as? String ?: "").startsWith(day) }
                                }
                            }
                        TradesTable(txs, unknown, onOpenItem)
                    }
                }
            }
        }
    }
}

// ─── Layout pieces ────────────────────────────────────────────────────────────────────────

@Composable
private fun Header(
    title: String,
    range: Range,
    onRange: (Range) -> Unit,
    stacked: Boolean,
    onStacked: (Boolean) -> Unit,
    combined: Boolean,
    onCombined: (Boolean) -> Unit,
    isLoading: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (isLoading) CircularProgressIndicator(Modifier.padding(start = 6.dp).size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.weight(1f))
        Range.entries.forEach { r -> Segment(stringResource(r.label), r == range) { onRange(r) } }
        Spacer(Modifier.width(8.dp))
        Tip(stringResource(Res.string.tip_stacked)) {
            Segment(stringResource(Res.string.composition), stacked) { onStacked(!stacked) }
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.combine_all), style = MaterialTheme.typography.labelSmall, color = AXIS_COLOR)
        Switch(checked = combined, onCheckedChange = onCombined, modifier = Modifier.height(24.dp))
    }
}

// ─── KPI strip ────────────────────────────────────────────────────────────────────────────

@Composable
private fun KpiStrip(
    s: Snapshot,
    stats: RangeStats?,
    pos: List<Position>,
) {
    val today = s.days.lastOrNull()
    val buys = s.orders.filter { it.order.isBuyOrder }
    val priced = pos.filter { it.netValue != null }
    val market = priced.sumOf { it.netValue ?: 0.0 }
    val unrealized = priced.sumOf { it.unrealized ?: 0.0 }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.dp, outline(), MaterialTheme.shapes.extraSmall),
    ) {
        Kpi(
            stringResource(Res.string.net_worth),
            formatIsk(today?.netWorth ?: 0.0),
            stats?.let { st -> signed(st.netWorthChange) + (st.netWorthChangePct?.let { "  " + pct(it) } ?: "") },
            pnlColor(stats?.netWorthChange),
            NET_COLOR,
            Res.string.tip_net_worth,
        )
        Kpi(stringResource(Res.string.wallet), formatIsk(today?.wallet ?: 0.0), null, null, WALLET_COLOR)
        Kpi(
            stringResource(Res.string.escrow),
            formatIsk(today?.escrow ?: 0.0),
            stringResource(Res.string.n_buy_orders, buys.size.toString()),
            null,
            ESCROW_COLOR,
            Res.string.tip_escrow,
        )
        Kpi(
            stringResource(Res.string.items),
            today?.items?.let(::formatIsk) ?: "—",
            stringResource(Res.string.in_sell_orders, formatIsk(s.sellOrdersNet)),
            null,
            ITEMS_COLOR,
            Res.string.tip_items,
        )
        Kpi(
            stringResource(Res.string.stock_cost),
            formatIsk(today?.stock ?: 0.0),
            stringResource(
                Res.string.n_types,
                s.fifo.inventory.size
                    .toString(),
            ),
            null,
            STOCK_COLOR,
            Res.string.tip_stock,
        )
        Kpi(
            stringResource(Res.string.stock_market),
            if (priced.isEmpty()) "—" else formatIsk(market),
            if (priced.isEmpty()) null else signed(unrealized),
            pnlColor(unrealized),
            null,
            Res.string.tip_stock_market,
        )
        Kpi(
            stringResource(Res.string.realized),
            stats?.let { signed(it.realized) } ?: "—",
            stats?.marginPct?.let { stringResource(Res.string.margin_n, pct(it)) },
            null,
            null,
            Res.string.tip_realized_pnl,
            valueColor = pnlColor(stats?.realized),
        )
        Kpi(
            stringResource(Res.string.cash_flow),
            stats?.let { signed(it.cashFlow) } ?: "—",
            null,
            null,
            null,
            Res.string.tip_cash_flow,
            valueColor = pnlColor(stats?.cashFlow),
            last = true,
        )
    }
}

// ─── Stats ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatsPanel(st: RangeStats) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        KV(stringResource(Res.string.st_realized), signed(st.realized), pnlColor(st.realized))
        KV(stringResource(Res.string.st_avg_day), signed(st.avgDay), pnlColor(st.avgDay))
        KV(stringResource(Res.string.st_sells), st.sells.toString())
        KV(stringResource(Res.string.st_win_rate), st.winRatePct?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—")
        KV(
            stringResource(Res.string.st_profit_factor),
            st.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "∞".takeIf { st.realized > 0 } ?: "—",
        )
        KV(stringResource(Res.string.st_margin), st.marginPct?.let(::pct) ?: "—", pnlColor(st.marginPct))
        KV(stringResource(Res.string.st_held), st.avgHeldDays?.let { String.format(Locale.US, "%.1f", it) } ?: "—")
        Spacer(Modifier.height(6.dp))
        Tip(stringResource(Res.string.tip_roi)) {
            Column {
                KV(stringResource(Res.string.st_capital), formatIsk(st.avgCapital))
                KV(stringResource(Res.string.st_roi), st.roiPct?.let(::pct) ?: "—", pnlColor(st.roiPct))
                KV(stringResource(Res.string.st_roi_year), st.roiYearPct?.let(::pct) ?: "—", pnlColor(st.roiYearPct))
            }
        }
        Spacer(Modifier.height(6.dp))
        KV(stringResource(Res.string.st_turnover), formatIsk(st.turnover))
        KV(stringResource(Res.string.st_fees), formatIsk(st.fees), negativeColor.takeIf { st.fees > 0 })
        KV(
            stringResource(Res.string.st_fees_share),
            if (st.turnover >
                0
            ) {
                String.format(Locale.US, "%.2f%%", st.fees / st.turnover * 100)
            } else {
                "—"
            },
        )
        KV(stringResource(Res.string.cash_flow), signed(st.cashFlow), pnlColor(st.cashFlow))
        Spacer(Modifier.height(6.dp))
        KV(
            stringResource(Res.string.st_best_day),
            st.bestDay?.let { "${it.day.drop(5)}  ${signed(it.realized)}" } ?: "—",
            positiveColor.takeIf {
                st.bestDay !=
                    null
            },
        )
        KV(
            stringResource(Res.string.st_worst_day),
            st.worstDay?.let { "${it.day.drop(5)}  ${signed(it.realized)}" } ?: "—",
            negativeColor.takeIf {
                st.worstDay !=
                    null
            },
        )
        Tip(stringResource(Res.string.tip_drawdown)) {
            KV(
                stringResource(Res.string.st_drawdown),
                if (st.maxDrawdown >
                    0
                ) {
                    "-" + formatIsk(st.maxDrawdown)
                } else {
                    "0"
                },
                negativeColor.takeIf { st.maxDrawdown > 0 },
            )
        }
        KV(
            stringResource(Res.string.st_net_worth_change),
            signed(st.netWorthChange) + (st.netWorthChangePct?.let { " (${pct(it)})" } ?: ""),
            pnlColor(st.netWorthChange),
        )
    }
}

// ─── Orders ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun OrdersPanel(
    orders: List<OrderRow>,
    fills: Fills24h,
    onOpen: (ActiveOrderDao.ActiveOrderRecord) -> Unit,
) {
    val (buys, sells) = orders.partition { it.order.isBuyOrder }
    val expiring = orders.filter { it.expiresIn != null && it.expiresIn < EXPIRY_WARN }
    val outbid = orders.filter { it.outbid }
    KV(stringResource(Res.string.ord_buy), "${buys.size} · ${formatIsk(buys.sumOf { it.isk })}", positiveColor.takeIf { buys.isNotEmpty() })
    KV(
        stringResource(Res.string.ord_sell),
        "${sells.size} · ${formatIsk(sells.sumOf { it.isk })}",
        negativeColor.takeIf { sells.isNotEmpty() },
    )
    KV(
        stringResource(Res.string.ord_outbid),
        "${outbid.count { it.order.isBuyOrder }} / ${outbid.count { !it.order.isBuyOrder }}",
        warningColor.takeIf { outbid.isNotEmpty() },
    )
    KV(stringResource(Res.string.ord_expiring), expiring.size.toString(), warningColor.takeIf { expiring.isNotEmpty() })
    orders
        .count {
            it.standing
        }.takeIf { it > 0 }
        ?.let { Tip(stringResource(Res.string.tip_standing)) { KV(stringResource(Res.string.ord_standing), it.toString()) } }
    KV(stringResource(Res.string.ord_fills_24h), "${fills.buys} / ${fills.sells}")
    KV(stringResource(Res.string.ord_volume_24h), "${formatIsk(fills.buyIsk)} / ${formatIsk(fills.sellIsk)}")
    Spacer(Modifier.height(6.dp))
    // Orders that need a hand: beaten first, then the ones about to expire, biggest first.
    val attention = (outbid.sortedByDescending { it.isk } + expiring.sortedBy { it.expiresIn }).distinct()
    Text(
        stringResource(Res.string.attention).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = AXIS_COLOR,
    )
    if (attention.isEmpty()) {
        Hint(stringResource(if (orders.isEmpty()) Res.string.no_orders else Res.string.all_orders_fine))
        return
    }
    val outbidWord = stringResource(Res.string.outbid_by)
    val expiresWord = stringResource(Res.string.expires_in)
    LazyColumn {
        items(attention, key = { it.order.orderId }) { o ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(o.order) }.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SideBadge(o.order.isBuyOrder)
                Text(
                    o.order.typeName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (o.outbid && o.best != null) {
                        "$outbidWord ${formatPriceAbbr(o.best)}"
                    } else {
                        "$expiresWord ${o.expiresIn?.toHours()?.coerceAtLeast(0)}h"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = Mono,
                    color = warningColor,
                )
            }
        }
    }
}

@Composable
private fun SideBadge(isBuy: Boolean) {
    val c = if (isBuy) positiveColor else negativeColor
    Text(
        stringResource(if (isBuy) Res.string.buy_badge else Res.string.sell_badge),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        color = c,
        modifier = Modifier.border(1.dp, c.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall).padding(horizontal = 3.dp),
    )
}

// ─── Tables ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun PositionsTable(
    pos: List<Position>,
    pricesLoading: Boolean,
    onOpen: (Int, String) -> Unit,
) {
    val cost = pos.sumOf { it.cost }
    val priced = pos.filter { it.netValue != null }
    val unrealized = priced.sumOf { it.unrealized ?: 0.0 }
    val pending = if (pricesLoading) "…" else "—"
    Table(
        cols =
            listOf(
                Col<Position>(stringResource(Res.string.col_item), 2.6f, end = false) { it.name.lowercase() },
                Col(stringResource(Res.string.col_qty), 0.8f) { it.qty },
                Col(stringResource(Res.string.col_avg_cost), 1f) { it.avgCost },
                Col(stringResource(Res.string.col_jita_sell), 1f) { it.price },
                Col(stringResource(Res.string.col_cost), 1f) { it.cost },
                Col(stringResource(Res.string.col_unrealized), 1.1f) { it.unrealized },
                Col("%", 0.7f) { it.unrealizedPct },
                // Sorts by when the position was opened: descending = freshest first.
                Col(stringResource(Res.string.col_days), 0.5f) { it.since },
            ),
        rows = pos,
        defaultSort = 5,
        empty = stringResource(Res.string.no_positions),
        key = { it.typeId },
        onClick = { onOpen(it.typeId, it.name) },
        footer =
            listOf(
                Cell(stringResource(Res.string.total)),
                Cell(""),
                Cell(""),
                Cell(""),
                Cell(formatIsk(cost)),
                Cell(if (priced.isEmpty()) pending else signed(unrealized), pnlColor(unrealized)),
                Cell(if (priced.isEmpty() || cost <= 0) "" else pct(unrealized / priced.sumOf { it.cost } * 100), pnlColor(unrealized)),
                Cell(""),
            ),
    ) { p ->
        val stale = p.daysHeld != null && p.daysHeld > 30
        listOf(
            Cell(p.name),
            Cell(formatVolume(p.qty.toLong())),
            Cell(formatPriceAbbr(p.avgCost)),
            Cell(p.price?.let(::formatPriceAbbr) ?: pending),
            Cell(formatIsk(p.cost)),
            Cell(p.unrealized?.let(::signed) ?: pending, pnlColor(p.unrealized)),
            Cell(p.unrealizedPct?.let(::pct) ?: "", pnlColor(p.unrealized)),
            Cell(p.daysHeld?.toString() ?: "", warningColor.takeIf { stale }),
        )
    }
}

@Composable
private fun ItemsTable(
    items: List<ItemPnl>,
    onOpen: (Int, String) -> Unit,
) {
    Table(
        cols =
            listOf(
                Col<ItemPnl>(stringResource(Res.string.col_item), 3f, end = false) { it.name.lowercase() },
                Col(stringResource(Res.string.col_sold), 1f) { it.qty },
                Col(stringResource(Res.string.col_profit), 1.2f) { it.profit },
                Col(stringResource(Res.string.col_margin), 0.9f) { it.marginPct },
                Col(stringResource(Res.string.col_held), 0.8f) { it.avgHeldDays },
                Col(stringResource(Res.string.col_last), 0.9f) { it.lastSold },
            ),
        rows = items,
        defaultSort = 5,
        empty = stringResource(Res.string.no_realized_sales),
        key = { it.typeId },
        onClick = { onOpen(it.typeId, it.name) },
    ) { i ->
        listOf(
            Cell(i.name),
            Cell(formatVolume(i.qty.toLong())),
            Cell(signed(i.profit), pnlColor(i.profit)),
            Cell(i.marginPct?.let(::pct) ?: "—", pnlColor(i.marginPct)),
            Cell(String.format(Locale.US, "%.1f", i.avgHeldDays), warningColor.takeIf { i.avgHeldDays > 30 }),
            Cell(shortDate(i.lastSold), AXIS_COLOR),
        )
    }
}

// Stock by market group: a share bar per group, biggest first, and how concentrated it is.
@Composable
private fun AllocationPanel(
    rows: List<Allocation>,
    top5Pct: Double?,
) {
    if (rows.isEmpty()) {
        Hint(stringResource(Res.string.no_positions))
        return
    }
    KV(
        stringResource(Res.string.top5_share),
        top5Pct?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—",
        warningColor.takeIf {
            (
                top5Pct
                    ?: 0.0
            ) >
                60
        },
    )
    Spacer(Modifier.height(4.dp))
    val bar = STOCK_COLOR
    LazyColumn {
        items(rows, key = { it.group }) { a ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    a.group,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(2f),
                )
                Box(Modifier.weight(3f).height(10.dp)) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(
                                (a.sharePct / 100).toFloat().coerceIn(0.005f, 1f),
                            ).background(bar.copy(alpha = 0.7f)),
                    )
                }
                Text(
                    String.format(Locale.US, "%5.1f%%", a.sharePct),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = Mono,
                    modifier = Modifier.width(52.dp),
                    textAlign = TextAlign.End,
                )
                Text(
                    formatIsk(a.value),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = Mono,
                    modifier = Modifier.width(72.dp),
                    textAlign = TextAlign.End,
                )
                Text(
                    "${a.types}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = Mono,
                    color = AXIS_COLOR,
                    modifier = Modifier.width(28.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun CounterpartiesTable(
    rows: List<Counterparty>,
    unknown: String,
) {
    Table(
        cols =
            listOf(
                Col<Counterparty>(stringResource(Res.string.col_counterparty), 3f, end = false) { it.name.lowercase() },
                Col(stringResource(Res.string.col_bought_from_us), 1.2f) { it.boughtFromUs },
                Col(stringResource(Res.string.col_sold_to_us), 1.2f) { it.soldToUs },
                Col(stringResource(Res.string.col_trades), 0.7f) { it.count },
                Col(stringResource(Res.string.total), 1.2f) { it.total },
            ),
        rows = rows,
        defaultSort = 4,
        empty = stringResource(Res.string.no_transactions_recorded),
        key = { it.name },
        // Wallet → Transactions filtered to this client: what exactly they bought and sold.
        onClick = { if (it.name != unknown) AppState.openInWallet(it.name) },
    ) { c ->
        listOf(
            Cell(c.name),
            // Same colours as the Trades tab: our buys (they sold to us) green, our sells red.
            Cell(if (c.boughtFromUs > 0) formatIsk(c.boughtFromUs) else "—", negativeColor.takeIf { c.boughtFromUs > 0 }),
            Cell(if (c.soldToUs > 0) formatIsk(c.soldToUs) else "—", positiveColor.takeIf { c.soldToUs > 0 }),
            Cell(c.count.toString()),
            Cell(formatIsk(c.total)),
        )
    }
}

@Composable
private fun TradesTable(
    txs: List<Map<String, Any?>>,
    unknown: String,
    onOpen: (Int, String) -> Unit,
) {
    Table(
        cols =
            listOf(
                Col<Map<String, Any?>>(stringResource(Res.string.col_time), 1.2f, end = false) { it["date"] as? String },
                Col(stringResource(Res.string.col_side), 0.6f, end = false) { it["is_buy"] == true },
                Col(stringResource(Res.string.col_item), 2.4f, end = false) { (it["type_name"] as? String)?.lowercase() },
                Col(stringResource(Res.string.col_qty), 0.8f) { (it["quantity"] as? Number)?.toLong() },
                Col(stringResource(Res.string.col_price), 1f) { (it["unit_price"] as? Number)?.toDouble() },
                Col(stringResource(Res.string.col_isk), 1f) { txTotal(it) },
                Col(stringResource(Res.string.col_counterparty), 1.6f, end = false) { (it["client_name"] as? String)?.lowercase() },
            ),
        rows = txs,
        defaultSort = 0,
        empty = stringResource(Res.string.no_transactions_recorded),
        onClick = { tx -> (tx["type_id"] as? Number)?.toInt()?.let { onOpen(it, tx["type_name"] as? String ?: "") } },
    ) { tx ->
        val isBuy = tx["is_buy"] == true
        val c = if (isBuy) positiveColor else negativeColor
        listOf(
            Cell((tx["date"] as? String ?: "").drop(5).take(11).replace('T', ' '), AXIS_COLOR),
            Cell(stringResource(if (isBuy) Res.string.buy_badge else Res.string.sell_badge), c),
            Cell(tx["type_name"] as? String ?: unknown),
            Cell(formatVolume(((tx["quantity"] as? Number)?.toLong() ?: 0L))),
            Cell(formatPriceAbbr((tx["unit_price"] as? Number)?.toDouble() ?: 0.0)),
            Cell(formatIsk(txTotal(tx)), c),
            Cell((tx["client_name"] as? String)?.ifEmpty { null } ?: unknown, AXIS_COLOR),
        )
    }
}
