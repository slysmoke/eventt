package org.eventt.features.p2pmarket

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.LeaderboardRankDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.model.CharacterModel
import org.eventt.core.nostr.LeaderboardEntry
import org.eventt.core.nostr.LeaderboardPublish
import org.eventt.core.nostr.LeaderboardService
import org.eventt.core.nostr.LeaderboardStats
import org.eventt.core.nostr.NostrIdentityService
import org.eventt.p2pmarket.generated.resources.*
import org.eventt.ui.common.*
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

private const val WINDOW_SETTING = "leaderboard.window"

private fun pctOrDash(v: Double?) = v?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "—"

private fun plainPct(v: Double?) = v?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"

private fun ratio(v: Double?) = v?.let { String.format(Locale.US, "%.2f", it) } ?: "—"

private fun nowSec() = System.currentTimeMillis() / 1000

/**
 * Opt-in trader leaderboard (issue #17), its own top-level page: one ranked, sortable table of
 * every trader's self-published entry ([LeaderboardService.entries]) with a detail card for the
 * selected one, and below it the publishing controls — which single local character's identity
 * signs and publishes *this machine's* combined figures (see LeaderboardPublisher in
 * features/dashboard), whether net worth goes with them, and what went out last.
 */
@Composable
fun LeaderboardScreen() {
    val entries by LeaderboardService.entries.collectAsState()
    LeaderboardContent(entries.values)
}

/** The whole page for a given set of entries — split out so it can be drawn without relays. */
@Composable
internal fun LeaderboardContent(entries: Collection<LeaderboardEntry>) {
    val scope = rememberCoroutineScope()
    var window by remember { mutableStateOf(LbWindow.MONTH) }
    var myPubkeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var previous by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var selected by remember { mutableStateOf<String?>(null) }
    // Re-read every minute so "updated 3h ago" and staleness keep moving while the page is open.
    var now by remember { mutableStateOf(nowSec()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = nowSec()
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            StaticDataDao.getSetting(WINDOW_SETTING)?.let { s -> LbWindow.entries.find { it.name == s } }?.let { window = it }
            myPubkeys = NostrIdentityService.listIdentities().map { it.pubkey }.toSet()
        }
    }
    LaunchedEffect(window) {
        previous = withContext(Dispatchers.IO) { LeaderboardRankDao.previous(LocalDate.now().toString(), window.key) }
    }
    val ranked = remember(entries, window, previous, myPubkeys, now) { rank(entries, window, previous, myPubkeys, now) }
    // Today's places, for tomorrow's arrows.
    LaunchedEffect(ranked) {
        if (ranked.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                LeaderboardRankDao.record(LocalDate.now().toString(), window.key, ranked.associate { it.entry.pubkey to it.rank })
            }
        }
    }
    val focus = ranked.find { it.entry.pubkey == selected } ?: ranked.find { it.mine } ?: ranked.firstOrNull()

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(Res.string.trader_leaderboard),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            LbWindow.entries.forEach { w ->
                Segment(stringResource(w.label), w == window) {
                    window = w
                    scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(WINDOW_SETTING, w.name) }
                }
            }
        }

        SummaryStrip(ranked, window, now)

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Panel(stringResource(Res.string.lb_ranking, stringResource(window.label)), Modifier.weight(0.7f).fillMaxHeight()) {
                RankingTable(ranked, window, now) { selected = it.entry.pubkey }
            }
            Panel(stringResource(Res.string.lb_trader), Modifier.weight(0.3f).fillMaxHeight()) {
                if (focus == null) Hint(stringResource(Res.string.no_one_opted)) else TraderCard(focus, now)
            }
        }

        PublishPanel()
    }
}

