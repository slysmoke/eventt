package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
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
import org.eventt.core.database.NostrReservationModel
import java.util.concurrent.TimeUnit

sealed class NostrRelayEvent {
    data class OrderUpdated(
        val order: ParsedOrder,
    ) : NostrRelayEvent()

    data object ReservationActivity : NostrRelayEvent()

    /** A brand-new incoming buy request landed (not a duplicate re-delivery) — drives the notification banner. */
    data class IncomingBuyRequest(
        val reservation: NostrReservationModel,
    ) : NostrRelayEvent()

    data object RelayStatusChanged : NostrRelayEvent()
}

private const val ORDERS_SUBSCRIPTION_ID = "p2pmarket-orders"
private const val DMS_SUBSCRIPTION_ID = "p2pmarket-dms"
private const val RECEIPTS_SUBSCRIPTION_ID = "p2pmarket-receipts"
private const val GIFT_WRAP_KIND = 1059

private val DEFAULT_RELAYS =
    listOf(
        "wss://nos.lol",
        "wss://nostr.wine",
        "wss://relay.primal.net",
        "wss://relay.snort.social",
        "wss://relay.nostr.info",
        "wss://relay.wellorder.net",
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
        connect()
    }

    /**
     * The DM/order/receipt subscriptions below are all keyed to the pubkey that was active when
     * they were opened — switching the active P2P Market identity in Settings doesn't change
     * which pubkey the running subscriptions listen for, so incoming DMs addressed to the newly
     * active character would otherwise be silently missed until the app restarts. Call this after
     * [NostrIdentityService.switchActive] to tear down and reconnect under the new identity.
     */
    fun restart() {
        log("restart() called")
        stop()
        connect()
    }

    private fun connect() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            try {
                log("connect() starting")
                NostrRelayDao.seedDefaultsIfEmpty(DEFAULT_RELAYS)
                log("relay defaults seeded")
                normalizeStoredRelayUrls()
                log("relay urls normalized")

                // No identity yet (first run, nothing generated/imported) — nothing useful to connect
                // for until Settings creates one; NostrIdentityService itself doesn't require a
                // running relay connection, so this is a clean early-out, not a broken dependency.
                val identity = NostrIdentityService.getActiveIdentity()
                log("active identity = ${identity?.pubkey}")
                if (identity == null) return@launch
                val myPubkey = identity.pubkey

                val httpClient =
                    OkHttpClient
                        .Builder()
                        .callTimeout(0, TimeUnit.MILLISECONDS)
                        .pingInterval(25, TimeUnit.SECONDS)
                        .build()
                val websocketBuilder: WebsocketBuilder = BasicOkHttpWebSocket.Builder { httpClient }
                val c = NostrClient(websocketBuilder, s)
                client = c
                c.addConnectionListener(
                    object : RelayConnectionListener {
                        override fun onConnected(
                            relay: IRelayClient,
                            pingMillis: Int,
                            compressed: Boolean,
                        ) {
                            log("onConnected ${relay.url.url}")
                            NostrRelayDao.updateStatus(relay.url.url, "connected", null)
                            _events.tryEmit(NostrRelayEvent.RelayStatusChanged)
                        }

                        override fun onDisconnected(relay: IRelayClient) {
                            log("onDisconnected ${relay.url.url}")
                            NostrRelayDao.updateStatus(relay.url.url, "disconnected", null)
                            _events.tryEmit(NostrRelayEvent.RelayStatusChanged)
                        }

                        override fun onCannotConnect(
                            relay: IRelayClient,
                            errorMessage: String,
                        ) {
                            log("onCannotConnect ${relay.url.url} reason=$errorMessage")
                            NostrRelayDao.updateStatus(relay.url.url, "error", errorMessage)
                            _events.tryEmit(NostrRelayEvent.RelayStatusChanged)
                        }
                    },
                )
                log("calling c.connect()")
                c.connect()
                log("c.connect() returned")

                val relayUrls = NostrRelayDao.getAll().filter { it.enabled }.mapNotNull { it.url.normalizeRelayUrlOrNull() }
                log("relayUrls = $relayUrls")
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
                                        traderCharId = parsed.traderCharId,
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
                log("subscribing DMs for p=$myPubkey")
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
                            log("DM onEvent id=${event.id} from relay=${relay.url} isLive=$isLive")
                            // unwrapGiftWrap/handleIncomingDm are both suspend — this callback isn't,
                            // so hop onto the manager's own scope rather than blocking the relay client.
                            s.launch {
                                val unwrapped = QuartzGateway.unwrapGiftWrap(event, QuartzGateway.asyncSignerFor(identity.keyPair))
                                if (unwrapped == null) {
                                    log("DM unwrap FAILED (not ours or malformed) for event ${event.id}")
                                    return@launch
                                }
                                log("DM unwrapped from=${unwrapped.pubKey} content=${unwrapped.content.take(120)}")
                                val newRequest = ReservationService.handleIncomingDm(unwrapped.pubKey, unwrapped.content)
                                _events.tryEmit(NostrRelayEvent.ReservationActivity)
                                if (newRequest != null) _events.tryEmit(NostrRelayEvent.IncomingBuyRequest(newRequest))
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
                log("all subscriptions set up")
            } catch (e: Throwable) {
                log("EXCEPTION during connect(): ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // TEMP diagnostic — remove once the "relays stuck on Not yet connected" bug is found.
    private fun log(msg: String) = println("[NostrRelay] $msg")

    /** Normalized form of [url] (e.g. adds the trailing slash relay clients expect) — use this before [NostrRelayDao.upsert] so newly added relays don't hit the same stuck-status bug as [normalizeStoredRelayUrls] repairs for existing ones. */
    fun normalizeUrl(url: String): String = url.normalizeRelayUrlOrNull()?.url ?: url

    /**
     * One-time repair for rows seeded/added before URLs were stored in normalized form — those
     * rows' primary key never matches the normalized URL [updateStatus] is called with, so their
     * connection status silently never updates (see [NostrRelayDao.renameUrl]).
     */
    private fun normalizeStoredRelayUrls() {
        NostrRelayDao.getAll().forEach { relay ->
            val normalized = normalizeUrl(relay.url)
            if (normalized != relay.url) NostrRelayDao.renameUrl(relay.url, normalized)
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
