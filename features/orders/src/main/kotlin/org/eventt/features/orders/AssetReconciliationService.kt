package org.eventt.features.orders

import org.eventt.core.database.AssetDao
import org.eventt.core.database.ContractDao
import org.eventt.features.assets.fetchCharacterAssets
import org.eventt.features.assets.fetchCorporationAssets

// Cross-checks FIFO's "should still be holding this" quantities against what's actually sitting
// in the character's/corp's ESI assets -- catches exactly the drift a manual write-off (see
// InventoryAdjustmentDao) is meant to fix: cargo lost in transit, or bought under one entity and
// sold/transferred under another. Always refreshes the assets snapshot first, since a stale one
// would just compare FIFO against itself from the last time assets happened to be viewed.
//
// Raw ESI assets alone isn't the full "still yours" picture, though: listing an item removes it
// from /assets/ into the order book's escrow, and issuing a contract (courier or item-exchange)
// removes it into that contract's escrow -- neither is a loss, so both are added back before
// comparing. A contract that's actually failed (courier ganked, etc.) is deliberately NOT added
// back: that cargo is genuinely gone, and surfacing it as a shortfall is the whole point.
object AssetReconciliationService {
    data class Discrepancy(
        val typeId: Int,
        val typeName: String,
        val fifoQty: Int,
        val actualQty: Int,
    ) {
        val shortfall: Int get() = fifoQty - actualQty
    }

    private val IN_FLIGHT_CONTRACT_STATUSES = setOf("outstanding", "in_progress")

    internal suspend fun reconcile(
        inventory: Map<Int, CostBasisService.InventoryItem>,
        sellOrders: List<CharacterOrder>,
        characterId: Int?,
        corporationId: Int?,
        actingCharId: Int,
    ): List<Discrepancy> {
        val assets =
            when {
                characterId != null -> {
                    fetchCharacterAssets(characterId)
                    AssetDao.getByCharacter(characterId)
                }

                corporationId != null -> {
                    fetchCorporationAssets(corporationId, actingCharId)
                    AssetDao.getByCorporation(corporationId)
                }

                else -> {
                    return emptyList()
                }
            }
        val heldByType = assets.groupBy { it.typeId }.mapValues { (_, rows) -> rows.sumOf { it.quantity } }

        val listedByType =
            sellOrders
                .filter { it.state == "active" }
                .groupBy { it.typeId }
                .mapValues { (_, orders) -> orders.sumOf { it.volumeRemaining } }

        val inTransitByType =
            ContractDao
                .getAll(characterId, corporationId)
                .filter { it.status in IN_FLIGHT_CONTRACT_STATUSES }
                .filter { c -> if (characterId != null) c.issuerId == characterId else c.forCorp && c.issuerCorpId == corporationId }
                .flatMap { ContractDao.getItemsForContract(it.contractId) }
                .filter { it.isIncluded }
                .groupBy { it.typeId }
                .mapValues { (_, items) -> items.sumOf { it.quantity } }

        fun actualFor(typeId: Int) = (heldByType[typeId] ?: 0) + (listedByType[typeId] ?: 0) + (inTransitByType[typeId] ?: 0)

        // Only a shortfall (FIFO says more than accounted-for) is a write-off candidate. A surplus
        // (manufactured, gifted, or otherwise acquired outside the market) isn't a FIFO integrity
        // problem, so it's not flagged here.
        return inventory.values
            .mapNotNull { item ->
                val actual = actualFor(item.typeId)
                if (actual < item.remainingQty) Discrepancy(item.typeId, item.typeName, item.remainingQty, actual) else null
            }.sortedByDescending { it.shortfall }
    }
}
