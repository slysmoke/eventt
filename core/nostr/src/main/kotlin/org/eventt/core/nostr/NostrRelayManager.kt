package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrlOrNull
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.eventt.core.database.NostrOrderDao
import org.eventt.core.database.NostrOrderModel
import org.eventt.core.database.NostrRelayDao
import java.util.concurrent.TimeUnit

sealed class NostrRelayEvent {
    data class OrderUpdated(val order: ParsedOrder) : NostrRelayEvent()

    data object ReservationActivity : NostrRelayEvent()
}

private const val ORDERS_SUBSCRIPTION_ID = "p2pmarket-orders"
private const val DMS_SUBSCRIPTION_ID = "p2pmarket-dms"
private const val RECEIPTS_SUBSCRIPTION_ID = "p2pmarket-receipts"
private const val GIFT_WRAP_KIND = 1059

private val DEFAULT_RELAYS =
    listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band",
        "wss://nostr.wine",
        "wss://relay.primal.net",
    )

/**
 * App-lifetime background service — same start()/stop()-from-Main.kt shape as
 * core/marketlogs/MarketLogWatcher, so it keeps running across tab switches without being owned
 * by any one screen's composable. Quartz's relay client needs its own explicit OkHttpClient (it
 * doesn't manage a default one) — see the Phase 0 spike findings; callTimeout is disabled since a
 * websocket is long-lived, unlike EveHttpClient's request/response-tuned client.
 */
object NostrRelayManager {
    private var scope: CoroutineScope? = null
    private var client: NostrClient? = null

    private val _events = MutableSharedFlow<NostrRelayEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NostrRelayEvent> = _events.asSharedFlow()

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            NostrRelayDao.seedDefaultsIfEmpty(DEFAULT_RELAYS)

            // No identity yet (first run, nothing generated/imported) — nothing useful to connect
            // for until Settings creates one; NostrIdentityService itself doesn't require a
            // running relay connection, so this is a clean early-out, not a broken dependency.
            val identity = NostrIdentityService.getActiveIdentity() ?: return@launch
            val myPubkey = identity.pubkey

            val httpClient =
                OkHttpClient.Builder()
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(25, TimeUnit.SECONDS)
                    .build()
            val websocketBuilder: WebsocketBuilder = BasicOkHttpWebSocket.Builder { httpClient }
            val c = NostrClient(websocketBuilder, s)
            client = c
            c.connect()

            val relayUrls = NostrRelayDao.getAll().filter { it.enabled }.mapNotNull { it.url.normalizeRelayUrlOrNull() }
            if (relayUrls.isEmpty()) return@launch

            val filter = Filter(null, null, listOf(ORDER_KIND), mapOf("t" to listOf("eve-otc")), null, null, null, null, null)
            c.subscribe(
                ORDERS_SUBSCRIPTION_ID,
                relayUrls.associateWith { listOf(filter) },
                object : SubscriptionListener {
                    override fun onEvent(
                        event: Event,
                        isLive: Boolean,
                        relay: NormalizedRelayUrl,
                        forFilters: List<Filter>?,
                    ) {
                        val parsed = NostrEventFactory.parseOrderEvent(event) ?: return
                        val isNewer =
                            NostrOrderDao.upsertIfNewer(
                                NostrOrderModel(
                                    orderUuid = parsed.orderUuid,
                                    pubkey = parsed.pubkey,
                                    eventId = parsed.eventId,
                                    createdAt = parsed.createdAt,
                                    side = parsed.side.name.lowercase(),
                                    typeId = parsed.typeId,
                                    regionId = parsed.regionId,
                                    price = parsed.price,
                                    qtyTotal = parsed.qtyTotal,
                                    qtyRemaining = parsed.qtyRemaining,
                                    minLot = parsed.minLot,
                                    minLotUnit = parsed.minLotUnit.name.lowercase(),
                                    traderChar = parsed.traderChar,
                                    expiration = parsed.expiration,
                                    rawEventJson = event.toJson(),
                                    isMine = parsed.pubkey == myPubkey,
                                ),
                            )
                        if (isNewer) _events.tryEmit(NostrRelayEvent.OrderUpdated(parsed))
                    }
                },
            )

            val dmFilter = Filter(null, null, listOf(GIFT_WRAP_KIND), mapOf("p" to listOf(myPubkey)), null, null, null, null, null)
            c.subscribe(
                DMS_SUBSCRIPTION_ID,
                relayUrls.associateWith { listOf(dmFilter) },
                object : SubscriptionListener {
                    override fun onEvent(
                        event: Event,
                        isLive: Boolean,
                        relay: NormalizedRelayUrl,
                        forFilters: List<Filter>?,
                    ) {
                        // unwrapGiftWrap/handleIncomingDm are both suspend — this callback isn't,
                        // so hop onto the manager's own scope rather than blocking the relay client.
                        s.launch {
                            val unwrapped = QuartzGateway.unwrapGiftWrap(event, QuartzGateway.asyncSignerFor(identity.keyPair)) ?: return@launch
                            ReservationService.handleIncomingDm(unwrapped.pubKey, unwrapped.content)
                            _events.tryEmit(NostrRelayEvent.ReservationActivity)
                        }
                    }
                },
            )

            val receiptFilter = Filter(null, null, listOf(RECEIPT_KIND), mapOf("p" to listOf(myPubkey)), null, null, null, null, null)
            c.subscribe(
                RECEIPTS_SUBSCRIPTION_ID,
                relayUrls.associateWith { listOf(receiptFilter) },
                object : SubscriptionListener {
                    override fun onEvent(
                        event: Event,
                        isLive: Boolean,
                        relay: NormalizedRelayUrl,
                        forFilters: List<Filter>?,
                    ) {
                        ReceiptService.handleIncomingReceipt(event)
                        _events.tryEmit(NostrRelayEvent.ReservationActivity)
                    }
                },
            )
        }
    }

    fun stop() {
        client?.disconnect()
        client = null
        scope?.cancel()
        scope = null
    }

    /** No-op (silently) if the relay connection isn't up yet — callers can't usefully retry a publish mid-outage in Phase 1. */
    suspend fun publish(event: Event) {
        withContext(Dispatchers.IO) {
            val c = client ?: return@withContext
            val relayUrls = NostrRelayDao.getAll().filter { it.enabled && it.write }.mapNotNull { it.url.normalizeRelayUrlOrNull() }
            if (relayUrls.isNotEmpty()) c.publish(event, relayUrls.toSet())
        }
    }
}
