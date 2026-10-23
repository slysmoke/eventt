package org.eventt.features.dashboard

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.eventt.core.database.WalletDao.BalanceRow
import org.eventt.features.orders.CostBasisService.RealizedSellTx
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DashboardDataTest {
    private val today = LocalDate.parse("2026-10-05")

    @Test
    fun `buildDays sums each wallet's last balance and carries every series across quiet days`() {
        val days =
            buildDays(
                walletRows =
                    listOf(
                        BalanceRow("2026-10-01T10:00:00", "char:1", 100.0),
                        BalanceRow("2026-10-01T11:00:00", "char:1", 150.0),
                        BalanceRow("2026-10-02T09:00:00", "corp:9:1", 1000.0),
                        BalanceRow("2026-10-04T09:00:00", "char:1", 50.0),
                    ),
                stockCostByDay = mapOf("2026-10-02" to 500.0, "2026-10-04" to 200.0),
                escrowFlowByDay = mapOf("2026-10-03" to 100.0, "2026-10-04" to -30.0),
                itemsByDay = mapOf("2026-10-03" to 900.0),
                realizedByDay = mapOf("2026-10-04" to 30.0),
                walletNow = null,
                today = today,
            )

        days.map { it.day } shouldBe listOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05")
        days.map { it.wallet } shouldBe listOf(150.0, 1150.0, 1150.0, 1050.0, 1050.0)
        days.map { it.stock } shouldBe listOf(0.0, 500.0, 500.0, 200.0, 200.0)
        days.map { it.escrow } shouldBe listOf(0.0, 0.0, 100.0, 70.0, 70.0)
        // Before the first items snapshot: stock at cost + that day's gap (900 - 500).
        days.map { it.items } shouldBe listOf(400.0, 900.0, 900.0, 900.0, 900.0)
        days.map { it.netWorth } shouldBe listOf(550.0, 2050.0, 2150.0, 2020.0, 2020.0)
        days.map { it.realized } shouldBe listOf(0.0, 0.0, 0.0, 30.0, 0.0)
    }

    @Test
    fun `buildDays puts the live wallet balance on today only`() {
        val days =
            buildDays(
                listOf(BalanceRow("2026-10-04T09:00:00", "char:1", 50.0)),
                emptyMap(),
                emptyMap(),
                emptyMap(),
                emptyMap(),
                walletNow = 80.0,
                today = today,
            )

        days.map { it.wallet } shouldBe listOf(50.0, 80.0)
    }

    private fun sell(
        date: String,
        profit: Double,
        cost: Double = 100.0,
    ) = RealizedSellTx(date, 1, 1, sellPrice = cost + profit, costBasis = cost, profit = profit, marginPct = 0.0)

    @Test
    fun `rangeStats counts only the range and measures drawdown from the running peak`() {
        val days =
            listOf(
                Day("2026-10-01", 1000.0, 0.0, 0.0, 50.0),
                Day("2026-10-02", 1000.0, 0.0, 0.0, -80.0),
                Day("2026-10-03", 1100.0, 0.0, 0.0, 20.0),
            )
        val sells =
            listOf(
                sell("2026-09-30T12:00:00", 999.0), // before the range
                sell("2026-10-01T12:00:00", 50.0),
                sell("2026-10-02T12:00:00", -80.0),
                sell("2026-10-03T12:00:00", 20.0),
            )
        val txs =
            listOf(
                mapOf("date" to "2026-10-01T12:00:00", "is_buy" to false, "total" to 150.0),
                mapOf("date" to "2026-10-02T12:00:00", "is_buy" to true, "total" to 100.0),
            )

        val st = rangeStats(days, sells, txs, mapOf("2026-10-01" to 5.0, "2026-09-01" to 99.0))

        st.realized shouldBe -10.0
        st.sells shouldBe 3
        st.winRatePct!! should (66.67 plusOrMinus 0.01)
        st.profitFactor!! should (70.0 / 80.0 plusOrMinus 1e-9)
        st.maxDrawdown shouldBe 80.0
        st.fees shouldBe 5.0
        st.cashFlow shouldBe 45.0
        st.bestDay?.day shouldBe "2026-10-01"
        st.worstDay?.day shouldBe "2026-10-02"
        st.netWorthChangePct!! should (10.0 plusOrMinus 1e-9)
    }
}
