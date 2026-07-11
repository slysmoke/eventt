package org.eventt.core.staticdata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import org.eventt.core.database.StaticDataDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.http.EveHttpClient
import org.eventt.core.model.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

object StaticDataImporter {
    private const val LATEST_URL = "https://developers.eveonline.com/static-data/tranquility/latest.jsonl"
    private const val ZIP_URL_TEMPLATE = "https://developers.eveonline.com/static-data/tranquility/eve-online-static-data-%d-jsonl.zip"

    // EVE's category IDs are stable, well-known constants — 6 has always been "Ship".
    private const val SHIP_CATEGORY_ID = 6

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

    suspend fun importAll() =
        withContext(Dispatchers.IO) {
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
        val body =
            EveHttpClient.getClient().newCall(request).execute().use { r ->
                r.body?.string() ?: throw Exception("Empty response from latest.jsonl")
            }
        val obj = Json.parseToJsonElement(body).jsonObject
        return obj["buildNumber"]?.jsonPrimitive?.intOrNull
            ?: throw Exception("buildNumber not found in latest.jsonl response")
    }

    private suspend fun downloadAndParse(zipUrl: String) {
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
                        "types.jsonl" ->
                            parseJsonl(zip) { line ->
                                parseTypeLine(line)?.let {
                                    types.add(it)
                                    typeCount++
                                    if (typeCount % 5000 == 0 && typeCount > 0) {
                                        setState(0.10f + (typeCount.toFloat() / 55000f) * 0.25f, "Parsing types: $typeCount…")
                                    }
                                }
                            }
                        "groups.jsonl" -> parseJsonl(zip) { line -> parseGroupLine(line)?.let { groups.add(it) } }
                        "categories.jsonl" -> parseJsonl(zip) { line -> parseCategoryLine(line)?.let { categories.add(it) } }
                        "marketGroups.jsonl" -> parseJsonl(zip) { line -> parseMarketGroupLine(line)?.let { marketGroups.add(it) } }
                        "mapRegions.jsonl" -> parseJsonl(zip) { line -> parseRegionLine(line)?.let { regions.add(it) } }
                        "mapSolarSystems.jsonl" -> parseJsonl(zip) { line -> parseSystemLine(line)?.let { systems.add(it) } }
                        "npcStations.jsonl" -> parseJsonl(zip) { line -> parseStationLine(line)?.let { rawNpcStations.add(it) } }
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
    private fun parseJsonl(
        zip: ZipInputStream,
        onLine: (String) -> Unit,
    ) {
        val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line!!
            if (l.isBlank()) continue
            try {
                onLine(l)
            } catch (_: Exception) {
            }
        }
    }

    // ─── Line parsers ───────────────────────────────────────────────────

    private val types = mutableListOf<StaticTypeModel>()
    private var typeCount = 0

    private fun JsonObject.getEnglishName(): String? =
        this["name"]
            ?.jsonObject
            ?.get("en")
            ?.jsonPrimitive
            ?.content

