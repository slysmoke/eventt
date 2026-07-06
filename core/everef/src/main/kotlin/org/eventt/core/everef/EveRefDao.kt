package org.eventt.core.everef

import org.eventt.core.database.DatabaseManager

object EveRefDao {
    fun getDownloadedDates(): List<String> =
        DatabaseManager.transaction {
            createStatement().executeQuery("SELECT date FROM everef_downloads ORDER BY date").use { rs ->
                val list = mutableListOf<String>()
                while (rs.next()) list.add(rs.getString(1))
                list
            }
        }

    fun markDownloaded(
        date: String,
        fileName: String,
        size: Long,
    ) = DatabaseManager.transaction {
        prepareStatement(
            "INSERT OR REPLACE INTO everef_downloads (date, file_name, size, downloaded_at) VALUES (?, ?, ?, ?)",
        ).use { stmt ->
            stmt.setString(1, date)
            stmt.setString(2, fileName)
            stmt.setLong(3, size)
            stmt.setLong(4, System.currentTimeMillis())
            stmt.executeUpdate()
        }
    }

    fun deleteBeforeDate(date: String) =
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM everef_downloads WHERE date < ?").use { stmt ->
                stmt.setString(1, date)
                stmt.executeUpdate()
            }
        }

    fun getDownloadCount(): Int =
        DatabaseManager.transaction {
            createStatement().executeQuery("SELECT COUNT(*) FROM everef_downloads").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }

    fun getEarliestDate(): String? =
        DatabaseManager.transaction {
            createStatement().executeQuery("SELECT MIN(date) FROM everef_downloads").use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }

    fun getLatestDate(): String? =
        DatabaseManager.transaction {
            createStatement().executeQuery("SELECT MAX(date) FROM everef_downloads").use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }
}
