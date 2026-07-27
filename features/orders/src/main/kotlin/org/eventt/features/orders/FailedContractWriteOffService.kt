package org.eventt.features.orders

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.database.ContractDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.WalletDao
import org.eventt.core.database.syntheticTransactionId
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.ContractModel
import java.time.Instant

data class FailedContractLine(
    val contract: ContractModel,
    val typeId: Int,
    val typeName: String,
    val quantity: Int,
    // This line's share of the contract's collateral, allocated by value (qty × cost basis)
    // across every line in the same contract — a courier contract's collateral is one lump sum
    // for the whole shipment, not per item, so a cheap line and an expensive line in the same
    // contract must not just split it evenly.
    val unitPrice: Double,
)

/**
 * Finds failed courier contracts you issued (lost cargo, not one you were just hired to haul)
 * and books each item as a synthetic wallet sale — same `transactions` table and FIFO engine as
 * everything else, so it doesn't matter whether the lost item was originally bought on the
 * market or via P2P. Priced at the contract's collateral (what ESI actually pays back on
 * failure), not zero — a total loss only when there was no collateral to begin with.
 */
object FailedContractWriteOffService {
    suspend fun findWriteOffs(
        characterId: Int?,
        corporationId: Int?,
    ): List<FailedContractLine> =
        withContext(Dispatchers.IO) {
            val failedCourierContracts =
                ContractDao
                    .getByStatus("failed", characterId, corporationId)
                    .filter { it.type == "courier" }
                    // A "failed" contract you were just hired to haul isn't your own lost cargo —
                    // only the issuer's own goods (and collateral refund) are affected.
                    .filter { characterId == null || it.issuerId == characterId }

            val costBasisByType = CostBasisService.compute(characterId, corporationId).inventory.mapValues { it.value.avgCostBasis }

            failedCourierContracts.flatMap { contract ->
                val rawItems = runCatching { EsiClient.getContractItems(contract.contractId) }.getOrDefault(emptyList())
                val items =
                    rawItems.mapNotNull { raw ->
                        if ((raw["is_included"] as? Boolean) == false) return@mapNotNull null
                        val typeId = (raw["type_id"] as? Number)?.toInt() ?: return@mapNotNull null
                        val qty = (raw["quantity"] as? Number)?.toInt() ?: return@mapNotNull null
                        typeId to qty
                    }
                if (items.isEmpty()) return@flatMap emptyList()

                // Weight by known cost basis where available; a type with no known cost basis
                // still gets an even (weight 1) share rather than being valued at zero.
                val weights = items.map { (typeId, qty) -> qty * (costBasisByType[typeId]?.takeIf { it > 0 } ?: 1.0) }
                val totalWeight = weights.sum()

                items.mapIndexedNotNull { i, (typeId, qty) ->
                    val lineCollateral = if (totalWeight > 0) contract.collateral * (weights[i] / totalWeight) else 0.0
                    val line =
                        FailedContractLine(
                            contract = contract,
                            typeId = typeId,
                            typeName = StaticDataDao.getTypeById(typeId)?.name ?: "Type #$typeId",
                            quantity = qty,
                            unitPrice = lineCollateral / qty,
                        )
                    val alreadyWrittenOff = WalletDao.getTransactionDate(syntheticTransactionId(contract.contractId, typeId)) != null
                    if (alreadyWrittenOff) null else line
                }
            }
        }

    suspend fun writeOff(line: FailedContractLine) {
        withContext(Dispatchers.IO) {
            val contract = line.contract
            WalletDao.insertTransaction(
                transactionId = syntheticTransactionId(contract.contractId, line.typeId),
                date = contract.dateCompleted ?: contract.dateExpired.ifBlank { Instant.now().toString() },
                typeId = line.typeId,
                typeName = line.typeName,
                quantity = line.quantity,
                unitPrice = line.unitPrice,
                total = line.unitPrice * line.quantity,
                isBuy = false,
                clientId = 0,
                clientName = "Failed courier contract" + (contract.title.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
                locationId = contract.startStationId,
                locationName = "",
                isCorp = contract.isCorp,
                characterId = contract.characterId,
                corporationId = contract.corporationId,
            )
        }
    }
}
