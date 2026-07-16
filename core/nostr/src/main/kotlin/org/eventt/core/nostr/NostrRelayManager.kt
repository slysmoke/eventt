package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
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
import org.eventt.core.database.NostrOutboxDao
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
private const val PRESENCE_SUBSCRIPTION_ID = "p2pmarket-presence"
private const val GIFT_WRAP_KIND = 1059
private const val EXPIRED_HOLD_SWEEP_MILLIS = 10L * 60 * 1000

// Outbox rows older than an order's full lifetime aren't worth retrying — the revision has been
// superseded or the order has expired; pending DMs that old are equally moot.
private const val OUTBOX_MAX_AGE_SECONDS = 14L * 24 * 3600

// All six checked against their NIP-11 documents (2026-07-16): free writes, NIP-9 (deletion)
// and NIP-40 (expiration) supported — see docs/relay-nip-support.md.
private val DEFAULT_RELAYS =
    listOf(
        "wss://nos.lol",
        "wss://offchain.pub",
        "wss://relay.primal.net",
        "wss://relay.damus.io",
        "wss://relay.nostr.info",
        "wss://relay.wellorder.net",
    )

// Former defaults that turned out unusable for order storage (NIP-11 audit 2026-07-16):
// relay.snort.social is an in-memory relay (supported_nips [1, 11] — no persistence, no NIP-40),
// nostr.wine has restricted_writes + paid admission, so our publishes were silently rejected.
private val RETIRED_DEFAULT_RELAYS =
    mapOf(
        "wss://relay.snort.social" to "wss://relay.damus.io",
        "wss://nostr.wine" to "wss://offchain.pub",
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
        stop()
        connect()
    }

    private fun connect() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            try {
                NostrRelayDao.seedDefaultsIfEmpty(DEFAULT_RELAYS)
                normalizeStoredRelayUrls()
                migrateRetiredDefaultRelays()
                // Before the identity early-out on purpose — the Settings relay list (and its
                // NIP-11 warnings) should populate even when no P2P identity exists yet.
                s.launch { runCatching { Nip11Service.refreshStale() } }

                // No identities yet (first run, nothing generated/imported) — nothing useful to
                // connect for until Settings creates one; NostrIdentityService itself doesn't
                // require a running relay connection, so this is a clean early-out.
                // ALL local identities are subscribed, not just the active one — a request for an
                // order posted by character A must arrive (and notify) while character B is active.
                val identities = NostrIdentityService.listIdentities()
                if (identities.isEmpty()) return@launch
                val myPubkeys = identities.map { it.pubkey }.toSet()

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
                            NostrRelayDao.updateStatus(relay.url.url, "connected", null)
                            _events.tryEmit(NostrRelayEvent.RelayStatusChanged)
                        }

                        override fun onDisconnected(relay: IRelayClient) {
                            NostrRelayDao.updateStatus(relay.url.url, "disconnected", null)
                            _events.tryEmit(NostrRelayEvent.RelayStatusChanged)
                        }

                        override fun onCannotConnect(
                            relay: IRelayClient,
                            errorMessage: String,
                        ) {
                            NostrRelayDao.updateStatus(relay.url.url, "error", errorMessage)
                            _events.tryEmit(NostrRelayEvent.RelayStatusChanged)
                        }

                        override fun onIncomingMessage(
                            relay: IRelayClient,
                            msgStr: String,
                            msg: Message,
                        ) {
                            // First OK for one of our pending publishes — at least one relay now
                            // durably holds the event, so the persistent outbox can drop it.
                            if (msg is OkMessage && msg.success) NostrOutboxDao.remove(msg.eventId)
                        }
                    },
                )
                c.connect()

                val relayUrls = NostrRelayDao.getAll().filter { it.enabled }.mapNotNull { it.url.normalizeRelayUrlOrNull() }
                if (relayUrls.isEmpty()) return@launch

                val filter = Filter(null, null, listOf(ORDER_KIND), mapOf("t" to listOf("eventt-p2pmarket")), null, null, null, null, null)
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
                            // NIP-40 enforced client-side: a relay that ignores expiration (e.g. one
                            // whose NIP-11 lacks 40) happily replays dead orders forever — they must
                            // not reach the DB at all, not just be filtered out of queryActive.
                            if (parsed.expiration <= System.currentTimeMillis() / 1000) return
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
                                        isMine = parsed.pubkey in myPubkeys,
                                    ),
                                )
                            if (isNewer) _events.tryEmit(NostrRelayEvent.OrderUpdated(parsed))
                        }
                    },
                )

                val dmFilter = Filter(null, null, listOf(GIFT_WRAP_KIND), mapOf("p" to myPubkeys.toList()), null, null, null, null, null)
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
                            // The wrap's recipient tag is readable without decrypting — route to the
                            // owning identity's key, since each wrap decrypts with exactly one of them.
                            val recipient = QuartzGateway.giftWrapRecipient(event) ?: return
                            val identity = identities.find { it.pubkey == recipient } ?: return
                            // unwrapGiftWrap/handleIncomingDm are both suspend — this callback isn't,
                            // so hop onto the manager's own scope rather than blocking the relay client.
                            s.launch {
                                val unwrapped =
                                    QuartzGateway.unwrapGiftWrap(event, QuartzGateway.asyncSignerFor(identity.keyPair)) ?: return@launch
                                val newRequest = ReservationService.handleIncomingDm(unwrapped.pubKey, unwrapped.content)
                                _events.tryEmit(NostrRelayEvent.ReservationActivity)
                                if (newRequest != null) _events.tryEmit(NostrRelayEvent.IncomingBuyRequest(newRequest))
                            }
                        }
                    },
                )

                val receiptFilter = Filter(null, null, listOf(RECEIPT_KIND), mapOf("p" to myPubkeys.toList()), null, null, null, null, null)
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
                // Everyone's app-scoped presence, not just current counterparties' — the network is
                // only this app's users, and a static filter avoids re-subscribing every time a new
                // reservation appears. Addressable kind = at most one event per pubkey per d tag.
                val presenceFilter =
                    Filter(
                        null,
                        null,
                        listOf(PRESENCE_KIND),
                        mapOf("d" to listOf(PRESENCE_D_TAG, PRESENCE_APP_D_TAG)),
                        null,
                        null,
                        null,
                        null,
                        null,
                    )
                c.subscribe(
                    PRESENCE_SUBSCRIPTION_ID,
                    relayUrls.associateWith { listOf(presenceFilter) },
                    object : SubscriptionListener {
                        override fun onEvent(
                            event: Event,
                            isLive: Boolean,
                            relay: NormalizedRelayUrl,
                            forFilters: List<Filter>?,
                        ) {
                            PresenceService.onPresenceEvent(event)
                        }
                    },
                )

                s.launch {
                    val appInstanceSigner = QuartzGateway.signerFor(PresenceService.appInstanceKeyPair())
                    while (true) {
                        runCatching {
                            // durable=false everywhere here: a heartbeat that didn't get through is
                            // worthless seconds later — retrying stale ones from the outbox is junk.
                            // Re-list each round so identities added after connect() heartbeat too.
                            NostrIdentityService.listIdentities().forEach { identity ->
                                publish(PresenceService.buildHeartbeat(QuartzGateway.signerFor(identity.keyPair)), durable = false)
                            }
                            // The anonymous install counter — one per running app, whoever is active.
                            publish(
                                PresenceService.buildHeartbeat(appInstanceSigner, dTag = PRESENCE_APP_D_TAG),
                                durable = false,
                            )
                        }
                        kotlinx.coroutines.delay(PRESENCE_HEARTBEAT_SECONDS * 1000)
                    }
                }

                s.launch { runCatching { flushOutbox() } }

                // ponytail: fixed 10-min sweep; event-driven per-hold timers if the lag ever matters
                s.launch {
                    while (true) {
                        runCatching { ReservationService.releaseExpiredHolds() }
                        kotlinx.coroutines.delay(EXPIRED_HOLD_SWEEP_MILLIS)
                    }
                }
            } catch (e: Throwable) {
                println("[NostrRelay] connect() failed: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

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

    /**
     * One-time swap of [RETIRED_DEFAULT_RELAYS] in existing installs — [NostrRelayDao.renameUrl]
     * keeps the row's enabled/read/write settings, and no-ops if the replacement already exists
     * as its own row (then the retired one is just dropped). A user who genuinely wants a retired
     * relay (e.g. paid nostr.wine membership) can re-add it in Settings.
     */
    private fun migrateRetiredDefaultRelays() {
        RETIRED_DEFAULT_RELAYS.forEach { (retired, replacement) ->
            val old = normalizeUrl(retired)
            NostrRelayDao.renameUrl(old, normalizeUrl(replacement))
            if (NostrRelayDao.getAll().any { it.url == old }) NostrRelayDao.remove(old)
        }
    }

    fun stop() {
        client?.disconnect()
        client = null
        scope?.cancel()
        scope = null
    }

    /**
     * Lets a locally-initiated reservation change (accept/decline/cancel/release/mark-completed —
     * see [ReservationService]) notify the same listeners an incoming DM would. Without this,
     * screens/badges driven by [events] only refreshed when some *other* DM happened to arrive
     * afterward, since none of those local mutations otherwise touch this event stream themselves.
     */
    fun notifyReservationActivity() {
        _events.tryEmit(NostrRelayEvent.ReservationActivity)
    }

    /** Lets [Nip11Service] refresh the Settings relay list the same way a connection change does. */
    internal fun notifyRelayStatusChanged() {
        _events.tryEmit(NostrRelayEvent.RelayStatusChanged)
    }

    /**
     * Same idea as [notifyReservationActivity] but for orders — a locally-initiated post/renew/
     * cancel (see [OrderRepository]) needs to reach [OrderRepository.browse]'s collectors right
     * away, not whenever (if ever) the relay happens to echo our own just-published event back to
     * us. Without this, a freshly posted or cancelled order only showed up in "My active orders"
     * after some unrelated event forced a re-query (e.g. switching tabs and back).
     */
    fun notifyOrderUpdated(order: ParsedOrder) {
        _events.tryEmit(NostrRelayEvent.OrderUpdated(order))
    }

    /**
     * Never loses an event: it lands in the persistent outbox first and only leaves it on the
     * first relay OK (see [flushOutbox]). If the relay client isn't up, the send part is skipped —
     * the event goes out on the next [connect]. While connected, Quartz's own in-memory outbox
     * keeps retrying the remaining relays per event, so one OK here is enough to stop tracking.
     * [durable] = false opts out of the outbox for time-sensitive events (presence heartbeats)
     * where a delayed retry is worse than the event just not going out.
     */
    suspend fun publish(
        event: Event,
        durable: Boolean = true,
    ) {
        withContext(Dispatchers.IO) {
            if (durable) NostrOutboxDao.insert(event.id, event.toJson(), event.createdAt)
            val c = client ?: return@withContext
            val relayUrls = NostrRelayDao.getAll().filter { it.enabled && it.write }.mapNotNull { it.url.normalizeRelayUrlOrNull() }
            if (relayUrls.isNotEmpty()) c.publish(event, relayUrls.toSet())
        }
    }

    /** Re-publishes everything still awaiting a relay OK — pending gift wraps replay harmlessly (receivers dedup by trade_id), and an outdated order revision is simply ignored by relays that already hold a newer one. */
    private suspend fun flushOutbox() {
        NostrOutboxDao.deleteOlderThan(System.currentTimeMillis() / 1000 - OUTBOX_MAX_AGE_SECONDS)
        NostrOutboxDao
            .allEventJson()
            .mapNotNull { QuartzGateway.eventFromJson(it) }
            .forEach { publish(it) }
    }
}
