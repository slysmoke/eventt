package org.eventt.features.p2pmarket

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.CharacterDao
import org.eventt.core.model.AppLog
import org.eventt.core.nostr.GIVEAWAY_ADMIN_PUBKEY
import org.eventt.core.nostr.Giveaway
import org.eventt.core.nostr.GiveawayParticipant
import org.eventt.core.nostr.GiveawayService
import org.eventt.core.nostr.GiveawayStatus
import org.eventt.core.nostr.LeaderboardEntry
import org.eventt.core.nostr.NostrIdentityService
import org.eventt.p2pmarket.generated.resources.*
import org.eventt.ui.common.*
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val BLOCK_POLL_MS = 30_000L

// The latest giveaway's winners, wherever their names show.
internal val GOLD = Color(0xFFE8B730)

internal enum class DrawUnit(
    val seconds: Long,
    val label: StringResource,
) {
    MINUTES(60, Res.string.gw_unit_min),
    HOURS(3600, Res.string.gw_unit_hour),
    DAYS(86400, Res.string.gw_unit_day),
}

/** What the organiser fills in to announce a giveaway; numbers kept as typed. */
internal data class GiveawayForm(
    val plex: String = "500",
    val winners: String = "3",
    val activeDays: String = "7",
    val drawIn: String = "7",
    val unit: DrawUnit = DrawUnit.DAYS,
    val note: String = "",
    val test: Boolean = false,
) {
    fun toGiveaway(nowSec: Long): Giveaway? {
        val plex = plex.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val winners = winners.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val days = activeDays.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val drawIn = drawIn.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return Giveaway(
            "g-$nowSec",
            GiveawayStatus.ANNOUNCED,
            plex,
            winners,
            days,
            nowSec + drawIn * unit.seconds,
            note.trim(),
            test = test,
        )
    }
}

private fun nowSec() = System.currentTimeMillis() / 1000

// Ticks once a second while composed — for countdowns.
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableStateOf(nowSec()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = nowSec()
        }
    }
    return now
}

/** "2d 4h" / "4h 12m" / "03:41" until [sec]; never negative. */
@Composable
internal fun timeLeft(sec: Long): String {
    val d = sec.coerceAtLeast(0)
    return when {
        d >= 86400 -> stringResource(Res.string.gw_left_dh, (d / 86400).toString(), (d % 86400 / 3600).toString())
        d >= 3600 -> stringResource(Res.string.gw_left_hm, (d / 3600).toString(), (d % 3600 / 60).toString())
        else -> String.format(Locale.US, "%02d:%02d", d / 60, d % 60)
    }
}

/** Whether this machine holds the organiser's key — loaded once. */
@Composable
private fun rememberIsAdmin(): Boolean {
    var isAdmin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isAdmin = NostrIdentityService.getIdentityByPubkey(GIVEAWAY_ADMIN_PUBKEY) != null }
    return isAdmin
}

/** Gift icon and countdown next to "Leaderboard" in the sidebar while a giveaway is running. */
@Composable
fun GiveawayNavBadge() {
    val all by GiveawayService.giveaways.collectAsState()
    val g = currentGiveaway(all.values, rememberIsAdmin()) ?: return
    if (g.status != GiveawayStatus.ANNOUNCED && g.status != GiveawayStatus.CLOSED) return
    val now = rememberNow()
    val color = MaterialTheme.colorScheme.primary
    val announced = g.status == GiveawayStatus.ANNOUNCED
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        // Hourglass once entries are closed: the countdown is over, the Bitcoin block is awaited.
        Icon(
            if (announced) Icons.Default.CardGiftcard else Icons.Default.HourglassEmpty,
            null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(
            // Once closed, how long the block has been awaited: Bitcoin blocks come at random, so any
            // "time left" would only ever say "about ten minutes".
            if (announced) timeLeft(g.drawAt - now) else shortDuration(now - g.updatedAt),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = Mono,
            color = color,
        )
    }
}

