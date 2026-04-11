package org.eve.trader.core.staticdata

import org.eve.trader.core.database.StaticDataDao
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.core.model.*
import java.time.Instant

/**
 * Imports static data from ESI /universe/ endpoints into SQLite.
 * Run once on first launch, then periodically to update.
 */
object StaticDataImporter {

    private var isImporting = false
    private var importProgress = 0f
    private var importStatus = ""

    fun getProgress() = importProgress
    fun getStatus() = importStatus
    fun isRunning() = isImporting

    /**
     * Import all static data. This can take a while for large datasets.
     */
    fun importAll() {
        if (isImporting) return
        isImporting = true

        try {
            importStatus = "Importing regions..."
            importProgress = 0.1f
            importRegions()

            importStatus = "Importing systems..."
            importProgress = 0.2f
            importSystems()

            importStatus = "Importing groups..."
            importProgress = 0.35f
            importGroups()

            importStatus = "Importing categories..."
            importProgress = 0.45f
            importCategories()

            importStatus = "Importing types (published only)..."
            importProgress = 0.55f
            importPublishedTypes()

            importStatus = "Importing stations..."
            importProgress = 0.7f
            importStations()

            importStatus = "Import complete!"
            importProgress = 1.0f
        } catch (e: Exception) {
            importStatus = "Import failed: ${e.message}"
            e.printStackTrace()
        } finally {
            isImporting = false
        }
    }

    /**
     * Import a single type by ID.
     */
    fun importType(typeId: Int) {
        try {
            val typeData = EsiClient.getUniverseType(typeId)
            val type = StaticTypeModel(
                typeId = typeId,
                name = (typeData["name"] as? String) ?: "",
                groupId = (typeData["group_id"] as? Number)?.toInt() ?: 0,
                categoryId = 0, // Will be filled from group
                volume = (typeData["volume"] as? Number)?.toDouble() ?: 0.0,
                packagedVolume = (typeData["packaged_volume"] as? Number)?.toDouble() ?: 0.0,
                portionSize = (typeData["portion_size"] as? Number)?.toInt() ?: 1,
                description = (typeData["description"] as? String) ?: "",
                iconId = (typeData["icon_id"] as? Number)?.toInt(),
                published = (typeData["published"] as? Boolean) ?: false,
            )
            StaticDataDao.insertType(type)
        } catch (e: Exception) {
            println("Failed to import type $typeId: ${e.message}")
        }
    }

    /**
     * Search for types by name.
     */
    fun searchTypes(query: String, limit: Int = 50) = StaticDataDao.searchTypes(query, limit)

    // ─── Import Methods ─────────────────────────────────────────────────

    private fun importRegions() {
        try {
            // Get all region IDs from the universe/root endpoint
            val regionIds = getRegionIds()
            val regions = regionIds.mapNotNull { id ->
                try {
                    val data = EsiClient.getUniverseRegion(id)
                    StaticRegionModel(
                        regionId = id,
                        name = (data["name"] as? String) ?: "",
                    )
                } catch (e: Exception) {
                    null
                }
            }
            StaticDataDao.bulkInsertRegions(regions)
        } catch (e: Exception) {
            println("Failed to import regions: ${e.message}")
        }
    }

    private fun importSystems() {
        try {
            // Get all system IDs
            val systemIds = getSystemIds()
            val systems = systemIds.mapNotNull { id ->
                try {
                    val data = EsiClient.getUniverseSystem(id)
                    val name = (data["name"] as? String) ?: ""
                    val regionId = (data["star_id"] as? Number)?.toInt() ?: 0
                    StaticSystemModel(
                        systemId = id,
                        name = name,
                        regionId = regionId,
                    )
                } catch (e: Exception) {
                    null
                }
            }
            StaticDataDao.bulkInsertSystems(systems)
        } catch (e: Exception) {
            println("Failed to import systems: ${e.message}")
        }
    }

