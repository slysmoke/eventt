package org.eve.trader.core.staticdata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import org.eve.trader.core.database.StaticDataDao
import org.eve.trader.core.esi.EsiClient
import org.eve.trader.core.http.EveHttpClient
import org.eve.trader.core.model.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

object StaticDataImporter {

    private const val LATEST_URL = "https://developers.eveonline.com/static-data/tranquility/latest.jsonl"
    private const val ZIP_URL_TEMPLATE = "https://developers.eveonline.com/static-data/tranquility/eve-online-static-data-%d-jsonl.zip"

    data class ImportState(
        val isRunning: Boolean = false,
        val progress: Float = 0f,
        val status: String = "",
        val error: String? = null,
        val isDone: Boolean = false,
    )

    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    /** True when the local DB has fewer than 1000 types — first run or corrupted. */
    fun isImportNeeded(): Boolean = StaticDataDao.countTypes() < 1000

    /**
     * Returns true if the remote SDE build number differs from the stored one,
     * meaning a new EVE patch has dropped since the last import.
     */
    fun checkVersionChanged(): Boolean {
        val stored = StaticDataDao.getSetting("sde_build_number") ?: return false
        return try {
            val current = fetchLatestBuildNumber()
            stored != current.toString()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Full import from SDE JSONL ZIP.
     * Downloads the ZIP once and streams through it, parsing only the files we need.
     * Progress is published via [state]. Call from a coroutine on any dispatcher.
     */
    suspend fun importAll() = withContext(Dispatchers.IO) {
        if (_state.value.isRunning) return@withContext
        try {
            setState(0.01f, "Checking latest SDE version…")
            val buildNumber = fetchLatestBuildNumber()

            setState(0.02f, "Downloading SDE build $buildNumber…")
            val zipUrl = ZIP_URL_TEMPLATE.format(buildNumber)
            downloadAndParse(zipUrl)

            StaticDataDao.setSetting("sde_build_number", buildNumber.toString())
            StaticDataDao.setSetting("sde_import_date", System.currentTimeMillis().toString())

            val count = StaticDataDao.countTypes()
            _state.value = ImportState(isDone = true, progress = 1f, status = "Done — $count types loaded")

        } catch (e: CancellationException) {
            _state.value = ImportState(status = "Import cancelled")
            throw e
        } catch (e: Exception) {
            _state.value = ImportState(isRunning = false, error = e.message ?: "Unknown error", status = "Import failed")
        }
    }

    // ─── Private implementation ───────────────────────────────────────────

    private fun fetchLatestBuildNumber(): Int {
        val request = Request.Builder().url(LATEST_URL).build()
        val body = EveHttpClient.getClient().newCall(request).execute().use { r ->
            r.body?.string() ?: throw Exception("Empty response from latest.jsonl")
        }
        val obj = Json.parseToJsonElement(body).jsonObject
        return obj["buildNumber"]?.jsonPrimitive?.intOrNull
            ?: throw Exception("buildNumber not found in latest.jsonl response")
    }

    private fun downloadAndParse(zipUrl: String) {
        val request = Request.Builder().url(zipUrl).build()
        val response = EveHttpClient.getClient().newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Failed to download SDE ZIP: HTTP ${response.code}")
        }

        response.body?.byteStream()?.use { bodyStream ->
            ZipInputStream(bodyStream.buffered(1024 * 1024)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    when (name) {
                        "types.jsonl"         -> parseTypes(zip)
                        "groups.jsonl"        -> parseGroups(zip)
                        "categories.jsonl"    -> parseCategories(zip)
                        "marketGroups.jsonl"  -> parseMarketGroups(zip)
                        "mapRegions.jsonl"    -> parseRegions(zip)
                        "mapSolarSystems.jsonl" -> parseSystems(zip)
                        "npcStations.jsonl"   -> parseNpcStations(zip)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw Exception("Empty ZIP response body")
    }

    private fun parseTypes(zip: ZipInputStream) {
        setState(0.10f, "Parsing types…")
        val types = mutableListOf<StaticTypeModel>()
        val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
        var lineCount = 0
        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                val obj = Json.parseToJsonElement(line).jsonObject
                val typeId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return@forEachLine
                val name = obj["name"]?.jsonObject?.get("en")?.jsonPrimitive?.content ?: return@forEachLine
                val published = obj["published"]?.jsonPrimitive?.booleanOrNull ?: false
                val groupId = obj["groupID"]?.jsonPrimitive?.intOrNull ?: 0
                val marketGroupId = obj["marketGroupID"]?.jsonPrimitive?.intOrNull
                val volume = obj["volume"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val portionSize = obj["portionSize"]?.jsonPrimitive?.intOrNull ?: 1
                val iconId = obj["iconID"]?.jsonPrimitive?.intOrNull

                types.add(StaticTypeModel(
                    typeId = typeId,
                    name = name,
                    groupId = groupId,
                    categoryId = 0,
                    volume = volume,
                    portionSize = portionSize,
                    published = published,
                    marketGroupId = marketGroupId,
                    iconId = iconId,
                ))
                lineCount++
            } catch (_: Exception) {}

            if (lineCount % 5000 == 0 && lineCount > 0) {
                setState(0.10f + (lineCount.toFloat() / 55000f) * 0.25f, "Parsing types: $lineCount…")
            }
        }

        setState(0.35f, "Saving ${types.size} types…")
        types.chunked(5000).forEachIndexed { idx, chunk ->
            StaticDataDao.bulkInsertTypes(chunk)
            setState(0.35f + (idx.toFloat() / (types.size / 5000 + 1)) * 0.10f,
                "Saved ${minOf((idx + 1) * 5000, types.size)} / ${types.size} types")
        }
    }

    private fun parseGroups(zip: ZipInputStream) {
        setState(0.46f, "Parsing groups…")
        val groups = mutableListOf<StaticGroupModel>()
        val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                val obj = Json.parseToJsonElement(line).jsonObject
                val groupId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return@forEachLine
                val name = obj["name"]?.jsonObject?.get("en")?.jsonPrimitive?.content ?: return@forEachLine
                val categoryId = obj["categoryID"]?.jsonPrimitive?.intOrNull ?: 0
                groups.add(StaticGroupModel(groupId = groupId, name = name, categoryId = categoryId))
            } catch (_: Exception) {}
        }
        setState(0.48f, "Saving ${groups.size} groups…")
        StaticDataDao.bulkInsertGroups(groups)
    }

    private fun parseCategories(zip: ZipInputStream) {
        setState(0.50f, "Parsing categories…")
        val categories = mutableListOf<StaticCategoryModel>()
        val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                val obj = Json.parseToJsonElement(line).jsonObject
                val catId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return@forEachLine
                val name = obj["name"]?.jsonObject?.get("en")?.jsonPrimitive?.content ?: return@forEachLine
                categories.add(StaticCategoryModel(categoryId = catId, name = name))
            } catch (_: Exception) {}
        }
        setState(0.52f, "Saving ${categories.size} categories…")
        StaticDataDao.bulkInsertCategories(categories)
    }

    private fun parseMarketGroups(zip: ZipInputStream) {
        setState(0.53f, "Parsing market groups…")
        val groups = mutableListOf<org.eve.trader.core.model.StaticMarketGroupModel>()
        val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                val obj = Json.parseToJsonElement(line).jsonObject
                val mgId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return@forEachLine
                val name = obj["name"]?.jsonObject?.get("en")?.jsonPrimitive?.content ?: return@forEachLine
                val parentId = obj["parentGroupID"]?.jsonPrimitive?.intOrNull
                groups.add(
                    org.eve.trader.core.model.StaticMarketGroupModel(
                        marketGroupId = mgId,
                        name = name,
                        parentGroupId = parentId,
                    )
                )
            } catch (_: Exception) {}
        }
        setState(0.54f, "Saving ${groups.size} market groups…")
        groups.chunked(2000).forEach { chunk ->
            org.eve.trader.core.database.StaticDataDao.bulkInsertMarketGroups(chunk)
        }
    }

    private fun parseRegions(zip: ZipInputStream) {
        setState(0.54f, "Parsing regions…")
        val regions = mutableListOf<StaticRegionModel>()
        val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                val obj = Json.parseToJsonElement(line).jsonObject
                val regionId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return@forEachLine
                if (regionId >= 11_000_000) return@forEachLine // skip wormhole space
                val name = obj["name"]?.jsonObject?.get("en")?.jsonPrimitive?.content ?: return@forEachLine
                regions.add(StaticRegionModel(regionId = regionId, name = name))
            } catch (_: Exception) {}
        }
        setState(0.58f, "Saving ${regions.size} regions…")
        StaticDataDao.bulkInsertRegions(regions)
    }

    private fun parseSystems(zip: ZipInputStream) {
        setState(0.60f, "Parsing solar systems…")
        val systems = mutableListOf<StaticSystemModel>()
        val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                val obj = Json.parseToJsonElement(line).jsonObject
                val systemId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return@forEachLine
                val name = obj["name"]?.jsonObject?.get("en")?.jsonPrimitive?.content ?: return@forEachLine
                val regionId = obj["regionID"]?.jsonPrimitive?.intOrNull ?: return@forEachLine
                if (regionId >= 11_000_000) return@forEachLine // skip wormhole systems
                systems.add(StaticSystemModel(systemId = systemId, name = name, regionId = regionId))
            } catch (_: Exception) {}
        }
        setState(0.70f, "Saving ${systems.size} systems…")
        StaticDataDao.bulkInsertSystems(systems)
    }

    private fun parseNpcStations(zip: ZipInputStream) {
        setState(0.72f, "Parsing NPC stations…")
        // npcStations.jsonl has no name field; collect IDs+system+type then resolve names via ESI
        data class RawStation(val stationId: Long, val systemId: Int, val typeId: Int)
        val raw = mutableListOf<RawStation>()
        val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                val obj = Json.parseToJsonElement(line).jsonObject
                val stationId = obj["_key"]?.jsonPrimitive?.longOrNull ?: return@forEachLine
                val systemId = obj["solarSystemID"]?.jsonPrimitive?.intOrNull ?: return@forEachLine
                val typeId = obj["typeID"]?.jsonPrimitive?.intOrNull ?: 0
                raw.add(RawStation(stationId, systemId, typeId))
            } catch (_: Exception) {}
        }

        setState(0.75f, "Resolving ${raw.size} station names via ESI…")
        // Resolve names via ESI /universe/names/ in batches
        val stationIds = raw.map { it.stationId.toInt() }
        val names = EsiClient.resolveNames(stationIds)

        val stations = raw.mapNotNull { s ->
            val name = names[s.stationId.toInt()] ?: return@mapNotNull null
            val regionId = StaticDataDao.getSystemRegionId(s.systemId) ?: 0
            StaticStationModel(
                stationId = s.stationId,
                name = name,
                systemId = s.systemId,
                systemName = "",
                regionId = regionId,
                regionName = "",
                typeId = s.typeId,
            )
        }
        setState(0.90f, "Saving ${stations.size} NPC stations…")
        StaticDataDao.bulkInsertStations(stations)
    }

    // ─── On-demand helpers (used by feature screens) ─────────────────────

    /**
     * Fetches full type details from ESI and upserts into the DB.
     * Call this when a type is displayed but group_id / volume are still 0.
     */
    fun importType(typeId: Int) {
        try {
            val d = EsiClient.getUniverseType(typeId)
            StaticDataDao.insertType(StaticTypeModel(
                typeId = typeId,
                name = (d["name"] as? String) ?: "",
                groupId = (d["group_id"] as? Number)?.toInt() ?: 0,
                categoryId = 0,
                volume = (d["volume"] as? Number)?.toDouble() ?: 0.0,
                packagedVolume = (d["packaged_volume"] as? Number)?.toDouble() ?: 0.0,
                portionSize = (d["portion_size"] as? Number)?.toInt() ?: 1,
                description = (d["description"] as? String) ?: "",
                iconId = (d["icon_id"] as? Number)?.toInt(),
                published = (d["published"] as? Boolean) ?: false,
                marketGroupId = (d["market_group_id"] as? Number)?.toInt(),
            ))
        } catch (e: Exception) {
            println("importType($typeId) failed: ${e.message}")
        }
    }

    fun searchTypes(query: String, limit: Int = 50) = StaticDataDao.searchTypes(query, limit)
    fun searchMarketTypes(query: String, limit: Int = 50) = StaticDataDao.searchMarketTypes(query, limit)
    fun searchStation(name: String) = StaticDataDao.searchStations(name)

    fun importStation(stationId: Long): StaticStationModel? {
        return try {
            val data = EsiClient.getUniverseStation(stationId)
            val name = (data["name"] as? String) ?: ""
            val systemId = (data["system_id"] as? Number)?.toInt() ?: 0
            val typeId = (data["type_id"] as? Number)?.toInt() ?: 0
            val regionId = StaticDataDao.getSystemRegionId(systemId) ?: 0
            val station = StaticStationModel(
                stationId = stationId,
                name = name,
                systemId = systemId,
                systemName = "",
                regionId = regionId,
                regionName = "",
                typeId = typeId,
            )
            StaticDataDao.insertStation(station)
            station
        } catch (e: Exception) {
            null
        }
    }

    // ─── Internal helpers ─────────────────────────────────────────────────

    private fun setState(progress: Float, status: String) {
        _state.value = ImportState(isRunning = true, progress = progress, status = status)
    }
}