/** "35m" / "2h 5m" — how long [sec] is, in whole minutes under an hour. */
@Composable
private fun shortDuration(sec: Long): String {
    val d = sec.coerceAtLeast(0)
    return if (d < 3600) stringResource(Res.string.gw_left_m, (d / 60).toString()) else timeLeft(d)
}

/**
 * The PLEX giveaway strip on the leaderboard: everyone sees the current giveaway, its countdown,
 * whether they're in and — once the named Bitcoin block is out — the winners, recomputed locally.
 * Only a machine holding the organiser's key gets the buttons; closing and drawing then run on
 * their own (see [GiveawayAutomation]).
 */
@Composable
internal fun GiveawayPanel(
    entries: Collection<LeaderboardEntry>,
    myPubkeys: Set<String>,
) {
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    val all by GiveawayService.giveaways.collectAsState()
    val isAdmin = GIVEAWAY_ADMIN_PUBKEY in myPubkeys
    val g = currentGiveaway(all.values, isAdmin)
    var myCharIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    LaunchedEffect(isAdmin) {
        if (isAdmin) myCharIds = withContext(Dispatchers.IO) { CharacterDao.getAll().map { it.id }.toSet() }
    }

    // The named block: polled until it exists, then fixed.
    var tip by remember { mutableStateOf<BlockTip?>(null) }
    var blockHash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(g?.id, g?.btcHeight) {
        blockHash = null
        val height = g?.btcHeight ?: return@LaunchedEffect
        while (blockHash == null) {
            withContext(Dispatchers.IO) {
                tip = BitcoinBlocks.tip()
                blockHash = BitcoinBlocks.hashAt(height)
            }
            if (blockHash == null) delay(BLOCK_POLL_MS)
        }
    }

    var form by remember { mutableStateOf<GiveawayForm?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun act(block: () -> Giveaway) {
        busy = true
        error = null
        scope.launch {
            runCatching {
                val next = withContext(Dispatchers.IO) { block() }
                val identity = requireNotNull(NostrIdentityService.getIdentityByPubkey(GIVEAWAY_ADMIN_PUBKEY))
                GiveawayService.publish(identity, next)
                form = null
            }.onFailure {
                AppLog.warn("Giveaway", "publish", it)
                error = it.message ?: it.toString()
            }
            busy = false
        }
    }

    if (g == null && !isAdmin) return
    // Matches closeGiveaway: a test run includes the organiser; otherwise the organiser's machine
    // knows all of their identities and characters, everyone else just the organiser's key.
    val exclude =
        when {
            g?.test == true -> emptySet()
            isAdmin -> myPubkeys
            else -> setOf(GIVEAWAY_ADMIN_PUBKEY)
        }
    val excludeChars = if (g?.test == true) emptySet() else myCharIds
    val eligibleNow =
        remember(
            entries,
            g?.activeDays,
            now / 60,
            exclude,
            excludeChars,
        ) { eligible(entries, g?.activeDays ?: 7, now, exclude, excludeChars) }

    Panel(stringResource(Res.string.gw_title), Modifier.fillMaxWidth(), header = {
        g?.let { StatusChip(it.status) }
        if (g?.test ==
            true
        ) {
            Text(
                stringResource(Res.string.gw_test).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = Mono,
                color = negativeColor,
            )
        }
        Spacer(Modifier.weight(1f))
        Tip(stringResource(Res.string.gw_tip_fair)) {
            Text(stringResource(Res.string.gw_fair), style = MaterialTheme.typography.labelSmall, color = AXIS_COLOR)
        }
    }) {
        val f = form
        if (f != null) {
            AnnounceForm(f, busy, onChange = { form = it }, onCancel = { form = null }) {
                f.toGiveaway(nowSec())?.let { new -> act { new } }
            }
        } else if (g != null) {
            GiveawayCard(g, eligibleNow, myPubkeys, isAdmin, tip, blockHash, now)
        }
        if (isAdmin && form == null) {
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (g?.status) {
                    null, GiveawayStatus.DRAWN, GiveawayStatus.CANCELLED -> {
                        ActionButton(stringResource(if (g == null) Res.string.gw_announce else Res.string.gw_new)) { form = GiveawayForm() }
                    }

                    GiveawayStatus.ANNOUNCED -> {
                        ActionButton(stringResource(Res.string.gw_close), enabled = eligibleNow.isNotEmpty(), busy = busy) {
                            act { closeGiveaway(g, entries, nowSec(), myPubkeys, myCharIds) }
                        }
                        // Only before the list is frozen: cancelling after the block is out would
                        // let the organiser throw away a result they don't like.
                        ActionButton(
                            stringResource(Res.string.gw_cancel),
                            busy = busy,
                        ) { act { g.copy(status = GiveawayStatus.CANCELLED) } }
                    }

                    GiveawayStatus.CLOSED -> {
                        Hint(stringResource(Res.string.gw_auto_results))
                    }
                }
                error?.let { StatusLine(it, negativeColor) }
            }
        }
    }
}

