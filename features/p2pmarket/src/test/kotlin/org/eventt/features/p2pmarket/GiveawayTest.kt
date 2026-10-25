package org.eventt.features.p2pmarket

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.eventt.core.nostr.Giveaway
import org.eventt.core.nostr.GiveawayParticipant
import org.eventt.core.nostr.GiveawayService
import org.eventt.core.nostr.GiveawayStatus
import org.eventt.core.nostr.LeaderboardEntry
import org.eventt.core.nostr.LeaderboardStats
import org.eventt.ui.common.Panel
import org.eventt.ui.theme.DarkColorScheme
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class GiveawayTest {
    private val now = 1_800_000_000L

    private fun entry(
        key: String,
        charId: Int?,
        ageDays: Long = 0,
        sells: Int? = 10,
        pnl30: Double = 1.0,
    ) = LeaderboardEntry(key, "Trader $key", charId, LeaderboardStats(0.0, pnl30, 0.0, sells30d = sells), now - ageDays * 86400)

    @Test
    fun `eligible traders were active in the window, sold something and count once per character`() {
        val entries =
            listOf(
                entry("a", 1),
                entry("old", 2, ageDays = 15),
                entry("idle", 3, sells = 0),
                entry("legacy", 4, sells = null, pnl30 = 5.0),
                entry("me", 5),
                entry("myAlt", 6),
                entry("dupOld", 7, ageDays = 2),
                entry("dupNew", 7, ageDays = 1),
            )

        eligible(entries, 7, now, excludePubkeys = setOf("me"), excludeCharIds = setOf(6)).map { it.pubkey } shouldBe
            listOf("a", "dupNew", "legacy")
    }

    @Test
    fun `wins add up per trader over drawn giveaways`() {
        val p = { k: String -> GiveawayParticipant(k, k) }
        val all =
            listOf(
                Giveaway("g-1", GiveawayStatus.DRAWN, 500, 2, 7, 0, "", winners = listOf(p("a"), p("b"))),
                Giveaway("g-2", GiveawayStatus.DRAWN, 1000, 1, 7, 0, "", winners = listOf(p("a"))),
                Giveaway("g-3", GiveawayStatus.CANCELLED, 500, 1, 7, 0, ""),
            )

        wins(all) shouldBe mapOf("a" to Wins(2, 1500), "b" to Wins(1, 500))
        currentGiveaway(all)?.id shouldBe "g-2"
    }

    // Writes PNGs to build/giveaway-screens/ for a look without launching the app.
    @Test
    fun `the giveaway card renders every state and the form`() {
        val people =
            listOf("Venalicius Perfide", "Finn Armitage", "HoneyGuru", "Thorfalahnur Kamydes", "Valera Britva").mapIndexed { i, n ->
                GiveawayParticipant("pk$i", n)
            }
        val hash = "00000000000000000002a7c4c1e48d76c5a37902165a270156b7a8d72728a054"
        val base =
            Giveaway("g-1", GiveawayStatus.ANNOUNCED, 500, 3, 7, now + 3 * 86400 + 4 * 3600, "Осенний розыгрыш среди трейдеров рейтинга")
        val closed = base.copy(status = GiveawayStatus.CLOSED, participants = people, btcHeight = 921_004)
        val drawn =
            closed.copy(
                status = GiveawayStatus.DRAWN,
                btcHash = hash,
                winners = GiveawayService.drawWinners(people, hash, "g-1", 3),
            )
        val out = File("build/giveaway-screens").apply { mkdirs() }
        runDesktopComposeUiTest(1200, 640) {
            setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Surface {
                        Column(Modifier.padding(8.dp)) {
                            listOf<@androidx.compose.runtime.Composable () -> Unit>(
                                { GiveawayCard(base, people, setOf("pk1"), isAdmin = false, tip = null, blockHash = null, now = now) },
                                {
                                    GiveawayCard(
                                        closed,
                                        people,
                                        setOf("pk1"),
                                        isAdmin = false,
                                        tip = BlockTip(921_003, now - 35 * 60),
                                        blockHash = null,
                                        now = now,
                                    )
                                },
                                {
                                    GiveawayCard(
                                        drawn,
                                        people,
                                        setOf(drawn.winners[0].pubkey),
                                        isAdmin = false,
                                        tip = BlockTip(921_010, now - 60),
                                        blockHash = hash,
                                        now = now,
                                    )
                                },
                                { AnnounceForm(GiveawayForm(), false, {}, {}, {}) },
                            ).forEach { body -> Panel("PLEX giveaway", Modifier.padding(bottom = 8.dp)) { body() } }
                        }
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Checked against", substring = true).assertExists()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "giveaway.png"))
        }
    }
}
