package org.eventt.core.database

data class MarketTopSnapshot(
    val ts: Long,
    val bestPrice: Double,
    val bestOrderId: Long,
    val bestIsMine: Boolean,
)

/** See the market_top_snapshots DDL — one top-of-book row per ESI tick per tracked order book. */
object MarketTopSnapshotDao {
    /** No-op when this ESI tick (same Last-Modified) was already recorded. */
    fun insertIfAbsent(
        typeId: Int,
        scopeId: Long,
        isBuy: Boolean,
        ts: Long,
        bestPrice: Double,
        bestOrderId: Long,
        bestIsMine: Boolean,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR IGNORE INTO market_top_snapshots
                (type_id, scope_id, is_buy, ts, best_price, best_order_id, best_is_mine)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setInt(1, typeId)
                stmt.setLong(2, scopeId)
                stmt.setInt(3, if (isBuy) 1 else 0)
                stmt.setLong(4, ts)
                stmt.setDouble(5, bestPrice)
                stmt.setLong(6, bestOrderId)
                stmt.setInt(7, if (bestIsMine) 1 else 0)
                stmt.executeUpdate()
            }
        }
    }

    fun getWindow(
        typeId: Int,
        scopeId: Long,
        isBuy: Boolean,
        sinceTs: Long,
    ): List<MarketTopSnapshot> =
        DatabaseManager.transaction {
            prepareStatement(
                "SELECT ts, best_price, best_order_id, best_is_mine FROM market_top_snapshots " +
                    "WHERE type_id = ? AND scope_id = ? AND is_buy = ? AND ts >= ? ORDER BY ts",
            ).use { stmt ->
                stmt.setInt(1, typeId)
                stmt.setLong(2, scopeId)
                stmt.setInt(3, if (isBuy) 1 else 0)
                stmt.setLong(4, sinceTs)
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<MarketTopSnapshot>()
                    while (rs.next()) {
                        result.add(
                            MarketTopSnapshot(
                                ts = rs.getLong("ts"),
                                bestPrice = rs.getDouble("best_price"),
                                bestOrderId = rs.getLong("best_order_id"),
                                bestIsMine = rs.getInt("best_is_mine") == 1,
                            ),
                        )
                    }
                    result
                }
            }
        }

    fun pruneOlderThan(cutoffTs: Long) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM market_top_snapshots WHERE ts < ?").use { stmt ->
                stmt.setLong(1, cutoffTs)
                stmt.executeUpdate()
            }
        }
    }
}
