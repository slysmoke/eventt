package org.eventt.core.database

import org.eventt.core.model.EsiCacheEntry
import java.security.MessageDigest

object EsiCacheDao {
    fun save(entry: EsiCacheEntry) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO esi_cache
                    (endpoint, params_hash, data, expires_at, source, last_fetched, etag, last_modified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, entry.endpoint)
                stmt.setString(2, entry.paramsHash)
                stmt.setString(3, entry.data)
                stmt.setLong(4, entry.expiresAt)
                stmt.setString(5, entry.source)
                stmt.setLong(6, entry.lastFetched)
                stmt.setString(7, entry.etag)
                stmt.setString(8, entry.lastModified)
                stmt.executeUpdate()
            }
        }
    }

    fun get(
        endpoint: String,
        params: Map<String, String>? = null,
    ): EsiCacheEntry? {
        val hash = computeHash(params)
        return DatabaseManager.transaction {
            prepareStatement(
                "SELECT * FROM esi_cache WHERE endpoint = ? AND params_hash = ?",
            ).use { stmt ->
                stmt.setString(1, endpoint)
                stmt.setString(2, hash)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        EsiCacheEntry(
                            endpoint = rs.getString("endpoint"),
                            paramsHash = rs.getString("params_hash"),
                            data = rs.getString("data"),
                            expiresAt = rs.getLong("expires_at"),
                            source = rs.getString("source"),
                            lastFetched = rs.getLong("last_fetched"),
                            etag = rs.getString("etag"),
                            lastModified = rs.getString("last_modified"),
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    // Called on 304 Not Modified: bump expiry and update etag/last_modified without touching data.
    fun refreshExpiry(
        endpoint: String,
        paramsHash: String,
        newExpiresAt: Long,
        etag: String? = null,
        lastModified: String? = null,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                UPDATE esi_cache
                SET expires_at = ?, last_fetched = ?,
                    etag = COALESCE(?, etag),
                    last_modified = COALESCE(?, last_modified)
                WHERE endpoint = ? AND params_hash = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setLong(1, newExpiresAt)
                stmt.setLong(2, System.currentTimeMillis())
                stmt.setString(3, etag)
                stmt.setString(4, lastModified)
                stmt.setString(5, endpoint)
                stmt.setString(6, paramsHash)
                stmt.executeUpdate()
            }
        }
    }

    fun isFresh(
        endpoint: String,
        params: Map<String, String>? = null,
    ): Boolean {
        val entry = get(endpoint, params) ?: return false
        return System.currentTimeMillis() < entry.expiresAt
    }

    // Default cutoff (now) deletes anything already expired. EsiCacheManager.cleanupExpired()
    // instead passes an older cutoff, so recently-expired (merely stale) rows survive long
    // enough to still be useful for a conditional If-None-Match/If-Modified-Since revalidation.
    fun deleteExpired(olderThan: Long = System.currentTimeMillis()) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM esi_cache WHERE expires_at < ?").use { stmt ->
                stmt.setLong(1, olderThan)
                stmt.executeUpdate()
            }
        }
    }

    fun clearAll() {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM esi_cache").use { stmt ->
                stmt.executeUpdate()
            }
        }
    }

    internal fun computeHash(params: Map<String, String>?): String {
        val input = params?.entries?.sortedBy { it.key }?.joinToString { "${it.key}=${it.value}" } ?: ""
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
