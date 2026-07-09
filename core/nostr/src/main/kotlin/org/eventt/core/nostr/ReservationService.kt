package org.eventt.core.nostr

import kotlinx.coroutines.Dispatchers
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
)

@Serializable
data class ReservationResponse(
    val type: String = "reservation_response",
    @SerialName("trade_id") val tradeId: String,
    val accepted: Boolean,
    @SerialName("reserved_qty") val reservedQty: Long? = null,
    @SerialName("hold_until") val holdUntil: Long? = null,
    @SerialName("contact_char") val contactChar: String = "",
)

/**
 * The NIP-17 DM handshake that turns a Browse click into a held quantity: buyer sends a
 * [ReservationRequest], seller answers with a [ReservationResponse] and — only on accept —
 * republishes the order with a reduced qty_remaining via [OrderRepository]. [nostr_reservations]
 * is the durable local record of this handshake; relays don't reliably replay old DMs, so once a
 * request/response has been seen once, the DB is the source of truth, not the relay.
 */
object ReservationService {
    private val json = Json { ignoreUnknownKeys = true }

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
                note = note,
                buyerChar = buyerChar,
                requestedAt = System.currentTimeMillis() / 1000,
            )
        }
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
        val order =
            withContext(Dispatchers.IO) {
                NostrOrderDao.getByCoordinate(reservation.orderUuid, reservation.sellerPubkey)
            } ?: return false

        val nowSec = System.currentTimeMillis() / 1000
        val reservedQty = if (accept) reservation.qty else null
        val holdUntil = if (accept) nowSec + holdHours * 3600 else null
        val contactChar = if (accept) order.traderChar else ""
        val payload =
            ReservationResponse(
                tradeId = reservation.tradeId,
                accepted = accept,
                reservedQty = reservedQty,
                holdUntil = holdUntil,
                contactChar = contactChar,
            )
        if (!sendDm(identity, reservation.buyerPubkey, json.encodeToString(payload))) return false

        withContext(Dispatchers.IO) {
            NostrReservationDao.updateResponse(reservation.tradeId, accept, reservedQty, holdUntil, contactChar, nowSec)
        }
        if (accept) {
            OrderRepository.setRemainingQty(order, (order.qtyRemaining - reservation.qty).coerceAtLeast(0))
        }
        return true
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
        }
        return restored != null
    }

    /** Called by [NostrRelayManager] for every DM it unwraps — routes it to the right local-DB update. */
    suspend fun handleIncomingDm(
        fromPubkey: String,
        content: String,
    ) {
        val type =
            runCatching { json.parseToJsonElement(content).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
                ?: return
        when (type) {
            "reservation_request" -> {
                val req = runCatching { json.decodeFromString<ReservationRequest>(content) }.getOrNull() ?: return
                withContext(Dispatchers.IO) {
                    NostrReservationDao.insertRequestIfAbsent(
                        tradeId = req.tradeId,
                        orderUuid = req.orderId,
                        orderPubkey = req.orderPubkey,
                        buyerPubkey = fromPubkey,
                        sellerPubkey = req.orderPubkey,
                        role = "seller",
                        qty = req.qty,
                        note = req.note,
                        buyerChar = req.buyerChar,
                        requestedAt = System.currentTimeMillis() / 1000,
                    )
                }
            }
            "reservation_response" -> {
                val resp = runCatching { json.decodeFromString<ReservationResponse>(content) }.getOrNull() ?: return
                withContext(Dispatchers.IO) {
                    NostrReservationDao.updateResponse(
                        resp.tradeId,
                        resp.accepted,
                        resp.reservedQty,
                        resp.holdUntil,
                        resp.contactChar,
                        System.currentTimeMillis() / 1000,
                    )
                }
            }
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
