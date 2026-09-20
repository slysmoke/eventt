package org.eventt.features.assets

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.CorporationDao
import org.eventt.core.database.StaticDataDao

internal const val ASSETS_AUTO_REFRESH_SETTING = "assets.auto_refresh"
internal const val ASSETS_REFRESH_INTERVAL_MILLIS = 5L * 60 * 1000

/**
 * App-lifetime background sweep, gated by the user's auto-refresh toggle (off by default, same
 * reasoning as ContractWatchService: this hits a real per-character/corp ESI endpoint that isn't
 * otherwise refreshed for any other reason, so it only runs when explicitly opted into). ESI's own
 * cache (see EsiCacheManager) still owns "is it actually time yet" for any one character/corp --
 * this loop just re-checks periodically rather than tracking per-entity due times itself.
 *
 * Keeps the Asset Viewer's data warm even while looking at another screen or character, so opening
 * it doesn't have to wait on a fresh ESI round-trip.
 */
object AssetWatchService {
    private var scope: CoroutineScope? = null

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            while (true) {
                delay(ASSETS_REFRESH_INTERVAL_MILLIS)
                if (StaticDataDao.getSetting(ASSETS_AUTO_REFRESH_SETTING) == "true") {
                    runCatching { sweep() }
                }
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }

    private suspend fun sweep() {
        val characters = CharacterDao.getAll()

        characters.forEach { char ->
            runCatching { fetchCharacterAssets(char.id) }
        }

        // One acting character per *tracked* corp is enough -- corp assets are a shared,
        // corp-wide list, not per-member (see CorporationDao.track).
        CorporationDao.actingPairsForTracked(characters).forEach { (corpId, actingId) ->
            runCatching { fetchCorporationAssets(corpId, actingId) }
        }
    }
}
