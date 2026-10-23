package org.eventt.features.dashboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.nostr.LeaderboardService
import org.eventt.core.nostr.LeaderboardStats
import org.eventt.core.nostr.NostrIdentityService
import org.eventt.features.orders.WalletSyncService

private const val LEADERBOARD_REPUBLISH_MILLIS = 2L * 3600 * 1000

/**
 * App-lifetime background sweep for the opt-in trader leaderboard (issue #17) — periodic, not
 * live: publishes the combined figures of every local character and corporation (the Dashboard's
 * "Combine all" snapshot, so the board and the Dashboard never disagree) roughly every two hours,
 * or right away on [LeaderboardService.requestPublish], signed by whichever single character
 * Settings designates as the publisher. Re-checks the setting every cycle, so changing the
 * publisher while the app runs takes effect on the next tick. Turning it off is handled
 * immediately on the Leaderboard screen (tombstone publish), not here — this loop only ever
 * adds/refreshes the entry, never removes one.
 *
 * Runs [WalletSyncService.syncAll] right before computing each cycle — publishing straight from
 * whatever happened to already be cached would silently lag behind reality, since nothing else
 * guarantees the local DB is fresh between Dashboard visits.
 */
object LeaderboardPublisher {
    private var scope: CoroutineScope? = null

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            while (true) {
                runCatching { publishIfEnabled() }
                withTimeoutOrNull(LEADERBOARD_REPUBLISH_MILLIS) { LeaderboardService.publishRequests.first() }
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }

    private suspend fun publishIfEnabled() {
        val charId = StaticDataDao.getLeaderboardPublisherCharId() ?: return
        val identity = NostrIdentityService.listIdentities().find { it.characterId == charId } ?: return

        WalletSyncService.syncAll()
        // The publisher's own tax/broker-fee settings, same as the Dashboard's "Combine all".
        val snapshot = loadSnapshot(null, null, "all", charId, null)
        val traderChar = CharacterDao.getById(charId)?.name ?: identity.label
        LeaderboardService.publishEntry(identity, leaderboardStats(snapshot, StaticDataDao.getLeaderboardShareNetWorth()), traderChar)
    }
}

// The published figures, computed exactly like the Dashboard's 1W / 1M / 1Y statistics.
internal fun leaderboardStats(
    s: Snapshot,
    shareNetWorth: Boolean,
): LeaderboardStats {
    fun over(days: Int) = rangeStats(s.days.takeLast(days), s.fifo.realizedSells, s.txs, s.feesByDay)
    val week = over(7)
    val month = over(30)
    val year = over(365)
    return LeaderboardStats(
        pnl7d = week.realized,
        pnl30d = month.realized,
        pnl365d = year.realized,
        roi7dPct = week.roiPct,
        roi30dPct = month.roiPct,
        roi365dPct = year.roiPct,
        winRate30dPct = month.winRatePct,
        margin30dPct = month.marginPct,
        profitFactor30d = month.profitFactor,
        sells30d = month.sells,
        turnover30d = month.turnover,
        daily30d = s.days.takeLast(30).map { it.realized },
        netWorth =
            s.days
                .lastOrNull()
                ?.netWorth
                ?.takeIf { shareNetWorth },
    )
}
