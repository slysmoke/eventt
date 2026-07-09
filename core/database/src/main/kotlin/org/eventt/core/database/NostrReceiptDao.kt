package org.eventt.core.database

data class NostrReceiptModel(
    val eventId: String,
    val tradeId: String,
    val orderCoordinate: String,
    val authorPubkey: String,
    val counterpartyPubkey: String,
    val role: String,
    val qty: Long,
    val createdAt: Long,
    val rawEventJson: String,
)

object NostrReceiptDao {
    /** No-op if [eventId] already exists — relay re-delivery of the same receipt event must not duplicate rows. */
    fun insertIfAbsent(
        eventId: String,
        tradeId: String,
        orderCoordinate: String,
        authorPubkey: String,
        counterpartyPubkey: String,
        role: String,
        qty: Long,
        createdAt: Long,
        rawEventJson: String,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR IGNORE INTO nostr_receipts
                (event_id, trade_id, order_coordinate, author_pubkey, counterparty_pubkey, role, qty, created_at, raw_event_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, eventId)
                stmt.setString(2, tradeId)
                stmt.setString(3, orderCoordinate)
                stmt.setString(4, authorPubkey)
                stmt.setString(5, counterpartyPubkey)
                stmt.setString(6, role)
                stmt.setLong(7, qty)
                stmt.setLong(8, createdAt)
                stmt.setString(9, rawEventJson)
                stmt.executeUpdate()
            }
        }
    }

    /** True once both sides of [tradeId] have published a receipt — a self-attested lone receipt earns nothing. */
    fun hasMutualReceipt(tradeId: String): Boolean =
        DatabaseManager.transaction {
            prepareStatement(
                """
                SELECT COUNT(DISTINCT author_pubkey) AS n FROM nostr_receipts WHERE trade_id = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tradeId)
                stmt.executeQuery().use { rs -> rs.next() && rs.getInt("n") >= 2 }
            }
        }

    /** Count of distinct trades where [pubkey] published a receipt AND some other pubkey published one for the same trade — a one-sided receipt never counts. */
    fun countConfirmedTrades(pubkey: String): Int =
        DatabaseManager.transaction {
            prepareStatement(
                """
                SELECT COUNT(DISTINCT a.trade_id) AS n
                FROM nostr_receipts a
                JOIN nostr_receipts b ON a.trade_id = b.trade_id AND b.author_pubkey != a.author_pubkey
                WHERE a.author_pubkey = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, pubkey)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt("n") else 0 }
            }
        }

    fun listForTrade(tradeId: String): List<NostrReceiptModel> =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM nostr_receipts WHERE trade_id = ?").use { stmt ->
                stmt.setString(1, tradeId)
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<NostrReceiptModel>()
                    while (rs.next()) result.add(rs.toModel())
                    result
                }
            }
        }

    private fun java.sql.ResultSet.toModel() =
        NostrReceiptModel(
            eventId = getString("event_id"),
            tradeId = getString("trade_id"),
            orderCoordinate = getString("order_coordinate"),
            authorPubkey = getString("author_pubkey"),
            counterpartyPubkey = getString("counterparty_pubkey"),
            role = getString("role"),
            qty = getLong("qty"),
            createdAt = getLong("created_at"),
            rawEventJson = getString("raw_event_json"),
        )
}
