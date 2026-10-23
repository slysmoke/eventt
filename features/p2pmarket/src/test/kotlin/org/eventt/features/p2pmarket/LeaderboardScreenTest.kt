package org.eventt.features.p2pmarket

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.eventt.core.database.DatabaseManager
import org.eventt.core.nostr.LeaderboardEntry
import org.eventt.core.nostr.LeaderboardStats
import org.eventt.ui.theme.DarkColorScheme
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sin

// Renders the leaderboard headless from a handful of entries and writes a PNG to
// build/leaderboard-screens/ for a look without launching the app.
@OptIn(ExperimentalTestApi::class)
class LeaderboardScreenTest {
    @Test
    fun `the board renders ranked entries with the detail card`() {
        DatabaseManager.close()
        DatabaseManager.initialize(":memory:")
        val now = System.currentTimeMillis() / 1000
        val entries =
            listOf("Sasha Winston" to 5.9e9, "Li Slysmoke" to 4.1e9, "Ash Knight" to -0.6e9, "Old Timer" to 9e9).mapIndexed {
                i,
                (name, pnl),
                ->
                val stats =
                    LeaderboardStats(
                        pnl7d = pnl / 4,
                        pnl30d = pnl,
                        pnl365d = pnl * 9,
                        roi7dPct = 0.3 * (i + 1),
                        roi30dPct = 1.2 * (3 - i),
                        roi365dPct = 9.0,
                        winRate30dPct = 40.0 + 12 * i,
                        margin30dPct = 7.0 - i,
                        profitFactor30d = 1.5 + i,
                        sells30d = 150 - 20 * i,
                        turnover30d = 4.2e10 / (i + 1),
                        daily30d = (0 until 30).map { d -> pnl / 30 * sin((d + i) / 3.0) },
                        netWorth = if (i == 0) 9.27e11 else null,
                    )
                // The last one hasn't republished for two days: it ranks below and shows as stale.
                val at = if (i == 3) now - 2 * 86400 else now - 3600L * (i + 1)
                LeaderboardEntry("pk$i", name, 2113351129 + i, stats, at)
            }
        runDesktopComposeUiTest(1440, 860) {
            setContent { MaterialTheme(colorScheme = DarkColorScheme) { Surface { LeaderboardContent(entries) } } }
            waitForIdle()
            // Leader strip, table row and detail card.
            onAllNodesWithText("Sasha Winston", substring = true, useUnmergedTree = true).assertCountEquals(3)
            val out = File("build/leaderboard-screens").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "leaderboard.png"))
        }
    }
}
