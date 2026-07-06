package org.eventt.core.cache

import org.eventt.core.database.EsiCacheDao
import org.eventt.core.model.EsiCacheEntry
import org.eventt.core.model.RequestSource
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class CacheState {
    FRESH,
    STALE,
    MISS,
}

data class CacheResult(
    val state: CacheState,
    val data: String? = null,
    val source: RequestSource = RequestSource.SERVER,
    val etag: String? = null,
    val lastModified: String? = null,
)

private data class MemEntry(
    val data: String,
    val expiresAt: Long,
    val etag: String?,
    val lastModified: String?,
)

object EsiCacheManager {

    private const val DEFAULT_TTL_MS = 5 * 60 * 1000L

    // L1 in-memory cache — survives within a single app session. Keys are "$endpoint|$hash".
    private val mem = ConcurrentHashMap<String, MemEntry>()

    fun get(endpoint: String, params: Map<String, String>? = null): CacheResult {
        val hash = computeHash(params)
        val key = "$endpoint|$hash"

        // L1: memory
        mem[key]?.let { e ->
            val state = if (System.currentTimeMillis() < e.expiresAt) CacheState.FRESH else CacheState.STALE
            return CacheResult(state = state, data = e.data, source = RequestSource.CACHE, etag = e.etag, lastModified = e.lastModified)
        }

        // L2: SQLite
        val entry = EsiCacheDao.get(endpoint, params) ?: return CacheResult(CacheState.MISS)
        val state = if (System.currentTimeMillis() < entry.expiresAt) CacheState.FRESH else CacheState.STALE
        mem[key] = MemEntry(entry.data, entry.expiresAt, entry.etag, entry.lastModified)
        return CacheResult(state = state, data = entry.data, source = RequestSource.CACHE, etag = entry.etag, lastModified = entry.lastModified)
    }

    fun save(
        endpoint: String,
        params: Map<String, String>?,
        data: String,
        expiresAtMs: Long,
        etag: String? = null,
        lastModified: String? = null,
    ) {
        val effectiveExpiry = if (expiresAtMs > 0) expiresAtMs else System.currentTimeMillis() + DEFAULT_TTL_MS
        val hash = computeHash(params)

        EsiCacheDao.save(EsiCacheEntry(
            endpoint = endpoint,
            paramsHash = hash,
            data = data,
            expiresAt = effectiveExpiry,
            etag = etag,
            lastModified = lastModified,
        ))

        mem["$endpoint|$hash"] = MemEntry(data, effectiveExpiry, etag, lastModified)
    }

    // Called on HTTP 304: bump expiry without re-downloading the body.
    fun refreshExpiry(
        endpoint: String,
        params: Map<String, String>?,
        newExpiresAt: Long,
        etag: String? = null,
        lastModified: String? = null,
    ) {
        val hash = computeHash(params)
        EsiCacheDao.refreshExpiry(endpoint, hash, newExpiresAt, etag, lastModified)

        val key = "$endpoint|$hash"
        val existing = mem[key]
        if (existing != null) {
            mem[key] = existing.copy(
                expiresAt = newExpiresAt,
                etag = etag ?: existing.etag,
                lastModified = lastModified ?: existing.lastModified,
            )
        }
    }

    fun checkState(endpoint: String, params: Map<String, String>? = null): CacheState =
        get(endpoint, params).state

    fun getExpiry(endpoint: String, params: Map<String, String>? = null): Long? {
        val key = "$endpoint|${computeHash(params)}"
        mem[key]?.expiresAt?.let { return it }
        return EsiCacheDao.get(endpoint, params)?.expiresAt
    }

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
        } catch (_: Exception) {
            System.currentTimeMillis() + DEFAULT_TTL_MS
        }
    }

    fun parseCacheControl(cacheControl: String): Long? {
        val match = "max-age=(\\d+)".toRegex().find(cacheControl)
        return match?.groupValues?.get(1)?.toLongOrNull()?.let { maxAge ->
            System.currentTimeMillis() + (maxAge * 1000)
        }
    }

    private fun computeHash(params: Map<String, String>?): String {
        val input = params?.entries?.sortedBy { it.key }?.joinToString { "${it.key}=${it.value}" } ?: ""
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun cleanupExpired() {
        EsiCacheDao.deleteExpired()
        // Prune memory entries that have been stale for > 1 hour so it doesn't grow unbounded.
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        mem.entries.removeIf { it.value.expiresAt < cutoff }
    }

    fun clearAll() {
        EsiCacheDao.clearAll()
        mem.clear()
    }
}
