package org.eventt.core.database

data class NostrOrderModel(
    val orderUuid: String,
    val pubkey: String,
    val eventId: String,
    val createdAt: Long,
    val side: String,
    val typeId: Int,
    val regionId: Int,
    val price: Double,
    val qtyTotal: Long,
    val qtyRemaining: Long,
    val minLot: Long,
    val minLotUnit: String,
    val traderChar: String,
    val expiration: Long,
    val rawEventJson: String,
    val isMine: Boolean,
)

object NostrOrderDao {
    /**
     * Mirrors NIP-33's "latest created_at wins" replace semantics locally — a relay replaying an
     * older revision after we've already seen a newer one must not overwrite it, or qty_remaining
     * could visibly revert for viewers. Returns true if the row was inserted/updated, false if a
     * newer-or-equal revision already present made this a no-op.
     */
    fun upsertIfNewer(order: NostrOrderModel): Boolean =
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT INTO nostr_orders (
                    order_uuid, pubkey, event_id, created_at, side, type_id, region_id, price,
                    qty_total, qty_remaining, min_lot, min_lot_unit, trader_char, expiration,
                    raw_event_json, is_mine, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, strftime('%s','now')*1000)
                ON CONFLICT(order_uuid, pubkey) DO UPDATE SET
                    event_id = excluded.event_id,
                    created_at = excluded.created_at,
                    side = excluded.side,
                    type_id = excluded.type_id,
                    region_id = excluded.region_id,
                    price = excluded.price,
                    qty_total = excluded.qty_total,
                    qty_remaining = excluded.qty_remaining,
                    min_lot = excluded.min_lot,
                    min_lot_unit = excluded.min_lot_unit,
                    trader_char = excluded.trader_char,
                    expiration = excluded.expiration,
                    raw_event_json = excluded.raw_event_json,
                    is_mine = excluded.is_mine,
                    updated_at = excluded.updated_at
                WHERE excluded.created_at > nostr_orders.created_at
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, order.orderUuid)
                stmt.setString(2, order.pubkey)
                stmt.setString(3, order.eventId)
                stmt.setLong(4, order.createdAt)
                stmt.setString(5, order.side)
                stmt.setInt(6, order.typeId)
                stmt.setInt(7, order.regionId)
                stmt.setDouble(8, order.price)
                stmt.setLong(9, order.qtyTotal)
                stmt.setLong(10, order.qtyRemaining)
                stmt.setLong(11, order.minLot)
                stmt.setString(12, order.minLotUnit)
                stmt.setString(13, order.traderChar)
                stmt.setLong(14, order.expiration)
                stmt.setString(15, order.rawEventJson)
                stmt.setInt(16, if (order.isMine) 1 else 0)
                stmt.executeUpdate() > 0
            }
        }

    fun queryActive(
        typeId: Int? = null,
        regionId: Int? = null,
        side: String? = null,
    ): List<NostrOrderModel> =
        DatabaseManager.transaction {
            val nowSec = System.currentTimeMillis() / 1000
            val conditions = mutableListOf("expiration > ?")
            val params = mutableListOf<Any>(nowSec)
            typeId?.let { conditions += "type_id = ?"; params += it }
            regionId?.let { conditions += "region_id = ?"; params += it }
            side?.let { conditions += "side = ?"; params += it }

            prepareStatement(
                "SELECT * FROM nostr_orders WHERE ${conditions.joinToString(" AND ")} ORDER BY updated_at DESC",
            ).use { stmt ->
                params.forEachIndexed { i, p ->
                    when (p) {
                        is Int -> stmt.setInt(i + 1, p)
                        is Long -> stmt.setLong(i + 1, p)
                        is String -> stmt.setString(i + 1, p)
                    }
                }
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<NostrOrderModel>()
                    while (rs.next()) {
                        result.add(
                            NostrOrderModel(
                                orderUuid = rs.getString("order_uuid"),
                                pubkey = rs.getString("pubkey"),
                                eventId = rs.getString("event_id"),
                                createdAt = rs.getLong("created_at"),
                                side = rs.getString("side"),
                                typeId = rs.getInt("type_id"),
                                regionId = rs.getInt("region_id"),
                                price = rs.getDouble("price"),
                                qtyTotal = rs.getLong("qty_total"),
                                qtyRemaining = rs.getLong("qty_remaining"),
                                minLot = rs.getLong("min_lot"),
                                minLotUnit = rs.getString("min_lot_unit"),
                                traderChar = rs.getString("trader_char") ?: "",
                                expiration = rs.getLong("expiration"),
                                rawEventJson = rs.getString("raw_event_json"),
                                isMine = rs.getInt("is_mine") == 1,
                            ),
                        )
                    }
                    result
                }
            }
        }

    fun getMine(pubkey: String): List<NostrOrderModel> = queryActive().filter { it.pubkey == pubkey && it.isMine }

    fun purgeExpired() {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM nostr_orders WHERE expiration <= strftime('%s','now')").use { it.executeUpdate() }
        }
    }
}
