package org.eventt.features.orders

import org.eventt.core.database.AssetDao
import org.eventt.features.assets.fetchCharacterAssets
import org.eventt.features.assets.fetchCorporationAssets

// Cross-checks FIFO's "should still be holding this" quantities against what's actually sitting
// in the character's/corp's ESI assets -- catches exactly the drift a manual write-off (see
// InventoryAdjustmentDao) is meant to fix: cargo lost in transit, or bought under one entity and
// sold/transferred under another. Always refreshes the assets snapshot first, since a stale one
// would just compare FIFO against itself from the last time assets happened to be viewed.
object AssetReconciliationService {
    data class Discrepancy(
        val typeId: Int,
        val typeName: String,
        val fifoQty: Int,
        val actualQty: Int,
    ) {
        val shortfall: Int get() = fifoQty - actualQty
    }

    suspend fun reconcile(
        inventory: Map<Int, CostBasisService.InventoryItem>,
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
        val actualByType = assets.groupBy { it.typeId }.mapValues { (_, rows) -> rows.sumOf { it.quantity } }

        // Only a shortfall (FIFO says more than assets show) is a write-off candidate. A surplus
        // (manufactured, gifted, or otherwise acquired outside the market) isn't a FIFO integrity
        // problem, so it's not flagged here.
        return inventory.values
            .mapNotNull { item ->
                val actual = actualByType[item.typeId] ?: 0
                if (actual < item.remainingQty) Discrepancy(item.typeId, item.typeName, item.remainingQty, actual) else null
            }.sortedByDescending { it.shortfall }
    }
}
