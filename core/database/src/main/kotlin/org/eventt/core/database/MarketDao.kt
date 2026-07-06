package org.eventt.core.database

import org.eventt.core.model.MarketHistoryModel

object MarketDao {
    // ─── Market History ──────────────────────────────────────────────────

    fun insertHistory(entry: MarketHistoryModel) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO market_history (type_id, region_id, date, average, volume, order_count, highest, lowest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setInt(1, entry.typeId)
                stmt.setInt(2, entry.regionId)
                stmt.setString(3, entry.date)
                stmt.setDouble(4, entry.average)
                stmt.setLong(5, entry.volume)
                stmt.setLong(6, entry.orderCount)
                stmt.setDouble(7, entry.highest)
                stmt.setDouble(8, entry.lowest)
                stmt.executeUpdate()
            }
        }
    }

    fun insertHistoryBatch(
        entries: List<MarketHistoryModel>,
        source: String = "esi",
    ) {
        if (entries.isEmpty()) return
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO market_history (type_id, region_id, date, average, volume, order_count, highest, lowest, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                var count = 0
                for (entry in entries) {
                    stmt.setInt(1, entry.typeId)
                    stmt.setInt(2, entry.regionId)
                    stmt.setString(3, entry.date)
                    stmt.setDouble(4, entry.average)
                    stmt.setLong(5, entry.volume)
                    stmt.setLong(6, entry.orderCount)
                    stmt.setDouble(7, entry.highest)
                    stmt.setDouble(8, entry.lowest)
                    stmt.setString(9, source)
                    stmt.addBatch()
                    if (++count % 10_000 == 0) stmt.executeBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    fun deleteEveRefBeforeDate(date: String) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM market_history WHERE date < ? AND source = 'everef'").use { stmt ->
                stmt.setString(1, date)
                stmt.executeUpdate()
            }
        }
    }

    fun getHistory(
        typeId: Int,
        regionId: Int,
        days: Int = 90,
    ): List<MarketHistoryModel> = getHistoryBySource(typeId, regionId, days, source = null)

    fun getHistoryBySource(
        typeId: Int,
        regionId: Int,
        days: Int = 90,
        source: String?,
    ): List<MarketHistoryModel> =
        DatabaseManager.transaction {
            val sql =
                buildString {
                    append("SELECT * FROM market_history WHERE type_id = ? AND region_id = ?")
                    if (source != null) append(" AND source = ?")
                    append(" ORDER BY date DESC LIMIT ?")
                }
            prepareStatement(sql).use { stmt ->
                stmt.setInt(1, typeId)
                stmt.setInt(2, regionId)
                if (source != null) {
                    stmt.setString(3, source)
                    stmt.setInt(4, days)
                } else {
                    stmt.setInt(3, days)
                }
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<MarketHistoryModel>()
                    while (rs.next()) {
                        list.add(
                            MarketHistoryModel(
                                typeId = rs.getInt("type_id"),
                                regionId = rs.getInt("region_id"),
                                date = rs.getString("date"),
                                average = rs.getDouble("average"),
                                volume = rs.getLong("volume"),
                                orderCount = rs.getLong("order_count"),
                                highest = rs.getDouble("highest"),
                                lowest = rs.getDouble("lowest"),
                            ),
                        )
                    }
                    list
                }
            }
        }
}
