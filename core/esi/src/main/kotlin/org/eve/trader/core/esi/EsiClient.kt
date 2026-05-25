package org.eve.trader.core.esi

import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.eve.trader.core.auth.SsoAuthManager
import org.eve.trader.core.cache.EsiCacheManager
import org.eve.trader.core.cache.CacheState
import org.eve.trader.core.http.EveHttpClient
import org.eve.trader.core.model.EsiResponseMetadata
import org.eve.trader.core.model.QueuedRequest
import org.eve.trader.core.model.RequestSource
import org.eve.trader.core.model.RequestStatus
import org.eve.trader.core.queue.RequestQueueManager
import okhttp3.Request
import java.io.IOException
import java.time.format.DateTimeFormatter

/**
 * Main ESI API client.
 * Handles caching, request queuing, auth token management, and response parsing.
 */
object EsiClient {

    private const val ESI_BASE_URL = "https://esi.evetech.net/latest"
    private const val ESI_DATASOURCE = "tranquility"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowSpecialFloatingPointValues = true
    }

    private fun JsonElement.toKotlinValue(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> longOrNull ?: doubleOrNull ?: booleanOrNull ?: content
        is JsonArray -> map { it.toKotlinValue() }
        is JsonObject -> mapValues { (_, v) -> v.toKotlinValue() }
    }

    private fun String.parseToMap(): Map<String, Any?> =
        json.parseToJsonElement(this).jsonObject.mapValues { (_, v) -> v.toKotlinValue() }

    private fun String.parseToListOfMaps(): List<Map<String, Any?>> =
        json.parseToJsonElement(this).jsonArray.map { it.jsonObject.mapValues { (_, v) -> v.toKotlinValue() } }

    private fun getMap(
        endpoint: String,
        params: Map<String, String> = emptyMap(),
        accessToken: String? = null,
    ): Map<String, Any?> {
        val (body, _) = getRaw(endpoint, params, accessToken)
        return body.parseToMap()
    }

    private fun getAllMaps(
        characterId: Int? = null,
        endpoint: String,
        params: Map<String, String> = emptyMap(),
    ): List<Map<String, Any?>> {
        // Check for a previously merged (all-pages) cache entry stored under the base params key.
        // Per-page caches use params+page; the merged entry uses params without page, so keys differ.
        val baseParams = params.toMutableMap().apply { put("datasource", ESI_DATASOURCE) }
        val mergedCache = EsiCacheManager.get(endpoint, baseParams)
        if (mergedCache.state == CacheState.FRESH && mergedCache.data != null) {
            return mergedCache.data!!.parseToListOfMaps()
        }

        val allResults  = mutableListOf<Map<String, Any?>>()
        val rawBodies   = mutableListOf<String>()
        var serverExpiry = 0L
        var page = 1

        while (true) {
            val (body, metadata) = if (characterId != null) {
                val token = SsoAuthManager.ensureTokenFresh(characterId) ?: break
                getRaw(endpoint, params, token, page)
            } else {
                getRaw(endpoint, params, null, page)
            }
            val list = body.parseToListOfMaps()
            allResults.addAll(list)
            rawBodies.add(body)

            // metadata.expires == 0L means the response was served from cache (EsiResponseMetadata() default).
            // In that case we can't rely on totalPages, so we keep going until we get an empty page.
            val fromCache = metadata.expires == 0L
            if (metadata.expires > serverExpiry) serverExpiry = metadata.expires
            if (list.isEmpty() || (!fromCache && metadata.totalPages <= page)) break
            page++
        }

        // Merge all page bodies into one JSON array and cache under the base params key.
        // This ensures subsequent calls (including after restart) serve the full result instantly.
        if (allResults.isNotEmpty()) {
            val merged = buildJsonArray {
                rawBodies.forEach { body ->
                    json.parseToJsonElement(body).jsonArray.forEach { add(it) }
                }
            }
            val expiry = if (serverExpiry > 0) serverExpiry else System.currentTimeMillis() + 5 * 60 * 1000L
            EsiCacheManager.save(endpoint, baseParams, merged.toString(), expiry)
        }

        return allResults
    }

    /**
     * Make a GET request to ESI with automatic cache handling.
     * Returns the raw JSON string.
     */
    fun getRaw(
        endpoint: String,
        params: Map<String, String> = emptyMap(),
        accessToken: String? = null,
        page: Int? = null,
    ): Pair<String, EsiResponseMetadata> {
        val fullParams = params.toMutableMap().apply {
            put("datasource", ESI_DATASOURCE)
            page?.let { put("page", it.toString()) }
        }

        // Check cache first
        val cacheResult = EsiCacheManager.get(endpoint, fullParams)

        if (cacheResult.state == CacheState.FRESH) {
            val cachedData = cacheResult.data
            if (cachedData != null) {
                return cachedData to EsiResponseMetadata()
            }
        }

        // Build URL
        val queryString = fullParams.entries.joinToString("&") { "${it.key}=${it.value}" }
        val url = "$ESI_BASE_URL$endpoint${if (queryString.isNotEmpty()) "?$queryString" else ""}"

        // Queue the request
        val queuedRequest = QueuedRequest(
            endpoint = url,
            description = endpoint,
            source = RequestSource.SERVER,
            status = RequestStatus.QUEUED,
        )
        RequestQueueManager.enqueue(queuedRequest)
        RequestQueueManager.markInProgress(queuedRequest.id)

        try {
            val requestBuilder = Request.Builder().url(url)
            accessToken?.let { requestBuilder.header("Authorization", "Bearer $it") }

            val client = EveHttpClient.getClient()
            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                RequestQueueManager.completeRequest(queuedRequest.id, error = "HTTP ${response.code}")
                throw IOException("ESI request failed: ${response.code} ${response.body?.string()}")
            }

            val body = response.body?.string() ?: ""
            RequestQueueManager.updateProgress(queuedRequest.id, 0.9f)

            if (body.isEmpty()) {
                RequestQueueManager.completeRequest(queuedRequest.id, error = "Empty response body")
                throw IOException("ESI returned empty response body")
            }

            // Parse cache headers
            val expiresHeader = response.header("Expires")
            val cacheControl = response.header("Cache-Control")
            val etag = response.header("ETag")
            val lastModified = response.header("Last-Modified")
            val xPages = response.header("X-Pages")?.toIntOrNull() ?: 1

            val expiresAt = when {
                expiresHeader != null -> EsiCacheManager.parseExpiresHeader(expiresHeader)
                cacheControl != null -> EsiCacheManager.parseCacheControl(cacheControl)
                    ?: (System.currentTimeMillis() + 5 * 60 * 1000L)
                else -> System.currentTimeMillis() + 5 * 60 * 1000L
            }

            // Save to cache
            EsiCacheManager.save(endpoint, fullParams, body, expiresAt)

            RequestQueueManager.completeRequest(queuedRequest.id)

            val metadata = EsiResponseMetadata(
                expires = expiresAt,
                cacheControl = cacheControl ?: "",
                etag = etag,
                lastModified = lastModified,
                totalPages = xPages,
            )

            return body to metadata

        } catch (e: IOException) {
            RequestQueueManager.completeRequest(queuedRequest.id, error = e.message)
            // If we have stale cache, return it as fallback
            if (cacheResult.state == CacheState.STALE) {
                val staleData = cacheResult.data
                if (staleData != null) {
                    return staleData to EsiResponseMetadata()
                }
            }
            throw e
        }
    }

    /**
     * Make a GET request and decode as a specific type.
     */
    inline fun <reified T> get(
        endpoint: String,
        params: Map<String, String> = emptyMap(),
        accessToken: String? = null,
        page: Int? = null,
    ): T {
        val (body, _) = getRaw(endpoint, params, accessToken, page)
        return json.decodeFromString<T>(body)
    }

    /**
     * Make a GET request with character auth token (auto-refresh if needed).
     */
    inline fun <reified T> getAuthenticated(
        characterId: Int,
        endpoint: String,
        params: Map<String, String> = emptyMap(),
        page: Int? = null,
    ): T {
        val accessToken = SsoAuthManager.ensureTokenFresh(characterId)
            ?: throw IOException("Could not refresh access token for character $characterId")
        return get(endpoint, params, accessToken, page)
    }

    /**
     * Get paginated results (auto-fetches all pages).
     */
    inline fun <reified T> getAllPages(
        characterId: Int? = null,
        endpoint: String,
        params: Map<String, String> = emptyMap(),
    ): List<T> {
        val allResults = mutableListOf<T>()
        var page = 1

        while (true) {
            val (body, metadata) = if (characterId != null) {
                val token = SsoAuthManager.ensureTokenFresh(characterId)
                    ?: break
                getRaw(endpoint, params, token, page)
            } else {
                getRaw(endpoint, params, null, page)
            }

            val list = json.decodeFromString<List<T>>(body)
            allResults.addAll(list)

            if (metadata.totalPages <= page || list.isEmpty()) break
            page++
        }

        return allResults
    }

    // ─── ESI Endpoint Helpers ─────────────────────────────────────────────

    // --- Public Endpoints ---

    // ─── Bulk / POST ──────────────────────────────────────────────────────

    /** POST to an ESI endpoint and return the raw response body. */
    fun postRaw(endpoint: String, jsonBody: String): String {
        val url = "$ESI_BASE_URL$endpoint?datasource=$ESI_DATASOURCE"
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = okhttp3.Request.Builder().url(url).post(requestBody).build()
        val response = EveHttpClient.getClient().newCall(request).execute()
        if (!response.isSuccessful) throw java.io.IOException("ESI POST $endpoint: HTTP ${response.code}")
        return response.body?.string() ?: "[]"
    }

    /**
     * Resolve up to 50 000 IDs → names via POST /universe/names/ (batches of 1000).
     * Returns map of id → name; IDs not found in ESI are omitted.
     */
    fun resolveNames(ids: List<Int>): Map<Int, String> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<Int, String>()
        ids.chunked(1000).forEach { chunk ->
            try {
                val body = "[${chunk.joinToString(",")}]"
                val response = postRaw("/universe/names/", body)
                json.parseToJsonElement(response).jsonArray.forEach { elem ->
                    val obj = elem.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    val name = obj["name"]?.jsonPrimitive?.content ?: return@forEach
                    result[id] = name
                }
            } catch (e: Exception) {
                println("resolveNames batch failed: ${e.message}")
            }
        }
        return result
    }

    // ─── Universe ─────────────────────────────────────────────────────────

    fun getUniverseType(typeId: Int): Map<String, Any?> = getMap("/universe/types/$typeId/")
    fun getUniverseGroup(groupId: Int): Map<String, Any?> = getMap("/universe/groups/$groupId/")
    fun getUniverseStation(stationId: Long): Map<String, Any?> = getMap("/universe/stations/$stationId/")
    fun getUniverseRegion(regionId: Int): Map<String, Any?> = getMap("/universe/regions/$regionId/")
    fun getUniverseSystem(systemId: Int): Map<String, Any?> = getMap("/universe/systems/$systemId/")
    fun getUniverseConstellation(constId: Int): Map<String, Any?> = getMap("/universe/constellations/$constId/")

    fun search(query: String, categories: List<String>, strict: Boolean = false): Map<String, List<Int>> {
        return get<Map<String, List<Int>>>("/search/", mapOf(
            "search" to query,
            "categories" to categories.joinToString(","),
            "strict" to strict.toString(),
        ))
    }

    // --- Market Group Endpoints ---

    fun getMarketGroupIds(): List<Int> = get("/markets/groups/")

    fun getMarketGroupTypeIds(groupId: Int): List<Int> {
        return try {
            val data = getMap("/markets/groups/$groupId/")
            (data["types"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    // --- Market Endpoints ---

    fun getMarketStructureOrders(structureId: Long): List<Map<String, Any?>> =
        getAllMaps(endpoint = "/markets/structures/$structureId/orders/", params = mapOf("order_type" to "all"))

    fun getMarketRegionOrders(regionId: Int, orderType: String = "all", typeId: Int? = null): List<Map<String, Any?>> {
        val params = mutableMapOf("order_type" to orderType)
        typeId?.let { params["type_id"] = it.toString() }
        return getAllMaps(endpoint = "/markets/$regionId/orders/", params = params)
    }

    fun getMarketRegionHistory(regionId: Int, typeId: Int): List<Map<String, Any?>> {
        val (body, _) = getRaw("/markets/$regionId/history/", mapOf("type_id" to typeId.toString()))
        return body.parseToListOfMaps()
    }

    // --- Character Endpoints ---

    fun getCharacterAssets(characterId: Int, accessToken: String): List<Map<String, Any?>> =
        getAllMaps(characterId = characterId, endpoint = "/characters/$characterId/assets/")

    fun getCharacterOrders(characterId: Int): List<Map<String, Any?>> =
        getAllMaps(characterId = characterId, endpoint = "/characters/$characterId/orders/")

    fun getCharacterOrdersHistory(characterId: Int): List<Map<String, Any?>> =
        getAllMaps(characterId = characterId, endpoint = "/characters/$characterId/orders/history/")

    fun getCharacterJournal(characterId: Int): List<Map<String, Any?>> =
        getAllMaps(characterId = characterId, endpoint = "/characters/$characterId/wallet/journal/")

    fun getCharacterTransactions(characterId: Int): List<Map<String, Any?>> =
        getAllMaps(characterId = characterId, endpoint = "/characters/$characterId/wallet/transactions/")

    fun getCharacterWallet(characterId: Int): Double {
        val token = SsoAuthManager.ensureTokenFresh(characterId) ?: return 0.0
        val (body, _) = getRaw("/characters/$characterId/wallet/", accessToken = token)
        return body.trim().toDoubleOrNull() ?: 0.0
    }

    // --- Corporation Endpoints ---

    fun getCorporationAssets(corporationId: Int): List<Map<String, Any?>> =
        getAllMaps(endpoint = "/corporations/$corporationId/assets/")

    fun getCorporationOrders(corporationId: Int): List<Map<String, Any?>> =
        getAllMaps(endpoint = "/corporations/$corporationId/orders/")

    fun getCorporationWallet(corporationId: Int, division: Int = 1): Double {
        val (body, _) = getRaw("/corporations/$corporationId/wallets/$division/balance/")
        return body.trim().toDoubleOrNull() ?: 0.0
    }

    fun getCorporationJournal(corporationId: Int, division: Int? = null): List<Map<String, Any?>> {
        val params = division?.let { mapOf("division" to it.toString()) } ?: emptyMap()
        return getAllMaps(endpoint = "/corporations/$corporationId/wallets/1/journal/", params = params)
    }

    fun getCorporationTransactions(corporationId: Int, division: Int? = null): List<Map<String, Any?>> {
        val params = division?.let { mapOf("division" to it.toString()) } ?: emptyMap()
        return getAllMaps(endpoint = "/corporations/$corporationId/wallets/1/transactions/", params = params)
    }

    fun getCorporationInfo(corporationId: Int): Map<String, Any?> = getMap("/corporations/$corporationId/")
    fun getCharacterInfo(characterId: Int): Map<String, Any?> = getMap("/characters/$characterId/")

    // --- Contract Endpoints ---

    fun getCharacterContracts(characterId: Int): List<Map<String, Any?>> =
        getAllMaps(characterId = characterId, endpoint = "/characters/$characterId/contracts/")

    fun getCorporationContracts(corporationId: Int): List<Map<String, Any?>> =
        getAllMaps(endpoint = "/corporations/$corporationId/contracts/")

    fun getContractItems(contractId: Int): List<Map<String, Any?>> =
        getAllMaps(endpoint = "/contracts/$contractId/items/")
}
