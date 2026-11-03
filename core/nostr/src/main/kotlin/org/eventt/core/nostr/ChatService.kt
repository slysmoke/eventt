package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.eventt.core.database.StaticDataDao
import java.security.MessageDigest

// NIP-28 channel message and NIP-25 reaction.
const val CHAT_MESSAGE_KIND = 42
const val CHAT_REACTION_KIND = 7

// Chat membership, addressable (one per pubkey). Next to GIVEAWAY_KIND; unassigned in the NIPs list.
const val CHAT_MEMBER_KIND = 30738
const val CHAT_MEMBER_D_TAG = "eventt-chat-member"

// Every chat event carries it, so one subscription covers messages, votes and members.
const val CHAT_T_TAG = "eventt-chat"

// Suggestions are long-form Markdown; chat lines stay short.
const val CHAT_MAX_LENGTH = 2000
const val CHAT_TOPIC_MAX_LENGTH = 20000
const val CHAT_TITLE_MAX_LENGTH = 120

private const val JOINED_SETTING = "chat_joined_"
private const val MENTIONS_SEEN_SETTING = "chat_mentions_seen_at"

/**
 * The two rooms. NIP-28 roots a channel at a kind-40 creation event; ours are fixed ids instead,
 * so no one key "owns" a room (other NIP-28 clients see the messages, just without room metadata).
 */
enum class ChatRoom(
    val channelId: String,
) {
    GENERAL(sha256Hex("eventt-chat-general")),
    IDEAS(sha256Hex("eventt-chat-ideas")),
}

/**
 * General: a message, optionally replying to another ([replyToId]). Ideas: a topic ([title] set,
 * Markdown [content]) or a comment on one ([topicId]); comments can reply to each other too.
 */
data class ChatMessage(
    val id: String,
    val pubkey: String,
    val name: String,
    val charId: Int?,
    val room: ChatRoom,
    val topicId: String?,
    val title: String?,
    val content: String,
    val createdAt: Long,
    val replyToId: String? = null,
    // Pubkeys @mentioned (and the replied-to author).
    val mentions: List<String> = emptyList(),
)

data class ChatVote(
    val value: Int,
    val createdAt: Long,
)

/** Someone who switched the chat on; only they show in the member list. */
data class ChatMember(
    val pubkey: String,
    val name: String,
    val charId: Int?,
    val joined: Boolean,
    val updatedAt: Long,
)

object ChatService {
    private val _messages = MutableStateFlow<Map<String, ChatMessage>>(emptyMap())
    val messages: StateFlow<Map<String, ChatMessage>> = _messages.asStateFlow()

    // topic id -> voter pubkey -> their latest vote.
    private val _votes = MutableStateFlow<Map<String, Map<String, ChatVote>>>(emptyMap())
    val votes: StateFlow<Map<String, Map<String, ChatVote>>> = _votes.asStateFlow()

    private val _members = MutableStateFlow<Map<String, ChatMember>>(emptyMap())
    val members: StateFlow<Map<String, ChatMember>> = _members.asStateFlow()

    // Mentions of us newer than this are unread (the nav badge).
    private val _mentionsSeenAt = MutableStateFlow<Long?>(null)
    val mentionsSeenAt: StateFlow<Long?> = _mentionsSeenAt.asStateFlow()

    suspend fun post(
        identity: NostrIdentity,
        room: ChatRoom,
        content: String,
        title: String? = null,
        topicId: String? = null,
        replyTo: ChatMessage? = null,
        mentions: List<String> = emptyList(),
    ) {
        val event =
            buildMessage(
                QuartzGateway.signerFor(identity.keyPair),
                identity.label,
                identity.characterId,
                room,
                content,
                title,
                topicId,
                replyTo,
                mentions,
            )
        onEvent(event)
        NostrRelayManager.publish(event)
    }

    // ponytail: one vote per key, and keys are free -- a determined spammer can stuff votes.
    suspend fun vote(
        identity: NostrIdentity,
        topic: ChatMessage,
        value: Int,
    ) {
        val event = buildVote(QuartzGateway.signerFor(identity.keyPair), topic, value)
        onEvent(event)
        NostrRelayManager.publish(event)
    }

    /** Local answer first (relays may not have replayed our member event yet). */
    suspend fun isJoined(pubkey: String): Boolean = withContext(Dispatchers.IO) { StaticDataDao.getSetting(JOINED_SETTING + pubkey) == "1" }

    suspend fun setJoined(
        identity: NostrIdentity,
        joined: Boolean,
    ) {
        withContext(Dispatchers.IO) { StaticDataDao.setSetting(JOINED_SETTING + identity.pubkey, if (joined) "1" else "0") }
        val event = buildMember(QuartzGateway.signerFor(identity.keyPair), identity.label, identity.characterId, joined)
        onEvent(event)
        NostrRelayManager.publish(event)
    }

    suspend fun loadMentionsSeenAt() {
        if (_mentionsSeenAt.value != null) return
        _mentionsSeenAt.value = withContext(Dispatchers.IO) { StaticDataDao.getSetting(MENTIONS_SEEN_SETTING)?.toLongOrNull() ?: 0L }
    }

    suspend fun markMentionsSeen() {
        val now = System.currentTimeMillis() / 1000
        _mentionsSeenAt.value = now
        withContext(Dispatchers.IO) { StaticDataDao.setSetting(MENTIONS_SEEN_SETTING, now.toString()) }
    }

