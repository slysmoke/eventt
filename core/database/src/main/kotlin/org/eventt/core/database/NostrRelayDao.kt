package org.eventt.core.database

data class NostrRelayModel(
    val url: String,
    val enabled: Boolean,
    val read: Boolean,
    val write: Boolean,
    val sortOrder: Int,
    val lastConnectedAt: Long?,
    val lastStatus: String,
    val lastError: String?,
    /** NIPs from the relay's NIP-11 document; empty until [nip11FetchedAt] is set. */
    val supportedNips: List<Int> = emptyList(),
    /** True when NIP-11 declares restricted_writes or payment_required — our publishes are likely rejected. */
    val restrictedWrites: Boolean = false,
    val nip11FetchedAt: Long? = null,
)

object NostrRelayDao {
    fun getAll(): List<NostrRelayModel> =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM nostr_relays ORDER BY sort_order").use { stmt ->
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<NostrRelayModel>()
                    while (rs.next()) {
                        result.add(
                            NostrRelayModel(
                                url = rs.getString("url"),
                                enabled = rs.getInt("enabled") == 1,
                                read = rs.getInt("read") == 1,
                                write = rs.getInt("write") == 1,
                                sortOrder = rs.getInt("sort_order"),
                                lastConnectedAt = rs.getLong("last_connected_at").takeIf { !rs.wasNull() },
                                lastStatus = rs.getString("last_status") ?: "unknown",
                                lastError = rs.getString("last_error"),
                                supportedNips =
                                    rs
                                        .getString("supported_nips")
                                        .orEmpty()
                                        .split(',')
                                        .mapNotNull { it.trim().toIntOrNull() },
                                restrictedWrites = rs.getInt("restricted_writes") == 1,
                                nip11FetchedAt = rs.getLong("nip11_fetched_at").takeIf { !rs.wasNull() },
                            ),
                        )
                    }
                    result
                }
            }
        }

    /** No-op if any relay row already exists — only ever populates a first-run empty list. */
    fun seedDefaultsIfEmpty(defaults: List<String>) {
        DatabaseManager.transaction {
            val alreadySeeded =
                prepareStatement("SELECT COUNT(*) FROM nostr_relays").use { stmt ->
                    stmt.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
                }
            if (alreadySeeded) return@transaction
            defaults.forEachIndexed { i, url ->
                prepareStatement("INSERT OR IGNORE INTO nostr_relays (url, sort_order) VALUES (?, ?)").use { stmt ->
                    stmt.setString(1, url)
                    stmt.setInt(2, i)
                    stmt.executeUpdate()
                }
            }
        }
    }

    fun upsert(url: String) {
        DatabaseManager.transaction {
            prepareStatement("INSERT OR IGNORE INTO nostr_relays (url) VALUES (?)").use { stmt ->
                stmt.setString(1, url)
                stmt.executeUpdate()
            }
        }
    }

    fun setEnabled(
        url: String,
        enabled: Boolean,
    ) {
        DatabaseManager.transaction {
            prepareStatement("UPDATE nostr_relays SET enabled = ? WHERE url = ?").use { stmt ->
                stmt.setInt(1, if (enabled) 1 else 0)
                stmt.setString(2, url)
                stmt.executeUpdate()
            }
        }
    }

    fun updateStatus(
        url: String,
        status: String,
        error: String?,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                "UPDATE nostr_relays SET last_status = ?, last_error = ?, last_connected_at = strftime('%s','now')*1000 WHERE url = ?",
            ).use { stmt ->
                stmt.setString(1, status)
                stmt.setString(2, error)
                stmt.setString(3, url)
                stmt.executeUpdate()
            }
        }
    }

    /** Stores what Nip11Service fetched — [supportedNips] is kept as a comma-separated list. */
    fun updateNip11(
        url: String,
        supportedNips: List<Int>,
        restrictedWrites: Boolean,
        fetchedAt: Long,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                "UPDATE nostr_relays SET supported_nips = ?, restricted_writes = ?, nip11_fetched_at = ? WHERE url = ?",
            ).use { stmt ->
                stmt.setString(1, supportedNips.joinToString(","))
                stmt.setInt(2, if (restrictedWrites) 1 else 0)
                stmt.setLong(3, fetchedAt)
                stmt.setString(4, url)
                stmt.executeUpdate()
            }
        }
    }

    fun remove(url: String) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM nostr_relays WHERE url = ?").use { stmt ->
                stmt.setString(1, url)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * [url] is the primary key, so any row whose stored form doesn't match how relay clients
     * normalize URLs (e.g. missing trailing slash) becomes permanently invisible to
     * [updateStatus]'s `WHERE url = ?` — the connection status update just silently no-ops. `OR
     * IGNORE` skips the rename if [newUrl] already exists as its own row (both forms present),
     * rather than throwing on the primary-key conflict.
     */
    fun renameUrl(
        oldUrl: String,
        newUrl: String,
    ) {
        if (oldUrl == newUrl) return
        DatabaseManager.transaction {
            prepareStatement("UPDATE OR IGNORE nostr_relays SET url = ? WHERE url = ?").use { stmt ->
                stmt.setString(1, newUrl)
                stmt.setString(2, oldUrl)
                stmt.executeUpdate()
            }
        }
    }
}
