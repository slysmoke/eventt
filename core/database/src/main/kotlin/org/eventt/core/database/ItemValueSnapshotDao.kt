package org.eventt.core.database

/** See the item_value_snapshots DDL — what everything owned besides ISK was worth, per day. */
object ItemValueSnapshotDao {
    fun upsert(
        scope: String,
        day: String,
        items: Double,
    ) {
        DatabaseManager.transaction {
            prepareStatement("INSERT OR REPLACE INTO item_value_snapshots (scope, day, items) VALUES (?, ?, ?)").use { stmt ->
                stmt.setString(1, scope)
                stmt.setString(2, day)
                stmt.setDouble(3, items)
                stmt.executeUpdate()
            }
        }
    }

    /** Day (yyyy-MM-dd) → value, ascending. */
    fun getAll(scope: String): Map<String, Double> =
        DatabaseManager.transaction {
            prepareStatement("SELECT day, items FROM item_value_snapshots WHERE scope = ? ORDER BY day").use { stmt ->
                stmt.setString(1, scope)
                stmt.executeQuery().use { rs ->
                    val result = linkedMapOf<String, Double>()
                    while (rs.next()) result[rs.getString("day")] = rs.getDouble("items")
                    result
                }
            }
        }
}
