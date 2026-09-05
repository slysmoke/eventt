package org.eventt.core.database

// Manual FIFO write-offs: a buy lot the character/corp no longer actually holds but that has no
// offsetting sell transaction (contract courier ganked in transit, item bought under one entity
// and sold under another, etc.) — see CostBasisService, which consumes these like a sell but
// books no revenue.
object InventoryAdjustmentDao {
    data class Adjustment(
        val id: Long,
        val typeId: Int,
        val typeName: String,
        val quantity: Int,
        val date: String,
        val reason: String,
    )

    private data class WhereClause(
        val sql: String,
        val params: List<Any?>,
    )

    private fun buildWhereClause(
        characterId: Int?,
        corporationId: Int?,
    ): WhereClause =
        when {
            characterId != null -> WhereClause("WHERE character_id = ?", listOf(characterId))
            corporationId != null -> WhereClause("WHERE corporation_id = ?", listOf(corporationId))
            else -> WhereClause("", emptyList())
        }

    fun insert(
        typeId: Int,
        typeName: String,
        quantity: Int,
        date: String,
        reason: String,
        characterId: Int?,
        corporationId: Int?,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT INTO inventory_adjustments (type_id, type_name, quantity, date, reason, character_id, corporation_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setInt(1, typeId)
                stmt.setString(2, typeName)
                stmt.setInt(3, quantity)
                stmt.setString(4, date)
                stmt.setString(5, reason)
                characterId?.let { stmt.setInt(6, it) } ?: stmt.setNull(6, java.sql.Types.INTEGER)
                corporationId?.let { stmt.setInt(7, it) } ?: stmt.setNull(7, java.sql.Types.INTEGER)
                stmt.executeUpdate()
            }
        }
    }

    fun getAll(
        characterId: Int? = null,
        corporationId: Int? = null,
    ): List<Adjustment> =
        DatabaseManager.transaction {
            val where = buildWhereClause(characterId, corporationId)
            prepareStatement(
                "SELECT id, type_id, type_name, quantity, date, reason FROM inventory_adjustments ${where.sql} ORDER BY date ASC",
            ).use { stmt ->
                where.params.forEachIndexed { i, param -> stmt.setObject(i + 1, param) }
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<Adjustment>()
                    while (rs.next()) {
                        result.add(
                            Adjustment(
                                id = rs.getLong("id"),
                                typeId = rs.getInt("type_id"),
                                typeName = rs.getString("type_name"),
                                quantity = rs.getInt("quantity"),
                                date = rs.getString("date"),
                                reason = rs.getString("reason"),
                            ),
                        )
                    }
                    result
                }
            }
        }

    /** Undoes a write-off — the lot goes back to being counted as held inventory on the next FIFO recompute. */
    fun delete(id: Long) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM inventory_adjustments WHERE id = ?").use { stmt ->
                stmt.setLong(1, id)
                stmt.executeUpdate()
            }
        }
    }
}
