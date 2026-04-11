package org.eve.trader.core.database

import org.eve.trader.core.model.TrackedOrderModel

object TrackedOrderDao {

    fun insert(order: TrackedOrderModel): Int {
        return DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT INTO tracked_orders (type_id, type_name, buy_price, quantity, current_sell_price, station_id, station_name, notes, character_id, corporation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                java.sql.Statement.RETURN_GENERATED_KEYS
            ).use { stmt ->
                stmt.setInt(1, order.typeId)
                stmt.setString(2, order.typeName)
                stmt.setDouble(3, order.buyPrice)
                stmt.setInt(4, order.quantity)
                stmt.setDouble(5, order.currentSellPrice)
                stmt.setLong(6, order.stationId)
                stmt.setString(7, order.stationName)
                stmt.setString(8, order.notes)
                order.characterId?.let { stmt.setInt(9, it) } ?: stmt.setNull(9, java.sql.Types.INTEGER)
                order.corporationId?.let { stmt.setInt(10, it) } ?: stmt.setNull(10, java.sql.Types.INTEGER)
                stmt.executeUpdate()
                stmt.generatedKeys.use { keys ->
                    if (keys.next()) keys.getInt(1) else 0
                }
            }
        }
    }

    fun update(order: TrackedOrderModel) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                UPDATE tracked_orders SET type_id = ?, type_name = ?, buy_price = ?, quantity = ?,
                current_sell_price = ?, station_id = ?, station_name = ?, notes = ?, updated_at = ?,
                character_id = ?, corporation_id = ? WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setInt(1, order.typeId)
                stmt.setString(2, order.typeName)
                stmt.setDouble(3, order.buyPrice)
                stmt.setInt(4, order.quantity)
                stmt.setDouble(5, order.currentSellPrice)
                stmt.setLong(6, order.stationId)
                stmt.setString(7, order.stationName)
                stmt.setString(8, order.notes)
                stmt.setLong(9, System.currentTimeMillis())
                order.characterId?.let { stmt.setInt(10, it) } ?: stmt.setNull(10, java.sql.Types.INTEGER)
                order.corporationId?.let { stmt.setInt(11, it) } ?: stmt.setNull(11, java.sql.Types.INTEGER)
                stmt.setInt(12, order.id)
                stmt.executeUpdate()
            }
        }
    }

    fun updateSellPrice(id: Int, sellPrice: Double) {
        DatabaseManager.transaction {
            prepareStatement(
                "UPDATE tracked_orders SET current_sell_price = ?, updated_at = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setDouble(1, sellPrice)
                stmt.setLong(2, System.currentTimeMillis())
                stmt.setInt(3, id)
                stmt.executeUpdate()
            }
        }
    }

    fun getById(id: Int): TrackedOrderModel? {
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM tracked_orders WHERE id = ?").use { stmt ->
                stmt.setInt(1, id)
                stmt.executeQuery().mapResultSetToOrders().firstOrNull()
            }
        }
    }

    fun getAll(): List<TrackedOrderModel> {
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM tracked_orders ORDER BY updated_at DESC").use { stmt ->
                stmt.executeQuery().mapResultSetToOrders()
            }
        }
    }

    fun getByCharacter(characterId: Int): List<TrackedOrderModel> {
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM tracked_orders WHERE character_id = ? ORDER BY updated_at DESC").use { stmt ->
                stmt.setInt(1, characterId)
                stmt.executeQuery().mapResultSetToOrders()
            }
        }
    }

    fun getByCorporation(corporationId: Int): List<TrackedOrderModel> {
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM tracked_orders WHERE corporation_id = ? ORDER BY updated_at DESC").use { stmt ->
                stmt.setInt(1, corporationId)
                stmt.executeQuery().mapResultSetToOrders()
            }
        }
    }

    fun delete(id: Int) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM tracked_orders WHERE id = ?").use { stmt ->
                stmt.setInt(1, id)
                stmt.executeUpdate()
            }
        }
    }

    private fun java.sql.ResultSet.mapResultSetToOrders(): List<TrackedOrderModel> {
        val list = mutableListOf<TrackedOrderModel>()
        while (next()) {
            list.add(
                TrackedOrderModel(
                    id = getInt("id"),
                    typeId = getInt("type_id"),
                    typeName = getString("type_name") ?: "",
                    buyPrice = getDouble("buy_price"),
                    quantity = getInt("quantity"),
                    currentSellPrice = getDouble("current_sell_price"),
                    stationId = getLong("station_id"),
                    stationName = getString("station_name") ?: "",
                    notes = getString("notes") ?: "",
                    createdAt = getLong("created_at"),
                    updatedAt = getLong("updated_at"),
                    characterId = getInt("character_id").takeIf { it != 0 },
                    corporationId = getInt("corporation_id").takeIf { it != 0 },
                )
            )
        }
        return list
    }
}
