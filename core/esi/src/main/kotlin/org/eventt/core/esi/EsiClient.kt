package org.eventt.core.esi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eventt.core.auth.SsoAuthManager
import org.eventt.core.cache.CacheState
import org.eventt.core.cache.EsiCacheManager
import org.eventt.core.http.EveHttpClient
import org.eventt.core.model.AppLog
import org.eventt.core.model.EsiResponseMetadata
import org.eventt.core.model.QueuedRequest
import org.eventt.core.model.RequestSource
import org.eventt.core.model.RequestStatus
import org.eventt.core.queue.RequestQueueManager
import java.io.IOException

/**
 * Main ESI API client.
 * Handles caching, request queuing, auth token management, and response parsing.
 */
object EsiClient {
    // internal var (not private const) so tests can point it at a local MockWebServer instead of
    // the real ESI. Never reassigned in production.
    internal var esiBaseUrl = "https://esi.evetech.net/latest"
    private const val ESI_DATASOURCE = "tranquility"

    val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            allowSpecialFloatingPointValues = true
        }

    private fun JsonElement.toKotlinValue(): Any? =
        when (this) {
            is JsonNull -> null

            // A genuine JSON string must stay a String even when its content looks numeric —
            // e.g. a market order's `range` field is "1"/"5"/"40" (a jump count) as opposed to the
            // keywords "station"/"solarsystem"/"region". longOrNull/doubleOrNull ignore whether the
            // literal was quoted, so without the isString guard "1" silently became the Long 1.
            is JsonPrimitive -> if (isString) content else (longOrNull ?: doubleOrNull ?: booleanOrNull ?: content)

            is JsonArray -> map { it.toKotlinValue() }

            is JsonObject -> mapValues { (_, v) -> v.toKotlinValue() }
        }

    private fun String.parseToMap(): Map<String, Any?> = json.parseToJsonElement(this).jsonObject.mapValues { (_, v) -> v.toKotlinValue() }

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

        val allResults = mutableListOf<Map<String, Any?>>()
        val rawBodies = mutableListOf<String>()
        var serverExpiry = 0L
        var firstPageLastModified: String? = null
        var page = 1

        while (true) {
            val (body, metadata) =
                if (characterId != null) {
                    val token = SsoAuthManager.ensureTokenFresh(characterId) ?: break
                    getRaw(endpoint, params, token, page, characterId)
                } else {
                    getRaw(endpoint, params, null, page)
                }
            val list = body.parseToListOfMaps()
            allResults.addAll(list)
            rawBodies.add(body)

            // metadata.expires == 0L means the response was served from cache (EsiResponseMetadata() default).
            // In that case we can't rely on totalPages, so we keep going until we get an empty page.
            val fromCache = metadata.expires == 0L

            // ESI best practice: a paginated resource's Last-Modified should be identical across
            // all pages of one fetch. A mismatch means the data changed mid-fetch — the merged
            // result below may mix an old and new snapshot. We don't retry the whole fetch (a
            // rare case, and pages are already individually cached/valid), just surface it.
            if (!fromCache && metadata.lastModified != null) {
                if (firstPageLastModified == null) {
                    firstPageLastModified = metadata.lastModified
                } else if (metadata.lastModified != firstPageLastModified) {
                    println(
                        "[EsiClient] $endpoint: Last-Modified changed mid-pagination (page $page) — " +
                            "results may mix data from different snapshots",
                    )
                }
            }
            // A cache hit doesn't carry its real expiry in metadata, so look it up directly —
            // otherwise serverExpiry stays 0 for a page served entirely from cache, and an empty
            // result (e.g. zero open orders) never gets a merged cache entry / refresh timer.
            val pageExpiry =
                if (fromCache) {
                    val pageParams =
                        params.toMutableMap().apply {
                            put("datasource", ESI_DATASOURCE)
                            put("page", page.toString())
                        }
                    EsiCacheManager.getExpiry(endpoint, pageParams) ?: 0L
                } else {
                    metadata.expires
                }
            if (pageExpiry > serverExpiry) serverExpiry = pageExpiry
            if (list.isEmpty() || (!fromCache && metadata.totalPages <= page)) break
            page++
        }

        // Merge all page bodies into one JSON array and cache under the base params key.
        // This ensures subsequent calls (including after restart) serve the full result instantly.
        // Cache even an empty result as long as we got a real server response (serverExpiry > 0),
        // otherwise endpoints that legitimately return zero items (e.g. no open orders) never get
        // an expiry recorded and refresh-cooldown timers never show.
        if (allResults.isNotEmpty() || serverExpiry > 0) {
            val merged =
                buildJsonArray {
                    rawBodies.forEach { body ->
                        json.parseToJsonElement(body).jsonArray.forEach { add(it) }
                    }
                }
            val expiry = if (serverExpiry > 0) serverExpiry else System.currentTimeMillis() + 5 * 60 * 1000L
            // Keep the server's Last-Modified on the merged entry too, so consumers can tell how
            // old the data behind this endpoint actually is (see getEndpointLastModifiedMillis).
            EsiCacheManager.save(endpoint, baseParams, merged.toString(), expiry, lastModified = firstPageLastModified)
        }

        return allResults
    }

    /**
     * Make a GET request to ESI with automatic cache handling (in-memory L1 → SQLite L2 → network).
     * Sends conditional If-None-Match / If-Modified-Since when stale cached data has an ETag.
     * Returns the raw JSON string.
     */
    fun getRaw(
        endpoint: String,
        params: Map<String, String> = emptyMap(),
        accessToken: String? = null,
        page: Int? = null,
        characterId: Int? = null,
    ): Pair<String, EsiResponseMetadata> {
        val fullParams =
            params.toMutableMap().apply {
                put("datasource", ESI_DATASOURCE)
                page?.let { put("page", it.toString()) }
            }

        val cacheResult = EsiCacheManager.get(endpoint, fullParams)

        if (cacheResult.state == CacheState.FRESH && cacheResult.data != null) {
            return cacheResult.data!! to EsiResponseMetadata()
        }

        // ESI itself reports this route degraded — ride out a stale snapshot rather than spend a
        // request that's likely to fail, or fail fast rather than hang the caller on a doomed call.
        if (!EsiStatusService.isHealthy("GET", endpoint)) {
            if (cacheResult.state == CacheState.STALE && cacheResult.data != null) {
                return cacheResult.data!! to EsiResponseMetadata()
            }
            throw EsiDegradedException(endpoint)
        }

        val queryString = fullParams.entries.joinToString("&") { "${it.key}=${it.value}" }
        val url = "$esiBaseUrl$endpoint${if (queryString.isNotEmpty()) "?$queryString" else ""}"

        val queuedRequest =
            QueuedRequest(
                endpoint = url,
                description = endpoint,
                source = RequestSource.SERVER,
                status = RequestStatus.QUEUED,
            )
        RequestQueueManager.enqueue(queuedRequest)
        RequestQueueManager.markInProgress(queuedRequest.id)

        try {
            val client = EveHttpClient.getClient()
            var currentToken = accessToken

            fun buildRequest(includeConditional: Boolean): Request {
                val builder = Request.Builder().url(url)
                currentToken?.let { builder.header("Authorization", "Bearer $it") }
                if (includeConditional && cacheResult.state == CacheState.STALE && cacheResult.data != null) {
                    cacheResult.etag?.let { builder.header("If-None-Match", it) }
                    cacheResult.lastModified?.let { builder.header("If-Modified-Since", it) }
                }
                return builder.build()
            }

            var response = client.newCall(buildRequest(includeConditional = true)).execute()

            // 401 Unauthorized — refresh token and retry once
            if (response.code == 401 && characterId != null) {
                response.close()
                currentToken = SsoAuthManager.ensureTokenFresh(characterId)
                if (currentToken != null) {
                    response = client.newCall(buildRequest(includeConditional = false)).execute()
                }
            }

            // 304 Not Modified — ESI confirmed our cached copy is still current.
            if (response.code == 304) {
                val cachedBody =
                    cacheResult.data
                        ?: throw IOException("Got 304 but no cached body for $endpoint")
                val newExpiry = parseExpiry(response)
                val newEtag = response.header("ETag") ?: cacheResult.etag
                val newLastModified = response.header("Last-Modified") ?: cacheResult.lastModified
                EsiCacheManager.refreshExpiry(endpoint, fullParams, newExpiry, newEtag, newLastModified)
                RequestQueueManager.completeRequest(queuedRequest.id)
                return cachedBody to
                    EsiResponseMetadata(
                        expires = newExpiry,
                        etag = newEtag,
                        lastModified = newLastModified,
                        totalPages = response.header("X-Pages")?.toIntOrNull() ?: 1,
                    )
            }

            if (!response.isSuccessful) {
                RequestQueueManager.completeRequest(queuedRequest.id, error = "HTTP ${response.code}")
                throw IOException("ESI request failed: ${response.code} ${response.body.string()}")
            }

            val body = response.body.string()
            RequestQueueManager.updateProgress(queuedRequest.id, 0.9f)

            if (body.isEmpty()) {
                RequestQueueManager.completeRequest(queuedRequest.id, error = "Empty response body")
                throw IOException("ESI returned empty response body for $endpoint")
            }

            val etag = response.header("ETag")
            val lastModified = response.header("Last-Modified")
            val cacheControl = response.header("Cache-Control")
            val xPages = response.header("X-Pages")?.toIntOrNull() ?: 1
            val expiresAt = parseExpiry(response)

            EsiCacheManager.save(endpoint, fullParams, body, expiresAt, etag, lastModified)

            RequestQueueManager.completeRequest(queuedRequest.id)

            return body to
                EsiResponseMetadata(
                    expires = expiresAt,
                    cacheControl = cacheControl ?: "",
                    etag = etag,
                    lastModified = lastModified,
                    totalPages = xPages,
                )
        } catch (e: IOException) {
            RequestQueueManager.completeRequest(queuedRequest.id, error = e.message)
            AppLog.warn("ESI", "$endpoint: ${e.message}")
            if (cacheResult.state == CacheState.STALE && cacheResult.data != null) {
                return cacheResult.data!! to EsiResponseMetadata()
            }
            throw e
        }
    }

    private fun parseExpiry(response: okhttp3.Response): Long {
        val expiresHeader = response.header("Expires")
        val cacheControl = response.header("Cache-Control")
        return when {
            expiresHeader != null -> {
                EsiCacheManager.parseExpiresHeader(expiresHeader)
            }

            cacheControl != null -> {
                EsiCacheManager.parseCacheControl(cacheControl)
                    ?: (System.currentTimeMillis() + 5 * 60 * 1000L)
            }

            else -> {
                System.currentTimeMillis() + 5 * 60 * 1000L
            }
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
        val accessToken =
            SsoAuthManager.ensureTokenFresh(characterId)
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
            val (body, metadata) =
                if (characterId != null) {
                    val token =
                        SsoAuthManager.ensureTokenFresh(characterId)
                            ?: break
                    getRaw(endpoint, params, token, page, characterId)
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
    fun postRaw(
        endpoint: String,
        jsonBody: String,
    ): String {
        val url = "$esiBaseUrl$endpoint?datasource=$ESI_DATASOURCE"
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request =
            okhttp3.Request
                .Builder()
                .url(url)
                .post(requestBody)
                .build()
        val response = EveHttpClient.getClient().newCall(request).execute()
        if (!response.isSuccessful) throw java.io.IOException("ESI POST $endpoint: HTTP ${response.code}")
        return response.body.string()
    }

    @Serializable
    data class FittingItemDto(
        @SerialName("type_id") val typeId: Int,
        val quantity: Int,
        val flag: String,
    )

    @Serializable
    data class FittingRequestDto(
        val name: String,
        val description: String,
        @SerialName("ship_type_id") val shipTypeId: Int,
        val items: List<FittingItemDto>,
    )

    @Serializable
    private data class FittingResponseDto(
        @SerialName("fitting_id") val fittingId: Int,
    )

    /**
     * POST /characters/{id}/fittings/ — saves a ship fitting (used by the Cargo Splitter tool to
     * push a computed split as an in-game-visible fitting). Mirrors getRaw()'s auth + 401-retry-once
     * + RequestQueueManager wrapping, but for a write; unlike postRaw() (unauthenticated, no queue),
     * this is the auth'd POST path, and unlike getRaw() writes are never served from/saved to cache.
     * Returns the new fitting_id on success; throws IOException on failure.
     */
    fun postCharacterFitting(
        characterId: Int,
        fitting: FittingRequestDto,
    ): Int {
        val endpoint = "/characters/$characterId/fittings/"
        val url = "$esiBaseUrl$endpoint?datasource=$ESI_DATASOURCE"
        val bodyJson = json.encodeToString(FittingRequestDto.serializer(), fitting)

        val queuedRequest =
            QueuedRequest(
                endpoint = url,
                description = "POST $endpoint (${fitting.name})",
                source = RequestSource.SERVER,
                status = RequestStatus.QUEUED,
            )
        RequestQueueManager.enqueue(queuedRequest)
        RequestQueueManager.markInProgress(queuedRequest.id)

        try {
            var token =
                SsoAuthManager.ensureTokenFresh(characterId)
                    ?: throw IOException("Could not refresh access token for character $characterId")

            fun buildRequest(bearer: String): Request =
                Request
                    .Builder()
                    .url(url)
                    .header("Authorization", "Bearer $bearer")
                    .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

            val client = EveHttpClient.getClient()
            var response = client.newCall(buildRequest(token)).execute()

            // 401 Unauthorized — refresh token and retry once
            if (response.code == 401) {
                response.close()
                token =
                    SsoAuthManager.ensureTokenFresh(characterId)
                        ?: throw IOException("Token refresh failed after 401 for character $characterId")
                response = client.newCall(buildRequest(token)).execute()
            }

            if (!response.isSuccessful) {
                val err = "HTTP ${response.code} ${response.body.string()}"
                RequestQueueManager.completeRequest(queuedRequest.id, error = err)
                throw IOException("ESI POST $endpoint failed: $err")
            }

            val respBody = response.body.string()
            RequestQueueManager.completeRequest(queuedRequest.id)
            return json.decodeFromString(FittingResponseDto.serializer(), respBody).fittingId
        } catch (e: IOException) {
            RequestQueueManager.completeRequest(queuedRequest.id, error = e.message)
            AppLog.warn("ESI", "$endpoint: ${e.message}")
            throw e
        }
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

    // Public endpoint — stargate topology never changes, so ESI's own cache TTL is long and we
    // additionally persist every edge locally (see core/staticdata/JumpGraphService.kt).
    fun getUniverseStargate(stargateId: Int): Map<String, Any?> = getMap("/universe/stargates/$stargateId/")

    // Player-owned structures (citadels) aren't public — this requires the docking character's
    // token and returns null if they have no docking access (e.g. structure since abandoned/moved).
    fun getStructureInfo(
        structureId: Long,
        characterId: Int,
    ): Map<String, Any?>? {
        return try {
            val token = SsoAuthManager.ensureTokenFresh(characterId)
            if (token == null) {
                println("[ESI] getStructureInfo($structureId): no valid token for character $characterId")
                return null
            }
            val (body, _) = getRaw("/universe/structures/$structureId/", accessToken = token, characterId = characterId)
            body.parseToMap()
        } catch (e: Exception) {
            println("[ESI] getStructureInfo($structureId) failed: ${e.message}")
            null
        }
    }

    // Used by the Sell Pricing tool to derive "which region is this character's market in"
    // without asking the user to pick one — {"solar_system_id": ..., "station_id": ..., ...}.
    fun getCharacterLocation(characterId: Int): Map<String, Any?>? {
        return try {
            val token = SsoAuthManager.ensureTokenFresh(characterId)
            if (token == null) {
                println("[ESI] getCharacterLocation($characterId): no valid token")
                return null
            }
            val (body, _) = getRaw("/characters/$characterId/location/", accessToken = token, characterId = characterId)
            body.parseToMap()
        } catch (e: Exception) {
            println("[ESI] getCharacterLocation($characterId) failed: ${e.message}")
            null
        }
    }

    /**
     * POST /universe/ids/ — unauthenticated exact-name-to-ID resolution, still live on ESI. This
     * replaces the old GET /search/ endpoint this app used to call for the same purpose; CCP has
     * since removed unauthenticated /search/ entirely (it 404s now), and the authenticated
     * replacement (/characters/{id}/search/) needs a logged-in character to search *as*, which is
     * more than a "show me this trader in-game" button should require. Null if [characterName]
     * doesn't resolve to exactly one character.
     */
    fun resolveCharacterId(characterName: String): Int? =
        try {
            val body = postRaw("/universe/ids/", json.encodeToString(ListSerializer(String.serializer()), listOf(characterName)))
            json
                .parseToJsonElement(body)
                .jsonObject["characters"]
                ?.jsonArray
                ?.singleOrNull()
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.int
        } catch (e: Exception) {
            null
        }

    // --- Market Group Endpoints ---

    fun getMarketGroupIds(): List<Int> = get("/markets/groups/")

    fun getMarketGroupTypeIds(groupId: Int): List<Int> =
        try {
            val data = getMap("/markets/groups/$groupId/")
            (data["types"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    // --- Market Endpoints ---

    /** Returns a map of typeId → adjustedPrice from /markets/prices/ (cached by ESI for ~1 day). */
    fun getMarketPrices(): Map<Int, Double> {
        return try {
            val (body, _) = getRaw("/markets/prices/")
            json
                .parseToJsonElement(body)
                .jsonArray
                .associate { elem ->
                    val obj = elem.jsonObject
                    val typeId = obj["type_id"]?.jsonPrimitive?.intOrNull ?: return@associate -1 to 0.0
                    val price =
                        obj["adjusted_price"]?.jsonPrimitive?.doubleOrNull
                            ?: obj["average_price"]?.jsonPrimitive?.doubleOrNull
                            ?: 0.0
                    typeId to price
                }.filterKeys { it >= 0 }
        } catch (e: Exception) {
            println("[EsiClient] getMarketPrices failed: ${e.message}")
            emptyMap()
        }
    }

    fun getMarketStructureOrders(structureId: Long): List<Map<String, Any?>> =
        getAllMaps(endpoint = "/markets/structures/$structureId/orders/", params = mapOf("order_type" to "all"))

    fun getMarketRegionOrders(
        regionId: Int,
        orderType: String = "all",
        typeId: Int? = null,
    ): List<Map<String, Any?>> {
        val params = mutableMapOf("order_type" to orderType)
        typeId?.let { params["type_id"] = it.toString() }
        return getAllMaps(endpoint = "/markets/$regionId/orders/", params = params)
    }

    fun getMarketRegionHistory(
        regionId: Int,
        typeId: Int,
    ): List<Map<String, Any?>> {
        val (body, _) = getRaw("/markets/$regionId/history/", mapOf("type_id" to typeId.toString()))
        return body.parseToListOfMaps()
    }

    // --- Character Endpoints ---

    fun getCharacterAssets(characterId: Int): List<Map<String, Any?>> =
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
        val (body, _) = getRaw("/characters/$characterId/wallet/", accessToken = token, characterId = characterId)
        return body.trim().toDoubleOrNull() ?: 0.0
    }

    // --- Corporation Endpoints ---
    // All corp-scope calls act as a specific member character (there is no "corp token") — the
    // acting characterId must hold the relevant corp role (accountant/director/etc.) or ESI 403s.

    fun getCorporationAssets(
        corporationId: Int,
        characterId: Int,
    ): List<Map<String, Any?>> = getAllMaps(characterId = characterId, endpoint = "/corporations/$corporationId/assets/")

    fun getCorporationOrders(
        corporationId: Int,
        characterId: Int,
    ): List<Map<String, Any?>> = getAllMaps(characterId = characterId, endpoint = "/corporations/$corporationId/orders/")

    fun getCorporationOrdersHistory(
        corporationId: Int,
        characterId: Int,
    ): List<Map<String, Any?>> = getAllMaps(characterId = characterId, endpoint = "/corporations/$corporationId/orders/history/")

    // Unlike the character wallet endpoint (one balance), ESI returns all of a corp's wallet
    // divisions in a single call: [{"division": 1, "balance": ...}, ...] — there is no
    // per-division balance endpoint.
    fun getCorporationWallet(
        corporationId: Int,
        characterId: Int,
    ): Map<Int, Double> =
        try {
            getAllMaps(characterId = characterId, endpoint = "/corporations/$corporationId/wallets/")
                .mapNotNull { entry ->
                    val division = (entry["division"] as? Number)?.toInt() ?: return@mapNotNull null
                    val balance = (entry["balance"] as? Number)?.toDouble() ?: return@mapNotNull null
                    division to balance
                }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }

    // Each entry is tagged with its source division (ESI's payload itself carries no division
    // field — you only know it from which division endpoint you queried) so callers can persist
    // per-division records.
    fun getCorporationJournal(
        corporationId: Int,
        characterId: Int,
    ): List<Map<String, Any?>> =
        (1..7).flatMap { division ->
            try {
                getAllMaps(characterId = characterId, endpoint = "/corporations/$corporationId/wallets/$division/journal/")
                    .map { it + ("division" to division) }
            } catch (_: Exception) {
                emptyList()
            }
        }

    fun getCorporationTransactions(
        corporationId: Int,
        characterId: Int,
    ): List<Map<String, Any?>> =
        (1..7).flatMap { division ->
            try {
                getAllMaps(characterId = characterId, endpoint = "/corporations/$corporationId/wallets/$division/transactions/")
                    .map { it + ("division" to division) }
            } catch (_: Exception) {
                emptyList()
            }
        }

    fun getCorporationInfo(corporationId: Int): Map<String, Any?> = getMap("/corporations/$corporationId/")

    fun getCharacterInfo(characterId: Int): Map<String, Any?> = getMap("/characters/$characterId/")

    // --- Contract Endpoints ---

    fun getCharacterContracts(characterId: Int): List<Map<String, Any?>> =
        getAllMaps(characterId = characterId, endpoint = "/characters/$characterId/contracts/")

    fun getCorporationContracts(
        corporationId: Int,
        characterId: Int,
    ): List<Map<String, Any?>> = getAllMaps(characterId = characterId, endpoint = "/corporations/$corporationId/contracts/")

    fun getContractItems(contractId: Int): List<Map<String, Any?>> = getAllMaps(endpoint = "/contracts/$contractId/items/")

    fun getEndpointExpiry(
        endpoint: String,
        params: Map<String, String>? = null,
    ): Long? {
        val fullParams = (params ?: emptyMap()).toMutableMap().apply { put("datasource", ESI_DATASOURCE) }
        return EsiCacheManager.getExpiry(endpoint, fullParams)
    }

    // The server's own "as of" time for this endpoint's cached response — see
    // EsiCacheManager.getLastModifiedMillis.
    fun getEndpointLastModifiedMillis(
        endpoint: String,
        params: Map<String, String>? = null,
    ): Long? {
        val fullParams = (params ?: emptyMap()).toMutableMap().apply { put("datasource", ESI_DATASOURCE) }
        return EsiCacheManager.getLastModifiedMillis(endpoint, fullParams)
    }

    // Forces the next call to this endpoint to hit ESI live instead of serving a cached response
    // that's technically still within its TTL but is now known to be stale — e.g. right after a
    // Marketlogs file import taught us the true current order state.
    fun invalidateEndpointCache(endpoint: String) = EsiCacheManager.invalidateEndpoint(endpoint)

    // ─── UI Endpoints (in-game window control) ────────────────────────────

    /** Opens the market details window in the running EVE client for the given type. */
    fun openMarketWindow(
        characterId: Int,
        typeId: Int,
    ) {
        val token = SsoAuthManager.ensureTokenFresh(characterId) ?: return
        val url = "$esiBaseUrl/ui/openwindow/marketdetails/?datasource=$ESI_DATASOURCE&type_id=$typeId"
        val request =
            Request
                .Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null))
                .build()
        EveHttpClient
            .getClient()
            .newCall(request)
            .execute()
            .use { /* 204 No Content = success */ }
    }

    /**
     * Opens the character info ("Show Info") window in the running EVE client for
     * [targetCharacterId], acting as [actingCharacterId] — only works while that acting
     * character is actually logged into a running client, same constraint as
     * [openMarketWindow]. False (rather than throwing) if there's no valid token for the acting
     * character or the call otherwise fails, so callers can fall back to something else.
     */
    fun openCharacterInfoWindow(
        actingCharacterId: Int,
        targetCharacterId: Int,
    ): Boolean {
        val token = SsoAuthManager.ensureTokenFresh(actingCharacterId) ?: return false
        val url = "$esiBaseUrl/ui/openwindow/information/?datasource=$ESI_DATASOURCE&target_id=$targetCharacterId"
        val request =
            Request
                .Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null))
                .build()
        return try {
            EveHttpClient
                .getClient()
                .newCall(request)
                .execute()
                .use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }
}
