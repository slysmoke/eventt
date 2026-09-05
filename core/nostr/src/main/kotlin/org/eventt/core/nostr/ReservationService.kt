package org.eventt.core.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.NostrOrderDao
import org.eventt.core.database.NostrOrderModel
import org.eventt.core.database.NostrReservationDao
import org.eventt.core.database.NostrReservationModel
import java.util.UUID

@Serializable
data class ReservationRequest(
    val type: String = "reservation_request",
    @SerialName("order_id") val orderId: String,
    @SerialName("order_pubkey") val orderPubkey: String,
    @SerialName("trade_id") val tradeId: String,
    val qty: Long,
    val note: String = "",
    @SerialName("buyer_char") val buyerChar: String = "",
    // The buyer's real EVE character ID, when known — see OrderDraft.traderCharId for why this
    // travels over Nostr instead of being resolved from the name on the viewing side.
    @SerialName("buyer_char_id") val buyerCharId: Int? = null,
)

@Serializable
data class ReservationResponse(
    val type: String = "reservation_response",
    @SerialName("trade_id") val tradeId: String,
    val accepted: Boolean,
    @SerialName("reserved_qty") val reservedQty: Long? = null,
    @SerialName("hold_until") val holdUntil: Long? = null,
    @SerialName("contact_char") val contactChar: String = "",
    @SerialName("contact_char_id") val contactCharId: Int? = null,
)

@Serializable
data class ReservationCancel(
    val type: String = "reservation_cancel",
    @SerialName("trade_id") val tradeId: String,
)

/**
 * The NIP-17 DM handshake that turns a Browse click into a held quantity: buyer sends a
 * [ReservationRequest], seller answers with a [ReservationResponse] and — only on accept —
 * republishes the order with a reduced qty_remaining via [OrderRepository]. [nostr_reservations]
 * is the durable local record of this handshake; relays don't reliably replay old DMs, so once a
 * request/response has been seen once, the DB is the source of truth, not the relay.
 */
object ReservationService {
    // encodeDefaults=true is required — every payload's "type" field relies on its default value
    // for the discriminator sent over the wire, and Json's own default (encodeDefaults=false)
    // drops fields equal to their default, silently stripping "type" from every message.
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // Serializes concurrent respond() calls for the same order coordinate -- see respond() for
    // why the whole read-check-decrement sequence has to be a single critical section, not just
    // the final write.
    private val orderLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun orderLock(
        orderUuid: String,
        sellerPubkey: String,
    ): Mutex = orderLocks.computeIfAbsent("$orderUuid:$sellerPubkey") { Mutex() }

    /** Null if there's no active identity to send from. */
    suspend fun sendRequest(
        order: NostrOrderModel,
        qty: Long,
        note: String,
    ): String? {
        val identity = NostrIdentityService.getActiveIdentity() ?: return null
        val tradeId = UUID.randomUUID().toString()
        val buyerChar =
            withContext(Dispatchers.IO) {
                identity.characterId?.let { CharacterDao.getById(it)?.name } ?: identity.label
            }
        val payload =
            ReservationRequest(
                orderId = order.orderUuid,
                orderPubkey = order.pubkey,
                tradeId = tradeId,
                qty = qty,
                note = note,
                buyerChar = buyerChar,
                buyerCharId = identity.characterId,
            )
        if (!sendDm(identity, order.pubkey, json.encodeToString(payload))) return null

        withContext(Dispatchers.IO) {
            NostrReservationDao.insertRequestIfAbsent(
                tradeId = tradeId,
                orderUuid = order.orderUuid,
                orderPubkey = order.pubkey,
                buyerPubkey = identity.pubkey,
                sellerPubkey = order.pubkey,
                role = "buyer",
                qty = qty,
                price = order.price,
                typeId = order.typeId,
                orderSide = order.side,
                note = note,
                buyerChar = buyerChar,
                buyerCharacterId = identity.characterId,
                requestedAt = System.currentTimeMillis() / 1000,
            )
        }
        NostrRelayManager.notifyReservationActivity()
        return tradeId
    }

