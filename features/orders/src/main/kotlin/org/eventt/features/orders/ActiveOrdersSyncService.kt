package org.eventt.features.orders

import org.eventt.core.database.ActiveOrderDao
import org.eventt.core.database.CharacterDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.AppLog

/**
 * Pulls one character's/corp's active order book from ESI into the local `active_orders` cache —
 * the same fetch → [mergeRelistStats] → replace logic OrdersScreen's own refresh uses ([parseOrder],
 * [CharacterOrder.toActiveOrderRecord]), just runnable for every local character/corp at once
 * rather than only whichever one happens to be on screen.
 *
 * Without this, a character nobody has opened the Orders tab for recently keeps showing
 * long-since-filled/cancelled orders forever — nothing else ever removes a stale row from
 * `active_orders`, since [MarketWatchService]'s own sweep only reads the cache, it doesn't refresh
 * it from ESI. That stale-forever cache is exactly what made the OBS overlay's buy/sell order
 * counts (issue: reads [ActiveOrderDao.getAll] directly) show orders that were no longer real.
 */
object ActiveOrdersSyncService {
    fun syncCharacter(charId: Int) = sync(characterId = charId, corporationId = null, actingCharId = charId)

    fun syncCorporation(
        corpId: Int,
        actingCharId: Int,
    ) = sync(characterId = null, corporationId = corpId, actingCharId = actingCharId)

    /** Every local character, then one acting character per corp (corp orders are shared, not per-member). */
    fun syncAll() {
        val characters = CharacterDao.getAll()
        characters.forEach { char -> runCatching { syncCharacter(char.id) } }
        characters
            .mapNotNull { it.corporationId }
            .distinct()
            .forEach { corpId ->
                val actingId = characters.first { it.corporationId == corpId }.id
                runCatching { syncCorporation(corpId, actingId) }
            }
    }

    private fun sync(
        characterId: Int?,
        corporationId: Int?,
        actingCharId: Int,
    ) {
        try {
            val raw =
                if (corporationId != null) {
                    EsiClient.getCorporationOrders(corporationId, actingCharId)
                } else {
                    EsiClient.getCharacterOrders(actingCharId).filter { (it["is_corporation"] as? Boolean) != true }
                }
            val freshlyParsed = raw.map { m -> parseOrder(m, if (corporationId != null) (m["issued_by"] as? Number)?.toInt() else null) }
            val ordersEndpoint = if (corporationId != null) "/corporations/$corporationId/orders/" else "/characters/$actingCharId/orders/"
            val fetchAsOf = EsiClient.getEndpointLastModifiedMillis(ordersEndpoint)
            val previousSnapshot =
                ActiveOrderDao
                    .getAll(
                        characterId = characterId,
                        corporationId = corporationId,
                    ).associateBy { it.orderId }
            val parsed = mergeRelistStats(previousSnapshot, freshlyParsed, fetchAsOf)

            if (corporationId != null) {
                // Stored per placing character, not a flat corp-wide scope — see ActiveOrderDao.replaceCorpOrders.
                val byPlacer = parsed.mapNotNull { o -> o.issuedByCharId?.let { placer -> o.toActiveOrderRecord(placer, corporationId) } }
                ActiveOrderDao.replaceCorpOrders(corporationId, byPlacer)
            } else {
                ActiveOrderDao.replaceAll(actingCharId, parsed.map { it.toActiveOrderRecord(actingCharId, null) })
            }
        } catch (e: Exception) {
            AppLog.warn("ActiveOrdersSync", "orders ESI: ${e.message}")
        }
    }
}
