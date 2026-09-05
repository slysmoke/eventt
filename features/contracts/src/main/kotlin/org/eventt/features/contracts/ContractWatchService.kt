package org.eventt.features.contracts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.CorporationDao
import org.eventt.core.database.StaticDataDao

internal const val CONTRACTS_AUTO_REFRESH_SETTING = "contracts.auto_refresh"
internal const val CONTRACTS_REFRESH_INTERVAL_MILLIS = 5L * 60 * 1000

/**
 * App-lifetime background sweep, gated by the user's auto-refresh toggle (off by default —
 * unlike MarketWatchService's beaten-order check, which reads already-cached public market data,
 * this hits a real per-character/corp ESI endpoint that isn't otherwise refreshed for any other
 * reason, so it only runs when explicitly opted into).
 *
 * Powers the Contracts nav badge even while looking at another screen or character. Only this
 * background sweep feeds the badge counter — the Contracts screen's own refresh/auto-refresh
 * (while it's the one actually open) never touches it, so simply having the tab open can't
 * re-arm a badge you're already looking at.
 */
object ContractWatchService {
    private var scope: CoroutineScope? = null
    private val _changedCount = MutableStateFlow(0)

    /** Contracts whose status changed since the Contracts screen was last opened. */
    val changedCount: StateFlow<Int> = _changedCount.asStateFlow()

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            while (true) {
                delay(CONTRACTS_REFRESH_INTERVAL_MILLIS)
                if (StaticDataDao.getSetting(CONTRACTS_AUTO_REFRESH_SETTING) == "true") {
                    runCatching { sweep() }
                }
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }

    /** Clears the badge — called when the Contracts screen is opened. */
    fun markSeen() {
        _changedCount.value = 0
    }

    private fun sweep() {
        val characters = CharacterDao.getAll()
        var changed = 0

        characters.forEach { char ->
            changed +=
                runCatching {
                    ContractSyncService.refresh(characterId = char.id, corporationId = null, actingCharId = char.id)
                }.getOrDefault(0)
        }

        // One acting character per *tracked* corp is enough -- corp contracts are a shared,
        // corp-wide list, not per-member (see CorporationDao.track).
        CorporationDao.actingPairsForTracked(characters).forEach { (corpId, actingId) ->
            changed +=
                runCatching {
                    ContractSyncService.refresh(characterId = null, corporationId = corpId, actingCharId = actingId)
                }.getOrDefault(0)
        }

        if (changed > 0) _changedCount.value += changed
    }
}
