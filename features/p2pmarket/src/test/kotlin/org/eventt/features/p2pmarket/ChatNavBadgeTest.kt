package org.eventt.features.p2pmarket

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.runBlocking
import org.eventt.core.database.DatabaseManager
import org.eventt.core.nostr.ChatRoom
import org.eventt.core.nostr.ChatService
import org.eventt.core.nostr.QuartzGateway
import org.eventt.p2pmarket.generated.resources.Res
import org.eventt.p2pmarket.generated.resources.chat_new_messages
import org.jetbrains.compose.resources.getString
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ChatNavBadgeTest {
    @Test
    fun `dot on a new message, number on a mention, cleared only by opening the chat`() {
        DatabaseManager.close()
        DatabaseManager.initialize(":memory:")
        val me = QuartzGateway.pubKeyHex(KeyPair())
        val other = QuartzGateway.signerFor(KeyPair())
        val dot = runBlocking { getString(Res.string.chat_new_messages) }
        val now = System.currentTimeMillis() / 1000

        fun receive(
            at: Long,
            mentions: List<String> = emptyList(),
        ) = ChatService.onEvent(ChatService.buildMessage(other, "Li", null, ChatRoom.GENERAL, "hi", mentions = mentions, createdAt = at))

        runDesktopComposeUiTest {
            var chatOpen by mutableStateOf(true)
            var joined by mutableStateOf(true)
            setContent { MaterialTheme { ChatNavBadge(chatOpen, joined, setOf(me)) } }

            fun dotShown() = onAllNodesWithContentDescription(dot).fetchSemanticsNodes().isNotEmpty()

            fun numberShown() = onAllNodesWithText("1").fetchSemanticsNodes().isNotEmpty()

            // Everything read while the chat is on screen.
            receive(now + 60)
            waitUntil(timeoutMillis = 5_000) { ChatService.seenAt.value == now + 60 }
            waitForIdle()
            assertFalse(dotShown())

            // On another tab a new message lights the dot and stays lit.
            chatOpen = false
            waitForIdle()
            receive(now + 70)
            waitUntil(timeoutMillis = 5_000) { dotShown() }

            // A mention replaces the dot with a number.
            receive(now + 80, listOf(me))
            waitUntil(timeoutMillis = 5_000) { numberShown() }
            assertFalse(dotShown())

            // Not in the chat: nothing at all.
            joined = false
            waitUntil(timeoutMillis = 5_000) { !numberShown() }
            joined = true
            waitUntil(timeoutMillis = 5_000) { numberShown() }

            // Opening the chat clears both.
            chatOpen = true
            waitUntil(timeoutMillis = 5_000) { !numberShown() }
            assertFalse(dotShown())
        }
    }
}
