package org.eventt.core.database

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TOLERANCE = 0.0001

class WalletDaoTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initInMemoryDb() {
            DatabaseManager.close()
            DatabaseManager.initialize(":memory:")
        }
    }

    @AfterEach
    fun cleanUp() {
        DatabaseManager.transaction {
            createStatement().use {
                it.execute("DELETE FROM transactions")
                it.execute("DELETE FROM journal")
            }
        }
    }

    private fun insertTx(
        id: Long,
        date: String,
        isBuy: Boolean,
        total: Double,
        characterId: Int = 1,
    ) = WalletDao.insertTransaction(
        transactionId = id,
        date = date,
        typeId = 34,
        typeName = "Tritanium",
        quantity = 100,
        unitPrice = total / 100,
        total = total,
        isBuy = isBuy,
        clientId = 0,
        clientName = "",
        locationId = 60003760L,
        locationName = "Jita IV - Moon 4",
        isCorp = false,
        characterId = characterId,
        corporationId = null,
    )

    @Test
    fun `getAllTransactions returns rows for the character sorted ascending by date`() {
        insertTx(2, "2024-01-02", isBuy = false, total = 200.0)
        insertTx(1, "2024-01-01", isBuy = true, total = 100.0)

        val txs = WalletDao.getAllTransactions(1)

        txs.map { it.date } shouldBe listOf("2024-01-01", "2024-01-02")
    }

    @Test
    fun `getAllTransactions only returns rows for the requested character`() {
        insertTx(1, "2024-01-01", isBuy = true, total = 100.0, characterId = 1)
        insertTx(2, "2024-01-01", isBuy = true, total = 100.0, characterId = 2)

        WalletDao.getAllTransactions(1) shouldHaveSize 1
    }

    @Test
    fun `updateTransactionNames updates only the fields that were passed in`() {
        insertTx(1, "2024-01-01", isBuy = false, total = 100.0)

        WalletDao.updateTransactionNames(1, clientName = "Some Trader")

        val row = WalletDao.getTransactions(characterId = 1).single()
        row["client_name"] shouldBe "Some Trader"
        row["location_name"] shouldBe "Jita IV - Moon 4" // untouched
    }

    @Test
    fun `getTransactions respects limit and offset`() {
        insertTx(1, "2024-01-01", isBuy = false, total = 100.0)
        insertTx(2, "2024-01-02", isBuy = false, total = 200.0)
        insertTx(3, "2024-01-03", isBuy = false, total = 300.0)

        val page1 = WalletDao.getTransactions(characterId = 1, limit = 2, offset = 0)
        val page2 = WalletDao.getTransactions(characterId = 1, limit = 2, offset = 2)

        page1.map { it["transaction_id"] } shouldBe listOf(3L, 2L) // newest first
        page2.map { it["transaction_id"] } shouldBe listOf(1L)
    }

    @Test
    fun `insertTransaction converts a UTC ESI timestamp to the local time zone`() {
        val utcDate = "2026-07-11T23:45:00Z"
        val expectedLocal =
            Instant.parse(utcDate).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        WalletDao.insertTransaction(
            transactionId = 1,
            date = utcDate,
            typeId = 34,
            typeName = "Tritanium",
            quantity = 1,
            unitPrice = 1.0,
            total = 1.0,
            isBuy = true,
            clientId = 0,
            clientName = "",
            locationId = 0L,
            locationName = "",
            isCorp = false,
            characterId = 1,
            corporationId = null,
        )

        WalletDao.getTransactions(characterId = 1).single()["date"] shouldBe expectedLocal
    }

    @Test
    fun `insertTransaction leaves a date with no UTC offset untouched`() {
        insertTx(1, "2024-01-01", isBuy = false, total = 100.0)

        WalletDao.getTransactions(characterId = 1).single()["date"] shouldBe "2024-01-01"
    }

    private fun insertJournal(
        id: Long,
        date: String,
        amount: Double,
        balance: Double,
        refType: String = "market_transaction",
        characterId: Int = 1,
    ) = WalletDao.insertJournalEntry(
        entryId = id,
        date = date,
        amount = amount,
        balance = balance,
        reason = "",
        refType = refType,
        firstPartyId = 0,
        firstPartyName = "",
        secondPartyId = 0,
        secondPartyName = "",
        taxAmount = null,
        isCorp = false,
        characterId = characterId,
        corporationId = null,
        divisionId = null,
    )

    @Test
    fun `insertJournalEntry converts a UTC ESI timestamp to the local time zone`() {
        val utcDate = "2026-07-11T23:45:00Z"
        val expectedLocal =
            Instant.parse(utcDate).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        insertJournal(1, utcDate, amount = 100.0, balance = 100.0)

        WalletDao.getJournalEntries(characterId = 1).single()["date"] shouldBe expectedLocal
    }

    @Test
    fun `getWalletSummary reads the balance from the most recent journal entry`() {
        insertJournal(1, "2024-01-01T10:00:00", amount = 100.0, balance = 100.0)
        insertJournal(2, "2024-01-01T12:00:00", amount = -30.0, balance = 70.0)
        insertJournal(3, "2024-01-02T09:00:00", amount = 50.0, balance = 120.0)

        val summary = WalletDao.getWalletSummary(characterId = 1)

        summary.balance should (120.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `getWalletSummary's daily breakdown separates income, expenses, and net per day`() {
        insertJournal(1, "2024-01-01T10:00:00", amount = 100.0, balance = 100.0)
        insertJournal(2, "2024-01-01T12:00:00", amount = -30.0, balance = 70.0)
        insertJournal(3, "2024-01-02T09:00:00", amount = 50.0, balance = 120.0)

        val summary = WalletDao.getWalletSummary(characterId = 1)

        val jan1 = summary.dailyBreakdown.single { it.date == "2024-01-01" }
        jan1.income should (100.0 plusOrMinus TOLERANCE)
        jan1.expenses should (30.0 plusOrMinus TOLERANCE)
        jan1.net should (70.0 plusOrMinus TOLERANCE)
        summary.totalEarned should (150.0 plusOrMinus TOLERANCE)
        summary.totalSpent should (30.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `getTransactionBreakdown treats sells as income and buys as expenses`() {
        insertTx(1, "2024-01-01", isBuy = false, total = 200.0)
        insertTx(2, "2024-01-01", isBuy = true, total = 80.0)

        val day = WalletDao.getTransactionBreakdown(characterId = 1).single { it.date == "2024-01-01" }

        day.income should (200.0 plusOrMinus TOLERANCE)
        day.expenses should (80.0 plusOrMinus TOLERANCE)
        day.net should (120.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `getTransactionBreakdown excludes rows before the given since date`() {
        insertTx(1, "2023-01-01", isBuy = false, total = 999.0)
        insertTx(2, "2024-01-01", isBuy = false, total = 200.0)

        val breakdown = WalletDao.getTransactionBreakdown(characterId = 1, since = "2024-01-01")

        breakdown.map { it.date } shouldBe listOf("2024-01-01")
    }

    @Test
    fun `getTradingPnlBreakdown adds sales tax and broker fees from the journal onto the day's expenses`() {
        insertTx(1, "2024-01-01", isBuy = false, total = 200.0)
        insertJournal(101, "2024-01-01T10:00:00", amount = -16.0, balance = 1000.0, refType = "transaction_tax")
        insertJournal(102, "2024-01-01T10:01:00", amount = -6.0, balance = 994.0, refType = "brokers_fee")

        val day = WalletDao.getTradingPnlBreakdown(characterId = 1).single { it.date == "2024-01-01" }

        day.income should (200.0 plusOrMinus TOLERANCE)
        day.expenses should (22.0 plusOrMinus TOLERANCE) // 16 tax + 6 broker fee, no matching transaction expense
        day.net should (178.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `insertTransaction does not let a later personal sync overwrite a transaction already attributed to a corp`() {
        WalletDao.insertTransaction(
            transactionId = 1,
            date = "2024-01-01",
            typeId = 34,
            typeName = "Tritanium",
            quantity = 100,
            unitPrice = 5.0,
            total = 500.0,
            isBuy = false,
            clientId = 0,
            clientName = "",
            locationId = 0L,
            locationName = "",
            isCorp = true,
            characterId = null,
            corporationId = 999,
        )

        // A later personal re-sync of the character's own wallet transactions happens to include
        // the same ESI transaction_id (the character funded it through the corp wallet).
        insertTx(1, "2024-01-01", isBuy = false, total = 500.0, characterId = 1)

        val row = WalletDao.getTransactions(corporationId = 999).single()
        row["corporation_id"] shouldBe 999
        WalletDao.getTransactions(characterId = 1) shouldHaveSize 0
    }

    @Test
    fun `insertJournalEntry does not let a later personal sync overwrite an entry already attributed to a corp`() {
        WalletDao.insertJournalEntry(
            entryId = 1,
            date = "2024-01-01T10:00:00",
            amount = -16.0,
            balance = 1000.0,
            reason = "",
            refType = "transaction_tax",
            firstPartyId = 0,
            firstPartyName = "",
            secondPartyId = 0,
            secondPartyName = "",
            taxAmount = null,
            isCorp = true,
            characterId = null,
            corporationId = 999,
            divisionId = 1,
        )

        insertJournal(1, "2024-01-01T10:00:00", amount = -16.0, balance = 1000.0, characterId = 1)

        val row = WalletDao.getJournalEntries(corporationId = 999).single()
        row["corporation_id"] shouldBe 999
        WalletDao.getJournalEntries(characterId = 1) shouldHaveSize 0
    }

    @Test
    fun `getTradingPnlBreakdown ignores market escrow and other non-trading journal entries`() {
        insertTx(1, "2024-01-01", isBuy = false, total = 200.0)
        insertJournal(101, "2024-01-01T10:00:00", amount = -500.0, balance = 1000.0, refType = "market_escrow")
        insertJournal(102, "2024-01-01T10:01:00", amount = 500.0, balance = 1500.0, refType = "market_escrow_refund")
        insertJournal(103, "2024-01-01T10:02:00", amount = 1_000_000.0, balance = 2500.0, refType = "bounty_prizes")

        val day = WalletDao.getTradingPnlBreakdown(characterId = 1).single { it.date == "2024-01-01" }

        day.income should (200.0 plusOrMinus TOLERANCE)
        day.expenses should (0.0 plusOrMinus TOLERANCE)
    }
}
