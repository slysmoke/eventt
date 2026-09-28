package org.eventt.features.market

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.eventt.core.database.WalletDao
import org.junit.jupiter.api.Test

class TxCostBasisTest {
    private fun tx(
        date: String,
        qty: Int,
        price: Double,
        buy: Boolean,
    ) = WalletDao.RawTxRecord(date, 18789, "Coreli", qty, price, isBuy = buy)

    @Test
    fun `a rebuy after a closed round trip isn't averaged with the sold lots`() {
        // The real Coreli case: 5 bought ~241M and all sold ~276M in July, 34 rebought in September.
        val b =
            txCostBasis(
                listOf(
                    tx("2026-07-17", 5, 241.0, buy = true),
                    tx("2026-07-18", 5, 276.0, buy = false),
                    tx("2026-09-08", 20, 186.0, buy = true),
                    tx("2026-09-14", 14, 177.0, buy = true),
                ),
            )
        b.avgCost!! shouldBe ((20 * 186.0 + 14 * 177.0) / 34 plusOrMinus 1e-9)
        b.realized shouldBe (5 * 35.0 plusOrMinus 1e-9)
    }

    @Test
    fun `partial sells keep the average, and selling untracked stock is ignored`() {
        val b =
            txCostBasis(
                listOf(
                    tx("2026-01-01", 10, 100.0, buy = true),
                    tx("2026-01-02", 4, 130.0, buy = false),
                    tx("2026-01-03", 10, 90.0, buy = true),
                    tx("2026-01-04", 50, 120.0, buy = false), // only 16 tracked units
                ),
            )
        b.avgCost shouldBe null
        // 4*(130-100) + 16*(120 - (6*100+10*90)/16)
        b.realized shouldBe (120.0 + 16 * (120.0 - 1500.0 / 16) plusOrMinus 1e-9)
    }
}