    fun buildMessage(
        signer: NostrSignerSync,
        name: String,
        charId: Int?,
        room: ChatRoom,
        content: String,
        title: String? = null,
        topicId: String? = null,
        replyTo: ChatMessage? = null,
        mentions: List<String> = emptyList(),
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): Event {
        val tags =
            buildList {
                add(arrayOf("e", room.channelId, "", "root"))
                topicId?.let { add(arrayOf("e", it, "", "reply")) }
                // NIP-18 quote: which message this one answers.
                replyTo?.let { add(arrayOf("q", it.id)) }
                (listOfNotNull(replyTo?.pubkey) + mentions).distinct().forEach { add(arrayOf("p", it)) }
                add(arrayOf("t", CHAT_T_TAG))
                add(arrayOf("name", name))
                charId?.let { add(arrayOf("char_id", it.toString())) }
                // NIP-14 subject.
                title?.let { add(arrayOf("subject", it.take(CHAT_TITLE_MAX_LENGTH))) }
            }
        val max = if (title != null) CHAT_TOPIC_MAX_LENGTH else CHAT_MAX_LENGTH
        return QuartzGateway.signEvent(signer, createdAt, CHAT_MESSAGE_KIND, tags.toTypedArray(), content.take(max))
    }

    fun buildVote(
        signer: NostrSignerSync,
        topic: ChatMessage,
        value: Int,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): Event =
        QuartzGateway.signEvent(
            signer,
            createdAt,
            CHAT_REACTION_KIND,
            arrayOf(
                arrayOf("e", topic.id),
                arrayOf("p", topic.pubkey),
                arrayOf("k", CHAT_MESSAGE_KIND.toString()),
                arrayOf("t", CHAT_T_TAG),
            ),
            if (value > 0) "+" else "-",
        )

    fun buildMember(
        signer: NostrSignerSync,
        name: String,
        charId: Int?,
        joined: Boolean,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): Event {
        val tags =
            buildList {
                add(arrayOf("d", CHAT_MEMBER_D_TAG))
                add(arrayOf("t", CHAT_T_TAG))
                add(arrayOf("name", name))
                charId?.let { add(arrayOf("char_id", it.toString())) }
                add(arrayOf("joined", if (joined) "1" else "0"))
            }
        return QuartzGateway.signEvent(signer, createdAt, CHAT_MEMBER_KIND, tags.toTypedArray(), "")
    }

    private fun tag(
        event: Event,
        name: String,
    ) = event.tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    // e tag with the given NIP-10 marker.
    private fun eTag(
        event: Event,
        marker: String,
    ) = event.tags.firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == marker }?.get(1)

    private fun name(event: Event) = tag(event, "name")?.take(40)?.takeIf { it.isNotBlank() } ?: event.pubKey.take(8)

    fun parseMessage(event: Event): ChatMessage? {
        if (event.kind != CHAT_MESSAGE_KIND) return null
        val room = ChatRoom.entries.find { it.channelId == eTag(event, "root") } ?: return null
        val topicId = eTag(event, "reply")
        val title = tag(event, "subject")?.take(CHAT_TITLE_MAX_LENGTH)?.takeIf { it.isNotBlank() }
        // General has neither; an Ideas event is a topic (title) or a comment (topic id).
        val ok =
            when (room) {
                ChatRoom.GENERAL -> topicId == null
                ChatRoom.IDEAS -> (title != null) != (topicId != null)
            }
        if (!ok || event.content.isBlank()) return null
        val isTopic = room == ChatRoom.IDEAS && topicId == null
        return ChatMessage(
            id = event.id,
            pubkey = event.pubKey,
            name = name(event),
            charId = tag(event, "char_id")?.toIntOrNull(),
            room = room,
            topicId = topicId,
            title = title.takeIf { isTopic },
            content = event.content.take(if (isTopic) CHAT_TOPIC_MAX_LENGTH else CHAT_MAX_LENGTH),
            createdAt = event.createdAt,
            replyToId = tag(event, "q"),
            mentions =
                event.tags
                    .filter { it.size >= 2 && it[0] == "p" }
                    .map { it[1] }
                    .distinct(),
        )
    }

    fun parseMember(event: Event): ChatMember? {
        if (event.kind != CHAT_MEMBER_KIND || runCatching { event.dTag() }.getOrNull() != CHAT_MEMBER_D_TAG) return null
        return ChatMember(event.pubKey, name(event), tag(event, "char_id")?.toIntOrNull(), tag(event, "joined") == "1", event.createdAt)
    }

    /** Called by [NostrRelayManager]'s chat subscription and right after our own publishes. */
    fun onEvent(event: Event) {
        // Relays are untrusted: a forged event could put words in someone else's name.
        if (!runCatching { event.verify() }.getOrDefault(false)) return
        when (event.kind) {
            CHAT_MESSAGE_KIND -> {
                val m = parseMessage(event) ?: return
                _messages.update { if (m.id in it) it else it + (m.id to m) }
            }

            CHAT_MEMBER_KIND -> {
                val m = parseMember(event) ?: return
                // Relays replay in any order; a same-second toggle from this machine arrives last.
                _members.update { all -> if ((all[m.pubkey]?.updatedAt ?: -1) > m.updatedAt) all else all + (m.pubkey to m) }
            }

            CHAT_REACTION_KIND -> {
                val topic = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
                val value =
                    when (event.content) {
                        "+" -> 1
                        "-" -> -1
                        else -> return
                    }
                _votes.update { all ->
                    val forTopic = all[topic].orEmpty()
                    val prev = forTopic[event.pubKey]
                    // Relays replay in any order: the newest vote per voter wins (a same-second
                    // re-vote from this machine arrives last, so ties go to the later one).
                    if (prev != null && prev.createdAt > event.createdAt) {
                        all
                    } else {
                        all + (topic to (forTopic + (event.pubKey to ChatVote(value, event.createdAt))))
                    }
                }
            }
        }
    }
}

private fun sha256Hex(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
