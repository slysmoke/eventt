package org.eventt.core.database

/**
 * Publishes not yet acknowledged (NIP-01 OK) by any relay — see the nostr_outbox DDL for why this
 * exists on top of Quartz's in-memory outbox. A row lives from publish until the first relay OK,
 * or until it ages out (events older than the 2-week order lifetime aren't worth retrying:
 * an order revision that stale has either been superseded or expired anyway).
 */
object NostrOutboxDao {
    fun insert(
        eventId: String,
        eventJson: String,
        createdAt: Long,
    ) {
        DatabaseManager.transaction {
            prepareStatement("INSERT OR IGNORE INTO nostr_outbox (event_id, event_json, created_at) VALUES (?, ?, ?)").use { stmt ->
                stmt.setString(1, eventId)
                stmt.setString(2, eventJson)
                stmt.setLong(3, createdAt)
                stmt.executeUpdate()
            }
        }
    }

    /** Called on the first successful OK for [eventId]; no-op for events we aren't tracking. */
    fun remove(eventId: String) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM nostr_outbox WHERE event_id = ?").use { stmt ->
                stmt.setString(1, eventId)
                stmt.executeUpdate()
            }
        }
    }

    fun allEventJson(): List<String> =
        DatabaseManager.transaction {
            prepareStatement("SELECT event_json FROM nostr_outbox ORDER BY created_at").use { stmt ->
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<String>()
                    while (rs.next()) result.add(rs.getString("event_json"))
                    result
                }
            }
        }

    fun deleteOlderThan(cutoffSeconds: Long) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM nostr_outbox WHERE created_at < ?").use { stmt ->
                stmt.setLong(1, cutoffSeconds)
                stmt.executeUpdate()
            }
        }
    }
}
