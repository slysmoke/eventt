package org.eventt.features.p2pmarket

import org.eventt.core.nostr.LeaderboardEntry
import org.eventt.core.nostr.LeaderboardStats
import org.eventt.p2pmarket.generated.resources.*
import org.jetbrains.compose.resources.StringResource

// An entry not republished for this long is out of date: the publisher stopped running the app.
internal const val STALE_SECONDS = 24 * 3600L

internal enum class LbWindow(
    val label: StringResource,
    // Key the daily places are stored under.
    val key: String,
    val pnl: (LeaderboardStats) -> Double,
    val roi: (LeaderboardStats) -> Double?,
) {
    WEEK(Res.string.lb_7_days, "7d", { it.pnl7d }, { it.roi7dPct }),
    MONTH(Res.string.lb_30_days, "30d", { it.pnl30d }, { it.roi30dPct }),
    YEAR(Res.string.lb_365_days, "365d", { it.pnl365d }, { it.roi365dPct }),
}

internal data class Ranked(
    val entry: LeaderboardEntry,
    val rank: Int,
    // Places gained since the previous recorded day (negative = lost); null = new on the board.
    val change: Int?,
    val stale: Boolean,
    val mine: Boolean,
)

/** Places by the window's P&L; out-of-date entries are only flagged, not demoted. */
internal fun rank(
    entries: Collection<LeaderboardEntry>,
    window: LbWindow,
    previous: Map<String, Int>,
    mine: Set<String>,
    nowSec: Long,
): List<Ranked> =
    entries
        .sortedByDescending { window.pnl(it.stats) }
        .mapIndexed { i, e ->
            Ranked(e, i + 1, previous[e.pubkey]?.let { it - (i + 1) }, nowSec - e.updatedAt > STALE_SECONDS, e.pubkey in mine)
        }

internal data class BoardSummary(
    val traders: Int,
    val active: Int,
    val medianPnl: Double?,
    val totalPnl: Double,
)

internal fun summary(
    ranked: List<Ranked>,
    window: LbWindow,
): BoardSummary {
    val pnl = ranked.filter { !it.stale }.map { window.pnl(it.entry.stats) }.sorted()
    val median =
        when {
            pnl.isEmpty() -> null
            pnl.size % 2 == 1 -> pnl[pnl.size / 2]
            else -> (pnl[pnl.size / 2 - 1] + pnl[pnl.size / 2]) / 2
        }
    return BoardSummary(ranked.size, ranked.count { !it.stale }, median, pnl.sum())
}

// How long ago a unix-seconds timestamp was, as a count and its unit's "%1$sm"-style resource.
internal fun ago(
    sec: Long,
    nowSec: Long,
): Pair<Long, StringResource> {
    val d = (nowSec - sec).coerceAtLeast(0)
    return when {
        d < 3600 -> d / 60 to Res.string.ago_m
        d < 86400 -> d / 3600 to Res.string.ago_h
        else -> d / 86400 to Res.string.ago_d
    }
}
