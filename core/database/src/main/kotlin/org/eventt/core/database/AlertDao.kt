package org.eventt.core.database

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eventt.core.model.PriceAlertModel

object AlertDao {
    // Bumped after every write — screens stay mounted after their first visit, so the Alerts list
    // watches this instead of only loading once (an alert created from Market Analysis or fired by
    // AlertMonitor otherwise never showed up there until a restart).
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun changed() {
        _revision.value++
    }

    fun insert(alert: PriceAlertModel): Int = insertRow(alert).also { changed() }

    /** Several inserts, one revision bump — a bulk alert run shouldn't reload the list per row. */
    fun insertAll(alerts: List<PriceAlertModel>) {
        if (alerts.isEmpty()) return
        alerts.forEach { insertRow(it) }
        changed()
    }

    private fun insertRow(alert: PriceAlertModel): Int =
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT INTO price_alerts (type_id, type_name, target_price, condition_type, station_id, region_id, order_type, enabled, triggered, triggered_at, character_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { stmt ->
                stmt.setInt(1, alert.typeId)
                stmt.setString(2, alert.typeName)
                stmt.setDouble(3, alert.targetPrice)
                stmt.setString(4, alert.condition)
                stmt.setLong(5, alert.stationId)
                stmt.setInt(6, alert.regionId)
                stmt.setString(7, alert.orderType)
                stmt.setInt(8, if (alert.enabled) 1 else 0)
                stmt.setInt(9, if (alert.triggered) 1 else 0)
                alert.triggeredAt?.let { stmt.setLong(10, it) } ?: stmt.setNull(10, java.sql.Types.INTEGER)
                alert.characterId?.let { stmt.setInt(11, it) } ?: stmt.setNull(11, java.sql.Types.INTEGER)
                stmt.executeUpdate()
                stmt.generatedKeys.use { keys ->
                    if (keys.next()) keys.getInt(1) else 0
                }
            }
        }

    fun update(alert: PriceAlertModel) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                UPDATE price_alerts SET type_id = ?, type_name = ?, target_price = ?, condition_type = ?,
                    station_id = ?, region_id = ?, order_type = ?, enabled = ?, triggered = ?, triggered_at = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setInt(1, alert.typeId)
                stmt.setString(2, alert.typeName)
                stmt.setDouble(3, alert.targetPrice)
                stmt.setString(4, alert.condition)
                stmt.setLong(5, alert.stationId)
                stmt.setInt(6, alert.regionId)
                stmt.setString(7, alert.orderType)
                stmt.setInt(8, if (alert.enabled) 1 else 0)
                stmt.setInt(9, if (alert.triggered) 1 else 0)
                alert.triggeredAt?.let { stmt.setLong(10, it) } ?: stmt.setNull(10, java.sql.Types.INTEGER)
                stmt.setInt(11, alert.id)
                stmt.executeUpdate()
            }
        }
        changed()
    }

    fun getAll(): List<PriceAlertModel> =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM price_alerts ORDER BY created_at DESC").use { stmt ->
                stmt.executeQuery().mapResultSetToAlerts()
            }
        }

    fun getEnabled(): List<PriceAlertModel> =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM price_alerts WHERE enabled = 1 ORDER BY created_at DESC").use { stmt ->
                stmt.executeQuery().mapResultSetToAlerts()
            }
        }

    fun delete(id: Int) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM price_alerts WHERE id = ?").use { stmt ->
                stmt.setInt(1, id)
                stmt.executeUpdate()
            }
        }
        changed()
    }

    fun setEnabled(
        id: Int,
        enabled: Boolean,
    ) {
        DatabaseManager.transaction {
            prepareStatement("UPDATE price_alerts SET enabled = ? WHERE id = ?").use { stmt ->
                stmt.setInt(1, if (enabled) 1 else 0)
                stmt.setInt(2, id)
                stmt.executeUpdate()
            }
        }
        changed()
    }

    fun markTriggered(id: Int) {
        DatabaseManager.transaction {
            prepareStatement("UPDATE price_alerts SET triggered = 1, triggered_at = ? WHERE id = ?").use { stmt ->
                stmt.setLong(1, System.currentTimeMillis())
                stmt.setInt(2, id)
                stmt.executeUpdate()
            }
        }
        changed()
    }

    private fun java.sql.ResultSet.mapResultSetToAlerts(): List<PriceAlertModel> {
        val list = mutableListOf<PriceAlertModel>()
        while (next()) {
            list.add(
                PriceAlertModel(
                    id = getInt("id"),
                    typeId = getInt("type_id"),
                    typeName = getString("type_name") ?: "",
                    targetPrice = getDouble("target_price"),
                    condition = getString("condition_type") ?: "below",
                    stationId = getLong("station_id"),
                    regionId = getInt("region_id"),
                    orderType = getString("order_type") ?: "sell",
                    enabled = getInt("enabled") == 1,
                    triggered = getInt("triggered") == 1,
                    triggeredAt = getLong("triggered_at").takeIf { it != 0L },
                    createdAt = getLong("created_at"),
                    characterId = getInt("character_id").takeIf { it != 0 },
                ),
            )
        }
        return list
    }
}
