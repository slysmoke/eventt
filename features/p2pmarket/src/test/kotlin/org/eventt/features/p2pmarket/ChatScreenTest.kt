package org.eventt.features.p2pmarket

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import org.eventt.core.database.DatabaseManager
import org.eventt.core.nostr.ChatRoom
import org.eventt.core.nostr.ChatService
import org.eventt.core.nostr.NostrIdentity
import org.eventt.core.nostr.QuartzGateway
import org.eventt.ui.theme.DarkColorScheme
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

// Renders the chat headless (General, Suggestions, a topic, the editor) and writes PNGs to
// build/chat-screens/ for a look without launching the app.
@OptIn(ExperimentalTestApi::class)
class ChatScreenTest {
    @Test
    fun `chat views render`() {
        DatabaseManager.close()
        DatabaseManager.initialize(":memory:")
        val now = System.currentTimeMillis() / 1000
        val meKey = KeyPair()
        val me = NostrIdentity(QuartzGateway.pubKeyHex(meKey), "Sasha Winston", meKey, 2113351129)
        val people = listOf(me.label to meKey, "Li Slysmoke" to KeyPair(), "Ash Knight" to KeyPair(), "Old Timer" to KeyPair())
        val signers = people.associate { (n, k) -> n to QuartzGateway.signerFor(k) }
        people.forEachIndexed { i, (n, _) ->
            ChatService.onEvent(
                ChatService.buildMember(
                    signers.getValue(n),
                    n,
                    2113351129 + i,
                    true,
                    now - 999,
                ),
            )
        }

        fun say(
            who: String,
            text: String,
            at: Long,
            room: ChatRoom = ChatRoom.GENERAL,
            title: String? = null,
            topicId: String? = null,
            replyTo: String? = null,
            mentions: List<String> = emptyList(),
        ): String {
            val e =
                ChatService.buildMessage(
                    signers.getValue(who),
                    who,
                    2113351129 + people.indexOfFirst { it.first == who },
                    room,
                    text,
                    title,
                    topicId,
                    replyTo?.let { ChatService.messages.value[it] },
                    mentions,
                    at,
                )
            ChatService.onEvent(e)
            return e.id
        }
        say("Old Timer", "Anyone seen tritanium move today?", now - 86400 - 600)
        val q = say("Li Slysmoke", "Jita buy orders up about 4% since downtime.", now - 86400 - 500)
        say("Li Slysmoke", "Sell side is thin too.", now - 86400 - 450)
        say("Ash Knight", "Thanks, adding a ladder.", now - 3000, replyTo = q)
        say("Li Slysmoke", "@Sasha Winston did the PLEX draw run yet?", now - 1200, mentions = listOf(me.pubkey))
        say("Sasha Winston", "Tonight after the next block.", now - 1100)

        val topic =
            say(
                "Ash Knight",
                "## Why\nRight now I copy prices by hand.\n\n- **Export** current ladder as CSV\n- Import into *Excel*\n\n1. one click\n2. done",
                now - 7200,
                ChatRoom.IDEAS,
                title = "Export order ladder to CSV",
            )
        say("Old Timer", "Dark theme for charts", now - 5000, ChatRoom.IDEAS, title = "Chart colour presets")
        say("Li Slysmoke", "+1, would use it daily", now - 3600, ChatRoom.IDEAS, topicId = topic)
        say("Sasha Winston", "@Li Slysmoke agreed, will look at it", now - 600, ChatRoom.IDEAS, topicId = topic)
        people.forEach { (n, _) ->
            ChatService.onEvent(ChatService.buildVote(signers.getValue(n), ChatService.messages.value.getValue(topic), 1))
        }

        val out = File("build/chat-screens").apply { mkdirs() }
        listOf<Pair<String, @androidx.compose.runtime.Composable () -> Unit>>(
            "general" to { ChatLayout(me) {} },
            "ideas" to { ChatLayout(me, ChatRoom.IDEAS) {} },
            "topic" to { ChatLayout(me, ChatRoom.IDEAS, topic) {} },
            "editor" to { IdeaEditor(Modifier, {}) { _, _ -> } },
            "join" to { JoinCard {} },
        ).forEach { (name, content) ->
            runDesktopComposeUiTest(1200, 760) {
                setContent { MaterialTheme(colorScheme = DarkColorScheme) { Surface { content() } } }
                waitForIdle()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
            }
        }
    }
}
