package org.eventt.core.database

import org.eventt.core.model.DailyWalletEntry
import org.eventt.core.model.WalletSummary
import org.eventt.core.model.utcToLocalDateTime

object WalletDao {
    // Both transactions and journal are keyed by ESI's own id (transaction_id/entry_id), so a
    // personal sync re-inserting a row that's already attributed to a corporation would silently
    // flip it back to personal (INSERT OR REPLACE on the same primary key) — that corp attribution
    // must win once established, since ESI's character-scoped wallet endpoints aren't guaranteed
    // to exclude entries that are actually funded through a corp wallet division.
    private fun java.sql.Connection.isAlreadyCorpOwned(
        table: String,
        idColumn: String,
        id: Long,
    ): Boolean =
        prepareStatement("SELECT 1 FROM $table WHERE $idColumn = ? AND corporation_id IS NOT NULL").use { stmt ->
            stmt.setLong(1, id)
            stmt.executeQuery().use { it.next() }
        }

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
            if (!isCorp && isAlreadyCorpOwned("transactions", "transaction_id", transactionId)) return@transaction
            prepareStatement(
                """
                INSERT OR REPLACE INTO transactions (transaction_id, date, type_id, type_name, quantity, unit_price, total, is_buy, client_id, client_name, location_id, location_name, is_corp, character_id, corporation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setLong(1, transactionId)
                stmt.setString(2, date.utcToLocalDateTime())
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

    // Names already resolved by a previous sync (from either the Wallet or Dashboard screen)
    // live on past transaction rows — reuse them so callers skip an ESI /universe/names/ round
    // trip for IDs we've already looked up.
    fun getKnownClientNames(clientIds: Collection<Int>): Map<Int, String> {
        if (clientIds.isEmpty()) return emptyMap()
        return DatabaseManager.transaction {
            val placeholders = clientIds.joinToString(",") { "?" }
            prepareStatement(
                "SELECT DISTINCT client_id, client_name FROM transactions WHERE client_name != '' AND client_id IN ($placeholders)",
            ).use { stmt ->
                clientIds.forEachIndexed { i, id -> stmt.setInt(i + 1, id) }
                stmt.executeQuery().use { rs ->
                    val result = mutableMapOf<Int, String>()
                    while (rs.next()) result[rs.getInt("client_id")] = rs.getString("client_name")
                    result
                }
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
        // Booked from a P2P trade (core:nostr's ReceiptService.bookCostBasisEntry) rather than a
        // real ESI market transaction — negative transaction_id is that source's own marker (see
        // p2pTransactionId). A P2P trade is a direct item/ISK exchange outside the market, so
        // unlike a real transaction it never carries sales tax or a broker fee.
        val isP2p: Boolean = false,
        val locationId: Long = 0,
    )

    fun getAllTransactions(
        characterId: Int? = null,
        corporationId: Int? = null,
    ): List<RawTxRecord> =
        DatabaseManager.transaction {
            val where = buildWhereClause(characterId, corporationId)
            prepareStatement(
                "SELECT transaction_id, date, type_id, type_name, quantity, unit_price, is_buy, location_id FROM transactions " +
                    "${where.sql} ORDER BY date ASC",
            ).use { stmt ->
                where.params.forEachIndexed { i, param -> stmt.setObject(i + 1, param) }
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
                                isP2p = rs.getLong("transaction_id") < 0,
                                locationId = rs.getLong("location_id"),
                            ),
                        )
                    }
                    result
                }
            }
        }

    /** Stored date of a transaction, if it already exists — lets a re-booked P2P entry keep its original date instead of drifting to "now" on every attribution edit. */
    fun getTransactionDate(transactionId: Long): String? =
        DatabaseManager.transaction {
            prepareStatement("SELECT date FROM transactions WHERE transaction_id = ?").use { stmt ->
                stmt.setLong(1, transactionId)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("date") else null }
            }
        }

    /** Who a transaction (P2P-synthetic or real) currently counts against — null if it's attributed to neither. */
    fun getAttribution(transactionId: Long): TransactionAttribution? =
        DatabaseManager.transaction {
            prepareStatement("SELECT character_id, corporation_id, is_corp FROM transactions WHERE transaction_id = ?").use { stmt ->
                stmt.setLong(1, transactionId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@transaction null
                    if (rs.getInt("is_corp") == 1) {
                        rs.getInt("corporation_id").takeIf { !rs.wasNull() }?.let { TransactionAttribution.Corporation(it) }
                    } else {
                        rs.getInt("character_id").takeIf { !rs.wasNull() }?.let { TransactionAttribution.Character(it) }
                    }
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
            if (!isCorp && isAlreadyCorpOwned("journal", "entry_id", entryId)) return@transaction
            prepareStatement(
                """
                INSERT OR REPLACE INTO journal (entry_id, date, amount, balance, reason, ref_type, first_party_id, first_party_name, second_party_id, second_party_name, tax_amount, is_corp, character_id, corporation_id, division_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setLong(1, entryId)
                stmt.setString(2, date.utcToLocalDateTime())
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

    // One wallet's balance after a journal entry. [wallet] tells wallets apart: a character's own,
    // or one division of a corporation's — each journal row's balance is only that wallet's.
    data class BalanceRow(
        val date: String,
        val wallet: String,
        val balance: Double,
    )

    // Every journal balance in ledger order, for the dashboard's wallet history. Character scope
    // leaves out corp-wallet rows, which carry no meaning for that character's own balance.
    fun getBalanceHistory(
        characterId: Int? = null,
        corporationId: Int? = null,
    ): List<BalanceRow> =
        DatabaseManager.transaction {
            val where =
                when {
                    characterId != null -> WhereClause("WHERE character_id = ? AND corporation_id IS NULL", listOf(characterId))
                    else -> buildWhereClause(null, corporationId)
                }
            prepareStatement(
                "SELECT date, character_id, corporation_id, division_id, balance FROM journal ${where.sql} ORDER BY date, entry_id",
            ).use { stmt ->
                where.params.forEachIndexed { i, p -> stmt.setObject(i + 1, p) }
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<BalanceRow>()
                    while (rs.next()) {
                        val corp = rs.getInt("corporation_id")
                        val wallet = if (corp != 0) "corp:$corp:${rs.getInt("division_id")}" else "char:${rs.getInt("character_id")}"
                        result.add(BalanceRow(rs.getString("date"), wallet, rs.getDouble("balance")))
                    }
                    result
                }
            }
        }

    // Net ISK moved into buy-order escrow per day (yyyy-MM-dd): market_escrow deposits minus refunds,
    // minus market buys (a buy-order fill is paid out of escrow and leaves no journal entry of its
    // own), plus any buy that did hit the journal (a direct buy from a sell order never touched
    // escrow). Summed from the start this tracks the open buy orders' escrow — on real data within
    // a fraction of a percent of price × remaining.
    fun getEscrowFlowByDay(
        characterId: Int? = null,
        corporationId: Int? = null,
    ): Map<String, Double> =
        DatabaseManager.transaction {
            val where = buildWhereClause(characterId, corporationId)

            fun and(cond: String) = if (where.sql.isEmpty()) "WHERE $cond" else "${where.sql} AND $cond"
            prepareStatement(
                """
                SELECT day, SUM(flow) AS flow FROM (
                    SELECT substr(date, 1, 10) AS day, -amount AS flow FROM journal
                    ${and("(ref_type = 'market_escrow' OR (ref_type = 'market_transaction' AND amount < 0))")}
                    UNION ALL
                    SELECT substr(date, 1, 10) AS day, -total AS flow FROM transactions
                    ${and("is_buy = 1 AND transaction_id > 0")}
                ) GROUP BY day
                """.trimIndent(),
            ).use { stmt ->
                var i = 1
                repeat(2) { where.params.forEach { p -> stmt.setObject(i++, p) } }
                stmt.executeQuery().use { rs ->
                    val result = mutableMapOf<String, Double>()
                    while (rs.next()) result[rs.getString("day")] = rs.getDouble("flow")
                    result
                }
            }
        }

    // Sales tax + broker fees paid per day (yyyy-MM-dd), as positive ISK.
    fun getFeesByDay(
        characterId: Int? = null,
        corporationId: Int? = null,
    ): Map<String, Double> =
        DatabaseManager.transaction {
            val where = buildWhereClause(characterId, corporationId)
            val sql = (if (where.sql.isEmpty()) "WHERE" else "${where.sql} AND") + " ref_type IN ('transaction_tax', 'brokers_fee')"
            prepareStatement("SELECT substr(date, 1, 10) AS day, SUM(ABS(amount)) AS fees FROM journal $sql GROUP BY day").use { stmt ->
                where.params.forEachIndexed { i, p -> stmt.setObject(i + 1, p) }
                stmt.executeQuery().use { rs ->
                    val result = mutableMapOf<String, Double>()
                    while (rs.next()) result[rs.getString("day")] = rs.getDouble("fees")
                    result
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