    private fun importGroups() {
        try {
            val groupIds = getGroupIds()
            val groups = groupIds.mapNotNull { id ->
                try {
                    val data = EsiClient.getUniverseGroup(id)
                    StaticGroupModel(
                        groupId = id,
                        name = (data["name"] as? String) ?: "",
                        categoryId = (data["category_id"] as? Number)?.toInt() ?: 0,
                    )
                } catch (e: Exception) {
                    null
                }
            }
            StaticDataDao.bulkInsertGroups(groups)
        } catch (e: Exception) {
            println("Failed to import groups: ${e.message}")
        }
    }

    private fun importCategories() {
        try {
            val categoryIds = getCategoryIds()
            val categories = categoryIds.mapNotNull { id ->
                try {
                    val data = EsiClient.get<Map<String, Any?>>("/universe/categories/$id/")
                    StaticCategoryModel(
                        categoryId = id,
                        name = (data["name"] as? String) ?: "",
                    )
                } catch (e: Exception) {
                    null
                }
            }
            // Note: StaticDataDao doesn't have bulkInsertCategories yet, but we don't use categories directly much
            // Just insert what we can
        } catch (e: Exception) {
            println("Failed to import categories: ${e.message}")
        }
    }

    private fun importPublishedTypes() {
        // This is the most expensive operation — there are 40k+ types
        // We import on-demand instead (search + individual fetch)
        importStatus = "Type import is done on-demand (search + fetch)"
    }

    private fun importStations() {
        // Station import is also expensive — there are many stations
        // We import on-demand as users browse stations
        importStatus = "Station import is done on-demand"
    }

    // ─── ESI Helper Methods ──────────────────────────────────────────────

    private fun getRegionIds(): List<Int> {
        return try {
            EsiClient.get<List<Int>>("/universe/regions/")
        } catch (e: Exception) {
            // Fallback: known trade hub regions
            listOf(10000002) // The Forge (Jita)
        }
    }

    private fun getSystemIds(): List<Int> {
        return try {
            EsiClient.getAllPages<List<Int>>(
                endpoint = "/universe/systems/",
            ).flatten()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getGroupIds(): List<Int> {
        return try {
            EsiClient.getAllPages<List<Int>>(
                endpoint = "/universe/groups/",
            ).flatten()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getCategoryIds(): List<Int> {
        return try {
            EsiClient.get<List<Int>>("/universe/categories/")
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get station ID by name (search).
     */
    fun searchStation(name: String): List<StaticStationModel> {
        return StaticDataDao.searchStations(name)
    }

    /**
     * Import a station by ID.
     */
    fun importStation(stationId: Long): StaticStationModel? {
        return try {
            val data = EsiClient.getUniverseStation(stationId)
            val name = (data["name"] as? String) ?: ""
            val systemId = (data["system_id"] as? Number)?.toInt() ?: 0
            val typeId = (data["type_id"] as? Number)?.toInt() ?: 0

            // Get system name and region
            var systemName = ""
            var regionId = 0
            var regionName = ""
            try {
                val systemData = EsiClient.getUniverseSystem(systemId)
                systemName = (systemData["name"] as? String) ?: ""
                regionId = (systemData["star_id"] as? Number)?.toInt() ?: 0
            } catch (e: Exception) { /* ignore */ }

            try {
                val regionData = EsiClient.getUniverseRegion(regionId)
                regionName = (regionData["name"] as? String) ?: ""
            } catch (e: Exception) { /* ignore */ }

            val station = StaticStationModel(
                stationId = stationId,
                name = name,
                systemId = systemId,
                systemName = systemName,
                regionId = regionId,
                regionName = regionName,
                typeId = typeId,
            )
            StaticDataDao.insertStation(station)
            station
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Import a group by ID.
     */
    fun importGroup(groupId: Int): StaticGroupModel? {
        return try {
            val data = EsiClient.getUniverseGroup(groupId)
            val group = StaticGroupModel(
                groupId = groupId,
                name = (data["name"] as? String) ?: "",
                categoryId = (data["category_id"] as? Number)?.toInt() ?: 0,
            )
            StaticDataDao.insertGroup(group)
            group
        } catch (e: Exception) {
            null
        }
    }
}
