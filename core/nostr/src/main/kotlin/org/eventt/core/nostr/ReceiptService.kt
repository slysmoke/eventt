package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.database.NostrReceiptDao
import org.eventt.core.database.NostrReservationModel

// Checked against github.com/nostr-protocol/nips (2026-07-09) — 7733 is unassigned, same check as
// ORDER_KIND in NostrEventFactory.
const val RECEIPT_KIND = 7733

/**
 * Publishes and ingests kind-[RECEIPT_KIND] trade receipts — the "I did this trade" attestation
 * each side fires once a reservation completes. Reputation ([ReputationAggregator]) only counts a
 * trade once BOTH sides' receipts exist locally, so a receipt alone proves nothing; it's the
 * *pair* that's meaningful. There is no reservation-status gate here beyond what
 * [ReservationService.markCompleted] already enforces — this object only knows how to build,
 * sign, publish, and parse the event itself.
 */
object ReceiptService {
    /**
     * Signs+publishes a receipt for [reservation] from this side's identity — resolved from
     * [reservation]'s own buyer/seller pubkey for its `role`, not whichever identity happens to be
     * currently active, so completing a trade never depends on switching your active trader first.
     * False if we don't hold that identity.
     */
    suspend fun publish(reservation: NostrReservationModel): Boolean {
        val myPubkey = if (reservation.role == "buyer") reservation.buyerPubkey else reservation.sellerPubkey
        val identity = NostrIdentityService.getIdentityByPubkey(myPubkey) ?: return false
        val counterpartyPubkey = if (reservation.role == "buyer") reservation.sellerPubkey else reservation.buyerPubkey
        val orderCoordinate = "$ORDER_KIND:${reservation.orderPubkey}:${reservation.orderUuid}"
        val createdAt = System.currentTimeMillis() / 1000
        val signer = QuartzGateway.signerFor(identity.keyPair)
        val tags =
            arrayOf(
                arrayOf("trade_id", reservation.tradeId),
                arrayOf("p", counterpartyPubkey),
                arrayOf("a", orderCoordinate),
                arrayOf("role", reservation.role),
                arrayOf("qty", reservation.qty.toString()),
            )
        val event = QuartzGateway.signEvent(signer, createdAt, RECEIPT_KIND, tags, "")

        withContext(Dispatchers.IO) {
            NostrReceiptDao.insertIfAbsent(
                eventId = event.id,
                tradeId = reservation.tradeId,
                orderCoordinate = orderCoordinate,
                authorPubkey = identity.pubkey,
                counterpartyPubkey = counterpartyPubkey,
                role = reservation.role,
                qty = reservation.qty,
                createdAt = createdAt,
                rawEventJson = event.toJson(),
            )
        }
        // Same fire-and-forget shape as OrderRepository.persistAndPublish — the local write above
        // already makes the receipt count toward our own reputation view without a relay round-trip.
        NostrRelayManager.publish(event)
        return true
    }

    /** Called by [NostrRelayManager] for every kind-[RECEIPT_KIND] event it receives. Defensive parse, never throws. */
    fun handleIncomingReceipt(event: Event) {
        if (event.kind != RECEIPT_KIND) return
        runCatching {
            val tagsByName = event.tags.filter { it.size >= 2 }.groupBy({ it[0] }, { it[1] })

            fun tag(name: String) = requireNotNull(tagsByName[name]?.firstOrNull())
            NostrReceiptDao.insertIfAbsent(
                eventId = event.id,
                tradeId = tag("trade_id"),
                orderCoordinate = tag("a"),
                authorPubkey = event.pubKey,
                counterpartyPubkey = tag("p"),
                role = tag("role"),
                qty = tag("qty").toLong(),
                createdAt = event.createdAt,
                rawEventJson = event.toJson(),
            )
        }
    }
}
