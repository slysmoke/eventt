package org.eventt.features.wallet

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.eventt.core.database.DatabaseManager
import org.eventt.ui.theme.DarkColorScheme
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO

// Wallet stats from synthetic rows, plus a headless render of the body written to
// build/wallet-screens/wallet.png for a look without launching the app.
@OptIn(ExperimentalTestApi::class)
class WalletBodyTest {
    private val today = LocalDate.parse("2026-10-24")

    private fun tx(
        id: Long,
        daysAgo: Long,
        name: String,
        buy: Boolean,
        total: Double,
        client: String = "Some Trader",
    ) = Tx(
        id,
        today.minusDays(daysAgo).toString() + "T12:00:00",
        34 + name.length,
        name,
        10,
        total / 10,
        total,
        buy,
        client,
        "Jita IV - Moon 4 - Caldari Navy Assembly Plant",
    )

    private fun entry(
        id: Long,
        daysAgo: Long,
        ref: String,
        amount: Double,
    ) = Entry(id, today.minusDays(daysAgo).toString() + "T12:00:00", ref, "", amount, null, 1e9)

    private val txs =
        listOf(
            tx(1, 1, "Tritanium", true, 100.0),
            tx(2, 2, "Tritanium", false, 150.0, "Other Trader"),
            tx(3, 3, "Pyerite", false, 50.0),
            tx(4, 40, "Pyerite", true, 1000.0),
        )
    private val journal =
        listOf(
            entry(1, 1, "market_escrow", -100.0),
            entry(2, 2, "market_transaction", 150.0),
            entry(3, 2, "transaction_tax", -5.0),
            entry(4, 2, "brokers_fee", -3.0),
            entry(5, 40, "brokers_fee", -50.0),
        )

    @Test
    fun `totals follow the period`() {
        val t = walletTotals(txs, journal, null, Period.D30, today)

        t.sales shouldBe 200.0
        t.sells shouldBe 2
        t.purchases shouldBe 100.0
        t.fees shouldBe 8.0
        t.cashFlow shouldBe 42.0
        t.items shouldBe 2
        t.realized shouldBe null
        walletTotals(txs, journal, null, Period.ALL, today).purchases shouldBe 1100.0
        // 7d covers today and the six days before it.
        Period.D7.since(today) shouldBe "2026-10-18"
    }

    @Test
    fun `items, clients and ref types group the rows`() {
        byItem(txs, null).map { it.label to it.turnover } shouldBe listOf("Pyerite" to 1050.0, "Tritanium" to 250.0)
        byClient(txs).first().let { (it.label to it.bought) shouldBe ("Some Trader" to 1100.0) }
        byRefType(journal).first().refType shouldBe "market_transaction"
        isFee("transaction_tax") shouldBe true
        isFee("contract_brokers_fee_corp") shouldBe true
        isFee("market_escrow") shouldBe false
    }

    @Test
    fun `body renders`() {
        DatabaseManager.close()
        DatabaseManager.initialize(":memory:")
        runDesktopComposeUiTest(1440, 760) {
            setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Surface { WalletBody(1.23e9, txs, journal, emptyList(), null, null, today = today) }
                }
            }
            waitForIdle()
            onAllNodesWithText("Tritanium", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() shouldBe true
            val out = File("build/wallet-screens").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "wallet.png"))
        }
    }
}
