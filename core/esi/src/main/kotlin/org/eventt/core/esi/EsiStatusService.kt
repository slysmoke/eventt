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
