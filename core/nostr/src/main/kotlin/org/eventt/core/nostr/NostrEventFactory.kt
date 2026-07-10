package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip40Expiration.expiration
import java.util.UUID

// Checked against github.com/nostr-protocol/nips (2026-07-09) — 30735 is unassigned, and the
// Phase 0 spike confirmed it doesn't collide with any kind Quartz itself has registered.
const val ORDER_KIND = 30735

private const val TWO_WEEKS_SECONDS = 14L * 24 * 3600

// Leading zero bits required of the mined event id — cheap enough to stay near-instant
// (order of tens of milliseconds) while still satisfying relays with a low minimum-PoW policy.
// Temporarily 0 (disabled) — QuartzGateway.signEvent skips mining entirely at 0.
private const val ORDER_POW_DIFFICULTY = 0

enum class OrderSide { BUY, SELL }

enum class MinLotUnit { UNITS, ISK }

data class OrderDraft(
    val side: OrderSide,
    val typeId: Int,
    val regionId: Int,
    val price: Double,
    val qtyTotal: Long,
    val minLot: Long,
    val minLotUnit: MinLotUnit,
    val traderChar: String,
    // The trader's real EVE character ID, when the posting identity is backed by one — carried
    // over Nostr alongside the display name so viewers' Show Info button can open the right
    // character directly instead of re-resolving trader_char through ESI (which can't
    // disambiguate two characters sharing a name, and needs a network round-trip at all).
    val traderCharId: Int? = null,
)

data class ParsedOrder(
    val orderUuid: String,
    val pubkey: String,
    val eventId: String,
    val createdAt: Long,
    val side: OrderSide,
    val typeId: Int,
    val regionId: Int,
    val price: Double,
    val qtyTotal: Long,
    val qtyRemaining: Long,
    val minLot: Long,
    val minLotUnit: MinLotUnit,
    val traderChar: String,
    val traderCharId: Int?,
    val expiration: Long,
)

/**
 * Builds and parses P2P Market order events. Orders are NIP-33 addressable events (same `kind` +
 * `d` tag = latest-created_at-wins on every relay) — updating qty_remaining or renewing before
 * expiration is just republishing with a fresh created_at, never a new order_uuid. Only the
 * order's own privkey holder can produce a valid replacement, which is what makes "seller is sole
 * arbiter of remaining volume" race-free without needing any consensus mechanism.
 */
object NostrEventFactory {
    fun buildOrderEvent(
        signer: NostrSignerSync,
        draft: OrderDraft,
        orderUuid: String = UUID.randomUUID().toString(),
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): Event =
        QuartzGateway.signEvent(
            signer,
            createdAt,
            ORDER_KIND,
            buildTags(
                orderUuid,
                draft.side,
                draft.typeId,
                draft.regionId,
                draft.price,
                draft.qtyTotal,
                draft.qtyTotal,
                draft.minLot,
                draft.minLotUnit,
                draft.traderChar,
                draft.traderCharId,
                createdAt + TWO_WEEKS_SECONDS,
            ),
            "",
            powDifficulty = ORDER_POW_DIFFICULTY,
        )

    /**
     * Republishes an existing order under the same order_uuid with a new qty_remaining and/or a
     * refreshed expiration (manual Renew) — this republish IS the reservation-confirmation,
     * cancel (qtyRemaining=0), and renew mechanism; there's no separate protocol message for any
     * of them.
     */
    fun republishOrder(
        signer: NostrSignerSync,
        previous: ParsedOrder,
        newQtyRemaining: Long = previous.qtyRemaining,
        renew: Boolean = false,
    ): Event {
        val createdAt = System.currentTimeMillis() / 1000
        val expirationAt = if (renew) createdAt + TWO_WEEKS_SECONDS else previous.expiration
        return QuartzGateway.signEvent(
            signer,
            createdAt,
            ORDER_KIND,
            buildTags(
                previous.orderUuid,
                previous.side,
                previous.typeId,
                previous.regionId,
                previous.price,
                previous.qtyTotal,
                newQtyRemaining,
                previous.minLot,
                previous.minLotUnit,
                previous.traderChar,
                previous.traderCharId,
                expirationAt,
            ),
            "",
            powDifficulty = ORDER_POW_DIFFICULTY,
        )
    }

    private fun buildTags(
        orderUuid: String,
        side: OrderSide,
        typeId: Int,
        regionId: Int,
        price: Double,
        qtyTotal: Long,
        qtyRemaining: Long,
        minLot: Long,
        minLotUnit: MinLotUnit,
        traderChar: String,
        traderCharId: Int?,
        expirationAt: Long,
    ): Array<Array<String>> {
        val builder =
            TagArrayBuilder<Event>()
                .add(DTag(orderUuid).toTagArray())
                .add(arrayOf("t", "eventt-p2pmarket"))
                .add(arrayOf("t", "side:${side.name.lowercase()}"))
                .add(arrayOf("t", "type:$typeId"))
                .add(arrayOf("t", "region:$regionId"))
                .expiration(expirationAt)
                .add(arrayOf("price", price.toString()))
                .add(arrayOf("qty_total", qtyTotal.toString()))
                .add(arrayOf("qty_remaining", qtyRemaining.toString()))
                .add(arrayOf("min_lot", minLot.toString()))
                .add(arrayOf("min_lot_unit", minLotUnit.name.lowercase()))
                .add(arrayOf("trader_char", traderChar))
        traderCharId?.let { builder.add(arrayOf("trader_char_id", it.toString())) }
        return builder.build()
    }

    /** Defensive parse — a malformed/unknown-shape event from another client is skipped (null), never crashes the app. */
    fun parseOrderEvent(event: Event): ParsedOrder? {
        if (event.kind != ORDER_KIND) return null
        return runCatching {
            val tagsByName = event.tags.filter { it.size >= 2 }.groupBy({ it[0] }, { it[1] })

            fun tag(name: String) = tagsByName[name]?.firstOrNull()
            val tTags = tagsByName["t"].orEmpty()

            ParsedOrder(
                orderUuid = event.dTag(),
                pubkey = event.pubKey,
                eventId = event.id,
                createdAt = event.createdAt,
                side = OrderSide.valueOf(requireNotNull(tTags.firstOrNull { it.startsWith("side:") }).removePrefix("side:").uppercase()),
                typeId = requireNotNull(tTags.firstOrNull { it.startsWith("type:") }).removePrefix("type:").toInt(),
                regionId = requireNotNull(tTags.firstOrNull { it.startsWith("region:") }).removePrefix("region:").toInt(),
                price = requireNotNull(tag("price")).toDouble(),
                qtyTotal = requireNotNull(tag("qty_total")).toLong(),
                qtyRemaining = requireNotNull(tag("qty_remaining")).toLong(),
                minLot = requireNotNull(tag("min_lot")).toLong(),
                minLotUnit = MinLotUnit.valueOf(requireNotNull(tag("min_lot_unit")).uppercase()),
                traderChar = tag("trader_char") ?: "",
                traderCharId = tag("trader_char_id")?.toIntOrNull(),
                expiration = requireNotNull(event.expiration()),
            )
        }.getOrNull()
    }
}
