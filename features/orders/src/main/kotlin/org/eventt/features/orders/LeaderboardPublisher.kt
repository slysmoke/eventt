package org.eventt.features.orders

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.nostr.LeaderboardService
import org.eventt.core.nostr.NostrIdentityService

private const val LEADERBOARD_REPUBLISH_MILLIS = 2L * 3600 * 1000

/**
 * App-lifetime background sweep for the opt-in trader leaderboard (issue #17) — periodic, not
 * live: publishes the combined realized P&L (every local character and corporation, same
 * unfiltered [CostBasisService.compute] the Dashboard's "Combine all" mode uses) roughly every two
 * hours, signed by whichever single character Settings designates as the publisher — same
 * start()/stop()-from-Main.kt shape as [org.eventt.features.contracts.ContractWatchService].
 * Re-checks the setting every cycle (like NostrRelayManager's presence loop) so changing the
 * publisher character while the app is already running takes effect on the next tick without a
 * restart. Turning it off is handled immediately in Settings (tombstone publish), not here — this
 * loop only ever adds/refreshes the entry, never removes one.
 *
 * Runs [WalletSyncService.syncAll] right before computing P&L each cycle — publishing straight
 * from whatever happened to already be cached would silently lag behind reality, since nothing
 * else guarantees the local DB is fresh between Dashboard visits.
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
                delay(LEADERBOARD_REPUBLISH_MILLIS)
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
        // The publisher's own tax/broker-fee settings, same as Dashboard's "Combine all" — using
        // CostBasisService's plain defaults here would silently mismatch whatever the trader
        // actually sees in their own Dashboard for the same combined numbers.
        val taxConfig =
            CostBasisService.TaxConfig(
                salesTaxPct = StaticDataDao.getCharSalesTax(charId),
                brokerFeePct = StaticDataDao.getCharBrokersFee(charId),
            )
        val window = CostBasisService.compute(characterId = null, corporationId = null, taxConfig = taxConfig).realizedPnlWindow()
        val traderChar = CharacterDao.getById(charId)?.name ?: identity.label
        LeaderboardService.publishEntry(identity, window.pnl7d, window.pnl30d, window.pnl365d, traderChar)
    }
}
