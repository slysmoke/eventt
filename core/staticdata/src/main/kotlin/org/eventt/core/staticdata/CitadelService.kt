package org.eventt.core.staticdata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import org.eventt.core.database.StaticDataDao
import org.eventt.core.http.EveHttpClient
import org.eventt.core.model.StaticStationModel

object CitadelService {
    private const val CITADEL_URL =
        "https://raw.githubusercontent.com/slysmoke/evernus-db/refs/heads/main/citadel.json"
    private const val PREF_LAST_SYNC = "citadel.last_sync_millis"

    data class SyncState(
        val isRunning: Boolean = false,
        val count: Int = 0,
        val status: String = "",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    fun getLastSyncMillis(): Long? = StaticDataDao.getSetting(PREF_LAST_SYNC)?.toLongOrNull()

    fun getCitadelCount(): Int = StaticDataDao.getCitadelCount()

    suspend fun sync() =
        withContext(Dispatchers.IO) {
            if (_state.value.isRunning) return@withContext
            _state.value = SyncState(isRunning = true, status = "Downloading citadel list…")
            try {
                val json = download()
                _state.value = _state.value.copy(status = "Parsing…")
                val stations = parse(json)
                _state.value = _state.value.copy(status = "Saving ${stations.size} citadels…")
                StaticDataDao.bulkInsertStations(stations)
                StaticDataDao.setSetting(PREF_LAST_SYNC, System.currentTimeMillis().toString())
                _state.value = SyncState(isRunning = false, count = stations.size, status = "Synced ${stations.size} citadels")
            } catch (e: Exception) {
                _state.value = SyncState(isRunning = false, error = e.message ?: "Unknown error")
            }
        }

    private fun download(): String {
        val request = Request.Builder().url(CITADEL_URL).build()
        EveHttpClient.getClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string() ?: error("Empty response")
        }
    }

    private fun parse(raw: String): List<StaticStationModel> {
        val root = Json.parseToJsonElement(raw).jsonObject
        return root.entries.mapNotNull { (idStr, elem) ->
            val id = idStr.toLongOrNull() ?: return@mapNotNull null
            val obj = elem.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            StaticStationModel(
                stationId = id,
                name = name,
                systemId = obj["systemId"]?.jsonPrimitive?.intOrNull ?: 0,
                systemName = obj["systemName"]?.jsonPrimitive?.contentOrNull ?: "",
                regionId = obj["regionId"]?.jsonPrimitive?.intOrNull ?: 0,
                regionName = obj["regionName"]?.jsonPrimitive?.contentOrNull ?: "",
                typeId = obj["typeId"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }
}
