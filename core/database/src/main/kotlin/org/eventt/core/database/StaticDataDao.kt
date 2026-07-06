package org.eventt.core.database

import org.eventt.core.model.StaticCategoryModel
import org.eventt.core.model.StaticGroupModel
import org.eventt.core.model.StaticMarketGroupModel
import org.eventt.core.model.StaticRegionModel
import org.eventt.core.model.StaticStationModel
import org.eventt.core.model.StaticSystemModel
import org.eventt.core.model.StaticTypeModel

object StaticDataDao {
    // ─── Types ────────────────────────────────────────────────────────────

    fun insertType(type: StaticTypeModel) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO static_types
                (type_id, name, group_id, category_id, volume, packaged_volume, portion_size, description, icon_id, published, market_group_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
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
                """.trimIndent(),
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

    fun getTypeById(typeId: Int): StaticTypeModel? =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM static_types WHERE type_id = ?").use { stmt ->
                stmt.setInt(1, typeId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.mapResultSetToType() else null
                }
            }
        }

    fun getTypeName(typeId: Int): String? =
        DatabaseManager.transaction {
            prepareStatement("SELECT name FROM static_types WHERE type_id = ?").use { stmt ->
                stmt.setInt(1, typeId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

    /** Search published types — includes non-market items (for assets, contracts). */
    fun searchTypes(
        query: String,
        limit: Int = 50,
    ): List<StaticTypeModel> =
        DatabaseManager.transaction {
            prepareStatement(
                """SELECT * FROM static_types WHERE published = 1 AND name LIKE ?
                   ORDER BY CASE WHEN lower(name) = lower(?) THEN 0
                                 WHEN lower(name) LIKE lower(?) || '%' THEN 1
                                 ELSE 2 END, name LIMIT ?""",
            ).use { stmt ->
                stmt.setString(1, "%$query%")
                stmt.setString(2, query)
                stmt.setString(3, query)
                stmt.setInt(4, limit)
                stmt.executeQuery().mapResultSetToTypes()
            }
        }

    /** Search only market-tradeable types (market_group_id IS NOT NULL). */
    fun searchMarketTypes(
        query: String,
        limit: Int = 50,
    ): List<StaticTypeModel> =
        DatabaseManager.transaction {
            prepareStatement(
                """SELECT * FROM static_types WHERE market_group_id IS NOT NULL AND name LIKE ?
                   ORDER BY CASE WHEN lower(name) = lower(?) THEN 0
                                 WHEN lower(name) LIKE lower(?) || '%' THEN 1
                                 ELSE 2 END, name LIMIT ?""",
            ).use { stmt ->
                stmt.setString(1, "%$query%")
                stmt.setString(2, query)
                stmt.setString(3, query)
                stmt.setInt(4, limit)
                stmt.executeQuery().mapResultSetToTypes()
            }
        }

    fun getTypesByGroup(groupId: Int): List<StaticTypeModel> =
        DatabaseManager.transaction {
            prepareStatement(
                "SELECT * FROM static_types WHERE group_id = ? AND published = 1 ORDER BY name",
            ).use { stmt ->
                stmt.setInt(1, groupId)
                stmt.executeQuery().mapResultSetToTypes()
            }
        }

    fun getCitadelCount(): Int =
        DatabaseManager.transaction {
            prepareStatement("SELECT COUNT(*) FROM static_stations WHERE station_id > 1000000000000").use { stmt ->
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }

    fun countTypes(): Int =
        DatabaseManager.transaction {
            prepareStatement("SELECT COUNT(*) FROM static_types").use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }

    // ─── Groups ───────────────────────────────────────────────────────────

    fun insertGroup(group: StaticGroupModel) {
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO static_groups (group_id, name, category_id) VALUES (?, ?, ?)",
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
                "INSERT OR REPLACE INTO static_groups (group_id, name, category_id) VALUES (?, ?, ?)",
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

    fun getGroupById(groupId: Int): StaticGroupModel? =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM static_groups WHERE group_id = ?").use { stmt ->
                stmt.setInt(1, groupId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        StaticGroupModel(
                            groupId = rs.getInt("group_id"),
                            name = rs.getString("name"),
                            categoryId = rs.getInt("category_id"),
                        )
                    } else {
                        null
                    }
                }
            }
        }

    fun getGroupNameForType(typeId: Int): String? =
        DatabaseManager.transaction {
            prepareStatement(
                "SELECT g.name FROM static_types t JOIN static_groups g ON t.group_id = g.group_id WHERE t.type_id = ?",
            ).use { stmt ->
                stmt.setInt(1, typeId)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    // ─── Market Groups ──────────────────────────────────────────────────

    fun insertMarketGroup(group: StaticMarketGroupModel) {
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO market_groups (market_group_id, name, parent_group_id) VALUES (?, ?, ?)",
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
                "INSERT OR REPLACE INTO market_groups (market_group_id, name, parent_group_id) VALUES (?, ?, ?)",
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
    fun getTopMarketGroups(): List<StaticMarketGroupModel> =
        DatabaseManager.transaction {
            prepareStatement(
                "SELECT * FROM market_groups WHERE parent_group_id IS NULL ORDER BY name",
            ).use { stmt ->
                stmt.executeQuery().mapResultSetToMarketGroups()
            }
        }

    /** Get child market groups of a given parent. */
    fun getChildMarketGroups(parentGroupId: Int): List<StaticMarketGroupModel> =
        DatabaseManager.transaction {
            prepareStatement(
                "SELECT * FROM market_groups WHERE parent_group_id = ? ORDER BY name",
            ).use { stmt ->
                stmt.setInt(1, parentGroupId)
                stmt.executeQuery().mapResultSetToMarketGroups()
            }
        }

    /** Get types that belong to a specific market group. */
    fun getTypesByMarketGroup(
        marketGroupId: Int,
        limit: Int = 200,
    ): List<StaticTypeModel> =
        DatabaseManager.transaction {
            prepareStatement(
                "SELECT * FROM static_types WHERE market_group_id = ? AND published = 1 ORDER BY name LIMIT ?",
            ).use { stmt ->
                stmt.setInt(1, marketGroupId)
                stmt.setInt(2, limit)
                stmt.executeQuery().mapResultSetToTypes()
            }
        }

    fun getTypeIdsByMarketGroups(groupIds: Set<Int>): List<Int> {
        if (groupIds.isEmpty()) return emptyList()
        val placeholders = groupIds.joinToString(",") { "?" }
        return DatabaseManager.transaction {
            prepareStatement(
                "SELECT type_id FROM static_types WHERE market_group_id IN ($placeholders) AND published = 1",
            ).use { stmt ->
                groupIds.forEachIndexed { i, id -> stmt.setInt(i + 1, id) }
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<Int>()
                    while (rs.next()) list.add(rs.getInt("type_id"))
                    list
                }
            }
        }
    }

    fun getAllMarketTypeIds(): List<Int> =
        DatabaseManager.transaction {
            prepareStatement(
                "SELECT type_id FROM static_types WHERE market_group_id IS NOT NULL AND published = 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<Int>()
                    while (rs.next()) list.add(rs.getInt("type_id"))
                    list
                }
            }
        }

    fun getMarketGroupById(marketGroupId: Int): StaticMarketGroupModel? =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM market_groups WHERE market_group_id = ?").use { stmt ->
                stmt.setInt(1, marketGroupId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        StaticMarketGroupModel(
                            marketGroupId = rs.getInt("market_group_id"),
                            name = rs.getString("name"),
                            parentGroupId = rs.getInt("parent_group_id").takeIf { !rs.wasNull() },
                        )
                    } else {
                        null
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
                ),
            )
        }
        return list
    }

    // ─── Categories ───────────────────────────────────────────────────────

    fun bulkInsertCategories(categories: List<StaticCategoryModel>) {
        if (categories.isEmpty()) return
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO static_categories (category_id, name) VALUES (?, ?)",
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
                """.trimIndent(),
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
                """.trimIndent(),
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

    fun getStationById(stationId: Long): StaticStationModel? =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM static_stations WHERE station_id = ?").use { stmt ->
                stmt.setLong(1, stationId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.mapResultSetToStation() else null
                }
            }
        }

    fun getStationsByRegion(regionId: Int): List<StaticStationModel> =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM static_stations WHERE region_id = ? ORDER BY name").use { stmt ->
                stmt.setInt(1, regionId)
                stmt.executeQuery().mapResultSetToStations()
            }
        }

    fun searchStations(
        query: String,
        limit: Int = 20,
    ): List<StaticStationModel> =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM static_stations WHERE name LIKE ? ORDER BY name LIMIT ?").use { stmt ->
                stmt.setString(1, "%$query%")
                stmt.setInt(2, limit)
                stmt.executeQuery().mapResultSetToStations()
            }
        }

    // ─── Regions ──────────────────────────────────────────────────────────

    fun insertRegion(region: StaticRegionModel) {
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO static_regions (region_id, name) VALUES (?, ?)",
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
                "INSERT OR REPLACE INTO static_regions (region_id, name) VALUES (?, ?)",
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

    fun getAllRegions(): List<StaticRegionModel> =
        DatabaseManager.transaction {
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

    // ─── Systems ──────────────────────────────────────────────────────────

    fun insertSystem(system: StaticSystemModel) {
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO static_systems (system_id, name, region_id) VALUES (?, ?, ?)",
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
                "INSERT OR REPLACE INTO static_systems (system_id, name, region_id) VALUES (?, ?, ?)",
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

    fun getSystemRegionId(systemId: Int): Int? =
        DatabaseManager.transaction {
            prepareStatement("SELECT region_id FROM static_systems WHERE system_id = ?").use { stmt ->
                stmt.setInt(1, systemId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1).takeIf { it != 0 } else null
                }
            }
        }

    fun getSystemById(systemId: Int): StaticSystemModel? =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM static_systems WHERE system_id = ?").use { stmt ->
                stmt.setInt(1, systemId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        StaticSystemModel(
                            systemId = rs.getInt("system_id"),
                            name = rs.getString("name"),
                            regionId = rs.getInt("region_id"),
                        )
                    } else {
                        null
                    }
                }
            }
        }

    fun getRegionById(regionId: Int): StaticRegionModel? =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM static_regions WHERE region_id = ?").use { stmt ->
                stmt.setInt(1, regionId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) StaticRegionModel(regionId = rs.getInt("region_id"), name = rs.getString("name")) else null
                }
            }
        }

    fun getSystemIdsByRegion(regionId: Int): List<Int> =
        DatabaseManager.transaction {
            prepareStatement("SELECT system_id FROM static_systems WHERE region_id = ?").use { stmt ->
                stmt.setInt(1, regionId)
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<Int>()
                    while (rs.next()) list.add(rs.getInt(1))
                    list
                }
            }
        }

    // ─── Jump graph (stargate connectivity) ────────────────────────────────

    fun isSystemJumpsFetched(systemId: Int): Boolean =
        DatabaseManager.transaction {
            prepareStatement("SELECT 1 FROM static_system_jumps_fetched WHERE system_id = ?").use { stmt ->
                stmt.setInt(1, systemId)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }

    fun markSystemJumpsFetched(systemId: Int) {
        DatabaseManager.transaction {
            prepareStatement("INSERT OR REPLACE INTO static_system_jumps_fetched (system_id) VALUES (?)").use { stmt ->
                stmt.setInt(1, systemId)
                stmt.executeUpdate()
            }
        }
    }

    /** Inserts an undirected edge (both directions) between two systems. */
    fun insertSystemJumpEdges(edges: List<Pair<Int, Int>>) {
        if (edges.isEmpty()) return
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO static_system_jumps (system_id, neighbor_system_id) VALUES (?, ?)",
            ).use { stmt ->
                edges.forEach { (a, b) ->
                    stmt.setInt(1, a)
                    stmt.setInt(2, b)
                    stmt.addBatch()
                    stmt.setInt(1, b)
                    stmt.setInt(2, a)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    /** Adjacency list restricted to the given set of systems (e.g. all systems in one region). */
    fun getJumpGraph(systemIds: Collection<Int>): Map<Int, List<Int>> {
        if (systemIds.isEmpty()) return emptyMap()
        return DatabaseManager.transaction {
            val placeholders = systemIds.joinToString(",") { "?" }
            prepareStatement(
                "SELECT system_id, neighbor_system_id FROM static_system_jumps WHERE system_id IN ($placeholders)",
            ).use { stmt ->
                systemIds.forEachIndexed { i, id -> stmt.setInt(i + 1, id) }
                stmt.executeQuery().use { rs ->
                    val graph = mutableMapOf<Int, MutableList<Int>>()
                    while (rs.next()) {
                        graph.getOrPut(rs.getInt("system_id")) { mutableListOf() }.add(rs.getInt("neighbor_system_id"))
                    }
                    graph
                }
            }
        }
    }

    // ─── Settings ─────────────────────────────────────────────────────────

    fun getSetting(key: String): String? =
        DatabaseManager.transaction {
            prepareStatement("SELECT value FROM settings WHERE key = ?").use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

    fun setSetting(
        key: String,
        value: String,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, value)
                stmt.executeUpdate()
            }
        }
    }

    // ─── Character tax settings ───────────────────────────────────────────

    fun getCharSalesTax(charId: Int): Double = getSetting("char.$charId.sales_tax")?.toDoubleOrNull() ?: 8.0

    fun getCharBrokersFee(charId: Int): Double = getSetting("char.$charId.brokers_fee")?.toDoubleOrNull() ?: 3.0

    fun setCharSalesTax(
        charId: Int,
        pct: Double,
    ) = setSetting("char.$charId.sales_tax", "%.4f".format(pct))

    fun setCharBrokersFee(
        charId: Int,
        pct: Double,
    ) = setSetting("char.$charId.brokers_fee", "%.4f".format(pct))

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun java.sql.ResultSet.mapResultSetToType(): StaticTypeModel =
        StaticTypeModel(
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

    private fun java.sql.ResultSet.mapResultSetToTypes(): List<StaticTypeModel> {
        val list = mutableListOf<StaticTypeModel>()
        while (next()) list.add(mapResultSetToType())
        return list
    }

    private fun java.sql.ResultSet.mapResultSetToStation(): StaticStationModel =
        StaticStationModel(
            stationId = getLong("station_id"),
            name = getString("name"),
            systemId = getInt("system_id"),
            systemName = getString("system_name") ?: "",
            regionId = getInt("region_id"),
            regionName = getString("region_name") ?: "",
            typeId = getInt("type_id"),
        )

    private fun java.sql.ResultSet.mapResultSetToStations(): List<StaticStationModel> {
        val list = mutableListOf<StaticStationModel>()
        while (next()) list.add(mapResultSetToStation())
        return list
    }
}
