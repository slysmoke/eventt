package org.eventt.core.database

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.eventt.core.model.toPnlWindow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

private const val TOLERANCE = 0.01

// Simulates a month of realistic corp trading -- buy a lot, sell it the same day at either a
// markup or a loss, plus the sales tax and broker fee EVE actually charges -- with weekends
// skipped entirely, so the daily breakdown has gaps just like a real wallet. After each day's
// data is inserted, it checks the DB-level per-day P&L and the today/7d/30d rollups (the ones
// Dashboard and Wallet's P&L tab show) against hand-computed expectations, day by day through
// the month. This is the scenario that exposed the Wallet screen bug where "today" and the
// 7/30-day totals were computed by list position instead of by calendar date.
class WalletPnlSimulationTest {
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

    private val start: LocalDate = LocalDate.of(2026, 6, 1)
    private val characterId = 1

    private data class SimDay(
        val date: LocalDate,
        val income: Double,
        val expenses: Double,
        val net: Double,
    )

    private fun insertTx(
        id: Long,
        date: LocalDate,
        isBuy: Boolean,
        total: Double,
    ) = WalletDao.insertTransaction(
        transactionId = id,
        date = date.toString(),
        typeId = 34,
        typeName = "Tritanium",
        quantity = 1000,
        unitPrice = total / 1000,
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

    private fun insertJournal(
        id: Long,
        date: LocalDate,
        hour: Int,
        amount: Double,
        refType: String,
    ) = WalletDao.insertJournalEntry(
        entryId = id,
        date = "${date}T%02d:00:00".format(hour),
        amount = amount,
        balance = 0.0,
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
    fun `P&L tracks real profit and loss day by day across a simulated month of corp trading`() {
        var txId = 1L
        var journalId = 1L
        var activeDayCount = 0
        // Days with actual trading activity so far, in chronological order -- built up
        // incrementally, exactly like a real wallet accumulates history day by day.
        val seenDays = mutableListOf<SimDay>()

        for (offset in 0 until 30) {
            val date = start.plusDays(offset.toLong())
            val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

            if (!isWeekend) {
                // Profitable two days out of every three, a loss on the third -- a realistic mixed month.
                val isProfitDay = activeDayCount % 3 != 0
                activeDayCount++

                val buyTotal = 100_000.0
                val sellTotal = if (isProfitDay) 120_000.0 else 90_000.0
                val brokerFee = buyTotal * 0.01
                val tax = sellTotal * 0.02

                insertTx(txId++, date, isBuy = true, total = buyTotal)
                insertTx(txId++, date, isBuy = false, total = sellTotal)
                insertJournal(journalId++, date, hour = 10, amount = -brokerFee, refType = "brokers_fee")
                insertJournal(journalId++, date, hour = 18, amount = -tax, refType = "transaction_tax")

                val expenses = buyTotal + brokerFee + tax
                seenDays += SimDay(date, income = sellTotal, expenses = expenses, net = sellTotal - expenses)
            }

            // What Dashboard/Wallet would show if "today" were `date`, given only the data
            // that exists up to this point in the simulation.
            val breakdown = WalletDao.getTradingPnlBreakdown(characterId = characterId, since = start.toString())
            val window = breakdown.toPnlWindow(date)

            val expectedToday = seenDays.lastOrNull { it.date == date }?.net ?: 0.0
            val expectedNet7 = seenDays.filter { it.date >= date.minusDays(7) }.sumOf { it.net }
            val expectedNet30 = seenDays.filter { it.date >= date.minusDays(30) }.sumOf { it.net }
            val expectedNetAll = seenDays.sumOf { it.net }

            withClue("as of $date (weekend=$isWeekend)") {
                window.todayNet should (expectedToday plusOrMinus TOLERANCE)
                window.net7d should (expectedNet7 plusOrMinus TOLERANCE)
                window.net30d should (expectedNet30 plusOrMinus TOLERANCE)
                window.netAll should (expectedNetAll plusOrMinus TOLERANCE)
            }
        }

        // Sanity: the DB-level per-day figures match the buy/sell/tax/fee math exactly.
        val finalBreakdown = WalletDao.getTradingPnlBreakdown(characterId = characterId, since = start.toString())
        seenDays.forEach { day ->
            val actual = finalBreakdown.single { it.date == day.date.toString() }
            actual.income should (day.income plusOrMinus TOLERANCE)
            actual.expenses should (day.expenses plusOrMinus TOLERANCE)
            actual.net should (day.net plusOrMinus TOLERANCE)
        }

        val expectedActiveDays =
            (0 until 30).count { offset ->
                val d = start.plusDays(offset.toLong())
                d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
            }
        seenDays.size shouldBe expectedActiveDays
    }
}