    internal fun parseTypeLine(line: String): StaticTypeModel? {
        val obj = Json.parseToJsonElement(line).jsonObject
        val typeId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return null
        val name = obj.getEnglishName() ?: return null
        return StaticTypeModel(
            typeId = typeId,
            name = name,
            groupId = obj["groupID"]?.jsonPrimitive?.intOrNull ?: 0,
            // types.jsonl has no categoryID field at all — types only carry a groupID, and the
            // group->category relationship is resolved once, after all of groups.jsonl is also
            // parsed, in saveAll() (this file alone doesn't have that data available).
            categoryId = 0,
            volume = obj["volume"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            // types.jsonl has no packaged-volume field either (verified against a real SDE
            // export) — CCP only computes/exposes it via ESI's per-type endpoint. Resolved
            // separately in saveAll(), and only for ships, since that's the one category where
            // packaged and unpacked volume meaningfully differ.
            packagedVolume = 0.0,
            portionSize = obj["portionSize"]?.jsonPrimitive?.intOrNull ?: 1,
            published = obj["published"]?.jsonPrimitive?.booleanOrNull ?: false,
            marketGroupId = obj["marketGroupID"]?.jsonPrimitive?.intOrNull,
            iconId = obj["iconID"]?.jsonPrimitive?.intOrNull,
        )
    }

    private val groups = mutableListOf<StaticGroupModel>()

    internal fun parseGroupLine(line: String): StaticGroupModel? {
        val obj = Json.parseToJsonElement(line).jsonObject
        val groupId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return null
        val name = obj.getEnglishName() ?: return null
        val categoryId = obj["categoryID"]?.jsonPrimitive?.intOrNull ?: 0
        return StaticGroupModel(groupId = groupId, name = name, categoryId = categoryId)
    }

    private val categories = mutableListOf<StaticCategoryModel>()

    internal fun parseCategoryLine(line: String): StaticCategoryModel? {
        val obj = Json.parseToJsonElement(line).jsonObject
        val catId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return null
        val name = obj.getEnglishName() ?: return null
        return StaticCategoryModel(categoryId = catId, name = name)
    }

    private val marketGroups = mutableListOf<StaticMarketGroupModel>()

    internal fun parseMarketGroupLine(line: String): StaticMarketGroupModel? {
        val obj = Json.parseToJsonElement(line).jsonObject
        val mgId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return null
        val name = obj.getEnglishName() ?: return null
        val parentId = obj["parentGroupID"]?.jsonPrimitive?.intOrNull
        return StaticMarketGroupModel(marketGroupId = mgId, name = name, parentGroupId = parentId)
    }

    private val regions = mutableListOf<StaticRegionModel>()

    internal fun parseRegionLine(line: String): StaticRegionModel? {
        val obj = Json.parseToJsonElement(line).jsonObject
        val regionId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return null
        val name = obj.getEnglishName() ?: return null
        return StaticRegionModel(regionId = regionId, name = name)
    }

    private val systems = mutableListOf<StaticSystemModel>()

    internal fun parseSystemLine(line: String): StaticSystemModel? {
        val obj = Json.parseToJsonElement(line).jsonObject
        val systemId = obj["_key"]?.jsonPrimitive?.intOrNull ?: return null
        val name = obj.getEnglishName() ?: return null
        val regionId = obj["regionID"]?.jsonPrimitive?.intOrNull ?: 0
        return StaticSystemModel(systemId = systemId, name = name, regionId = regionId)
    }

    // npcStations.jsonl has no name field — names are resolved from ESI after parsing
    internal data class RawNpcStation(
        val stationId: Long,
        val solarSystemId: Int,
        val typeId: Int,
    )

    private val rawNpcStations = mutableListOf<RawNpcStation>()

    internal fun parseStationLine(line: String): RawNpcStation? {
        val obj = Json.parseToJsonElement(line).jsonObject
        val stationId = obj["_key"]?.jsonPrimitive?.longOrNull ?: return null
        val systemId = obj["solarSystemID"]?.jsonPrimitive?.intOrNull ?: return null
        val typeId = obj["typeID"]?.jsonPrimitive?.intOrNull ?: 0
        return RawNpcStation(stationId, systemId, typeId)
    }

    // ─── Save to DB ─────────────────────────────────────────────────────

    private suspend fun saveAll() {
        // types.jsonl only carries a groupID per type, not a category — resolve it here now that
        // groups.jsonl (which does carry categoryID) has also been fully parsed.
        val groupCategoryMap = groups.associate { it.groupId to it.categoryId }
        val typesWithCategory = types.map { it.copy(categoryId = groupCategoryMap[it.groupId] ?: 0) }

        setState(0.35f, "Saving ${typesWithCategory.size} types…")
        typesWithCategory.chunked(5000).forEachIndexed { idx, chunk ->
            StaticDataDao.bulkInsertTypes(chunk)
            setState(
                0.35f + (idx.toFloat() / (typesWithCategory.size / 5000 + 1)) * 0.10f,
                "Saved ${minOf((idx + 1) * 5000, typesWithCategory.size)} / ${typesWithCategory.size} types",
            )
        }

        setState(0.46f, "Saving ${groups.size} groups…")
        groups.chunked(5000).forEach { chunk ->
            StaticDataDao.bulkInsertGroups(chunk)
        }

        setState(0.50f, "Saving ${categories.size} categories…")
        categories.chunked(5000).forEach { chunk ->
            StaticDataDao.bulkInsertCategories(chunk)
        }

        setState(0.53f, "Saving ${marketGroups.size} market groups…")
        marketGroups.chunked(5000).forEach { chunk ->
            StaticDataDao.bulkInsertMarketGroups(chunk)
        }

        setState(0.60f, "Saving ${regions.size} regions…")
        regions.chunked(2000).forEach { chunk ->
            StaticDataDao.bulkInsertRegions(chunk)
        }

        setState(0.65f, "Saving ${systems.size} systems…")
        systems.chunked(2000).forEach { chunk ->
            StaticDataDao.bulkInsertSystems(chunk)
        }

        // Build lookup maps from already-parsed data
        val systemNameById = systems.associate { it.systemId to it.name }
        val systemRegionById = systems.associate { it.systemId to it.regionId }
        val regionNameById = regions.associate { it.regionId to it.name }

        // Resolve NPC station names via ESI /universe/names/ (batches of 1000)
        setState(0.70f, "Resolving ${rawNpcStations.size} NPC station names from ESI…")
        val nameMap = EsiClient.resolveNames(rawNpcStations.map { it.stationId.toInt() })

        val npcStations =
            rawNpcStations.map { raw ->
                val regionId = systemRegionById[raw.solarSystemId] ?: 0
                StaticStationModel(
                    stationId = raw.stationId,
                    name = nameMap[raw.stationId.toInt()] ?: "Station ${raw.stationId}",
                    systemId = raw.solarSystemId,
                    systemName = systemNameById[raw.solarSystemId] ?: "",
                    regionId = regionId,
                    regionName = regionNameById[regionId] ?: "",
                    typeId = raw.typeId,
                )
            }
        setState(0.78f, "Saving ${npcStations.size} NPC stations…")
        npcStations.chunked(2000).forEach { chunk ->
            StaticDataDao.bulkInsertStations(chunk)
        }

        // Packaged volume (what actually matters for "how much cargo space does this take to
        // haul") isn't in the bulk SDE export at all for any type — only ESI's per-type endpoint
        // computes and exposes it. Ships are the one category where packaged and unpacked volume
        // meaningfully differ (a ship's own `volume` is its flying/assembled size, wildly bigger
        // than what it takes up crated in a hauler's hold), and a small enough subset (low
        // thousands) that fetching it individually here is reasonable for a one-time import step.
        val shipTypeIds = typesWithCategory.filter { it.categoryId == SHIP_CATEGORY_ID }.map { it.typeId }
        setState(0.85f, "Resolving ${shipTypeIds.size} ship packaged volumes from ESI…")
        val resolved = AtomicInteger(0)
        val semaphore = Semaphore(4)
        coroutineScope {
            shipTypeIds
                .map { typeId ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            runCatching {
                                val packagedVolume = (EsiClient.getUniverseType(typeId)["packaged_volume"] as? Number)?.toDouble()
                                if (packagedVolume != null && packagedVolume > 0) {
                                    StaticDataDao.updatePackagedVolume(typeId, packagedVolume)
                                }
                            }
                            val done = resolved.incrementAndGet()
                            if (done % 100 == 0) {
                                setState(
                                    0.85f + (done.toFloat() / shipTypeIds.size) * 0.08f,
                                    "Resolved $done/${shipTypeIds.size} ship volumes…",
                                )
                            }
                        }
                    }
                }.awaitAll()
        }

        // Clear memory
        types.clear()
        groups.clear()
        categories.clear()
        marketGroups.clear()
        regions.clear()
        systems.clear()
        rawNpcStations.clear()
    }

    // ─── State ──────────────────────────────────────────────────────────

    private fun setState(
        progress: Float,
        status: String,
    ) {
        _state.value = ImportState(isRunning = true, progress = progress, status = status)
    }
}
