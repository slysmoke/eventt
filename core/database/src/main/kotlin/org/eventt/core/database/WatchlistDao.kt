package org.eventt.core.database

import org.eventt.core.model.WatchlistEntryModel
import org.eventt.core.model.WatchlistPriceSnapshot

object WatchlistDao {

    // ─── Watchlist Entries ────────────────────────────────────────────────

    fun insert(entry: WatchlistEntryModel): Int {
        return DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT INTO watchlist (type_id, type_name, watchlist_name, station_id, region_id, sort_order)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                java.sql.Statement.RETURN_GENERATED_KEYS
            ).use { stmt ->
                stmt.setInt(1, entry.typeId)
                stmt.setString(2, entry.typeName)
                stmt.setString(3, entry.watchlistName)
                stmt.setLong(4, entry.stationId)
                stmt.setInt(5, entry.regionId)
                stmt.setInt(6, entry.sortOrder)
                stmt.executeUpdate()
                stmt.generatedKeys.use { keys ->
                    if (keys.next()) keys.getInt(1) else 0
                }
            }
        }
    }

    fun delete(id: Int) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM watchlist WHERE id = ?").use { stmt ->
                stmt.setInt(1, id)
                stmt.executeUpdate()
            }
        }
    }

    fun getByWatchlistName(name: String): List<WatchlistEntryModel> {
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM watchlist WHERE watchlist_name = ? ORDER BY sort_order").use { stmt ->
                stmt.setString(1, name)
                stmt.executeQuery().mapResultSetToEntries()
            }
        }
    }

    fun getAllWatchlists(): Map<String, List<WatchlistEntryModel>> {
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM watchlist ORDER BY watchlist_name, sort_order").use { stmt ->
                stmt.executeQuery().use { rs ->
                    val map = mutableMapOf<String, MutableList<WatchlistEntryModel>>()
                    while (rs.next()) {
                        val entry = rs.mapResultSetToEntry()
                        map.getOrPut(entry.watchlistName) { mutableListOf() }.add(entry)
                    }
                    map
                }
            }
        }
    }

    // ─── Price Snapshots ──────────────────────────────────────────────────

    fun insertPriceSnapshot(snapshot: WatchlistPriceSnapshot) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT INTO watchlist_prices (type_id, station_id, best_buy_price, best_sell_price, spread, spread_percent, volume_24h, change_percent_24h, change_percent_7d, change_percent_30d, sparkline_data)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setInt(1, snapshot.typeId)
                stmt.setLong(2, snapshot.stationId)
                stmt.setDouble(3, snapshot.bestBuyPrice)
                stmt.setDouble(4, snapshot.bestSellPrice)
                stmt.setDouble(5, snapshot.spread)
                stmt.setDouble(6, snapshot.spreadPercent)
                stmt.setLong(7, snapshot.volume24h)
                stmt.setDouble(8, snapshot.changePercent24h)
                stmt.setDouble(9, snapshot.changePercent7d)
                stmt.setDouble(10, snapshot.changePercent30d)
                // Serialize sparkline data as JSON
                val sparklineJson = snapshot.sparklineData.joinToString(",") { "[\"${it.first}\",${it.second}]" }
                stmt.setString(11, "[$sparklineJson]")
                stmt.executeUpdate()
            }
        }
    }

    fun getLatestPrice(typeId: Int): WatchlistPriceSnapshot? {
        return DatabaseManager.transaction {
            prepareStatement(
                "SELECT * FROM watchlist_prices WHERE type_id = ? ORDER BY captured_at DESC LIMIT 1"
            ).use { stmt ->
                stmt.setInt(1, typeId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val sparklineJson = rs.getString("sparkline_data") ?: "[]"
                        WatchlistPriceSnapshot(
                            typeId = rs.getInt("type_id"),
                            stationId = rs.getLong("station_id"),
                            bestBuyPrice = rs.getDouble("best_buy_price"),
                            bestSellPrice = rs.getDouble("best_sell_price"),
                            spread = rs.getDouble("spread"),
                            spreadPercent = rs.getDouble("spread_percent"),
                            volume24h = rs.getLong("volume_24h"),
                            changePercent24h = rs.getDouble("change_percent_24h"),
                            changePercent7d = rs.getDouble("change_percent_7d"),
                            changePercent30d = rs.getDouble("change_percent_30d"),
                            sparklineData = parseSparklineJson(sparklineJson),
                        )
                    } else null
                }
            }
        }
    }

    private fun parseSparklineJson(json: String): List<Pair<String, Double>> {
        // Format stored: [["2024-01-01",1234.5],...]
        return runCatching {
            json.trim('[', ']').split("],[").mapNotNull { entry ->
                val clean = entry.trim('[', ']')
                val parts = clean.split(",")
                if (parts.size < 2) return@mapNotNull null
                val date = parts[0].trim('"')
                val price = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                date to price
            }
        }.getOrDefault(emptyList())
    }

    fun getPriceHistory(typeId: Int, stationId: Long, days: Int = 30): List<WatchlistPriceSnapshot> {
        return DatabaseManager.transaction {
            prepareStatement(
                """
                SELECT * FROM watchlist_prices WHERE type_id = ? AND station_id = ?
                ORDER BY captured_at DESC LIMIT ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setInt(1, typeId)
                stmt.setLong(2, stationId)
                stmt.setInt(3, days)
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<WatchlistPriceSnapshot>()
                    while (rs.next()) {
                        list.add(
                            WatchlistPriceSnapshot(
                                typeId = rs.getInt("type_id"),
                                stationId = rs.getLong("station_id"),
                                bestBuyPrice = rs.getDouble("best_buy_price"),
                                bestSellPrice = rs.getDouble("best_sell_price"),
                                spread = rs.getDouble("spread"),
                                spreadPercent = rs.getDouble("spread_percent"),
                                volume24h = rs.getLong("volume_24h"),
                                changePercent24h = rs.getDouble("change_percent_24h"),
                                changePercent7d = rs.getDouble("change_percent_7d"),
                                changePercent30d = rs.getDouble("change_percent_30d"),
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun java.sql.ResultSet.mapResultSetToEntry(): WatchlistEntryModel {
        return WatchlistEntryModel(
            id = getInt("id"),
            typeId = getInt("type_id"),
            typeName = getString("type_name") ?: "",
            watchlistName = getString("watchlist_name") ?: "Default",
            stationId = getLong("station_id"),
            regionId = getInt("region_id"),
            sortOrder = getInt("sort_order"),
        )
    }

    private fun java.sql.ResultSet.mapResultSetToEntries(): List<WatchlistEntryModel> {
        val list = mutableListOf<WatchlistEntryModel>()
        while (next()) {
            list.add(mapResultSetToEntry())
        }
        return list
    }
}
