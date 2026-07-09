package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.eventt.core.database.NostrOrderDao
import org.eventt.core.database.NostrOrderModel

data class OrderFilter(
    val typeId: Int? = null,
    val regionId: Int? = null,
    val side: OrderSide? = null,
)

private fun NostrOrderModel.toParsedOrder(): ParsedOrder =
    ParsedOrder(
        orderUuid = orderUuid,
        pubkey = pubkey,
        eventId = eventId,
        createdAt = createdAt,
        side = OrderSide.valueOf(side.uppercase()),
        typeId = typeId,
        regionId = regionId,
        price = price,
        qtyTotal = qtyTotal,
        qtyRemaining = qtyRemaining,
        minLot = minLot,
        minLotUnit = MinLotUnit.valueOf(minLotUnit.uppercase()),
        traderChar = traderChar,
        expiration = expiration,
    )

/** Read/write facade over P2P Market orders — screens go through this, never the DAO directly. */
object OrderRepository {
    /**
     * Emits the current local DB snapshot immediately (so Browse has something to show before
     * any relay round-trip completes), then re-emits every time [NostrRelayManager] persists a
     * newer order revision — collectors don't poll, they just react to the shared relay-event flow.
     */
    fun browse(filter: OrderFilter = OrderFilter()): Flow<List<NostrOrderModel>> =
        flow {
            suspend fun query() =
                withContext(Dispatchers.IO) {
                    NostrOrderDao.queryActive(filter.typeId, filter.regionId, filter.side?.name?.lowercase())
                }
            emit(query())
            NostrRelayManager.events.collect { emit(query()) }
        }

    /** Null if there's no active identity to sign with. */
    suspend fun postNewOrder(draft: OrderDraft): ParsedOrder? {
        val identity = NostrIdentityService.getActiveIdentity() ?: return null
        val event = NostrEventFactory.buildOrderEvent(QuartzGateway.signerFor(identity.keyPair), draft)
        return persistAndPublish(event, identity.pubkey)
    }

    /** Refreshes the expiration to +2 weeks from now, keeping the current qty_remaining. Null if [order] isn't ours. */
    suspend fun renewOrder(order: NostrOrderModel): ParsedOrder? =
        republish(order) { signer, parsed -> NostrEventFactory.republishOrder(signer, parsed, renew = true) }

    /** Republishes with a new qty_remaining — this is also the reservation-confirmation step once a DM handshake lands in a later phase. Null if [order] isn't ours. */
    suspend fun setRemainingQty(
        order: NostrOrderModel,
        newQtyRemaining: Long,
    ): ParsedOrder? =
        republish(order) { signer, parsed ->
            NostrEventFactory.republishOrder(signer, parsed, newQtyRemaining = newQtyRemaining)
        }

    /** Cancelling is just republishing with qty_remaining = 0 — there's no separate "cancelled" protocol state. */
    suspend fun cancelOrder(order: NostrOrderModel): ParsedOrder? = setRemainingQty(order, 0)

    private suspend fun republish(
        order: NostrOrderModel,
        buildEvent: (NostrSignerSync, ParsedOrder) -> Event,
    ): ParsedOrder? {
        // Resolves the identity that actually owns this order, not whichever one is currently
        // "active" — renewing/cancelling an order posted by a different one of your own
        // characters shouldn't require switching your active trader first.
        val identity = NostrIdentityService.getIdentityByPubkey(order.pubkey) ?: return null
        val event = buildEvent(QuartzGateway.signerFor(identity.keyPair), order.toParsedOrder())
        return persistAndPublish(event, identity.pubkey)
    }

    private suspend fun persistAndPublish(
        event: Event,
        myPubkey: String,
    ): ParsedOrder? {
        val parsed = NostrEventFactory.parseOrderEvent(event) ?: return null
        withContext(Dispatchers.IO) {
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
        }
        // Fire-and-forget from the caller's perspective — the local DB write above already made
        // the poster's own order visible to them immediately, without waiting on any relay.
        NostrRelayManager.publish(event)
        return parsed
    }
}
