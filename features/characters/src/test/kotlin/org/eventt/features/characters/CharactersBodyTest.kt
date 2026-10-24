package org.eventt.features.characters

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.CorporationDao
import org.eventt.core.database.DatabaseManager
import org.eventt.core.model.CharacterModel
import org.eventt.ui.theme.DarkColorScheme
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO

// Builds the rows from a seeded in-memory database (personal vs corp orders, trades in and out of
// the 30-day window) and renders the body to build/characters-screens/characters.png.
@OptIn(ExperimentalTestApi::class)
class CharactersBodyTest {
    private val today = LocalDate.parse("2026-10-24")

    private fun exec(sql: String) = DatabaseManager.transaction { createStatement().use { it.execute(sql) } }

    private fun seed() {
        DatabaseManager.close()
        DatabaseManager.initialize(":memory:")
        CharacterDao.insert(CharacterModel(1, "Sasha Winston", "r", "a", Long.MAX_VALUE, 10, "Yellow Aviation"))
        CharacterDao.insert(CharacterModel(2, "Li Slysmoke", "r", "a", 0, 10, "Yellow Aviation"))
        CorporationDao.insert(10, "Yellow Aviation", "WIN2K", null)
        CorporationDao.insert(20, "Other Corp", "OTHR", null)
        CorporationDao.track(10)
        // Sasha: 1 sell + 2 buys of her own; Li: 1 order placed for the corp.
        val order =
            "INSERT INTO active_orders " +
                "(order_id, type_id, price, volume_total, volume_remaining, is_buy_order, issued, character_id, corporation_id)"
        exec("$order VALUES (1, 34, 1.0, 1, 1, 0, '2026-10-01', 1, NULL)")
        exec("$order VALUES (2, 34, 1.0, 1, 1, 1, '2026-10-01', 1, NULL)")
        exec("$order VALUES (3, 34, 1.0, 1, 1, 1, '2026-10-01', 1, NULL)")
        exec("$order VALUES (4, 34, 1.0, 1, 1, 0, '2026-10-01', 2, 10)")
        // Sasha traded inside and outside the 30-day window; the corp once inside it.
        val tx = "INSERT INTO transactions (transaction_id, date, type_id, quantity, unit_price, total, character_id, corporation_id)"
        exec("$tx VALUES (1, '2026-10-20', 34, 1, 1.0, 1.0, 1, NULL)")
        exec("$tx VALUES (2, '2026-08-01', 34, 1, 1.0, 1.0, 1, NULL)")
        exec("$tx VALUES (3, '2026-10-22', 34, 1, 1.0, 1.0, NULL, 10)")
    }

    @Test
    fun `rows split personal and corp activity`() {
        seed()
        val (chars, corps) = loadRows(now = 1000, today = today)
        val sasha = chars.first { it.c.id == 1 }
        sasha.tokenValid shouldBe true
        (sasha.sellOrders to sasha.buyOrders) shouldBe (1 to 2)
        sasha.trades30d shouldBe 1
        sasha.lastTrade shouldBe "2026-10-20"
        chars.first { it.c.id == 2 }.let { (it.corpOrders to it.tokenValid) shouldBe (1 to false) }
        corps.first { it.id == 10 }.let { (Triple(it.tracked, it.orders, it.trades30d)) shouldBe Triple(true, 1, 1) }
        corps.first { it.id == 10 }.members shouldBe listOf("Li Slysmoke", "Sasha Winston")
    }

    @Test
    fun `body renders`() {
        seed()
        val (chars, corps) = loadRows(now = 1000, today = today)
        runDesktopComposeUiTest(1280, 600) {
            setContent { MaterialTheme(colorScheme = DarkColorScheme) { Surface { CharactersBody(chars, corps) } } }
            waitForIdle()
            onAllNodesWithText("Sasha Winston", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() shouldBe true
            val out = File("build/characters-screens").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "characters.png"))
        }
    }
}
