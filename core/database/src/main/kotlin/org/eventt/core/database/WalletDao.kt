package org.eventt.core.database

import org.eventt.core.model.DailyWalletEntry
import org.eventt.core.model.WalletSummary

object WalletDao {
    // ─── Transactions ─────────────────────────────────────────────────────

    fun insertTransaction(
        transactionId: Long,
        date: String,
        typeId: Int,
        typeName: String,
        quantity: Int,
        unitPrice: Double,
        total: Double,
        isBuy: Boolean,
        clientId: Int,
        clientName: String,
        locationId: Long,
        locationName: String,
        isCorp: Boolean,
        characterId: Int?,
        corporationId: Int?,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO transactions (transaction_id, date, type_id, type_name, quantity, unit_price, total, is_buy, client_id, client_name, location_id, location_name, is_corp, character_id, corporation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setLong(1, transactionId)
                stmt.setString(2, date)
                stmt.setInt(3, typeId)
                stmt.setString(4, typeName)
                stmt.setInt(5, quantity)
                stmt.setDouble(6, unitPrice)
                stmt.setDouble(7, total)
                stmt.setInt(8, if (isBuy) 1 else 0)
                stmt.setInt(9, clientId)
                stmt.setString(10, clientName)
                stmt.setLong(11, locationId)
                stmt.setString(12, locationName)
                stmt.setInt(13, if (isCorp) 1 else 0)
                characterId?.let { stmt.setInt(14, it) } ?: stmt.setNull(14, java.sql.Types.INTEGER)
                corporationId?.let { stmt.setInt(15, it) } ?: stmt.setNull(15, java.sql.Types.INTEGER)
                stmt.executeUpdate()
            }
        }
    }

    fun updateTransactionNames(
        transactionId: Long,
        clientName: String? = null,
        locationName: String? = null,
    ) {
        val parts =
            buildList {
                if (clientName != null) add("client_name = ?")
                if (locationName != null) add("location_name = ?")
            }
        if (parts.isEmpty()) return
        DatabaseManager.transaction {
            prepareStatement("UPDATE transactions SET ${parts.joinToString(", ")} WHERE transaction_id = ?").use { stmt ->
                var i = 1
                if (clientName != null) stmt.setString(i++, clientName)
                if (locationName != null) stmt.setString(i++, locationName)
                stmt.setLong(i, transactionId)
                stmt.executeUpdate()
            }
        }
    }

    data class RawTxRecord(
        val date: String,
        val typeId: Int,
        val typeName: String,
        val quantity: Int,
        val unitPrice: Double,
        val isBuy: Boolean,
    )

    fun getAllTransactions(characterId: Int): List<RawTxRecord> =
        DatabaseManager.transaction {
            prepareStatement(
                "SELECT date, type_id, type_name, quantity, unit_price, is_buy FROM transactions WHERE character_id = ? ORDER BY date ASC",
            ).use { stmt ->
                stmt.setInt(1, characterId)
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<RawTxRecord>()
                    while (rs.next()) {
                        result.add(
                            RawTxRecord(
                                date = rs.getString("date"),
                                typeId = rs.getInt("type_id"),
                                typeName = rs.getString("type_name"),
                                quantity = rs.getInt("quantity"),
                                unitPrice = rs.getDouble("unit_price"),
                                isBuy = rs.getInt("is_buy") == 1,
                            ),
                        )
                    }
                    result
                }
            }
        }

    fun getTransactions(
        characterId: Int? = null,
        corporationId: Int? = null,
        limit: Int = 100,
        offset: Int = 0,
    ): List<Map<String, Any?>> =
        DatabaseManager.transaction {
            val where = buildWhereClause(characterId, corporationId)
            prepareStatement(
                "SELECT * FROM transactions ${where.sql} ORDER BY date DESC LIMIT ? OFFSET ?",
            ).use { stmt ->
                var i = 1
                where.params.forEach { stmt.setObject(i++, it) }
                stmt.setInt(i++, limit)
                stmt.setInt(i, offset)
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) {
                        result.add(
                            mapOf(
                                "transaction_id" to rs.getLong("transaction_id"),
                                "date" to rs.getString("date"),
                                "type_id" to rs.getInt("type_id"),
                                "type_name" to rs.getString("type_name"),
                                "quantity" to rs.getInt("quantity"),
                                "unit_price" to rs.getDouble("unit_price"),
                                "total" to rs.getDouble("total"),
                                "is_buy" to (rs.getInt("is_buy") == 1),
                                "client_id" to rs.getInt("client_id"),
                                "client_name" to rs.getString("client_name"),
                                "location_id" to rs.getLong("location_id"),
                                "location_name" to rs.getString("location_name"),
                                "is_corp" to (rs.getInt("is_corp") == 1),
                                "character_id" to rs.getInt("character_id").takeIf { it != 0 },
                                "corporation_id" to rs.getInt("corporation_id").takeIf { it != 0 },
                            ),
                        )
                    }
                    result
                }
            }
        }

    // ─── Journal ──────────────────────────────────────────────────────────

    fun insertJournalEntry(
        entryId: Long,
        date: String,
        amount: Double,
        balance: Double,
        reason: String,
        refType: String,
        firstPartyId: Int,
        firstPartyName: String,
        secondPartyId: Int,
        secondPartyName: String,
        taxAmount: Double?,
        isCorp: Boolean,
        characterId: Int?,
        corporationId: Int?,
        divisionId: Int?,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO journal (entry_id, date, amount, balance, reason, ref_type, first_party_id, first_party_name, second_party_id, second_party_name, tax_amount, is_corp, character_id, corporation_id, division_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setLong(1, entryId)
                stmt.setString(2, date)
                stmt.setDouble(3, amount)
                stmt.setDouble(4, balance)
                stmt.setString(5, reason)
                stmt.setString(6, refType)
                stmt.setInt(7, firstPartyId)
                stmt.setString(8, firstPartyName)
                stmt.setInt(9, secondPartyId)
                stmt.setString(10, secondPartyName)
                taxAmount?.let { stmt.setDouble(11, it) } ?: stmt.setNull(11, java.sql.Types.REAL)
                stmt.setInt(12, if (isCorp) 1 else 0)
                characterId?.let { stmt.setInt(13, it) } ?: stmt.setNull(13, java.sql.Types.INTEGER)
                corporationId?.let { stmt.setInt(14, it) } ?: stmt.setNull(14, java.sql.Types.INTEGER)
                divisionId?.let { stmt.setInt(15, it) } ?: stmt.setNull(15, java.sql.Types.INTEGER)
                stmt.executeUpdate()
            }
        }
    }

    fun getJournalEntries(
        characterId: Int? = null,
        corporationId: Int? = null,
        limit: Int? = null,
        offset: Int = 0,
    ): List<Map<String, Any?>> =
        DatabaseManager.transaction {
            val where = buildWhereClause(characterId, corporationId)
            val limitClause = if (limit != null) " LIMIT ? OFFSET ?" else ""
            prepareStatement(
                "SELECT * FROM journal ${where.sql} ORDER BY date DESC$limitClause",
            ).use { stmt ->
                var i = 1
                where.params.forEach { stmt.setObject(i++, it) }
                if (limit != null) {
                    stmt.setInt(i++, limit)
                    stmt.setInt(i, offset)
                }
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) {
                        result.add(
                            mapOf(
                                "entry_id" to rs.getLong("entry_id"),
                                "date" to rs.getString("date"),
                                "amount" to rs.getDouble("amount"),
                                "balance" to rs.getDouble("balance"),
                                "reason" to rs.getString("reason"),
                                "ref_type" to rs.getString("ref_type"),
                                "first_party_id" to rs.getInt("first_party_id"),
                                "first_party_name" to rs.getString("first_party_name"),
                                "second_party_id" to rs.getInt("second_party_id"),
                                "second_party_name" to rs.getString("second_party_name"),
                                "tax_amount" to rs.getDouble("tax_amount").takeIf { !rs.wasNull() },
                                "is_corp" to (rs.getInt("is_corp") == 1),
                                "character_id" to rs.getInt("character_id").takeIf { it != 0 },
                                "corporation_id" to rs.getInt("corporation_id").takeIf { it != 0 },
                                "division_id" to rs.getInt("division_id").takeIf { it != 0 },
                            ),
                        )
                    }
                    result
                }
            }
        }

    // ─── Summary ──────────────────────────────────────────────────────────

    fun getWalletSummary(
        characterId: Int? = null,
        corporationId: Int? = null,
    ): WalletSummary =
        DatabaseManager.transaction {
            val where = buildWhereClause(characterId, corporationId)

            // Get latest balance from journal
            val balanceQuery =
                """
                SELECT COALESCE(balance, 0) FROM journal ${where.sql} ORDER BY date DESC, entry_id DESC LIMIT 1
                """.trimIndent()

            // Get daily breakdown
            val dailyQuery =
                """
                SELECT 
                    substr(date, 1, 10) as day,
                    SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) as income,
                    SUM(CASE WHEN amount < 0 THEN ABS(amount) ELSE 0 END) as expenses,
                    SUM(amount) as net
                FROM journal ${where.sql}
                GROUP BY substr(date, 1, 10)
                ORDER BY day DESC
                LIMIT 90
                """.trimIndent()

            val balance =
                prepareStatement(balanceQuery).use { stmt ->
                    var i = 1
                    where.params.forEach { stmt.setObject(i++, it) }
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getDouble(1) else 0.0
                    }
                }

            val dailyBreakdown =
                prepareStatement(dailyQuery).use { stmt ->
                    var i = 1
                    where.params.forEach { stmt.setObject(i++, it) }
                    stmt.executeQuery().use { rs ->
                        val list = mutableListOf<DailyWalletEntry>()
                        while (rs.next()) {
                            list.add(
                                DailyWalletEntry(
                                    date = rs.getString("day"),
                                    income = rs.getDouble("income"),
                                    expenses = rs.getDouble("expenses"),
                                    net = rs.getDouble("net"),
                                ),
                            )
                        }
                        list
                    }
                }

            val totalEarned = dailyBreakdown.sumOf { it.income }
            val totalSpent = dailyBreakdown.sumOf { it.expenses }

            WalletSummary(
                characterId = characterId,
                corporationId = corporationId,
                balance = balance,
                totalEarned = totalEarned,
                totalSpent = totalSpent,
                dailyBreakdown = dailyBreakdown,
            )
        }

    fun getTransactionBreakdown(
        characterId: Int? = null,
        corporationId: Int? = null,
        since: String = "1970-01-01",
    ): List<DailyWalletEntry> =
        DatabaseManager.transaction {
            val where = buildWhereClause(characterId, corporationId)
            val whereSql = if (where.sql.isEmpty()) "WHERE date >= ?" else "${where.sql} AND date >= ?"
            prepareStatement(
                """
                SELECT
                    substr(date, 1, 10) as day,
                    SUM(CASE WHEN is_buy = 0 THEN total ELSE 0 END) as income,
                    SUM(CASE WHEN is_buy = 1 THEN total ELSE 0 END) as expenses,
                    SUM(CASE WHEN is_buy = 0 THEN total ELSE -total END) as net
                FROM transactions
                $whereSql
                GROUP BY substr(date, 1, 10)
                ORDER BY day DESC
                LIMIT 90
                """.trimIndent(),
            ).use { stmt ->
                var i = 1
                where.params.forEach { stmt.setObject(i++, it) }
                stmt.setString(i, since)
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<DailyWalletEntry>()
                    while (rs.next()) {
                        list.add(
                            DailyWalletEntry(
                                date = rs.getString("day"),
                                income = rs.getDouble("income"),
                                expenses = rs.getDouble("expenses"),
                                net = rs.getDouble("net"),
                            ),
                        )
                    }
                    list
                }
            }
        }

    // Trading P&L: gross buy/sell cash flow from `transactions`, plus the sales tax and broker
    // fees actually charged for that trading (from `journal`) — the real cost of trading that
    // `transactions.total` doesn't include. Deliberately excludes every other journal ref_type
    // (market_escrow/market_escrow_refund, contracts, bounties, PI, etc.): escrow in particular
    // is money reserved for a still-open buy order, not a realized trading expense, and including
    // it would make P&L swing on order placement/cancellation rather than actual fills.
    fun getTradingPnlBreakdown(
        characterId: Int? = null,
        corporationId: Int? = null,
        since: String = "1970-01-01",
    ): List<DailyWalletEntry> =
        DatabaseManager.transaction {
            val txWhere = buildWhereClause(characterId, corporationId)
            val journalWhere = buildWhereClause(characterId, corporationId)
            val txWhereSql = if (txWhere.sql.isEmpty()) "WHERE date >= ?" else "${txWhere.sql} AND date >= ?"
            val journalWhereSql = if (journalWhere.sql.isEmpty()) "WHERE date >= ?" else "${journalWhere.sql} AND date >= ?"

            prepareStatement(
                """
                WITH combined AS (
                    SELECT substr(date, 1, 10) as day,
                           CASE WHEN is_buy = 0 THEN total ELSE 0 END as income,
                           CASE WHEN is_buy = 1 THEN total ELSE 0 END as expenses
                    FROM transactions
                    $txWhereSql
                    UNION ALL
                    SELECT substr(date, 1, 10) as day,
                           0.0 as income,
                           ABS(amount) as expenses
                    FROM journal
                    $journalWhereSql AND ref_type IN ('transaction_tax', 'brokers_fee')
                )
                SELECT
                    day,
                    SUM(income) as income,
                    SUM(expenses) as expenses,
                    SUM(income) - SUM(expenses) as net
                FROM combined
                GROUP BY day
                ORDER BY day DESC
                LIMIT 90
                """.trimIndent(),
            ).use { stmt ->
                var i = 1
                txWhere.params.forEach { stmt.setObject(i++, it) }
                stmt.setString(i++, since)
                journalWhere.params.forEach { stmt.setObject(i++, it) }
                stmt.setString(i, since)
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<DailyWalletEntry>()
                    while (rs.next()) {
                        list.add(
                            DailyWalletEntry(
                                date = rs.getString("day"),
                                income = rs.getDouble("income"),
                                expenses = rs.getDouble("expenses"),
                                net = rs.getDouble("net"),
                            ),
                        )
                    }
                    list
                }
            }
        }

    // ─── Helper ───────────────────────────────────────────────────────────

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
}
