package org.eventt.core.database

import org.eventt.core.model.AssetModel

object AssetDao {
    private val INSERT_SQL =
        """
        INSERT OR REPLACE INTO assets (item_id, type_id, type_name, quantity, location_id, location_name,
            region_id, region_name, system_id, system_name, station_id, station_name,
            is_singleton, location_flag, estimated_price, is_corp_asset, character_id, corporation_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    private fun java.sql.PreparedStatement.bindAsset(asset: AssetModel) {
        setLong(1, asset.itemId)
        setInt(2, asset.typeId)
        setString(3, asset.typeName)
        setInt(4, asset.quantity)
        setLong(5, asset.locationId)
        setString(6, asset.locationName)
        setInt(7, asset.regionId)
        setString(8, asset.regionName)
        setInt(9, asset.systemId)
        setString(10, asset.systemName)
        setLong(11, asset.stationId)
        setString(12, asset.stationName)
        setInt(13, if (asset.isSingleton) 1 else 0)
        setString(14, asset.locationFlag)
        setDouble(15, asset.estimatedPrice)
        setInt(16, if (asset.isCorpAsset) 1 else 0)
        asset.characterId?.let { setInt(17, it) } ?: setNull(17, java.sql.Types.INTEGER)
        asset.corporationId?.let { setInt(18, it) } ?: setNull(18, java.sql.Types.INTEGER)
    }

    fun upsert(asset: AssetModel) {
        DatabaseManager.transaction {
            prepareStatement(INSERT_SQL).use { stmt ->
                stmt.bindAsset(asset)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Atomically replace one owner's stored snapshot: delete every asset row belonging to the
     * character (or corporation) and insert the fresh ESI list in the same transaction. Without
     * the delete, items that no longer exist in game (sold, moved, destroyed) linger forever.
     */
    fun replaceFor(
        characterId: Int? = null,
        corporationId: Int? = null,
        assets: List<AssetModel>,
    ) {
        require((characterId != null) xor (corporationId != null)) {
            "replaceFor needs exactly one of characterId/corporationId"
        }
        DatabaseManager.transaction {
            autoCommit = false
            try {
                val (deleteSql, ownerId) =
                    if (characterId != null) {
                        "DELETE FROM assets WHERE character_id = ?" to characterId
                    } else {
                        "DELETE FROM assets WHERE corporation_id = ?" to corporationId!!
                    }
                prepareStatement(deleteSql).use { stmt ->
                    stmt.setInt(1, ownerId)
                    stmt.executeUpdate()
                }
                prepareStatement(INSERT_SQL).use { stmt ->
                    assets.forEach { asset ->
                        stmt.bindAsset(asset)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                commit()
            } catch (e: Exception) {
                rollback()
                throw e
            }
        }
    }

    fun getByCharacter(characterId: Int): List<AssetModel> =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM assets WHERE character_id = ? ORDER BY location_id, type_name").use { stmt ->
                stmt.setInt(1, characterId)
                stmt.executeQuery().mapResultSetToAssets()
            }
        }

    fun getByCorporation(corporationId: Int): List<AssetModel> =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM assets WHERE corporation_id = ? ORDER BY location_id, type_name").use { stmt ->
                stmt.setInt(1, corporationId)
                stmt.executeQuery().mapResultSetToAssets()
            }
        }

    fun getTotalValue(
        characterId: Int? = null,
        corporationId: Int? = null,
    ): Double =
        DatabaseManager.transaction {
            val where =
                when {
                    characterId != null -> "WHERE character_id = ?"
                    corporationId != null -> "WHERE corporation_id = ?"
                    else -> ""
                }
            prepareStatement("SELECT SUM(estimated_price * quantity) FROM assets $where").use { stmt ->
                if (characterId != null) {
                    stmt.setInt(1, characterId)
                } else if (corporationId != null) {
                    stmt.setInt(1, corporationId)
                }
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getDouble(1) else 0.0
                }
            }
        }

    fun deleteByCharacter(characterId: Int) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM assets WHERE character_id = ?").use { stmt ->
                stmt.setInt(1, characterId)
                stmt.executeUpdate()
            }
        }
    }

    fun deleteByCorporation(corporationId: Int) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM assets WHERE corporation_id = ?").use { stmt ->
                stmt.setInt(1, corporationId)
                stmt.executeUpdate()
            }
        }
    }

    private fun java.sql.ResultSet.mapResultSetToAssets(): List<AssetModel> {
        val list = mutableListOf<AssetModel>()
        while (next()) {
            list.add(
                AssetModel(
                    itemId = getLong("item_id"),
                    typeId = getInt("type_id"),
                    typeName = getString("type_name") ?: "",
                    quantity = getInt("quantity"),
                    locationId = getLong("location_id"),
                    locationName = getString("location_name") ?: "",
                    regionId = getInt("region_id"),
                    regionName = getString("region_name") ?: "",
                    systemId = getInt("system_id"),
                    systemName = getString("system_name") ?: "",
                    stationId = getLong("station_id"),
                    stationName = getString("station_name") ?: "",
                    isSingleton = getInt("is_singleton") == 1,
                    locationFlag = getString("location_flag") ?: "",
                    estimatedPrice = getDouble("estimated_price"),
                    isCorpAsset = getInt("is_corp_asset") == 1,
                    characterId = getInt("character_id").takeIf { it != 0 },
                    corporationId = getInt("corporation_id").takeIf { it != 0 },
                ),
            )
        }
        return list
    }
}
