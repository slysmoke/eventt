package org.eve.trader.core.database

import org.eve.trader.core.model.EsiCacheEntry
import java.security.MessageDigest

object EsiCacheDao {

    fun save(entry: EsiCacheEntry) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO esi_cache (endpoint, params_hash, data, expires_at, source, last_fetched)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, entry.endpoint)
                stmt.setString(2, entry.paramsHash)
                stmt.setString(3, entry.data)
                stmt.setLong(4, entry.expiresAt)
                stmt.setString(5, entry.source)
                stmt.setLong(6, entry.lastFetched)
                stmt.executeUpdate()
            }
        }
    }

    fun get(endpoint: String, params: Map<String, String>? = null): EsiCacheEntry? {
        val hash = computeHash(params)
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM esi_cache WHERE endpoint = ? AND params_hash = ?").use { stmt ->
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
                        )
                    } else null
                }
            }
        }
    }

    fun isFresh(endpoint: String, params: Map<String, String>? = null): Boolean {
        val entry = get(endpoint, params) ?: return false
        return System.currentTimeMillis() < entry.expiresAt
    }

    fun deleteExpired() {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM esi_cache WHERE expires_at < ?").use { stmt ->
                stmt.setLong(1, System.currentTimeMillis())
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

    private fun computeHash(params: Map<String, String>?): String {
        val input = params?.entries?.sortedBy { it.key }?.joinToString { "${it.key}=${it.value}" } ?: ""
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
