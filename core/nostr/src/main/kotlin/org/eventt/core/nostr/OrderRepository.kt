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

// Client-side posting limit — this app's own guard against one identity flooding Browse with
// new orders. It can't stop spam posted by other Nostr clients, but it does stop this one from
// being (or becoming) the source of it.
private const val MAX_NEW_ORDERS_PER_WINDOW = 10
private const val POST_RATE_WINDOW_SECONDS = 3600L

sealed class PostOrderResult {
    data class Posted(
        val order: ParsedOrder,
    ) : PostOrderResult()

    data object NoIdentity : PostOrderResult()

    /** Hit [MAX_NEW_ORDERS_PER_WINDOW] new-order posts within [POST_RATE_WINDOW_SECONDS]. */
    data object RateLimited : PostOrderResult()
}

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
        traderCharId = traderCharId,
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

    suspend fun postNewOrder(draft: OrderDraft): PostOrderResult {
        val identity = NostrIdentityService.getActiveIdentity() ?: return PostOrderResult.NoIdentity
        val recentPosts = withContext(Dispatchers.IO) { NostrOrderDao.countRecentPosts(identity.pubkey, POST_RATE_WINDOW_SECONDS) }
        if (recentPosts >= MAX_NEW_ORDERS_PER_WINDOW) return PostOrderResult.RateLimited

        val event = NostrEventFactory.buildOrderEvent(QuartzGateway.signerFor(identity.keyPair), draft)
        val parsed = persistAndPublish(event, identity.pubkey) ?: return PostOrderResult.NoIdentity
        withContext(Dispatchers.IO) { NostrOrderDao.recordPost(identity.pubkey) }
        return PostOrderResult.Posted(parsed)
    }

    /** Refreshes the expiration to +2 weeks from now, keeping the current qty_remaining. Null if [order] isn't ours. */
    suspend fun renewOrder(order: NostrOrderModel): ParsedOrder? =
        republish(order) { signer, parsed -> NostrEventFactory.republishOrder(signer, parsed, renew = true) }

    /**
     * Changes price and/or how much is left available on an existing order — [newQtyRemaining]
     * is what the seller/buyer actually has on hand right now, matching the "remaining" figure
     * shown everywhere else in the UI (Browse/My Orders' qty column), not the order's original
     * qtyTotal. Qty already spoken for by a confirmed reservation (qtyTotal - qtyRemaining)
     * carries over untouched — qtyTotal is derived as [newQtyRemaining] plus that held-back
     * amount, so the reservation's own accounting never gets disturbed. Null if [order] isn't ours.
     */
    suspend fun editOrder(
        order: NostrOrderModel,
        newPrice: Double,
        newQtyRemaining: Long,
    ): ParsedOrder? =
        republish(order) { signer, parsed ->
            val alreadyReserved = parsed.qtyTotal - parsed.qtyRemaining
            val newQtyTotal = newQtyRemaining + alreadyReserved
            NostrEventFactory.republishOrder(
                signer,
                parsed,
                newPrice = newPrice,
                newQtyTotal = newQtyTotal,
                newQtyRemaining = newQtyRemaining,
            )
        }

    /** Republishes with a new qty_remaining — this is also the reservation-confirmation step once a DM handshake lands in a later phase. Null if [order] isn't ours. */
    suspend fun setRemainingQty(
        order: NostrOrderModel,
        newQtyRemaining: Long,
    ): ParsedOrder? =
        republish(order) { signer, parsed ->
            NostrEventFactory.republishOrder(signer, parsed, newQtyRemaining = newQtyRemaining)
        }

    /** Cancelling is just republishing with qty_remaining = 0 — there's no separate "cancelled" protocol state. */
    suspend fun cancelOrder(order: NostrOrderModel): ParsedOrder? {
        val cancelled = setRemainingQty(order, 0) ?: return null
        // Best-effort NIP-09 cleanup on top of the tombstone (see buildDeletionEvent) — if a relay
        // ignores it, the qty_remaining=0 revision above still supersedes the order everywhere.
        NostrIdentityService.getIdentityByPubkey(order.pubkey)?.let { identity ->
            NostrRelayManager.publish(NostrEventFactory.buildDeletionEvent(QuartzGateway.signerFor(identity.keyPair), cancelled))
        }
        return cancelled
    }

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
                    traderCharId = parsed.traderCharId,
                    expiration = parsed.expiration,
                    rawEventJson = event.toJson(),
                    isMine = parsed.pubkey == myPubkey,
                ),
            )
        }
        // The DB write alone doesn't make this visible anywhere — browse()'s Flow only re-queries
        // when a NostrRelayEvent fires, and publish() below doesn't emit one itself (only an
        // incoming relay echo of this same event would, which may be slow or may never happen).
        NostrRelayManager.notifyOrderUpdated(parsed)
        // Fire-and-forget from the caller's perspective — the local DB write above already made
        // the poster's own order visible to them immediately, without waiting on any relay.
        NostrRelayManager.publish(event)
        return parsed
    }
}