@Composable
private fun SummaryStrip(
    ranked: List<Ranked>,
    window: LbWindow,
    now: Long,
) {
    val sum = summary(ranked, window)
    val me = ranked.find { it.mine }
    val leader = ranked.firstOrNull { !it.stale }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), MaterialTheme.shapes.extraSmall),
    ) {
        Kpi(
            stringResource(Res.string.lb_traders),
            sum.traders.toString(),
            stringResource(Res.string.lb_active_24h, sum.active.toString()),
            null,
            null,
        )
        Kpi(
            stringResource(Res.string.lb_your_place),
            me?.let { "#${it.rank}" } ?: "—",
            when {
                me == null -> stringResource(Res.string.lb_not_on_board)
                me.change == null -> stringResource(Res.string.lb_new)
                me.change == 0 -> stringResource(Res.string.lb_no_change)
                else -> (if (me.change > 0) "▲" else "▼") + abs(me.change)
            },
            me?.change?.let {
                if (it > 0) {
                    positiveColor
                } else if (it < 0) {
                    negativeColor
                } else {
                    null
                }
            },
            null,
        )
        Kpi(
            stringResource(Res.string.lb_leader),
            leader?.let { window.pnl(it.entry.stats) }?.let(::signed) ?: "—",
            leader?.entry?.displayName(),
            null,
            null,
            valueColor = leader?.let { pnlColor(window.pnl(it.entry.stats)) },
        )
        Kpi(
            stringResource(Res.string.lb_median),
            sum.medianPnl?.let(::signed) ?: "—",
            stringResource(Res.string.lb_active_only),
            null,
            null,
            valueColor = pnlColor(sum.medianPnl),
        )
        Kpi(
            stringResource(Res.string.lb_total),
            signed(sum.totalPnl),
            me?.let { stringResource(Res.string.lb_updated, agoText(it.entry.updatedAt, now)) },
            null,
            null,
            valueColor = pnlColor(sum.totalPnl),
            last = true,
        )
    }
}

private fun LeaderboardEntry.displayName() = traderChar.ifBlank { pubkey.take(12) + "…" }

@Composable
private fun agoText(
    sec: Long,
    now: Long,
): String {
    val (n, unit) = ago(sec, now)
    return stringResource(unit, n.toString())
}

