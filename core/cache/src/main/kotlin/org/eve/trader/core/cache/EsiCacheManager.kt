package org.eve.trader.core.cache

import org.eve.trader.core.database.EsiCacheDao
import org.eve.trader.core.model.EsiCacheEntry
import org.eve.trader.core.model.RequestSource
import kotlin.math.max

enum class CacheState {
    FRESH,      // Cache is valid, serve from cache
    STALE,      // Cache expired, serve from cache but refresh in background
    MISS,       // No cache entry, must fetch from server
}

data class CacheResult(
    val state: CacheState,
    val data: String? = null,
    val source: RequestSource = RequestSource.SERVER,
)

object EsiCacheManager {

    // Default TTL in milliseconds if no Expires header (fallback)
    private const val DEFAULT_TTL_MS = 5 * 60 * 1000L // 5 minutes

    /**
     * Check cache state for a given endpoint.
     */
    fun checkState(endpoint: String, params: Map<String, String>? = null): CacheState {
        val entry = EsiCacheDao.get(endpoint, params) ?: return CacheState.MISS

        val now = System.currentTimeMillis()
        return when {
            now < entry.expiresAt -> CacheState.FRESH
            else -> CacheState.STALE
        }
    }

    /**
     * Get data from cache.
     */
    fun get(endpoint: String, params: Map<String, String>? = null): CacheResult {
        val entry = EsiCacheDao.get(endpoint, params) ?: return CacheResult(CacheState.MISS)

        val now = System.currentTimeMillis()
        val state = if (now < entry.expiresAt) CacheState.FRESH else CacheState.STALE

        return CacheResult(
            state = state,
            data = entry.data,
            source = RequestSource.CACHE,
        )
    }

    /**
     * Save response to cache with TTL from Expires header.
     */
    fun save(endpoint: String, params: Map<String, String>?, data: String, expiresAtMs: Long) {
        val effectiveExpiresAt = if (expiresAtMs > 0) expiresAtMs else System.currentTimeMillis() + DEFAULT_TTL_MS
        val entry = EsiCacheEntry(
            endpoint = endpoint,
            paramsHash = computeHash(params),
            data = data,
            expiresAt = effectiveExpiresAt,
            source = "server",
            lastFetched = System.currentTimeMillis(),
        )
        EsiCacheDao.save(entry)
    }

    /**
     * Calculate expiration timestamp from Expires header value.
     * ESI returns HTTP-date format: "Wed, 21 Oct 2026 07:28:00 GMT"
     */
    fun parseExpiresHeader(expiresHeader: String): Long {
        return try {
            val formats = listOf(
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
                java.time.format.DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz"),
            )
            val temporalAccessor = formats.firstNotNullOfOrNull { fmt ->
                try { fmt.parse(expiresHeader) } catch (_: Exception) { null }
            }
            if (temporalAccessor != null) {
                java.time.Instant.from(temporalAccessor).toEpochMilli()
            } else {
                System.currentTimeMillis() + DEFAULT_TTL_MS
            }
        } catch (e: Exception) {
            System.currentTimeMillis() + DEFAULT_TTL_MS
        }
    }

    /**
     * Calculate expiration from Cache-Control max-age directive.
     */
    fun parseCacheControl(cacheControl: String): Long? {
        val maxAgeRegex = "max-age=(\\d+)".toRegex()
        val match = maxAgeRegex.find(cacheControl)
        return match?.groupValues?.get(1)?.toLongOrNull()?.let { maxAge ->
            System.currentTimeMillis() + (maxAge * 1000)
        }
    }

    /**
     * Clean up expired cache entries.
     */
    fun cleanupExpired() {
        EsiCacheDao.deleteExpired()
    }

    /**
     * Clear all cache.
     */
    fun clearAll() {
        EsiCacheDao.clearAll()
    }

    private fun computeHash(params: Map<String, String>?): String {
        val input = params?.entries?.sortedBy { it.key }?.joinToString { "${it.key}=${it.value}" } ?: ""
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
