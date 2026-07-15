package org.eventt.core.database

import java.sql.Types

object OrderHistoryDao {
    data class OrderHistoryRecord(
        val orderId: Long,
        val typeId: Int,
        val typeName: String,
        val locationId: Long,
        val stationName: String,
        val price: Double,
        val volumeTotal: Int,
        val volumeRemaining: Int,
        val isBuyOrder: Boolean,
        val duration: Int,
        val issued: String,
        val range: String,
        val minVolume: Int,
        val state: String,
        val characterId: Int?,
        val corporationId: Int? = null,
        val isCorp: Boolean = false,
        // Broker fees paid relisting this order while it was active (tracked in active_orders,
        // copied here on the sync where the order leaves the active set).
        val relistFeesPaid: Double = 0.0,
    )

    private data class WhereClause(
        val sql: String,
        val params: List<Any?>,
    )

    private fun buildWhereClause(
        characterId: Int?,
        corporationId: Int?,
    ): WhereClause =
        when {
            characterId != null -> WhereClause("WHERE character_id = ?", listOf(characterId))
            corporationId != null -> WhereClause("WHERE corporation_id = ?", listOf(corporationId))
            else -> WhereClause("", emptyList())
        }

    fun upsertAll(records: List<OrderHistoryRecord>) {
        if (records.isEmpty()) return
        DatabaseManager.transaction {
            // ON CONFLICT (not OR REPLACE) with MAX on relist_fees_paid: ESI history rows carry no
            // fee info, so a later re-sync (fee 0 on the incoming row) must not wipe the stored value.
            val sql =
                """
                INSERT INTO order_history
                (order_id, type_id, type_name, location_id, station_name, price, volume_total,
                 volume_remaining, is_buy_order, duration, issued, range, min_volume, state, character_id,
                 corporation_id, is_corp, relist_fees_paid)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(order_id) DO UPDATE SET
                    type_id = excluded.type_id, type_name = excluded.type_name,
                    location_id = excluded.location_id, station_name = excluded.station_name,
                    price = excluded.price, volume_total = excluded.volume_total,
                    volume_remaining = excluded.volume_remaining, is_buy_order = excluded.is_buy_order,
                    duration = excluded.duration, issued = excluded.issued, range = excluded.range,
                    min_volume = excluded.min_volume, state = excluded.state,
                    character_id = excluded.character_id, corporation_id = excluded.corporation_id,
                    is_corp = excluded.is_corp,
                    relist_fees_paid = MAX(relist_fees_paid, excluded.relist_fees_paid)
                """.trimIndent()
            prepareStatement(sql).use { ps ->
                records.forEach { r ->
                    ps.setLong(1, r.orderId)
                    ps.setInt(2, r.typeId)
                    ps.setString(3, r.typeName)
                    ps.setLong(4, r.locationId)
                    ps.setString(5, r.stationName)
                    ps.setDouble(6, r.price)
                    ps.setInt(7, r.volumeTotal)
                    ps.setInt(8, r.volumeRemaining)
                    ps.setInt(9, if (r.isBuyOrder) 1 else 0)
                    ps.setInt(10, r.duration)
                    ps.setString(11, r.issued)
                    ps.setString(12, r.range)
                    ps.setInt(13, r.minVolume)
                    ps.setString(14, r.state)
                    if (r.characterId != null) ps.setInt(15, r.characterId) else ps.setNull(15, Types.INTEGER)
                    if (r.corporationId != null) ps.setInt(16, r.corporationId) else ps.setNull(16, Types.INTEGER)
                    ps.setInt(17, if (r.isCorp) 1 else 0)
                    ps.setDouble(18, r.relistFeesPaid)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    fun getAll(
        characterId: Int? = null,
        corporationId: Int? = null,
        isBuyOrder: Boolean? = null,
        limit: Int = 1000,
    ): List<OrderHistoryRecord> {
        val where = buildWhereClause(characterId, corporationId)
        val sql =
            buildString {
                append("SELECT * FROM order_history ${where.sql}")
                if (isBuyOrder != null) {
                    append(if (where.sql.isEmpty()) " WHERE" else " AND")
                    append(" is_buy_order = ${if (isBuyOrder) 1 else 0}")
                }
                append(" ORDER BY issued DESC LIMIT ?")
            }
        return DatabaseManager.transaction {
            prepareStatement(sql).use { ps ->
                var i = 1
                where.params.forEach { ps.setObject(i++, it) }
                ps.setInt(i, limit)
                val rs = ps.executeQuery()
                val result = mutableListOf<OrderHistoryRecord>()
                while (rs.next()) {
                    result.add(
                        OrderHistoryRecord(
                            orderId = rs.getLong("order_id"),
                            typeId = rs.getInt("type_id"),
                            typeName = rs.getString("type_name") ?: "",
                            locationId = rs.getLong("location_id"),
                            stationName = rs.getString("station_name") ?: "",
                            price = rs.getDouble("price"),
                            volumeTotal = rs.getInt("volume_total"),
                            volumeRemaining = rs.getInt("volume_remaining"),
                            isBuyOrder = rs.getInt("is_buy_order") == 1,
                            duration = rs.getInt("duration"),
                            issued = rs.getString("issued") ?: "",
                            range = rs.getString("range") ?: "station",
                            minVolume = rs.getInt("min_volume"),
                            state = rs.getString("state") ?: "expired",
                            characterId = rs.getInt("character_id").takeIf { !rs.wasNull() },
                            corporationId = rs.getInt("corporation_id").takeIf { !rs.wasNull() },
                            isCorp = rs.getInt("is_corp") == 1,
                            relistFeesPaid = rs.getDouble("relist_fees_paid"),
                        ),
                    )
                }
                result
            }
        }
    }
}
