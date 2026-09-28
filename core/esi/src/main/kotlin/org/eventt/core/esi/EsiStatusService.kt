package org.eventt.core.esi

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.eventt.core.http.EveHttpClient
import org.eventt.core.model.AppLog
import java.io.IOException

/** Thrown by [EsiClient.getRaw] when ESI itself reports a route degraded and there's no stale
 * cache to fall back on — an ordinary [IOException] so every existing ESI call site's catch
 * block already handles it like any other failed request. */
class EsiDegradedException(
    endpoint: String,
) : IOException("ESI reports $endpoint degraded — request skipped until it recovers")

/** Thrown by [EsiClient.getRaw] while the EVE server itself is unreachable (daily downtime, an
 * outage) and there's no stale cache to fall back on -- see [EsiStatusService.isServerOffline]. */
class EsiOfflineException(
    endpoint: String,
) : IOException("EVE server offline — $endpoint skipped"),
    org.eventt.core.model.QuietFailure

/** Thrown by [EsiClient.getRaw] on HTTP 403 — the acting character's token doesn't carry the
 * corp role (Accountant, Director, ...) the endpoint requires. An ordinary [IOException] so
 * existing catch blocks still handle it, but callers that fetch corp data can catch this
 * specifically to record "no access" rather than treat it as a transient failure. */
class EsiForbiddenException(
    endpoint: String,
) : IOException("ESI request forbidden (403): $endpoint")

/**
 * Tracks ESI's own self-reported route health (GET /meta/status) so a route already known to be
 * degraded can be skipped before spending a real request on it. The status page itself is only
 * ever re-fetched once per [REFRESH_INTERVAL_MS] — [isHealthy] is a cheap in-memory lookup on
 * every other call, so this never adds a network round-trip per ESI request.
 */
object EsiStatusService {
    private data class RouteStatus(
        val method: String,
        val path: String,
        val status: String,
    )

    // internal var (not private const) so tests can point it at a local MockWebServer instead of
    // the real ESI, matching EsiClient.esiBaseUrl. Never reassigned in production.
    internal var statusUrl = "https://esi.evetech.net/meta/status"
    private const val REFRESH_INTERVAL_MS = 60_000L

    @Volatile private var routes: List<RouteStatus> = emptyList()
    private var lastFetchAt = 0L
    private var previouslyDegraded: Set<String> = emptySet()

    /** Test-only: forces the next [isHealthy] call to re-fetch, and clears any cached routes. */
    internal fun resetForTest() {
        offlineUntil = 0L
        _serverOffline.value = false
        lastFetchAt = 0L
        routes = emptyList()
        previouslyDegraded = emptySet()
    }

    // ESI's route templates use "{param}" placeholders; our real request paths use the actual
    // (numeric) IDs — both collapse to the same "*" so e.g. "/characters/{character_id}/wallet"
    // matches "/characters/95465499/wallet".
    private fun normalize(path: String): String =
        path.trim('/').split('/').joinToString("/") { seg ->
            if ((seg.startsWith("{") && seg.endsWith("}")) || seg.toLongOrNull() != null) "*" else seg
        }

    private fun refreshIfStale() {
        if (System.currentTimeMillis() - lastFetchAt < REFRESH_INTERVAL_MS) return
        synchronized(this) {
            if (System.currentTimeMillis() - lastFetchAt < REFRESH_INTERVAL_MS) return
            lastFetchAt = System.currentTimeMillis()
            try {
                val request =
                    Request
                        .Builder()
                        .url(statusUrl)
                        .header("Accept", "application/json")
                        .build()
                EveHttpClient.getClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return
                    val body = response.body.string()
                    val routesJson =
                        EsiClient.json
                            .parseToJsonElement(body)
                            .jsonObject["routes"]
                            ?.jsonArray ?: return
                    routes =
                        routesJson.map { el ->
                            val o = el.jsonObject
                            RouteStatus(
                                method = o["method"]?.jsonPrimitive?.content ?: "",
                                path = o["path"]?.jsonPrimitive?.content ?: "",
                                status = o["status"]?.jsonPrimitive?.content ?: "",
                            )
                        }
                    val nowDegraded = routes.filter { it.status != "OK" }.map { "${it.method} ${normalize(it.path)}" }.toSet()
                    (nowDegraded - previouslyDegraded).forEach {
                        AppLog.warn("ESI", "$it reported degraded by ESI — calls to it are paused until it recovers")
                    }
                    (previouslyDegraded - nowDegraded).forEach { AppLog.warn("ESI", "$it has recovered") }
                    previouslyDegraded = nowDegraded
                }
            } catch (e: Exception) {
                // The status check itself failing must never block real ESI calls — isHealthy()
                // fails open (see below) when there's no usable status data.
            }
        }
    }

    // ── Whole-server outage (Tranquility down: daily downtime, incidents) ─────────────────────
    // /meta/status can't be trusted for this -- during downtime it may itself be unreachable (and
    // isHealthy fails open) -- so the signal is ESI's own 502/503/504 answers. The first one trips
    // the breaker; while it's open no request leaves the app, and once the pause lapses exactly one
    // caller is let through as a probe instead of the whole backlog stampeding a dead server.
    private const val OFFLINE_PAUSE_MS = 60_000L

    @Volatile private var offlineUntil = 0L
    private val _serverOffline = kotlinx.coroutines.flow.MutableStateFlow(false)
    val serverOffline: kotlinx.coroutines.flow.StateFlow<Boolean> = _serverOffline

    fun isServerUnavailableCode(code: Int): Boolean = code in 502..504

    /** True while requests should be skipped; hands the one post-pause probe to its caller. */
    @Synchronized
    fun isServerOffline(): Boolean {
        if (!_serverOffline.value) return false
        val now = System.currentTimeMillis()
        if (now < offlineUntil) return true
        offlineUntil = now + OFFLINE_PAUSE_MS // this caller probes; everyone else keeps waiting
        return false
    }

    /** Test-only: lets the next [isServerOffline] call through as the probe without waiting. */
    internal fun resetOfflinePauseForTest() {
        offlineUntil = 0L
    }

    @Synchronized
    fun reportServerUnavailable() {
        offlineUntil = System.currentTimeMillis() + OFFLINE_PAUSE_MS
        if (!_serverOffline.value) {
            _serverOffline.value = true
            AppLog.warn("ESI", "EVE server unreachable — ESI calls paused, showing cached data until it's back")
        }
    }

    @Synchronized
    fun reportServerOk() {
        if (!_serverOffline.value) return
        _serverOffline.value = false
        offlineUntil = 0L
        AppLog.warn("ESI", "EVE server is back online")
    }

    /** True when ESI reports this route healthy, or we have no status data at all (fails open). */
    fun isHealthy(
        method: String,
        path: String,
    ): Boolean {
        refreshIfStale()
        val currentRoutes = routes
        if (currentRoutes.isEmpty()) return true
        val key = normalize(path)
        val match = currentRoutes.firstOrNull { it.method.equals(method, ignoreCase = true) && normalize(it.path) == key }
        return match == null || match.status == "OK"
    }
}
