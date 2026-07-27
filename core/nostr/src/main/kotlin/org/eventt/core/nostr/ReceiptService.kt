package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.database.NostrOrderModel
import org.eventt.core.database.NostrReceiptDao
import org.eventt.core.database.NostrReservationModel
import org.eventt.core.database.P2pAttributionDefault
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.TransactionAttribution
import org.eventt.core.database.WalletDao
import org.eventt.core.database.syntheticTransactionId
import java.time.Instant

// Checked against github.com/nostr-protocol/nips (2026-07-09) — 7733 is unassigned, same check as
// ORDER_KIND in NostrEventFactory.
const val RECEIPT_KIND = 7733

/**
 * One synthetic wallet transaction id per (tradeId, role) — buyer and seller each book their own
 * side of the same trade. See [syntheticTransactionId] for why it's negative/deterministic.
 */
fun p2pTransactionId(
    tradeId: String,
    role: String,
): Long = syntheticTransactionId(tradeId, role)

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
            val myCharacterId = if (reservation.role == "buyer") reservation.buyerCharacterId else reservation.contactCharacterId
            val attribution = P2pAttributionDefault.get() ?: myCharacterId?.let { TransactionAttribution.Character(it) }
            attribution?.let { bookCostBasisEntry(reservation, it) }
        }
        // Same fire-and-forget shape as OrderRepository.persistAndPublish — the local write above
        // already makes the receipt count toward our own reputation view without a relay round-trip.
        NostrRelayManager.publish(event)
        return true
    }

    /**
     * Writes (or overwrites) this side's synthetic wallet transaction for [reservation] under
     * [attribution] — feeds CostBasisService's FIFO cost basis exactly like a real ESI market
     * transaction would, since ESI has no visibility into a P2P deal at all. Called automatically
     * from [publish] on every completion, and again from the P2P Inbox whenever the user first
     * assigns or reassigns a trade's attribution — [WalletDao.insertTransaction] is INSERT OR
     * REPLACE, so both paths safely converge on the same row.
     *
     * [fallbackOrder] backfills price/type_id/side for a reservation completed before those were
     * snapshotted (a pre-existing row reads price=0/type_id=0/order_side="", the migration's
     * default) — an order's type_id and side never change across edits, and its *current* price
     * is the best approximation available for a trade whose real price was never recorded.
     * Returns false (nothing written) only if neither the snapshot nor the fallback has a usable
     * price/type/side.
     *
     * [dateIfNew] only applies the first time this trade is booked — a re-attribution later
     * (Inbox) keeps whichever date the row already has instead of drifting to "now"/[dateIfNew]
     * on every edit.
     */
    fun bookCostBasisEntry(
        reservation: NostrReservationModel,
        attribution: TransactionAttribution,
        fallbackOrder: NostrOrderModel? = null,
        dateIfNew: String = Instant.now().toString(),
    ): Boolean {
        val typeId = reservation.typeId.takeIf { it > 0 } ?: fallbackOrder?.typeId ?: return false
        val price = reservation.price.takeIf { it > 0 } ?: fallbackOrder?.price ?: return false
        val orderSide = reservation.orderSide.takeIf { it.isNotBlank() } ?: fallbackOrder?.side ?: return false
        val counterpartyChar = if (reservation.role == "buyer") reservation.contactChar else reservation.buyerChar
        val transactionId = p2pTransactionId(reservation.tradeId, reservation.role)
        val date = WalletDao.getTransactionDate(transactionId) ?: dateIfNew
        // `role` is who sent the request vs who owns the order, not who's buying/selling the
        // *item* — those only match on a sell order. On a buy order they're inverted: the
        // requester is supplying the item (a sale for them), the order owner is paying for it
        // (a purchase for them). See the order_side migration comment in DatabaseManager.
        val isBuy = (orderSide == "sell") == (reservation.role == "buyer")

        WalletDao.insertTransaction(
            transactionId = transactionId,
            date = date,
            typeId = typeId,
            typeName = StaticDataDao.getTypeById(typeId)?.name ?: "",
            quantity = reservation.qty.toInt(),
            unitPrice = price,
            total = price * reservation.qty,
            isBuy = isBuy,
            clientId = 0,
            clientName = counterpartyChar,
            locationId = 0,
            locationName = "P2P Market",
            isCorp = attribution is TransactionAttribution.Corporation,
            characterId = (attribution as? TransactionAttribution.Character)?.characterId,
            corporationId = (attribution as? TransactionAttribution.Corporation)?.corporationId,
        )
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
