package org.eve.trader.core.database

import org.eve.trader.core.model.MarketHistoryModel

object MarketDao {

    // ─── Market History ──────────────────────────────────────────────────

    fun insertHistory(entry: MarketHistoryModel) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO market_history (type_id, region_id, date, average, volume, order_count, highest, lowest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
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

    fun insertHistoryBatch(entries: List<MarketHistoryModel>, source: String = "esi") {
        if (entries.isEmpty()) return
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO market_history (type_id, region_id, date, average, volume, order_count, highest, lowest, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
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

    fun getHistory(typeId: Int, regionId: Int, days: Int = 90): List<MarketHistoryModel> {
        return DatabaseManager.transaction {
            prepareStatement(
                """
                SELECT * FROM market_history WHERE type_id = ? AND region_id = ?
                ORDER BY date DESC LIMIT ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setInt(1, typeId)
                stmt.setInt(2, regionId)
                stmt.setInt(3, days)
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
                            )
                        )
                    }
                    list
                }
            }
        }
    }
}
