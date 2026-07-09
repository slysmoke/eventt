package org.eventt.core.database

data class NostrReservationModel(
    val tradeId: String,
    val orderUuid: String,
    val orderPubkey: String,
    val buyerPubkey: String,
    val sellerPubkey: String,
    val role: String,
    val qty: Long,
    val note: String,
    val status: String,
    val reservedQty: Long?,
    val holdUntil: Long?,
    val contactChar: String,
    val requestedAt: Long,
    val respondedAt: Long?,
)

object NostrReservationDao {
    /** No-op if [tradeId] already exists — relays/DM re-delivery must not clobber a response that already landed. */
    fun insertRequestIfAbsent(
        tradeId: String,
        orderUuid: String,
        orderPubkey: String,
        buyerPubkey: String,
        sellerPubkey: String,
        role: String,
        qty: Long,
        note: String,
        requestedAt: Long,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR IGNORE INTO nostr_reservations
                (trade_id, order_uuid, order_pubkey, buyer_pubkey, seller_pubkey, role, qty, note, requested_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tradeId)
                stmt.setString(2, orderUuid)
                stmt.setString(3, orderPubkey)
                stmt.setString(4, buyerPubkey)
                stmt.setString(5, sellerPubkey)
                stmt.setString(6, role)
                stmt.setLong(7, qty)
                stmt.setString(8, note)
                stmt.setLong(9, requestedAt)
                stmt.executeUpdate()
            }
        }
    }

    fun updateResponse(
        tradeId: String,
        accepted: Boolean,
        reservedQty: Long?,
        holdUntil: Long?,
        contactChar: String,
        respondedAt: Long,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                UPDATE nostr_reservations SET status = ?, reserved_qty = ?, hold_until = ?, contact_char = ?, responded_at = ?
                WHERE trade_id = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, if (accepted) "accepted" else "declined")
                reservedQty?.let { stmt.setLong(2, it) } ?: stmt.setNull(2, java.sql.Types.INTEGER)
                holdUntil?.let { stmt.setLong(3, it) } ?: stmt.setNull(3, java.sql.Types.INTEGER)
                stmt.setString(4, contactChar)
                stmt.setLong(5, respondedAt)
                stmt.setString(6, tradeId)
                stmt.executeUpdate()
            }
        }
    }

    fun updateStatus(
        tradeId: String,
        status: String,
    ) {
        DatabaseManager.transaction {
            prepareStatement("UPDATE nostr_reservations SET status = ? WHERE trade_id = ?").use { stmt ->
                stmt.setString(1, status)
                stmt.setString(2, tradeId)
                stmt.executeUpdate()
            }
        }
    }

    fun getByTradeId(tradeId: String): NostrReservationModel? =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM nostr_reservations WHERE trade_id = ?").use { stmt ->
                stmt.setString(1, tradeId)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.toModel() else null }
            }
        }

    fun listForRole(
        role: String,
        statuses: List<String>? = null,
    ): List<NostrReservationModel> =
        DatabaseManager.transaction {
            val sql =
                if (statuses.isNullOrEmpty()) {
                    "SELECT * FROM nostr_reservations WHERE role = ? ORDER BY requested_at DESC"
                } else {
                    "SELECT * FROM nostr_reservations WHERE role = ? AND status IN (${statuses.joinToString(",") { "?" }}) ORDER BY requested_at DESC"
                }
            prepareStatement(sql).use { stmt ->
                stmt.setString(1, role)
                statuses?.forEachIndexed { i, s -> stmt.setString(i + 2, s) }
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<NostrReservationModel>()
                    while (rs.next()) result.add(rs.toModel())
                    result
                }
            }
        }

    private fun java.sql.ResultSet.toModel() =
        NostrReservationModel(
            tradeId = getString("trade_id"),
            orderUuid = getString("order_uuid"),
            orderPubkey = getString("order_pubkey"),
            buyerPubkey = getString("buyer_pubkey"),
            sellerPubkey = getString("seller_pubkey"),
            role = getString("role"),
            qty = getLong("qty"),
            note = getString("note") ?: "",
            status = getString("status"),
            reservedQty = getLong("reserved_qty").takeIf { !wasNull() },
            holdUntil = getLong("hold_until").takeIf { !wasNull() },
            contactChar = getString("contact_char") ?: "",
            requestedAt = getLong("requested_at"),
            respondedAt = getLong("responded_at").takeIf { !wasNull() },
        )
}
