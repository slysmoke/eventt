package org.eve.trader.core.database

import org.eve.trader.core.model.StaticCategoryModel
import org.eve.trader.core.model.StaticGroupModel
import org.eve.trader.core.model.StaticMarketGroupModel
import org.eve.trader.core.model.StaticRegionModel
import org.eve.trader.core.model.StaticStationModel
import org.eve.trader.core.model.StaticSystemModel
import org.eve.trader.core.model.StaticTypeModel

object StaticDataDao {

    // ─── Types ────────────────────────────────────────────────────────────

    fun insertType(type: StaticTypeModel) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO static_types
                (type_id, name, group_id, category_id, volume, packaged_volume, portion_size, description, icon_id, published, market_group_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setInt(1, type.typeId)
                stmt.setString(2, type.name)
                stmt.setInt(3, type.groupId)
                stmt.setInt(4, type.categoryId)
                stmt.setDouble(5, type.volume)
                stmt.setDouble(6, type.packagedVolume)
                stmt.setInt(7, type.portionSize)
                stmt.setString(8, type.description)
                type.iconId?.let { stmt.setInt(9, it) } ?: stmt.setNull(9, java.sql.Types.INTEGER)
                stmt.setInt(10, if (type.published) 1 else 0)
                type.marketGroupId?.let { stmt.setInt(11, it) } ?: stmt.setNull(11, java.sql.Types.INTEGER)
                stmt.executeUpdate()
            }
        }
    }

    fun bulkInsertTypes(types: List<StaticTypeModel>) {
        if (types.isEmpty()) return
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO static_types
                (type_id, name, group_id, category_id, volume, packaged_volume, portion_size, description, icon_id, published, market_group_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                types.forEach { type ->
                    stmt.setInt(1, type.typeId)
                    stmt.setString(2, type.name)
                    stmt.setInt(3, type.groupId)
                    stmt.setInt(4, type.categoryId)
                    stmt.setDouble(5, type.volume)
                    stmt.setDouble(6, type.packagedVolume)
                    stmt.setInt(7, type.portionSize)
                    stmt.setString(8, type.description)
                    type.iconId?.let { stmt.setInt(9, it) } ?: stmt.setNull(9, java.sql.Types.INTEGER)
                    stmt.setInt(10, if (type.published) 1 else 0)
                    type.marketGroupId?.let { stmt.setInt(11, it) } ?: stmt.setNull(11, java.sql.Types.INTEGER)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    fun getTypeById(typeId: Int): StaticTypeModel? {
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM static_types WHERE type_id = ?").use { stmt ->
                stmt.setInt(1, typeId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.mapResultSetToType() else null
                }
            }
        }
    }

    fun getTypeName(typeId: Int): String? {
        return DatabaseManager.transaction {
            prepareStatement("SELECT name FROM static_types WHERE type_id = ?").use { stmt ->
                stmt.setInt(1, typeId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }
    }

    /** Search published types — includes non-market items (for assets, contracts). */
    fun searchTypes(query: String, limit: Int = 50): List<StaticTypeModel> {
        return DatabaseManager.transaction {
            prepareStatement(
                "SELECT * FROM static_types WHERE published = 1 AND name LIKE ? ORDER BY name LIMIT ?"
            ).use { stmt ->
                stmt.setString(1, "%$query%")
                stmt.setInt(2, limit)
                stmt.executeQuery().mapResultSetToTypes()
            }
        }
    }

    /** Search only market-tradeable types (market_group_id IS NOT NULL). */
    fun searchMarketTypes(query: String, limit: Int = 50): List<StaticTypeModel> {
        return DatabaseManager.transaction {
            prepareStatement(
                "SELECT * FROM static_types WHERE market_group_id IS NOT NULL AND name LIKE ? ORDER BY name LIMIT ?"
            ).use { stmt ->
                stmt.setString(1, "%$query%")
                stmt.setInt(2, limit)
                stmt.executeQuery().mapResultSetToTypes()
            }
        }
    }

    fun getTypesByGroup(groupId: Int): List<StaticTypeModel> {
        return DatabaseManager.transaction {
            prepareStatement(
                "SELECT * FROM static_types WHERE group_id = ? AND published = 1 ORDER BY name"
            ).use { stmt ->
                stmt.setInt(1, groupId)
                stmt.executeQuery().mapResultSetToTypes()
            }
        }
    }

    fun countTypes(): Int {
        return DatabaseManager.transaction {
            prepareStatement("SELECT COUNT(*) FROM static_types").use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    // ─── Groups ───────────────────────────────────────────────────────────

    fun insertGroup(group: StaticGroupModel) {
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO static_groups (group_id, name, category_id) VALUES (?, ?, ?)"
            ).use { stmt ->
                stmt.setInt(1, group.groupId)
                stmt.setString(2, group.name)
                stmt.setInt(3, group.categoryId)
                stmt.executeUpdate()
            }
        }
    }

    fun bulkInsertGroups(groups: List<StaticGroupModel>) {
        if (groups.isEmpty()) return
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO static_groups (group_id, name, category_id) VALUES (?, ?, ?)"
            ).use { stmt ->
                groups.forEach { group ->
                    stmt.setInt(1, group.groupId)
                    stmt.setString(2, group.name)
                    stmt.setInt(3, group.categoryId)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    fun getGroupById(groupId: Int): StaticGroupModel? {
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM static_groups WHERE group_id = ?").use { stmt ->
                stmt.setInt(1, groupId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        StaticGroupModel(
                            groupId = rs.getInt("group_id"),
                            name = rs.getString("name"),
                            categoryId = rs.getInt("category_id"),
                        )
                    } else null
                }
            }
        }
    }

    // ─── Market Groups ──────────────────────────────────────────────────

    fun insertMarketGroup(group: StaticMarketGroupModel) {
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO market_groups (market_group_id, name, parent_group_id) VALUES (?, ?, ?)"
            ).use { stmt ->
                stmt.setInt(1, group.marketGroupId)
                stmt.setString(2, group.name)
                group.parentGroupId?.let { stmt.setInt(3, it) } ?: stmt.setNull(3, java.sql.Types.INTEGER)
                stmt.executeUpdate()
            }
        }
    }

    fun bulkInsertMarketGroups(groups: List<StaticMarketGroupModel>) {
        if (groups.isEmpty()) return
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO market_groups (market_group_id, name, parent_group_id) VALUES (?, ?, ?)"
            ).use { stmt ->
                groups.forEach { g ->
                    stmt.setInt(1, g.marketGroupId)
                    stmt.setString(2, g.name)
                    g.parentGroupId?.let { stmt.setInt(3, it) } ?: stmt.setNull(3, java.sql.Types.INTEGER)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    /** Get top-level market groups (no parent). */
    fun getTopMarketGroups(): List<StaticMarketGroupModel> {
        return DatabaseManager.transaction {
            prepareStatement(
                "SELECT * FROM market_groups WHERE parent_group_id IS NULL ORDER BY name"
            ).use { stmt ->
                stmt.executeQuery().mapResultSetToMarketGroups()
            }
        }
    }

    /** Get child market groups of a given parent. */
    fun getChildMarketGroups(parentGroupId: Int): List<StaticMarketGroupModel> {
        return DatabaseManager.transaction {
            prepareStatement(
                "SELECT * FROM market_groups WHERE parent_group_id = ? ORDER BY name"
            ).use { stmt ->
                stmt.setInt(1, parentGroupId)
                stmt.executeQuery().mapResultSetToMarketGroups()
            }
        }
    }

    /** Get types that belong to a specific market group. */
    fun getTypesByMarketGroup(marketGroupId: Int, limit: Int = 200): List<StaticTypeModel> {
        return DatabaseManager.transaction {
            prepareStatement(
                "SELECT * FROM static_types WHERE market_group_id = ? AND published = 1 ORDER BY name LIMIT ?"
            ).use { stmt ->
                stmt.setInt(1, marketGroupId)
                stmt.setInt(2, limit)
                stmt.executeQuery().mapResultSetToTypes()
            }
        }
    }

    fun getMarketGroupById(marketGroupId: Int): StaticMarketGroupModel? {
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM market_groups WHERE market_group_id = ?").use { stmt ->
                stmt.setInt(1, marketGroupId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        StaticMarketGroupModel(
                            marketGroupId = rs.getInt("market_group_id"),
                            name = rs.getString("name"),
                            parentGroupId = rs.getInt("parent_group_id").takeIf { !rs.wasNull() },
                        )
                    } else null
                }
            }
        }
    }

    private fun java.sql.ResultSet.mapResultSetToMarketGroups(): List<StaticMarketGroupModel> {
        val list = mutableListOf<StaticMarketGroupModel>()
        while (next()) {
            list.add(
                StaticMarketGroupModel(
                    marketGroupId = getInt("market_group_id"),
                    name = getString("name"),
                    parentGroupId = getInt("parent_group_id").takeIf { !wasNull() },
                )
            )
        }
        return list
    }

    // ─── Categories ───────────────────────────────────────────────────────

    fun bulkInsertCategories(categories: List<StaticCategoryModel>) {
        if (categories.isEmpty()) return
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO static_categories (category_id, name) VALUES (?, ?)"
            ).use { stmt ->
                categories.forEach { cat ->
                    stmt.setInt(1, cat.categoryId)
                    stmt.setString(2, cat.name)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    // ─── Stations ─────────────────────────────────────────────────────────

    fun insertStation(station: StaticStationModel) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO static_stations (station_id, name, system_id, system_name, region_id, region_name, type_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, station.stationId)
                stmt.setString(2, station.name)
                stmt.setInt(3, station.systemId)
                stmt.setString(4, station.systemName)
                stmt.setInt(5, station.regionId)
                stmt.setString(6, station.regionName)
                stmt.setInt(7, station.typeId)
                stmt.executeUpdate()
            }
        }
    }

    fun bulkInsertStations(stations: List<StaticStationModel>) {
        if (stations.isEmpty()) return
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO static_stations (station_id, name, system_id, system_name, region_id, region_name, type_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stations.forEach { station ->
                    stmt.setLong(1, station.stationId)
                    stmt.setString(2, station.name)
                    stmt.setInt(3, station.systemId)
                    stmt.setString(4, station.systemName)
                    stmt.setInt(5, station.regionId)
                    stmt.setString(6, station.regionName)
                    stmt.setInt(7, station.typeId)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    fun getStationById(stationId: Long): StaticStationModel? {
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM static_stations WHERE station_id = ?").use { stmt ->
                stmt.setLong(1, stationId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.mapResultSetToStation() else null
                }
            }
        }
    }

    fun searchStations(query: String, limit: Int = 20): List<StaticStationModel> {
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM static_stations WHERE name LIKE ? ORDER BY name LIMIT ?").use { stmt ->
                stmt.setString(1, "%$query%")
                stmt.setInt(2, limit)
                stmt.executeQuery().mapResultSetToStations()
            }
        }
    }

    // ─── Regions ──────────────────────────────────────────────────────────

    fun insertRegion(region: StaticRegionModel) {
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO static_regions (region_id, name) VALUES (?, ?)"
            ).use { stmt ->
                stmt.setInt(1, region.regionId)
                stmt.setString(2, region.name)
                stmt.executeUpdate()
            }
        }
    }

    fun bulkInsertRegions(regions: List<StaticRegionModel>) {
        if (regions.isEmpty()) return
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO static_regions (region_id, name) VALUES (?, ?)"
            ).use { stmt ->
                regions.forEach { region ->
                    stmt.setInt(1, region.regionId)
                    stmt.setString(2, region.name)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    fun getAllRegions(): List<StaticRegionModel> {
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM static_regions ORDER BY name").use { stmt ->
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<StaticRegionModel>()
                    while (rs.next()) {
                        list.add(StaticRegionModel(regionId = rs.getInt("region_id"), name = rs.getString("name")))
                    }
                    list
                }
            }
        }
    }

    // ─── Systems ──────────────────────────────────────────────────────────

    fun insertSystem(system: StaticSystemModel) {
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO static_systems (system_id, name, region_id) VALUES (?, ?, ?)"
            ).use { stmt ->
                stmt.setInt(1, system.systemId)
                stmt.setString(2, system.name)
                stmt.setInt(3, system.regionId)
                stmt.executeUpdate()
            }
        }
    }

    fun bulkInsertSystems(systems: List<StaticSystemModel>) {
        if (systems.isEmpty()) return
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO static_systems (system_id, name, region_id) VALUES (?, ?, ?)"
            ).use { stmt ->
                systems.forEach { system ->
                    stmt.setInt(1, system.systemId)
                    stmt.setString(2, system.name)
                    stmt.setInt(3, system.regionId)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    fun getSystemRegionId(systemId: Int): Int? {
        return DatabaseManager.transaction {
            prepareStatement("SELECT region_id FROM static_systems WHERE system_id = ?").use { stmt ->
                stmt.setInt(1, systemId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1).takeIf { it != 0 } else null
                }
            }
        }
    }

    // ─── Settings ─────────────────────────────────────────────────────────

    fun getSetting(key: String): String? {
        return DatabaseManager.transaction {
            prepareStatement("SELECT value FROM settings WHERE key = ?").use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }
    }

    fun setSetting(key: String, value: String) {
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)"
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, value)
                stmt.executeUpdate()
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun java.sql.ResultSet.mapResultSetToType(): StaticTypeModel {
        return StaticTypeModel(
            typeId = getInt("type_id"),
            name = getString("name"),
            groupId = getInt("group_id"),
            categoryId = getInt("category_id"),
            volume = getDouble("volume"),
            packagedVolume = getDouble("packaged_volume"),
            portionSize = getInt("portion_size"),
            description = getString("description") ?: "",
            iconId = getInt("icon_id").takeIf { !wasNull() },
            published = getInt("published") == 1,
            marketGroupId = getInt("market_group_id").takeIf { !wasNull() },
        )
    }

    private fun java.sql.ResultSet.mapResultSetToTypes(): List<StaticTypeModel> {
        val list = mutableListOf<StaticTypeModel>()
        while (next()) list.add(mapResultSetToType())
        return list
    }

    private fun java.sql.ResultSet.mapResultSetToStation(): StaticStationModel {
        return StaticStationModel(
            stationId = getLong("station_id"),
            name = getString("name"),
            systemId = getInt("system_id"),
            systemName = getString("system_name") ?: "",
            regionId = getInt("region_id"),
            regionName = getString("region_name") ?: "",
            typeId = getInt("type_id"),
        )
    }

    private fun java.sql.ResultSet.mapResultSetToStations(): List<StaticStationModel> {
        val list = mutableListOf<StaticStationModel>()
        while (next()) list.add(mapResultSetToStation())
        return list
    }
}
