package org.eventt.core.staticdata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import org.eventt.core.database.StaticDataDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.http.EveHttpClient
import org.eventt.core.model.*
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

    fun isImportNeeded(): Boolean = StaticDataDao.countTypes() < 1000

    fun checkVersionChanged(): Boolean {
        val stored = StaticDataDao.getSetting("sde_build_number") ?: return false
        return try {
            val current = fetchLatestBuildNumber()
            stored != current.toString()
        } catch (e: Exception) {
            false
        }
    }

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
            println("[SDE] Import failed: ${e.message}")
            e.printStackTrace()
            _state.value = ImportState(isRunning = false, error = e.message ?: "Unknown error", status = "Import failed: ${e.message}")
        }
    }

    // ─── Private ────────────────────────────────────────────────────────

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
                        "types.jsonl"         -> parseJsonl(zip) { parseTypeLine(it) }
                        "groups.jsonl"        -> parseJsonl(zip) { parseGroupLine(it) }
                        "categories.jsonl"    -> parseJsonl(zip) { parseCategoryLine(it) }
                        "marketGroups.jsonl"  -> parseJsonl(zip) { parseMarketGroupLine(it) }
                        "mapRegions.jsonl"    -> parseJsonl(zip) { parseRegionLine(it) }
                        "mapSolarSystems.jsonl" -> parseJsonl(zip) { parseSystemLine(it) }
                        "npcStations.jsonl"   -> parseJsonl(zip) { parseStationLine(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw Exception("Empty ZIP response body")

        // Save all parsed data to DB
        saveAll()
    }

    /** Read lines from ONE ZipInputStream entry. Stops at entry boundary. */
    private fun parseJsonl(zip: ZipInputStream, onLine: (String) -> Unit) {
        val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line!!
            if (l.isBlank()) continue
            try { onLine(l) } catch (_: Exception) {}
        }
    }

    // ─── Line parsers ───────────────────────────────────────────────────

    private val _types = mutableListOf<StaticTypeModel>()
    private var _typeCount = 0

    private fun parseTypeLine(line: String) {
        val obj = Json.parseToJsonElement(line).jsonObject
        val typeId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return
        val name = obj["name"]?.jsonObject?.get("en")?.jsonPrimitive?.content ?: return
        _types.add(StaticTypeModel(
            typeId = typeId,
            name = name,
            groupId = obj["groupID"]?.jsonPrimitive?.intOrNull ?: 0,
            categoryId = 0,
            volume = obj["volume"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            packagedVolume = obj["packagedVolume"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            portionSize = obj["portionSize"]?.jsonPrimitive?.intOrNull ?: 1,
            published = obj["published"]?.jsonPrimitive?.booleanOrNull ?: false,
            marketGroupId = obj["marketGroupID"]?.jsonPrimitive?.intOrNull,
            iconId = obj["iconID"]?.jsonPrimitive?.intOrNull,
        ))
        _typeCount++
        if (_typeCount % 5000 == 0 && _typeCount > 0) {
            setState(0.10f + (_typeCount.toFloat() / 55000f) * 0.25f, "Parsing types: $_typeCount…")
        }
    }

    private val _groups = mutableListOf<StaticGroupModel>()
    private fun parseGroupLine(line: String) {
        val obj = Json.parseToJsonElement(line).jsonObject
        val groupId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return
        val name = obj["name"]?.jsonObject?.get("en")?.jsonPrimitive?.content ?: return
        val categoryId = obj["categoryID"]?.jsonPrimitive?.intOrNull ?: 0
        _groups.add(StaticGroupModel(groupId = groupId, name = name, categoryId = categoryId))
    }

    private val _categories = mutableListOf<StaticCategoryModel>()
    private fun parseCategoryLine(line: String) {
        val obj = Json.parseToJsonElement(line).jsonObject
        val catId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return
        val name = obj["name"]?.jsonObject?.get("en")?.jsonPrimitive?.content ?: return
        _categories.add(StaticCategoryModel(categoryId = catId, name = name))
    }

    private val _marketGroups = mutableListOf<StaticMarketGroupModel>()
    private fun parseMarketGroupLine(line: String) {
        val obj = Json.parseToJsonElement(line).jsonObject
        val mgId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return
        val name = obj["name"]?.jsonObject?.get("en")?.jsonPrimitive?.content ?: return
        val parentId = obj["parentGroupID"]?.jsonPrimitive?.intOrNull
        _marketGroups.add(StaticMarketGroupModel(marketGroupId = mgId, name = name, parentGroupId = parentId))
    }

    private val _regions = mutableListOf<StaticRegionModel>()
    private fun parseRegionLine(line: String) {
        val obj = Json.parseToJsonElement(line).jsonObject
        val regionId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return
        val name = obj["name"]?.jsonObject?.get("en")?.jsonPrimitive?.content ?: return
        _regions.add(StaticRegionModel(regionId = regionId, name = name))
    }

    private val _systems = mutableListOf<StaticSystemModel>()
    private fun parseSystemLine(line: String) {
        val obj = Json.parseToJsonElement(line).jsonObject
        val systemId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return
        val name = obj["name"]?.jsonObject?.get("en")?.jsonPrimitive?.content ?: return
        val regionId = obj["regionID"]?.jsonPrimitive?.intOrNull ?: 0
        _systems.add(StaticSystemModel(systemId = systemId, name = name, regionId = regionId))
    }

    // npcStations.jsonl has no name field — names are resolved from ESI after parsing
    private data class RawNpcStation(val stationId: Long, val solarSystemId: Int, val typeId: Int)
    private val _rawNpcStations = mutableListOf<RawNpcStation>()

    private fun parseStationLine(line: String) {
        val obj = Json.parseToJsonElement(line).jsonObject
        val stationId = obj["_key"]?.jsonPrimitive?.longOrNull ?: return
        val systemId  = obj["solarSystemID"]?.jsonPrimitive?.intOrNull ?: return
        val typeId    = obj["typeID"]?.jsonPrimitive?.intOrNull ?: 0
        _rawNpcStations.add(RawNpcStation(stationId, systemId, typeId))
    }

    // ─── Save to DB ─────────────────────────────────────────────────────

    private fun saveAll() {
        setState(0.35f, "Saving ${_types.size} types…")
        _types.chunked(5000).forEachIndexed { idx, chunk ->
            StaticDataDao.bulkInsertTypes(chunk)
            setState(0.35f + (idx.toFloat() / (_types.size / 5000 + 1)) * 0.10f,
                "Saved ${minOf((idx + 1) * 5000, _types.size)} / ${_types.size} types")
        }

        setState(0.46f, "Saving ${_groups.size} groups…")
        _groups.chunked(5000).forEach { chunk ->
            StaticDataDao.bulkInsertGroups(chunk)
        }

        setState(0.50f, "Saving ${_categories.size} categories…")
        _categories.chunked(5000).forEach { chunk ->
            StaticDataDao.bulkInsertCategories(chunk)
        }

        setState(0.53f, "Saving ${_marketGroups.size} market groups…")
        _marketGroups.chunked(5000).forEach { chunk ->
            StaticDataDao.bulkInsertMarketGroups(chunk)
        }

        setState(0.60f, "Saving ${_regions.size} regions…")
        _regions.chunked(2000).forEach { chunk ->
            StaticDataDao.bulkInsertRegions(chunk)
        }

        setState(0.65f, "Saving ${_systems.size} systems…")
        _systems.chunked(2000).forEach { chunk ->
            StaticDataDao.bulkInsertSystems(chunk)
        }

        // Build lookup maps from already-parsed data
        val systemNameById   = _systems.associate { it.systemId to it.name }
        val systemRegionById = _systems.associate { it.systemId to it.regionId }
        val regionNameById   = _regions.associate { it.regionId to it.name }

        // Resolve NPC station names via ESI /universe/names/ (batches of 1000)
        setState(0.70f, "Resolving ${_rawNpcStations.size} NPC station names from ESI…")
        val nameMap = EsiClient.resolveNames(_rawNpcStations.map { it.stationId.toInt() })

        val npcStations = _rawNpcStations.map { raw ->
            val regionId = systemRegionById[raw.solarSystemId] ?: 0
            StaticStationModel(
                stationId  = raw.stationId,
                name       = nameMap[raw.stationId.toInt()] ?: "Station ${raw.stationId}",
                systemId   = raw.solarSystemId,
                systemName = systemNameById[raw.solarSystemId] ?: "",
                regionId   = regionId,
                regionName = regionNameById[regionId] ?: "",
                typeId     = raw.typeId,
            )
        }
        setState(0.78f, "Saving ${npcStations.size} NPC stations…")
        npcStations.chunked(2000).forEach { chunk ->
            StaticDataDao.bulkInsertStations(chunk)
        }

        // Clear memory
        _types.clear()
        _groups.clear()
        _categories.clear()
        _marketGroups.clear()
        _regions.clear()
        _systems.clear()
        _rawNpcStations.clear()
    }

    // ─── State ──────────────────────────────────────────────────────────

    private fun setState(progress: Float, status: String) {
        _state.value = ImportState(isRunning = true, progress = progress, status = status)
    }
}