@Composable
private fun StatusChip(status: GiveawayStatus) {
    val (label, color) =
        when (status) {
            GiveawayStatus.ANNOUNCED -> Res.string.gw_status_announced to positiveColor
            GiveawayStatus.CLOSED -> Res.string.gw_status_closed to MaterialTheme.colorScheme.primary
            GiveawayStatus.DRAWN -> Res.string.gw_status_drawn to AXIS_COLOR
            GiveawayStatus.CANCELLED -> Res.string.gw_status_cancelled to negativeColor
        }
    Text(stringResource(label).uppercase(), style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = color)
}

// With the zone: a draw on the night clocks change would otherwise read as an hour off.
private val drawFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm z")

private fun fmtTime(sec: Long) = drawFormat.format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()))

internal fun formatPlex(n: Long) = String.format(Locale.US, "%,d", n).replace(',', ' ')

@Composable
internal fun GiveawayCard(
    g: Giveaway,
    eligibleNow: List<GiveawayParticipant>,
    myPubkeys: Set<String>,
    isAdmin: Boolean,
    tip: BlockTip?,
    blockHash: String?,
    now: Long,
) {
    val participants = if (g.status == GiveawayStatus.ANNOUNCED) eligibleNow else g.participants
    // Before the organiser publishes, every client already knows the winners once the block is out.
    val computed = blockHash?.let { GiveawayService.drawWinners(g.participants, it, g.id, g.winnerCount) }
    val winners = if (g.status == GiveawayStatus.DRAWN) g.winners else computed.orEmpty()

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.gw_prize, g.winnerCount.toString(), formatPlex(g.plexPerWinner.toLong())),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = Mono,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (g.note.isNotEmpty()) Text(g.note, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(Res.string.gw_rules, g.activeDays.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = AXIS_COLOR,
            )
        }
        Column(Modifier.weight(1f)) {
            when (g.status) {
                GiveawayStatus.ANNOUNCED -> {
                    val due = now >= g.drawAt
                    KV(
                        stringResource(Res.string.gw_time_left),
                        if (due) stringResource(Res.string.gw_closing) else timeLeft(g.drawAt - now),
                        MaterialTheme.colorScheme.primary,
                    )
                }

                GiveawayStatus.CLOSED -> {
                    val h = g.btcHeight ?: 0
                    val left = if (blockHash != null) 0 else tip?.let { (h - it.height).coerceIn(0, BLOCKS_AHEAD) }
                    Tip(stringResource(Res.string.gw_tip_blocks)) {
                        KV(
                            stringResource(Res.string.gw_blocks_left),
                            left?.let { stringResource(Res.string.gw_blocks_left_value, it.toString(), BLOCKS_AHEAD.toString()) } ?: "…",
                            MaterialTheme.colorScheme.primary,
                        )
                    }
                    tip?.let {
                        KV(
                            stringResource(Res.string.gw_last_block),
                            stringResource(Res.string.gw_ago, shortDuration(now - it.time)),
                        )
                    }
                }

                else -> {}
            }
            KV(stringResource(Res.string.gw_draw_at), fmtTime(g.drawAt))
            KV(stringResource(Res.string.gw_participants), participants.size.toString())
            g.btcHeight?.let { h ->
                KV(
                    stringResource(Res.string.gw_block),
                    if (tip != null && blockHash == null &&
                        g.btcHash == null
                    ) {
                        stringResource(Res.string.gw_block_wait, h.toString(), tip.height.toString())
                    } else {
                        "#$h"
                    },
                )
            }
            val you =
                when {
                    participants.any { it.pubkey in myPubkeys } -> stringResource(Res.string.gw_you_in) to positiveColor
                    isAdmin -> stringResource(Res.string.gw_you_organiser) to AXIS_COLOR
                    else -> stringResource(Res.string.gw_you_out) to AXIS_COLOR
                }
            KV(stringResource(Res.string.gw_you), you.first, you.second)
        }
        if (winners.isNotEmpty()) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.gw_winners).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AXIS_COLOR,
                )
                winners.forEachIndexed { i, w ->
                    val mine = w.pubkey in myPubkeys
                    Text(
                        "${i + 1}. ${w.name.ifEmpty { w.pubkey.take(8) }}" + if (mine) " ★" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (mine) FontWeight.Bold else FontWeight.SemiBold,
                        color = GOLD,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val h = g.btcHeight.toString()
                // A published result must be what its own published inputs give, and its block
                // hash the real one for that height (once fetched here).
                val honest =
                    g.btcHash?.let { GiveawayService.drawWinners(g.participants, it, g.id, g.winnerCount) } == g.winners &&
                        (blockHash == null || blockHash == g.btcHash)
                when {
                    g.status != GiveawayStatus.DRAWN -> Hint(stringResource(Res.string.gw_preliminary, h))
                    honest -> StatusLine(stringResource(Res.string.gw_verified, h), positiveColor)
                    else -> StatusLine(stringResource(Res.string.gw_mismatch, h), negativeColor)
                }
            }
        }
    }
}