    /**
     * False if we don't hold the seller's identity for this reservation. Resolves the identity
     * that actually owns [reservation]'s order rather than requiring it to be the currently
     * *active* one — a seller shouldn't have to switch their active trader character just to
     * answer a request that came in for a different one of their own characters.
     */
    suspend fun respond(
        reservation: NostrReservationModel,
        accept: Boolean,
        holdHours: Long = 24,
    ): Boolean {
        val identity = NostrIdentityService.getIdentityByPubkey(reservation.sellerPubkey) ?: return false

        // Oversell guard: two pending requests can both individually fit the order, but accepting
        // the second after the first already decremented qty_remaining would promise stock that no
        // longer exists — both buyers would get an "accepted" DM for the same units. The order is
        // re-fetched *inside* this lock (not before it) and the whole read-check-decrement sequence
        // held under it, so a second respond() for the same order (e.g. two near-simultaneous
        // Accept clicks on different requests) always sees the first one's already-applied
        // decrement instead of racing it on a stale snapshot.
        return orderLock(reservation.orderUuid, reservation.sellerPubkey).withLock {
            val order =
                withContext(Dispatchers.IO) {
                    NostrOrderDao.getByCoordinate(reservation.orderUuid, reservation.sellerPubkey)
                } ?: return@withLock false

            if (accept && order.qtyRemaining < reservation.qty) return@withLock false

            val nowSec = System.currentTimeMillis() / 1000
            val reservedQty = if (accept) reservation.qty else null
            val holdUntil = if (accept) nowSec + holdHours * 3600 else null
            val contactChar = if (accept) order.traderChar else ""
            val contactCharId = if (accept) order.traderCharId else null
            val payload =
                ReservationResponse(
                    tradeId = reservation.tradeId,
                    accepted = accept,
                    reservedQty = reservedQty,
                    holdUntil = holdUntil,
                    contactChar = contactChar,
                    contactCharId = contactCharId,
                )
            if (!sendDm(identity, reservation.buyerPubkey, json.encodeToString(payload))) return@withLock false

            withContext(Dispatchers.IO) {
                NostrReservationDao.updateResponse(reservation.tradeId, accept, reservedQty, holdUntil, contactChar, contactCharId, nowSec)
            }
            if (accept) {
                OrderRepository.setRemainingQty(order, (order.qtyRemaining - reservation.qty).coerceAtLeast(0))
            }
            NostrRelayManager.notifyReservationActivity()
            true
        }
    }

    /**
     * Either side confirms the in-game trade happened: publishes this side's kind-[RECEIPT_KIND]
     * receipt and marks the local reservation completed. Reputation only counts once the *other*
     * side's receipt also lands (see [ReputationAggregator]) — this alone doesn't prove anything
     * by itself. False if there's no active identity to sign the receipt with.
     */
    suspend fun markCompleted(reservation: NostrReservationModel): Boolean {
        if (!ReceiptService.publish(reservation)) return false
        withContext(Dispatchers.IO) { NostrReservationDao.updateStatus(reservation.tradeId, "completed") }
        NostrRelayManager.notifyReservationActivity()
        return true
    }

    /**
     * Buyer withdraws a still-pending request before the seller has responded — notifies the
     * seller so their copy drops out of Incoming requests too. Resolves the identity that
     * actually sent the original request (not necessarily the currently active one) the same
     * way [respond] resolves the seller's. Once accepted, qty is already held on the seller's
     * order, so a buyer backing out past this point should go through [release] instead.
     */
    suspend fun cancel(reservation: NostrReservationModel): Boolean {
        if (reservation.status != "sent") return false
        val identity = NostrIdentityService.getIdentityByPubkey(reservation.buyerPubkey) ?: return false
        val payload = ReservationCancel(tradeId = reservation.tradeId)
        if (!sendDm(identity, reservation.sellerPubkey, json.encodeToString(payload))) return false

        withContext(Dispatchers.IO) { NostrReservationDao.updateStatus(reservation.tradeId, "cancelled") }
        NostrRelayManager.notifyReservationActivity()
        return true
    }

    /**
     * Seller gives up on a held reservation (buyer never showed) — restores qty_remaining, no
     * receipt either way. [OrderRepository.setRemainingQty] resolves the owning identity itself,
     * so no active-identity check is needed here.
     */
    suspend fun release(reservation: NostrReservationModel): Boolean {
        if (reservation.status != "accepted") return false
        val order =
            withContext(Dispatchers.IO) {
                NostrOrderDao.getByCoordinate(reservation.orderUuid, reservation.sellerPubkey)
            } ?: return false
        val restored = OrderRepository.setRemainingQty(order, order.qtyRemaining + reservation.qty)
        if (restored != null) {
            withContext(Dispatchers.IO) { NostrReservationDao.updateStatus(reservation.tradeId, "released") }
            NostrRelayManager.notifyReservationActivity()
        }
        return restored != null
    }

