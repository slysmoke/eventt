package org.eve.trader.core.database

import org.eve.trader.core.model.ContractModel
import org.eve.trader.core.model.ContractItemModel

object ContractDao {

    // ─── Contracts ────────────────────────────────────────────────────────

    fun upsert(contract: ContractModel) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO contracts (contract_id, issuer_id, issuer_corp_id, assignee_id, acceptor_id,
                    start_station_id, end_station_id, type, status, title, description, date_issued, date_expired,
                    date_accepted, date_completed, num_days, price, reward, collateral, buyout, for_corp, is_corp,
                    character_id, corporation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setInt(1, contract.contractId)
                stmt.setInt(2, contract.issuerId)
                stmt.setInt(3, contract.issuerCorpId)
                stmt.setInt(4, contract.assigneeId)
                stmt.setInt(5, contract.acceptorId)
                stmt.setLong(6, contract.startStationId)
                stmt.setLong(7, contract.endStationId)
                stmt.setString(8, contract.type)
                stmt.setString(9, contract.status)
                stmt.setString(10, contract.title)
                stmt.setString(11, contract.description)
                stmt.setString(12, contract.dateIssued)
                stmt.setString(13, contract.dateExpired)
                stmt.setString(14, contract.dateAccepted)
                stmt.setString(15, contract.dateCompleted)
                stmt.setInt(16, contract.numDays)
                stmt.setDouble(17, contract.price)
                stmt.setDouble(18, contract.reward)
                stmt.setDouble(19, contract.collateral)
                stmt.setDouble(20, contract.buyout)
                stmt.setInt(21, if (contract.forCorp) 1 else 0)
                stmt.setInt(22, if (contract.isCorp) 1 else 0)
                contract.characterId?.let { stmt.setInt(23, it) } ?: stmt.setNull(23, java.sql.Types.INTEGER)
                contract.corporationId?.let { stmt.setInt(24, it) } ?: stmt.setNull(24, java.sql.Types.INTEGER)
                stmt.executeUpdate()
            }
        }
    }

    fun bulkUpsert(contracts: List<ContractModel>) {
        contracts.forEach { upsert(it) }
    }

    fun getAll(characterId: Int? = null, corporationId: Int? = null): List<ContractModel> {
        return DatabaseManager.transaction {
            val where = when {
                characterId != null -> "WHERE character_id = ?"
                corporationId != null -> "WHERE corporation_id = ?"
                else -> ""
            }
            prepareStatement("SELECT * FROM contracts $where ORDER BY date_issued DESC").use { stmt ->
                if (characterId != null) stmt.setInt(1, characterId)
                else if (corporationId != null) stmt.setInt(1, corporationId)
                stmt.executeQuery().mapResultSetToContracts()
            }
        }
    }

    fun getByStatus(status: String, characterId: Int? = null, corporationId: Int? = null): List<ContractModel> {
        return DatabaseManager.transaction {
            val baseWhere = when {
                characterId != null -> "WHERE character_id = ?"
                corporationId != null -> "WHERE corporation_id = ?"
                else -> "WHERE 1=1"
            }
            prepareStatement("SELECT * FROM contracts $baseWhere AND status = ? ORDER BY date_issued DESC").use { stmt ->
                var i = 1
                if (characterId != null) stmt.setInt(i++, characterId)
                else if (corporationId != null) stmt.setInt(i++, corporationId)
                stmt.setString(i, status)
                stmt.executeQuery().mapResultSetToContracts()
            }
        }
    }

    // ─── Contract Items ───────────────────────────────────────────────────

    fun insertContractItem(item: ContractItemModel) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO contract_items (contract_id, record_id, type_id, type_name, quantity, raw_quantity, is_included, is_singleton, estimated_price)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setInt(1, item.contractId)
                stmt.setInt(2, item.recordId)
                stmt.setInt(3, item.typeId)
                stmt.setString(4, item.typeName)
                stmt.setInt(5, item.quantity)
                stmt.setInt(6, item.rawQuantity)
                stmt.setInt(7, if (item.isIncluded) 1 else 0)
                stmt.setInt(8, if (item.isSingleton) 1 else 0)
                stmt.setDouble(9, item.estimatedPrice)
                stmt.executeUpdate()
            }
        }
    }

    fun getItemsForContract(contractId: Int): List<ContractItemModel> {
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM contract_items WHERE contract_id = ?").use { stmt ->
                stmt.setInt(1, contractId)
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<ContractItemModel>()
                    while (rs.next()) {
                        list.add(
                            ContractItemModel(
                                contractId = rs.getInt("contract_id"),
                                recordId = rs.getInt("record_id"),
                                typeId = rs.getInt("type_id"),
                                typeName = rs.getString("type_name") ?: "",
                                quantity = rs.getInt("quantity"),
                                rawQuantity = rs.getInt("raw_quantity"),
                                isIncluded = rs.getInt("is_included") == 1,
                                isSingleton = rs.getInt("is_singleton") == 1,
                                estimatedPrice = rs.getDouble("estimated_price"),
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun java.sql.ResultSet.mapResultSetToContracts(): List<ContractModel> {
        val list = mutableListOf<ContractModel>()
        while (next()) {
            list.add(
                ContractModel(
                    contractId = getInt("contract_id"),
                    issuerId = getInt("issuer_id"),
                    issuerCorpId = getInt("issuer_corp_id"),
                    assigneeId = getInt("assignee_id"),
                    acceptorId = getInt("acceptor_id"),
                    startStationId = getLong("start_station_id"),
                    endStationId = getLong("end_station_id"),
                    type = getString("type") ?: "unknown",
                    status = getString("status") ?: "outstanding",
                    title = getString("title") ?: "",
                    description = getString("description") ?: "",
                    dateIssued = getString("date_issued") ?: "",
                    dateExpired = getString("date_expired") ?: "",
                    dateAccepted = getString("date_accepted"),
                    dateCompleted = getString("date_completed"),
                    numDays = getInt("num_days"),
                    price = getDouble("price"),
                    reward = getDouble("reward"),
                    collateral = getDouble("collateral"),
                    buyout = getDouble("buyout"),
                    forCorp = getInt("for_corp") == 1,
                    isCorp = getInt("is_corp") == 1,
                    characterId = getInt("character_id").takeIf { it != 0 },
                    corporationId = getInt("corporation_id").takeIf { it != 0 },
                )
            )
        }
        return list
    }
}
