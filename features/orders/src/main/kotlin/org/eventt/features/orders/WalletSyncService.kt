package org.eventt.features.orders

import org.eventt.core.database.CharacterDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.WalletDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.AppLog

/**
 * Pulls wallet journal + transactions from ESI into the local DB for one character/corp, or every
 * local character/corp at once. Factored out of DashboardScreen's own per-context ESI sync (which
 * still calls [syncCharacter]/[syncCorporation] for whichever context is on screen) so
 * [org.eventt.features.orders.LeaderboardPublisher] can run the same sync for *everyone* before
 * computing the combined P&L it publishes — a leaderboard number is only as fresh as the local DB
 * it's computed from, so publishing against whatever happened to be cached already would silently
 * lag behind reality between app launches.
 */
object WalletSyncService {
    suspend fun syncCharacter(charId: Int) = sync(characterId = charId, corporationId = null, actingCharId = charId, isCorp = false)

    suspend fun syncCorporation(
        corpId: Int,
        actingCharId: Int,
    ) = sync(characterId = null, corporationId = corpId, actingCharId = actingCharId, isCorp = true)

    /** Every local character, then one acting character per corp (corp wallets are shared, not per-member). */
    suspend fun syncAll() {
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

    private suspend fun sync(
        characterId: Int?,
        corporationId: Int?,
        actingCharId: Int,
        isCorp: Boolean,
    ) {
        try {
            val journalEntries =
                if (isCorp) {
                    EsiClient.getCorporationJournal(
                        requireNotNull(corporationId),
                        actingCharId,
                    )
                } else {
                    EsiClient.getCharacterJournal(actingCharId)
                }
            journalEntries.forEach { entry ->
                runCatching {
                    WalletDao.insertJournalEntry(
                        entryId = (entry["id"] as? Number)?.toLong() ?: 0,
                        date = entry["date"] as? String ?: "",
                        amount = (entry["amount"] as? Number)?.toDouble() ?: 0.0,
                        balance = (entry["balance"] as? Number)?.toDouble() ?: 0.0,
                        reason = entry["reason"] as? String ?: "",
                        refType = entry["ref_type"] as? String ?: "",
                        firstPartyId = (entry["first_party_id"] as? Number)?.toInt() ?: 0,
                        firstPartyName = "",
                        secondPartyId = (entry["second_party_id"] as? Number)?.toInt() ?: 0,
                        secondPartyName = "",
                        taxAmount = (entry["tax"] as? Number)?.toDouble(),
                        isCorp = isCorp,
                        characterId = characterId,
                        corporationId = corporationId,
                        divisionId = if (isCorp) (entry["division"] as? Number)?.toInt() else null,
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.warn("WalletSync", "journal ESI: ${e.message}")
        }

        try {
            val txList =
                if (isCorp) {
                    EsiClient.getCorporationTransactions(
                        requireNotNull(corporationId),
                        actingCharId,
                    )
                } else {
                    EsiClient.getCharacterTransactions(actingCharId)
                }
            val typeNames =
                txList
                    .mapNotNull { (it["type_id"] as? Number)?.toInt() }
                    .toSet()
                    .associateWith { id -> StaticDataDao.getTypeName(id) ?: "" }
            val clientIds = txList.mapNotNull { (it["client_id"] as? Number)?.toInt() }.filter { it > 0 }.toSet()
            val knownClientNames = WalletDao.getKnownClientNames(clientIds)
            val missingClientIds = clientIds - knownClientNames.keys
            val freshClientNames = if (missingClientIds.isNotEmpty()) EsiClient.resolveNames(missingClientIds.toList()) else emptyMap()
            val clientNames = knownClientNames + freshClientNames

            txList.forEach { tx ->
                val typeId = (tx["type_id"] as? Number)?.toInt() ?: 0
                val unitPrice = (tx["unit_price"] as? Number)?.toDouble() ?: 0.0
                val quantity = (tx["quantity"] as? Number)?.toInt() ?: 0
                val clientId = (tx["client_id"] as? Number)?.toInt() ?: 0
                runCatching {
                    WalletDao.insertTransaction(
                        transactionId = (tx["transaction_id"] as? Number)?.toLong() ?: 0,
                        date = tx["date"] as? String ?: "",
                        typeId = typeId,
                        typeName = typeNames[typeId] ?: "",
                        quantity = quantity,
                        unitPrice = unitPrice,
                        total = unitPrice * quantity,
                        isBuy = (tx["is_buy"] as? Boolean) ?: false,
                        clientId = clientId,
                        clientName = clientNames[clientId] ?: "",
                        locationId = (tx["location_id"] as? Number)?.toLong() ?: 0L,
                        locationName = "",
                        isCorp = isCorp,
                        characterId = characterId,
                        corporationId = corporationId,
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.warn("WalletSync", "transactions ESI: ${e.message}")
        }
    }

    /**
     * Retries ESI name resolution for rows whose `client_name` is still blank — e.g. a prior
     * sync's ESI lookup for that id failed or got rate-limited — and persists any names it
     * recovers, so callers reading straight from the DB (Dashboard's top buyers/sellers) don't
     * get stuck showing "Unknown" until someone happens to reopen Wallet, which already retries
     * this same way.
     */
    fun resolveMissingClientNames(rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val missingIds =
            rows
                .filter { (it["client_name"] as? String).isNullOrEmpty() }
                .mapNotNull { (it["client_id"] as? Number)?.toInt() }
                .filter { it > 0 }
                .distinct()
        if (missingIds.isEmpty()) return rows

        val resolved = try { EsiClient.resolveNames(missingIds) } catch (_: Exception) { emptyMap() }
        if (resolved.isEmpty()) return rows

        return rows.map { tx ->
            if (!(tx["client_name"] as? String).isNullOrEmpty()) return@map tx
            val clientId = (tx["client_id"] as? Number)?.toInt() ?: return@map tx
            val name = resolved[clientId] ?: return@map tx
            val txId = (tx["transaction_id"] as? Number)?.toLong() ?: return@map tx
            WalletDao.updateTransactionNames(txId, clientName = name, locationName = null)
            tx.toMutableMap().apply { put("client_name", name) }
        }
    }
}
