package org.eventt.core.database

import java.sql.Types

/**
 * Local cache of a character's/corp's currently active orders — lets the Orders screen show the
 * last-known snapshot instantly on startup instead of an empty table while ESI's live fetch is
 * in flight, and survives an app restart for data that arrived via a Marketlogs file import.
 * Unlike [OrderHistoryDao] (append-only), this is a full-snapshot replace on every refresh: an
 * order that's no longer present in the latest fetch (filled/cancelled/expired) must vanish here
 * too, not just accumulate.
 */
object ActiveOrderDao {
    data class ActiveOrderRecord(
        val orderId: Long,
        val typeId: Int,
        val typeName: String,
        val locationId: Long,
        val regionId: Int,
        val stationName: String,
        val price: Double,
        val volumeTotal: Int,
        val volumeRemaining: Int,
        val isBuyOrder: Boolean,
        val duration: Int,
        val issued: String,
        val state: String,
        val issuedByCharId: Int?,
        val characterId: Int?,
        val corporationId: Int?,
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

    /** Replaces the entire active-orders snapshot for one character/corp scope in a single transaction. */
    fun replaceAll(
        characterId: Int?,
        corporationId: Int?,
        records: List<ActiveOrderRecord>,
    ) {
        val where = buildWhereClause(characterId, corporationId)
        if (where.sql.isEmpty()) return
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM active_orders ${where.sql}").use { ps ->
                where.params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                ps.executeUpdate()
            }
            if (records.isEmpty()) return@transaction
            val sql =
                """
                INSERT OR REPLACE INTO active_orders
                (order_id, type_id, type_name, location_id, region_id, station_name, price, volume_total,
                 volume_remaining, is_buy_order, duration, issued, state, issued_by_char_id, character_id, corporation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            prepareStatement(sql).use { ps ->
                records.forEach { r ->
                    ps.setLong(1, r.orderId)
                    ps.setInt(2, r.typeId)
                    ps.setString(3, r.typeName)
                    ps.setLong(4, r.locationId)
                    ps.setInt(5, r.regionId)
                    ps.setString(6, r.stationName)
                    ps.setDouble(7, r.price)
                    ps.setInt(8, r.volumeTotal)
                    ps.setInt(9, r.volumeRemaining)
                    ps.setInt(10, if (r.isBuyOrder) 1 else 0)
                    ps.setInt(11, r.duration)
                    ps.setString(12, r.issued)
                    ps.setString(13, r.state)
                    if (r.issuedByCharId != null) ps.setInt(14, r.issuedByCharId) else ps.setNull(14, Types.INTEGER)
                    if (r.characterId != null) ps.setInt(15, r.characterId) else ps.setNull(15, Types.INTEGER)
                    if (r.corporationId != null) ps.setInt(16, r.corporationId) else ps.setNull(16, Types.INTEGER)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    fun getAll(
        characterId: Int? = null,
        corporationId: Int? = null,
    ): List<ActiveOrderRecord> {
        val where = buildWhereClause(characterId, corporationId)
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM active_orders ${where.sql} ORDER BY issued DESC").use { ps ->
                where.params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                val rs = ps.executeQuery()
                val result = mutableListOf<ActiveOrderRecord>()
                while (rs.next()) {
                    result.add(
                        ActiveOrderRecord(
                            orderId = rs.getLong("order_id"),
                            typeId = rs.getInt("type_id"),
                            typeName = rs.getString("type_name") ?: "",
                            locationId = rs.getLong("location_id"),
                            regionId = rs.getInt("region_id"),
                            stationName = rs.getString("station_name") ?: "",
                            price = rs.getDouble("price"),
                            volumeTotal = rs.getInt("volume_total"),
                            volumeRemaining = rs.getInt("volume_remaining"),
                            isBuyOrder = rs.getInt("is_buy_order") == 1,
                            duration = rs.getInt("duration"),
                            issued = rs.getString("issued") ?: "",
                            state = rs.getString("state") ?: "active",
                            issuedByCharId = rs.getInt("issued_by_char_id").takeIf { !rs.wasNull() },
                            characterId = rs.getInt("character_id").takeIf { !rs.wasNull() },
                            corporationId = rs.getInt("corporation_id").takeIf { !rs.wasNull() },
                        ),
                    )
                }
                result
            }
        }
    }
}
