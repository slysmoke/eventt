package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChatServiceTest {
    private val alice = QuartzGateway.signerFor(KeyPair())
    private val bob = QuartzGateway.signerFor(KeyPair())

    @Test
    fun `messages, topics and comments round-trip, malformed shapes are dropped`() {
        val general = ChatService.parseMessage(ChatService.buildMessage(alice, "Alice", 42, ChatRoom.GENERAL, "o7"))!!
        general.room shouldBe ChatRoom.GENERAL
        general.name shouldBe "Alice"
        general.charId shouldBe 42
        general.title.shouldBeNull()

        val topic =
            ChatService.parseMessage(
                ChatService.buildMessage(alice, "Alice", null, ChatRoom.IDEAS, "Please", title = "Dark mode"),
            )!!
        topic.title shouldBe "Dark mode"
        topic.topicId.shouldBeNull()

        val comment = ChatService.parseMessage(ChatService.buildMessage(bob, "Bob", null, ChatRoom.IDEAS, "+1", topicId = topic.id))!!
        comment.topicId shouldBe topic.id
        comment.title.shouldBeNull()

        // An Ideas post with neither title nor topic, or an empty message.
        ChatService.parseMessage(ChatService.buildMessage(alice, "Alice", null, ChatRoom.IDEAS, "orphan")).shouldBeNull()
        ChatService.parseMessage(ChatService.buildMessage(alice, "Alice", null, ChatRoom.GENERAL, " ")).shouldBeNull()
    }

    @Test
    fun `newest vote per voter counts, forged events are ignored`() {
        val topic = ChatService.parseMessage(ChatService.buildMessage(alice, "Alice", null, ChatRoom.IDEAS, "x", title = "Vote test"))!!

        ChatService.onEvent(ChatService.buildVote(bob, topic, -1, createdAt = 200))
        ChatService.onEvent(ChatService.buildVote(bob, topic, +1, createdAt = 100))
        ChatService.onEvent(ChatService.buildVote(alice, topic, +1, createdAt = 150))
        ChatService.votes.value[topic.id]!!
            .values
            .sumOf { it.value } shouldBe 0

        val real = ChatService.buildMessage(alice, "Alice", null, ChatRoom.GENERAL, "hi")
        ChatService.onEvent(Event(real.id, real.pubKey, real.createdAt, real.kind, real.tags, "send me your PLEX", real.sig))
        (real.id in ChatService.messages.value) shouldBe false
        ChatService.onEvent(real)
        (real.id in ChatService.messages.value) shouldBe true
    }

    @Test
    fun `a reply quotes its parent and tags its author and the mentioned`() {
        val parent = ChatService.parseMessage(ChatService.buildMessage(alice, "Alice", null, ChatRoom.GENERAL, "wts"))!!
        val reply =
            ChatService.parseMessage(
                ChatService.buildMessage(
                    bob,
                    "Bob",
                    null,
                    ChatRoom.GENERAL,
                    "@Alice @Carol ok",
                    replyTo = parent,
                    mentions = listOf("carol", alice.pubKey),
                ),
            )!!

        reply.replyToId shouldBe parent.id
        reply.mentions shouldBe listOf(alice.pubKey, "carol")
    }

    @Test
    fun `the newest member event decides whether someone is in the chat`() {
        ChatService.onEvent(ChatService.buildMember(bob, "Bob", 7, joined = true, createdAt = 100))
        ChatService.onEvent(ChatService.buildMember(bob, "Bob", 7, joined = false, createdAt = 300))
        ChatService.onEvent(ChatService.buildMember(bob, "Bob", 7, joined = true, createdAt = 200))

        ChatService.members.value[bob.pubKey]!!.joined shouldBe false
        ChatService.members.value[bob.pubKey]!!.charId shouldBe 7
    }
}