@Composable
private fun RankingTable(
    ranked: List<Ranked>,
    window: LbWindow,
    now: Long,
    onSelect: (Ranked) -> Unit,
) {
    Table(
        cols =
            listOf(
                Col<Ranked>("#", 0.35f) { it.rank },
                Col("", 0.4f) { it.change },
                Col(stringResource(Res.string.lb_col_trader), 2.2f, end = false) { it.entry.displayName().lowercase() },
                Col(stringResource(Res.string.lb_col_pnl), 1.1f) { window.pnl(it.entry.stats) },
                Col(stringResource(Res.string.lb_col_roi), 0.8f) { window.roi(it.entry.stats) },
                Col(stringResource(Res.string.lb_col_win), 0.6f) { it.entry.stats.winRate30dPct },
                Col(stringResource(Res.string.lb_col_margin), 0.7f) { it.entry.stats.margin30dPct },
                Col(stringResource(Res.string.lb_col_pf), 0.6f) { it.entry.stats.profitFactor30d },
                Col(stringResource(Res.string.lb_col_turnover), 0.9f) { it.entry.stats.turnover30d },
                Col(stringResource(Res.string.lb_col_net_worth), 0.9f) { it.entry.stats.netWorth },
                Col(stringResource(Res.string.lb_col_30d), 1.1f),
                Col(stringResource(Res.string.lb_col_updated), 0.6f) { it.entry.updatedAt },
            ),
        rows = ranked,
        empty = stringResource(Res.string.no_one_opted),
        // By place, best first — out-of-date entries stay at the bottom.
        defaultSort = 0,
        defaultDesc = false,
        key = { it.entry.pubkey },
        onClick = onSelect,
        highlight = { it.mine },
    ) { r ->
        val s = r.entry.stats
        val dim = AXIS_COLOR.takeIf { r.stale }
        val pnl = window.pnl(s)
        listOf(
            Cell("${r.rank}", dim ?: AXIS_COLOR),
            Cell(
                when {
                    r.change == null -> "•"
                    r.change > 0 -> "▲${r.change}"
                    r.change < 0 -> "▼${-r.change}"
                    else -> "–"
                },
                when {
                    r.change == null -> MaterialTheme.colorScheme.primary
                    r.change > 0 -> positiveColor
                    r.change < 0 -> negativeColor
                    else -> AXIS_COLOR
                },
            ),
            Cell(r.entry.displayName()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CharacterPortrait(r.entry.traderCharId, 20.dp)
                    Text(
                        r.entry.displayName() + if (r.mine) "  ★" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold.takeIf { r.mine },
                        color = dim ?: MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            Cell(signed(pnl), dim ?: pnlColor(pnl)),
            Cell(pctOrDash(window.roi(s)), dim ?: pnlColor(window.roi(s))),
            Cell(plainPct(s.winRate30dPct), dim),
            Cell(pctOrDash(s.margin30dPct), dim ?: pnlColor(s.margin30dPct)),
            Cell(ratio(s.profitFactor30d), dim),
            Cell(s.turnover30d?.let(::formatIsk) ?: "—", dim),
            Cell(s.netWorth?.let(::formatIsk) ?: "—", dim),
            Cell("") { Sparkline(s.daily30d, Modifier.fillMaxWidth().height(16.dp)) },
            Cell(agoText(r.entry.updatedAt, now), if (r.stale) negativeColor else AXIS_COLOR),
        )
    }
}

// Daily P&L as thin up/down bars around a zero line.
@Composable
private fun Sparkline(
    daily: List<Double>,
    modifier: Modifier = Modifier,
) {
    if (daily.isEmpty()) {
        Text("—", style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR)
        return
    }
    val up = positiveColor
    val down = negativeColor
    val axis = AXIS_COLOR.copy(alpha = 0.5f)
    Canvas(modifier) {
        val maxAbs = daily.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        val mid = size.height / 2
        val slot = size.width / daily.size
        drawLine(axis, Offset(0f, mid), Offset(size.width, mid), 1f)
        daily.forEachIndexed { i, v ->
            if (v == 0.0) return@forEachIndexed
            val h = (abs(v) / maxAbs * mid).toFloat().coerceAtLeast(1f)
            drawRect(if (v > 0) up else down, Offset(i * slot, if (v > 0) mid - h else mid), Size((slot - 1f).coerceAtLeast(1f), h))
        }
    }
}

@Composable
private fun TraderCard(
    r: Ranked,
    now: Long,
) {
    val s = r.entry.stats
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CharacterPortrait(r.entry.traderCharId, 56.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.entry.displayName(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TraderInfoButton(r.entry.traderChar, r.entry.traderCharId)
                }
                Text(
                    "#${r.rank} · " + stringResource(Res.string.lb_updated, agoText(r.entry.updatedAt, now)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = Mono,
                    color = if (r.stale) negativeColor else AXIS_COLOR,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(Res.string.lb_daily_30d).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = AXIS_COLOR,
        )
        Sparkline(s.daily30d, Modifier.fillMaxWidth().height(56.dp))
        Spacer(Modifier.height(6.dp))
        KV(stringResource(Res.string.lb_pnl_7d), signed(s.pnl7d), pnlColor(s.pnl7d))
        KV(stringResource(Res.string.lb_pnl_30d), signed(s.pnl30d), pnlColor(s.pnl30d))
        KV(stringResource(Res.string.lb_pnl_365d), signed(s.pnl365d), pnlColor(s.pnl365d))
        KV(
            stringResource(Res.string.lb_roi_7_30_365),
            listOf(s.roi7dPct, s.roi30dPct, s.roi365dPct).joinToString(" / ", transform = ::pctOrDash),
        )
        Spacer(Modifier.height(4.dp))
        KV(stringResource(Res.string.lb_win_rate), plainPct(s.winRate30dPct))
        KV(stringResource(Res.string.lb_margin), pctOrDash(s.margin30dPct), pnlColor(s.margin30dPct))
        KV(stringResource(Res.string.lb_profit_factor), ratio(s.profitFactor30d))
        KV(stringResource(Res.string.lb_sells), s.sells30d?.toString() ?: "—")
        KV(stringResource(Res.string.lb_turnover), s.turnover30d?.let(::formatIsk) ?: "—")
        KV(stringResource(Res.string.lb_net_worth), s.netWorth?.let(::formatIsk) ?: stringResource(Res.string.lb_not_shared))
    }
}

// Who publishes, whether net worth goes along, and what went out last.
@Composable
private fun PublishPanel() {
    val scope = rememberCoroutineScope()
    var characters by remember { mutableStateOf<List<CharacterModel>>(emptyList()) }
    var publisherCharId by remember { mutableStateOf<Int?>(null) }
    var shareNetWorth by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val last by LeaderboardService.lastPublish.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            characters = CharacterDao.getAll()
            publisherCharId = StaticDataDao.getLeaderboardPublisherCharId()
            shareNetWorth = StaticDataDao.getLeaderboardShareNetWorth()
        }
    }

    fun select(newCharId: Int?) {
        val previousCharId = publisherCharId
        publisherCharId = newCharId
        menuExpanded = false
        scope.launch(Dispatchers.IO) {
            StaticDataDao.setLeaderboardPublisherCharId(newCharId)
            // Switching publisher (or turning off) leaves the old identity's entry stale forever
            // otherwise — the sweep only ever publishes the *current* choice, never cleans up a
            // previous one. Tombstone here is what makes the old entry disappear right away.
            if (previousCharId != null && previousCharId != newCharId) {
                NostrIdentityService.listIdentities().find { it.characterId == previousCharId }?.let {
                    LeaderboardService.publishTombstone(it)
                }
            }
            if (newCharId != null) LeaderboardService.requestPublish()
        }
    }

    Panel(stringResource(Res.string.lb_publishing)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.publish_as), style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR)
            if (characters.isEmpty()) {
                Text(stringResource(Res.string.no_characters_yet), style = MaterialTheme.typography.bodySmall)
            } else {
                Box {
                    Segment(characters.find { it.id == publisherCharId }?.name ?: stringResource(Res.string.off), publisherCharId != null) {
                        menuExpanded = true
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.off)) }, onClick = { select(null) })
                        characters.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { select(c.id) }) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = shareNetWorth,
                    onCheckedChange = {
                        shareNetWorth = it
                        scope.launch(Dispatchers.IO) {
                            StaticDataDao.setLeaderboardShareNetWorth(it)
                            LeaderboardService.requestPublish()
                        }
                    },
                    enabled = publisherCharId != null,
                )
                Tip(stringResource(Res.string.lb_share_net_worth_tip)) {
                    Text(stringResource(Res.string.lb_share_net_worth), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.weight(1f))
            LastPublish(last)
            if (publisherCharId != null) Segment(stringResource(Res.string.lb_publish_now), false) { LeaderboardService.requestPublish() }
        }
        Text(
            stringResource(Res.string.lb_desc) + " " + stringResource(Res.string.self_reported),
            style = MaterialTheme.typography.labelSmall,
            color = AXIS_COLOR,
        )
    }
}

@Composable
private fun LastPublish(last: LeaderboardPublish?) {
    if (last == null) {
        Text(
            stringResource(Res.string.lb_not_published),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = Mono,
            color = AXIS_COLOR,
        )
        return
    }
    val s: LeaderboardStats = last.stats
    Tip(
        stringResource(
            Res.string.lb_last_sent,
            signed(s.pnl7d),
            signed(s.pnl30d),
            signed(s.pnl365d),
            pctOrDash(s.roi30dPct),
            s.netWorth?.let(::formatIsk) ?: stringResource(Res.string.lb_not_shared),
        ),
    ) {
        Text(
            stringResource(Res.string.lb_published, agoText(last.at, nowSec()), last.traderChar),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = Mono,
            color = positiveColor,
        )
    }
}
