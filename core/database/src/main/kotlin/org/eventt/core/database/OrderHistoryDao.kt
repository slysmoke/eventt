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
    )

    fun upsertAll(records: List<OrderHistoryRecord>) {
        if (records.isEmpty()) return
        DatabaseManager.transaction {
            val sql = """
                INSERT OR REPLACE INTO order_history
                (order_id, type_id, type_name, location_id, station_name, price, volume_total,
                 volume_remaining, is_buy_order, duration, issued, range, min_volume, state, character_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    fun getAll(charId: Int, isBuyOrder: Boolean? = null, limit: Int = 1000): List<OrderHistoryRecord> {
        val sql = buildString {
            append("SELECT * FROM order_history WHERE character_id = ?")
            if (isBuyOrder != null) append(" AND is_buy_order = ${if (isBuyOrder) 1 else 0}")
            append(" ORDER BY issued DESC LIMIT ?")
        }
        return DatabaseManager.transaction {
            prepareStatement(sql).use { ps ->
                ps.setInt(1, charId)
                ps.setInt(2, limit)
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
                        )
                    )
                }
                result
            }
        }
    }
}
