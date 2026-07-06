package org.eventt.core.database

import org.eventt.core.model.PriceAlertModel

object AlertDao {
    fun insert(alert: PriceAlertModel): Int =
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
    }

    fun markTriggered(id: Int) {
        DatabaseManager.transaction {
            prepareStatement("UPDATE price_alerts SET triggered = 1, triggered_at = ? WHERE id = ?").use { stmt ->
                stmt.setLong(1, System.currentTimeMillis())
                stmt.setInt(2, id)
                stmt.executeUpdate()
            }
        }
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
