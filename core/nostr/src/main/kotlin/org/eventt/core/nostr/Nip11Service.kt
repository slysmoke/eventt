package org.eventt.core.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eventt.core.database.NostrRelayDao
import java.util.concurrent.TimeUnit

// Some relays put strings into supported_nips — JsonPrimitive + intOrNull tolerates both.
@Serializable
internal data class Nip11Doc(
    @SerialName("supported_nips") val supportedNips: List<JsonPrimitive> = emptyList(),
    val limitation: Nip11Limitation? = null,
) {
    fun nips(): List<Int> = supportedNips.mapNotNull { it.intOrNull }

    fun restrictedWrites(): Boolean = limitation?.restrictedWrites == true || limitation?.paymentRequired == true
}

@Serializable
internal data class Nip11Limitation(
    @SerialName("payment_required") val paymentRequired: Boolean = false,
    @SerialName("restricted_writes") val restrictedWrites: Boolean = false,
)

/**
 * Fetches each relay's NIP-11 information document (plain HTTP GET on the relay's host with
 * `Accept: application/nostr+json`) and stores supported NIPs + write restrictions on the relay
 * row. Settings uses this to warn about relays that can't store orders (no NIP-40) or silently
 * reject our publishes (paid/restricted writes) — see docs/relay-nip-support.md for why both
 * failure modes are real, not hypothetical.
 */
object Nip11Service {
    private const val REFRESH_MAX_AGE_SECONDS = 24L * 3600

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        OkHttpClient
            .Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Refreshes every relay whose NIP-11 data is missing or older than a day; no-op otherwise. */
    suspend fun refreshStale() {
        val nowSec = System.currentTimeMillis() / 1000
        val stale =
            withContext(Dispatchers.IO) { NostrRelayDao.getAll() }
                .filter { (it.nip11FetchedAt ?: 0) < nowSec - REFRESH_MAX_AGE_SECONDS }
        var updatedAny = false
        stale.forEach { relay ->
            val doc = fetch(relay.url) ?: return@forEach
            withContext(Dispatchers.IO) {
                NostrRelayDao.updateNip11(relay.url, doc.nips(), doc.restrictedWrites(), nowSec)
            }
            updatedAny = true
        }
        if (updatedAny) NostrRelayManager.notifyRelayStatusChanged()
    }

    /** Null on any failure (unreachable, non-2xx, malformed JSON) — a relay without NIP-11 is fine, just unknown. */
    private suspend fun fetch(wssUrl: String): Nip11Doc? =
        withContext(Dispatchers.IO) {
            runCatching {
                val httpUrl = wssUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
                val request =
                    Request
                        .Builder()
                        .url(httpUrl)
                        .header("Accept", "application/nostr+json")
                        .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    json.decodeFromString<Nip11Doc>(resp.body?.string() ?: return@use null)
                }
            }.getOrNull()
        }
}