@Composable
internal fun AnnounceForm(
    f: GiveawayForm,
    busy: Boolean,
    onChange: (GiveawayForm) -> Unit,
    onCancel: () -> Unit,
    onPublish: () -> Unit,
) {
    @Composable
    fun field(
        label: String,
        content: @Composable RowScope.() -> Unit,
    ) = Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AXIS_COLOR)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            field(
                stringResource(Res.string.gw_f_plex),
            ) { TerminalField(f.plex, Modifier.width(90.dp), number = true) { onChange(f.copy(plex = it)) } }
            field(stringResource(Res.string.gw_f_winners)) {
                TerminalField(f.winners, Modifier.width(60.dp), number = true) { onChange(f.copy(winners = it)) }
            }
            field(stringResource(Res.string.gw_f_days)) {
                TerminalField(f.activeDays, Modifier.width(60.dp), number = true) { onChange(f.copy(activeDays = it)) }
            }
            field(stringResource(Res.string.gw_f_draw_in)) {
                TerminalField(f.drawIn, Modifier.width(60.dp), number = true) { onChange(f.copy(drawIn = it)) }
                DrawUnit.entries.forEach { u -> Segment(stringResource(u.label), f.unit == u) { onChange(f.copy(unit = u)) } }
            }
            field(stringResource(Res.string.gw_f_note)) { TerminalField(f.note, Modifier.width(320.dp)) { onChange(f.copy(note = it)) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tip(
                stringResource(Res.string.gw_test_tip),
            ) { Segment(stringResource(Res.string.gw_test), f.test) { onChange(f.copy(test = !f.test)) } }
            ActionButton(stringResource(Res.string.gw_publish), enabled = f.toGiveaway(0) != null, busy = busy, onClick = onPublish)
            ActionButton(stringResource(Res.string.gw_back), onClick = onCancel)
        }
    }
}
