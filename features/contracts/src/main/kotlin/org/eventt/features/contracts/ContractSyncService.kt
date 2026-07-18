package org.eventt.features.contracts

import org.eventt.core.database.ContractDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.ContractModel

/**
 * Fetches + upserts one character's or one corporation's contracts from ESI. Shared by the
 * Contracts screen's manual refresh button and [ContractWatchService]'s background sweep so both
 * stay in sync and agree on what counts as a status change.
 */
object ContractSyncService {
    /**
     * Returns how many of the refreshed contracts had a locally-stored status different from what
     * ESI now reports — used to drive the "contract changed" nav badge (see [ContractWatchService]).
     */
    fun refresh(
        characterId: Int?,
        corporationId: Int?,
        actingCharId: Int,
    ): Int {
        val isCorp = corporationId != null
        val raw =
            if (isCorp) {
                EsiClient.getCorporationContracts(corporationId, actingCharId)
            } else {
                // A character's own contracts endpoint also returns ones they placed for their
                // corp (for_corporation=true) -- those belong to the corp's own list, not this
                // character's personal one, or the corp/character syncs would flip-flop the same
                // contract_id (the table's primary key) between scopes depending on whichever ran last.
                EsiClient.getCharacterContracts(characterId!!).filter { (it["for_corporation"] as? Boolean) != true }
            }

        val previousStatus =
            ContractDao.getAll(characterId = characterId, corporationId = corporationId).associate { it.contractId to it.status }

        var changed = 0
        raw.forEach { entry ->
            val model = entry.toContractModel(characterId, corporationId, isCorp) ?: return@forEach
            val prior = previousStatus[model.contractId]
            if (prior != null && prior != model.status) changed++
            ContractDao.upsert(model)
        }
        return changed
    }

    private fun Map<String, Any?>.toContractModel(
        characterId: Int?,
        corporationId: Int?,
        isCorp: Boolean,
    ): ContractModel? {
        val contractId = (this["contract_id"] as? Number)?.toInt() ?: return null
        return ContractModel(
            contractId = contractId,
            issuerId = (this["issuer_id"] as? Number)?.toInt() ?: 0,
            issuerCorpId = (this["issuer_corporation_id"] as? Number)?.toInt() ?: 0,
            assigneeId = (this["assignee_id"] as? Number)?.toInt() ?: 0,
            acceptorId = (this["acceptor_id"] as? Number)?.toInt() ?: 0,
            startStationId = (this["start_location_id"] as? Number)?.toLong() ?: 0,
            endStationId = (this["end_location_id"] as? Number)?.toLong() ?: 0,
            type = this["type"] as? String ?: "unknown",
            status = this["status"] as? String ?: "outstanding",
            title = this["title"] as? String ?: "",
            description = this["description"] as? String ?: "",
            dateIssued = this["date_issued"] as? String ?: "",
            dateExpired = this["date_expired"] as? String ?: "",
            dateAccepted = this["date_accepted"] as? String,
            dateCompleted = this["date_completed"] as? String,
            numDays = (this["num_days"] as? Number)?.toInt() ?: 0,
            price = (this["price"] as? Number)?.toDouble() ?: 0.0,
            reward = (this["reward"] as? Number)?.toDouble() ?: 0.0,
            collateral = (this["collateral"] as? Number)?.toDouble() ?: 0.0,
            buyout = (this["buyout"] as? Number)?.toDouble() ?: 0.0,
            forCorp = (this["for_corporation"] as? Boolean) ?: false,
            isCorp = isCorp,
            characterId = if (isCorp) null else characterId,
            corporationId = corporationId,
        )
    }
}
