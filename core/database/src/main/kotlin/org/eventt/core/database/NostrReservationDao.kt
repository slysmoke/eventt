package org.eventt.core.database

data class NostrReservationModel(
    val tradeId: String,
    val orderUuid: String,
    val orderPubkey: String,
    val buyerPubkey: String,
    val sellerPubkey: String,
    val role: String,
    val qty: Long,
    // Snapshotted from the order at request time — see the migration comment in
    // DatabaseManager for why these can't just be re-read off the order later.
    val price: Double,
    val typeId: Int,
    // "buy" or "sell" — the order's own side, not this trade's economic direction. [role] tells
    // you who sent the request vs who owns the order, which only matches buyer/seller of the
    // *item* when the order is a sell order; combine the two (see ReceiptService) to get it right.
    val orderSide: String,
    val note: String,
    val buyerChar: String,
    val buyerCharacterId: Int?,
    val status: String,
    val reservedQty: Long?,
    val holdUntil: Long?,
    val contactChar: String,
    val contactCharacterId: Int?,
    val requestedAt: Long,
    val respondedAt: Long?,
)

object NostrReservationDao {
    /**
     * No-op if [tradeId] already exists — relays/DM re-delivery must not clobber a response that
     * already landed (and the same gift-wrapped request routinely arrives twice, once per relay
     * that carried it). Returns true only when this call actually inserted the row, so callers
     * (e.g. the incoming-request notification) can tell a genuinely new request apart from a
     * duplicate re-delivery of one already seen.
     */
    fun insertRequestIfAbsent(
        tradeId: String,
        orderUuid: String,
        orderPubkey: String,
        buyerPubkey: String,
        sellerPubkey: String,
        role: String,
        qty: Long,
        price: Double,
        typeId: Int,
        orderSide: String,
        note: String,
        buyerChar: String,
        buyerCharacterId: Int?,
        requestedAt: Long,
    ): Boolean =
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR IGNORE INTO nostr_reservations
                (trade_id, order_uuid, order_pubkey, buyer_pubkey, seller_pubkey, role, qty, price, type_id, order_side, note, buyer_char, buyer_char_id, requested_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tradeId)
                stmt.setString(2, orderUuid)
                stmt.setString(3, orderPubkey)
                stmt.setString(4, buyerPubkey)
                stmt.setString(5, sellerPubkey)
                stmt.setString(6, role)
                stmt.setLong(7, qty)
                stmt.setDouble(8, price)
                stmt.setInt(9, typeId)
                stmt.setString(10, orderSide)
                stmt.setString(11, note)
                stmt.setString(12, buyerChar)
                buyerCharacterId?.let { stmt.setInt(13, it) } ?: stmt.setNull(13, java.sql.Types.INTEGER)
                stmt.setLong(14, requestedAt)
                stmt.executeUpdate() > 0
            }
        }

    /**
     * Only applies while the reservation is still awaiting a response (`status = 'sent'`) — gift
     * -wrapped DMs get replayed as relay backlog on every reconnect (seen firsthand: dozens of
     * historical DMs redelivered on startup), and without this guard a replayed "accepted"/
     * "declined" response would unconditionally stomp a status the buyer has since moved past
     * (e.g. reverting "completed" back to "accepted" on every restart).
     */
    fun updateResponse(
        tradeId: String,
        accepted: Boolean,
        reservedQty: Long?,
        holdUntil: Long?,
        contactChar: String,
        contactCharacterId: Int?,
        respondedAt: Long,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                UPDATE nostr_reservations
                SET status = ?, reserved_qty = ?, hold_until = ?, contact_char = ?, contact_char_id = ?, responded_at = ?
                WHERE trade_id = ? AND status = 'sent'
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, if (accepted) "accepted" else "declined")
                reservedQty?.let { stmt.setLong(2, it) } ?: stmt.setNull(2, java.sql.Types.INTEGER)
                holdUntil?.let { stmt.setLong(3, it) } ?: stmt.setNull(3, java.sql.Types.INTEGER)
                stmt.setString(4, contactChar)
                contactCharacterId?.let { stmt.setInt(5, it) } ?: stmt.setNull(5, java.sql.Types.INTEGER)
                stmt.setLong(6, respondedAt)
                stmt.setString(7, tradeId)
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
                    "SELECT * FROM nostr_reservations WHERE role = ? AND status IN (${statuses.joinToString(
                        ",",
                    ) { "?" }}) ORDER BY requested_at DESC"
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
            price = getDouble("price"),
            typeId = getInt("type_id"),
            orderSide = getString("order_side") ?: "",
            note = getString("note") ?: "",
            buyerChar = getString("buyer_char") ?: "",
            buyerCharacterId = getInt("buyer_char_id").takeIf { !wasNull() },
            status = getString("status"),
            reservedQty = getLong("reserved_qty").takeIf { !wasNull() },
            holdUntil = getLong("hold_until").takeIf { !wasNull() },
            contactChar = getString("contact_char") ?: "",
            contactCharacterId = getInt("contact_char_id").takeIf { !wasNull() },
            requestedAt = getLong("requested_at"),
            respondedAt = getLong("responded_at").takeIf { !wasNull() },
        )
}
