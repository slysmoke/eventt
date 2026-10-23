package org.eventt.core.database

/** See the leaderboard_ranks DDL — daily places, for the board's change-since-yesterday arrows. */
object LeaderboardRankDao {
    /** Replaces [day]'s places for [window] with [ranks] (pubkey → 1-based place). */
    fun record(
        day: String,
        window: String,
        ranks: Map<String, Int>,
    ) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM leaderboard_ranks WHERE day = ? AND window = ?").use {
                it.setString(1, day)
                it.setString(2, window)
                it.executeUpdate()
            }
            prepareStatement("INSERT INTO leaderboard_ranks (day, window, pubkey, rank) VALUES (?, ?, ?, ?)").use { stmt ->
                ranks.forEach { (pubkey, rank) ->
                    stmt.setString(1, day)
                    stmt.setString(2, window)
                    stmt.setString(3, pubkey)
                    stmt.setInt(4, rank)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    /** Places on the latest recorded day before [day] — empty when there's none yet. */
    fun previous(
        day: String,
        window: String,
    ): Map<String, Int> =
        DatabaseManager.transaction {
            prepareStatement(
                "SELECT pubkey, rank FROM leaderboard_ranks WHERE window = ? AND day = " +
                    "(SELECT MAX(day) FROM leaderboard_ranks WHERE window = ? AND day < ?)",
            ).use { stmt ->
                stmt.setString(1, window)
                stmt.setString(2, window)
                stmt.setString(3, day)
                stmt.executeQuery().use { rs ->
                    val result = mutableMapOf<String, Int>()
                    while (rs.next()) result[rs.getString("pubkey")] = rs.getInt("rank")
                    result
                }
            }
        }
}