    /**
     * Called by [NostrRelayManager] for every DM it unwraps — routes it to the right local-DB
     * update. Returns the freshly-inserted reservation only for a genuinely new incoming buy
     * request (not a duplicate re-delivery of one already seen, and not any other message type) —
     * that's the one case [NostrRelayManager] turns into a notification, so it needs to tell a new
     * request apart from the same gift wrap arriving twice via two different relays.
     */
    suspend fun handleIncomingDm(
        fromPubkey: String,
        content: String,
    ): NostrReservationModel? {
        val type =
            runCatching {
                json
                    .parseToJsonElement(content)
                    .jsonObject["type"]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()
                ?: return null
        when (type) {
            "reservation_request" -> {
                val req = runCatching { json.decodeFromString<ReservationRequest>(content) }.getOrNull() ?: return null
                // Anyone who can see an order can gift-wrap us arbitrary JSON — only requests that
                // target one of *our own*, actually existing orders with a protocol-valid qty reach
                // the DB (and thus the Incoming requests screen / notification banner). Everything
                // else is spam or a broken client and is dropped without a trace.
                if (NostrIdentityService.getIdentityByPubkey(req.orderPubkey) == null) return null
                val order =
                    withContext(Dispatchers.IO) {
                        NostrOrderDao.getByCoordinate(req.orderId, req.orderPubkey)
                    } ?: return null
                if (!isValidRequestQty(req.qty, order)) return null
                val isNew =
                    withContext(Dispatchers.IO) {
                        NostrReservationDao.insertRequestIfAbsent(
                            tradeId = req.tradeId,
                            orderUuid = req.orderId,
                            orderPubkey = req.orderPubkey,
                            buyerPubkey = fromPubkey,
                            sellerPubkey = req.orderPubkey,
                            role = "seller",
                            qty = req.qty,
                            price = order.price,
                            typeId = order.typeId,
                            orderSide = order.side,
                            note = req.note,
                            buyerChar = req.buyerChar,
                            buyerCharacterId = req.buyerCharId,
                            requestedAt = System.currentTimeMillis() / 1000,
                        )
                    }
                if (!isNew) return null
                return withContext(Dispatchers.IO) { NostrReservationDao.getByTradeId(req.tradeId) }
            }

            "reservation_response" -> {
                val resp = runCatching { json.decodeFromString<ReservationResponse>(content) }.getOrNull() ?: return null
                // Only the seller of this trade may answer it — a tradeId is not a capability.
                // Anyone who learns it (log, screenshot, compromised counterparty) could otherwise
                // send a buyer a forged "accepted" for a meetup that was never agreed to.
                val existing = withContext(Dispatchers.IO) { NostrReservationDao.getByTradeId(resp.tradeId) } ?: return null
                if (existing.sellerPubkey != fromPubkey) return null
                withContext(Dispatchers.IO) {
                    NostrReservationDao.updateResponse(
                        resp.tradeId,
                        resp.accepted,
                        resp.reservedQty,
                        resp.holdUntil,
                        resp.contactChar,
                        resp.contactCharId,
                        System.currentTimeMillis() / 1000,
                    )
                }
            }

            "reservation_cancel" -> {
                val cancel = runCatching { json.decodeFromString<ReservationCancel>(content) }.getOrNull() ?: return null
                // Same sender check as reservation_response, buyer's side; and only a still-pending
                // request may be cancelled — a replayed cancel from relay DM backlog (or a legit one
                // racing our accept) must not clobber an accepted/completed reservation whose qty is
                // already held on the order.
                val existing = withContext(Dispatchers.IO) { NostrReservationDao.getByTradeId(cancel.tradeId) } ?: return null
                if (existing.buyerPubkey != fromPubkey || existing.status != "sent") return null
                withContext(Dispatchers.IO) { NostrReservationDao.updateStatus(cancel.tradeId, "cancelled") }
            }
        }
        return null
    }

    /**
     * Restores qty for every accepted hold whose hold_until has passed — without this, a buyer who
     * never shows up leaves the seller's stock frozen until the seller remembers to hit Release by
     * hand. Called periodically by [NostrRelayManager]; [release] itself re-checks status, so a
     * race with a manual release is harmless.
     */
    suspend fun releaseExpiredHolds() {
        val nowSec = System.currentTimeMillis() / 1000
        val expired =
            withContext(Dispatchers.IO) { NostrReservationDao.listForRole("seller", listOf("accepted")) }
                .filter { (it.holdUntil ?: Long.MAX_VALUE) < nowSec }
        expired.forEach { release(it) }
    }

    /**
     * Protocol-valid request quantity: positive, within what the order still has, and at or above
     * the order's min lot (in units, or in ISK value when min_lot_unit is "isk"). Our own send UI
     * enforces this too — anything violating it over the wire is a bot or a broken client.
     */
    internal fun isValidRequestQty(
        qty: Long,
        order: NostrOrderModel,
    ): Boolean {
        if (qty <= 0 || qty > order.qtyRemaining) return false
        return when (order.minLotUnit) {
            "isk" -> qty * order.price >= order.minLot
            else -> qty >= order.minLot
        }
    }

    private suspend fun sendDm(
        identity: NostrIdentity,
        recipientPubkeyHex: String,
        content: String,
    ): Boolean {
        val signer = QuartzGateway.asyncSignerFor(identity.keyPair)
        val wraps = QuartzGateway.buildEncryptedDm(signer, recipientPubkeyHex, content, System.currentTimeMillis() / 1000)
        wraps.forEach { NostrRelayManager.publish(it) }
        return wraps.isNotEmpty()
    }
}
