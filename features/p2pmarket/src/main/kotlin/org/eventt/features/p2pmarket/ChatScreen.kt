package org.eventt.features.p2pmarket

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.nostr.CHAT_MAX_LENGTH
import org.eventt.core.nostr.ChatMember
import org.eventt.core.nostr.ChatMessage
import org.eventt.core.nostr.ChatRoom
import org.eventt.core.nostr.ChatService
import org.eventt.core.nostr.ChatVote
import org.eventt.core.nostr.NostrIdentity
import org.eventt.core.nostr.NostrIdentityService
import org.eventt.core.nostr.PresenceService
import org.eventt.p2pmarket.generated.resources.*
import org.eventt.ui.common.*
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val zone = ZoneId.systemDefault()
private val hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)

// Consecutive messages by one author within this window share one header.
private const val GROUP_SECONDS = 5 * 60

private fun day(sec: Long) = Instant.ofEpochSecond(sec).atZone(zone).toLocalDate()

/** Stable per-author name colour, readable on both themes. */
private fun nameColor(pubkey: String) = Color.hsl((pubkey.hashCode().mod(360)).toFloat(), 0.55f, 0.6f)

@Composable
fun ChatScreen() {
    var me by remember { mutableStateOf<NostrIdentity?>(null) }
    // null = still loading.
    var joined by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val id = withContext(Dispatchers.IO) { NostrIdentityService.getActiveIdentity() }
        me = id
        joined = id?.let { ChatService.isJoined(it.pubkey) } ?: false
    }
    val id = me
    when {
        joined == null -> {
            Unit
        }

        id == null -> {
            Hint(stringResource(Res.string.err_no_identity))
        }

        joined == false -> {
            JoinCard {
                scope.launch {
                    ChatService.setJoined(id, true)
                    joined = true
                }
            }
        }

        else -> {
            ChatLayout(id) {
                scope.launch {
                    ChatService.setJoined(id, false)
                    joined = false
                }
            }
        }
    }
}

@Composable
internal fun JoinCard(onJoin: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .border(1.dp, outline(), RoundedCornerShape(8.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.chat_join_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.chat_join_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.chat_public_note), style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR)
            ActionButton(stringResource(Res.string.chat_join), onClick = onJoin)
        }
    }
}

@Composable
internal fun ChatLayout(
    me: NostrIdentity,
    // Start somewhere other than General (the headless render test).
    startRoom: ChatRoom = ChatRoom.GENERAL,
    startTopicId: String? = null,
    onLeave: () -> Unit,
) {
    var room by remember { mutableStateOf(startRoom) }
    var openTopicId by remember { mutableStateOf(startTopicId) }
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    // Text to drop into the composer (clicking a member inserts their @name).
    var insert by remember { mutableStateOf<String?>(null) }
    val messages by ChatService.messages.collectAsState()
    val votes by ChatService.votes.collectAsState()
    val allMembers by ChatService.members.collectAsState()
    val members = remember(allMembers) { allMembers.values.filter { it.joined } }
    var myPubkeys by remember { mutableStateOf(setOf(me.pubkey)) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { myPubkeys = myPubkeys + withContext(Dispatchers.IO) { NostrIdentityService.listIdentities().map { it.pubkey } } }
    // Mentions count as read while the chat is open.
    LaunchedEffect(messages.size) { ChatService.markMentionsSeen() }

    fun send(
        text: String,
        title: String? = null,
        topicId: String? = null,
    ) {
        val mentions = members.filter { text.contains("@" + it.name) }.map { it.pubkey }
        val r = replyTo
        replyTo = null
        scope.launch { ChatService.post(me, room, text.trim(), title?.trim(), topicId, r, mentions) }
    }

    val inRoom = remember(messages, room) { messages.values.filter { it.room == room }.sortedBy { it.createdAt } }
    val names = remember(members, inRoom) { (members.map { it.name } + inRoom.map { it.name }).distinct() }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.tab_chat), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(stringResource(Res.string.chat_public_note), style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR)
            }
            TabBar(
                listOf(TabItem(stringResource(Res.string.chat_general)), TabItem(stringResource(Res.string.chat_ideas))),
                room.ordinal,
                {
                    room = ChatRoom.entries[it]
                    openTopicId = null
                    replyTo = null
                },
            )
            val topic = openTopicId?.let { messages[it] }
            when {
                room == ChatRoom.GENERAL -> {
                    MessageFeed(inRoom, messages, myPubkeys, names, Modifier.weight(1f)) { replyTo = it }
                    Composer(stringResource(Res.string.chat_message_placeholder), members, replyTo, insert, { insert = null }, {
                        replyTo =
                            null
                    }) {
                        send(it)
                    }
                }

                topic != null -> {
                    val comments = inRoom.filter { it.topicId == topic.id }
                    MessageFeed(comments, messages, myPubkeys, names, Modifier.weight(1f), startAtBottom = false, header = {
                        item(key = "topic") {
                            TopicHeader(topic, votes[topic.id].orEmpty(), me.pubkey, comments.size, { openTopicId = null }) {
                                scope.launch { ChatService.vote(me, topic, it) }
                            }
                        }
                    }) { replyTo = it }
                    Composer(stringResource(Res.string.chat_comment_placeholder), members, replyTo, insert, { insert = null }, {
                        replyTo =
                            null
                    }) {
                        send(it, topicId = topic.id)
                    }
                }

                else -> {
                    IdeasList(inRoom, votes, me.pubkey, Modifier.weight(1f), onOpen = { openTopicId = it }, onVote = { t, v ->
                        scope.launch { ChatService.vote(me, t, v) }
                    }) { title, body -> send(body, title = title) }
                }
            }
        }
        VerticalDivider(color = outline())
        MembersPanel(members, me.pubkey, onMention = { insert = "@$it " }, onLeave = onLeave)
    }
}

// ---- General feed ----

private sealed interface FeedItem {
    val key: String

    data class Day(
        val date: LocalDate,
    ) : FeedItem {
        override val key = "day-$date"
    }

    data class Msg(
        val m: ChatMessage,
        val grouped: Boolean,
    ) : FeedItem {
        override val key = m.id
    }
}

private fun feedItems(list: List<ChatMessage>): List<FeedItem> =
    buildList {
        var prev: ChatMessage? = null
        for (m in list) {
            val d = day(m.createdAt)
            if (prev == null || day(prev.createdAt) != d) add(FeedItem.Day(d))
            val grouped =
                prev != null && prev.pubkey == m.pubkey && m.replyToId == null &&
                    m.createdAt - prev.createdAt < GROUP_SECONDS && day(prev.createdAt) == d
            add(FeedItem.Msg(m, grouped))
            prev = m
        }
    }

@Composable
private fun MessageFeed(
    list: List<ChatMessage>,
    byId: Map<String, ChatMessage>,
    myPubkeys: Set<String>,
    names: List<String>,
    modifier: Modifier,
    startAtBottom: Boolean = true,
    header: (LazyListScope.() -> Unit)? = null,
    onReply: (ChatMessage) -> Unit,
) {
    val items = remember(list) { feedItems(list) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val offset = if (header != null) 1 else 0
    var seen by remember { mutableStateOf(-1) }
    // Follow new messages; the first load jumps to the end only when [startAtBottom].
    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && (seen >= 0 || startAtBottom)) state.scrollToItem(items.lastIndex + offset)
        seen = items.size
    }
    SelectionContainer(modifier.fillMaxWidth()) {
        LazyColumn(state = state, contentPadding = PaddingValues(vertical = 4.dp)) {
            header?.invoke(this)
            if (items.isEmpty() && header == null) item { Hint(stringResource(Res.string.chat_empty)) }
            items(items, key = { it.key }) { item ->
                when (item) {
                    is FeedItem.Day -> {
                        DaySeparator(item.date)
                    }

                    is FeedItem.Msg -> {
                        MessageRow(
                            item.m,
                            item.grouped,
                            item.m.replyToId?.let { byId[it] },
                            myPubkeys,
                            names,
                            { onReply(item.m) },
                        ) { target ->
                            val i = items.indexOfFirst { it.key == target }
                            if (i >= 0) scope.launch { state.animateScrollToItem(i + offset) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySeparator(date: LocalDate) {
    val today = LocalDate.now(zone)
    val label =
        when (date) {
            today -> stringResource(Res.string.chat_today)
            today.minusDays(1) -> stringResource(Res.string.chat_yesterday)
            else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
        }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = outline())
        Text(label, style = MaterialTheme.typography.labelSmall, color = AXIS_COLOR, modifier = Modifier.padding(horizontal = 10.dp))
        HorizontalDivider(Modifier.weight(1f), color = outline())
    }
}

@Composable
private fun MessageRow(
    m: ChatMessage,
    grouped: Boolean,
    parent: ChatMessage?,
    myPubkeys: Set<String>,
    names: List<String>,
    onReply: () -> Unit,
    onJump: (String) -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val mentionsMe = m.pubkey !in myPubkeys && m.mentions.any { it in myPubkeys }
    val warn = warningColor
    val bg =
        when {
            mentionsMe -> warn.copy(alpha = 0.10f)
            hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
            else -> Color.Transparent
        }
    Box(
        Modifier.fillMaxWidth().hoverable(hover).background(bg).drawBehind {
            // Left accent bar on messages that mention us.
            if (mentionsMe) drawRect(warn, size = Size(3.dp.toPx(), size.height))
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (grouped) 1.dp else 8.dp, bottom = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(36.dp)) {
                if (!grouped) {
                    CharacterPortrait(m.charId, 36.dp, Modifier.clip(CircleShape))
                } else if (hovered) {
                    Text(
                        hhmm.format(Instant.ofEpochSecond(m.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = AXIS_COLOR,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!grouped) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        Text(
                            m.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = nameColor(m.pubkey),
                        )
                        Text(
                            hhmm.format(Instant.ofEpochSecond(m.createdAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = AXIS_COLOR,
                        )
                    }
                }
                m.replyToId?.let { rid -> ReplyQuote(parent) { onJump(rid) } }
                Text(withMentions(m.content, names), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (hovered) {
            Text(
                "↩ " + stringResource(Res.string.chat_reply),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 12.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                        .border(1.dp, outline(), RoundedCornerShape(4.dp))
                        .clickable(onClick = onReply)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun ReplyQuote(
    parent: ChatMessage?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.height(IntrinsicSize.Min).clickable(enabled = parent != null, onClick = onClick).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)))
        Text(
            buildAnnotatedString {
                if (parent == null) {
                    append(stringResource(Res.string.chat_reply_missing))
                } else {
                    withStyle(SpanStyle(color = nameColor(parent.pubkey), fontWeight = FontWeight.SemiBold)) { append(parent.name) }
                    append("  ")
                    append(parent.content.replace('\n', ' '))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = AXIS_COLOR,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun withMentions(
    text: String,
    names: List<String>,
): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    val regex = remember(names) { names.sortedByDescending { it.length }.joinToString("|", "@(", ")") { Regex.escape(it) }.toRegex() }
    if (names.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var at = 0
        regex.findAll(text).forEach { match ->
            append(text, at, match.range.first)
            withStyle(
                SpanStyle(color = accent, fontWeight = FontWeight.SemiBold, background = accent.copy(alpha = 0.12f)),
            ) { append(match.value) }
            at = match.range.last + 1
        }
        append(text, at, text.length)
    }
}

// ---- Composer ----

private val mentionQuery = Regex("""(?:^|\s)@([^@\n]{0,24})$""")

/** Multi-line input: Enter sends, Shift+Enter breaks the line, @ suggests members. */
@Composable
private fun Composer(
    placeholder: String,
    members: List<ChatMember>,
    replyTo: ChatMessage?,
    insert: String?,
    onInserted: () -> Unit,
    onCancelReply: () -> Unit,
    onSend: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(insert) {
        insert ?: return@LaunchedEffect
        val t = value.text + (if (value.text.isEmpty() || value.text.endsWith(" ")) "" else " ") + insert
        value = TextFieldValue(t, TextRange(t.length))
        onInserted()
    }
    val query = mentionQuery.find(value.text.substring(0, value.selection.start))?.groupValues?.get(1)
    val suggestions =
        remember(query, members) {
            if (query == null) emptyList() else members.filter { it.name.startsWith(query, ignoreCase = true) }.sortedBy { it.name }.take(6)
        }

    fun pick(member: ChatMember) {
        val cursor = value.selection.start
        val start = value.text.lastIndexOf('@', cursor - 1)
        val t = value.text.substring(0, start) + "@" + member.name + " " + value.text.substring(cursor)
        value = TextFieldValue(t, TextRange(start + member.name.length + 2))
    }

    fun send() {
        if (value.text.isBlank()) return
        onSend(value.text)
        value = TextFieldValue("")
    }
    val ink = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth().border(1.dp, outline(), RoundedCornerShape(8.dp))) {
        if (replyTo != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                    ).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    buildAnnotatedString {
                        append("↩ " + stringResource(Res.string.chat_replying_to) + " ")
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = nameColor(replyTo.pubkey))) { append(replyTo.name) }
                        append("  " + replyTo.content.replace('\n', ' '))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AXIS_COLOR,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("✕", color = AXIS_COLOR, modifier = Modifier.clickable(onClick = onCancelReply).padding(horizontal = 4.dp))
            }
        }
        if (suggestions.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(6.dp)) {
                suggestions.forEachIndexed { i, s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (i == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { pick(s) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CharacterPortrait(s.charId, 20.dp, Modifier.clip(CircleShape))
                        Text(s.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider(color = outline())
        }
        Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = 4.dp)) {
                if (value.text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = AXIS_COLOR)
                BasicTextField(
                    value = value,
                    onValueChange = { if (it.text.length <= CHAT_MAX_LENGTH) value = it },
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink),
                    cursorBrush = SolidColor(ink),
                    modifier =
                        Modifier.fillMaxWidth().onPreviewKeyEvent {
                            if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                (it.key == Key.Enter || it.key == Key.Tab) && suggestions.isNotEmpty() -> {
                                    pick(suggestions.first())
                                    true
                                }

                                it.key == Key.Enter && !it.isShiftPressed -> {
                                    send()
                                    true
                                }

                                else -> {
                                    false
                                }
                            }
                        },
                )
            }
            val canSend = value.text.isNotBlank()
            Text(
                "➤",
                style = MaterialTheme.typography.titleMedium,
                color = if (canSend) MaterialTheme.colorScheme.primary else AXIS_COLOR,
                modifier = Modifier.clip(CircleShape).clickable(enabled = canSend) { send() }.padding(8.dp),
            )
        }
    }
}

// ---- Members ----

@Composable
private fun MembersPanel(
    members: List<ChatMember>,
    myPubkey: String,
    onMention: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val presence by PresenceService.presence.collectAsState()
    val now = System.currentTimeMillis() / 1000
    val sorted =
        remember(members, presence) {
            members.sortedWith(
                compareByDescending<ChatMember> { presence[it.pubkey]?.isOnline(now) == true }.thenBy { it.name.lowercase() },
            )
        }
    val online = sorted.count { presence[it.pubkey]?.isOnline(now) == true }
    Column(Modifier.width(240.dp).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.chat_members, members.size.toString(), online.toString()).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = AXIS_COLOR,
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(sorted, key = { it.pubkey }) { m ->
                val isOnline = presence[m.pubkey]?.isOnline(now) == true
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = m.pubkey != myPubkey) { onMention(m.name) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box {
                        CharacterPortrait(m.charId, 28.dp, Modifier.clip(CircleShape))
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(10.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                                .padding(2.dp)
                                .background(if (isOnline) positiveColor else AXIS_COLOR, CircleShape),
                        )
                    }
                    Text(
                        m.name + if (m.pubkey == myPubkey) " " + stringResource(Res.string.chat_you) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOnline) MaterialTheme.colorScheme.onSurface else AXIS_COLOR,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            stringResource(Res.string.chat_leave),
            style = MaterialTheme.typography.labelSmall,
            color = AXIS_COLOR,
            modifier = Modifier.clickable(onClick = onLeave).padding(vertical = 4.dp),
        )
    }
}

// ---- Suggestions ----

private fun score(votes: Map<String, ChatVote>?) = votes.orEmpty().values.sumOf { it.value }

// Markdown down to a one-paragraph preview.
private fun plainPreview(md: String) =
    md
        .lines()
        .map { it.trim().trimStart('#', '>', '-', '*', '+', ' ').replace(Regex("""[*_`~]"""), "") }
        .filter { it.isNotBlank() }
        .joinToString(" ")

@Composable
private fun IdeasList(
    inRoom: List<ChatMessage>,
    votes: Map<String, Map<String, ChatVote>>,
    myPubkey: String,
    modifier: Modifier,
    onOpen: (String) -> Unit,
    onVote: (ChatMessage, Int) -> Unit,
    onPost: (title: String, body: String) -> Unit,
) {
    var newestFirst by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    if (writing) {
        IdeaEditor(modifier, onCancel = { writing = false }) { title, body ->
            onPost(title, body)
            writing = false
        }
        return
    }
    val comments = remember(inRoom) { inRoom.filter { it.topicId != null }.groupingBy { it.topicId }.eachCount() }
    val topics =
        inRoom
            .filter { it.topicId == null }
            .sortedWith(
                if (newestFirst) {
                    compareByDescending { it.createdAt }
                } else {
                    compareByDescending<ChatMessage> { score(votes[it.id]) }.thenByDescending { it.createdAt }
                },
            )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Segment(stringResource(Res.string.chat_sort_top), !newestFirst) { newestFirst = false }
            Segment(stringResource(Res.string.chat_sort_new), newestFirst) { newestFirst = true }
            Spacer(Modifier.weight(1f))
            ActionButton("+ " + stringResource(Res.string.chat_new_idea)) { writing = true }
        }
        if (topics.isEmpty()) Hint(stringResource(Res.string.chat_no_ideas))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(topics, key = { it.id }) { t ->
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .hoverable(hover)
                        .border(
                            1.dp,
                            if (hovered) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else outline(),
                            RoundedCornerShape(8.dp),
                        ).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VoteBox(votes[t.id].orEmpty(), myPubkey) { onVote(t, it) }
                    Column(Modifier.weight(1f).clickable { onOpen(t.id) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t.title.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            plainPreview(t.content),
                            style = MaterialTheme.typography.bodySmall,
                            color = AXIS_COLOR,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        AuthorLine(t, comments[t.id] ?: 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthorLine(
    t: ChatMessage,
    commentCount: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        CharacterPortrait(t.charId, 16.dp, Modifier.clip(CircleShape))
        Text(t.name, style = MaterialTheme.typography.labelSmall, color = nameColor(t.pubkey), fontWeight = FontWeight.SemiBold)
        Text(
            "· " + DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(day(t.createdAt)) + " · " +
                pluralStringResource(Res.plurals.chat_comment_count, commentCount, commentCount.toString()),
            style = MaterialTheme.typography.labelSmall,
            color = AXIS_COLOR,
        )
    }
}

@Composable
private fun TopicHeader(
    topic: ChatMessage,
    votes: Map<String, ChatVote>,
    myPubkey: String,
    commentCount: Int,
    onBack: () -> Unit,
    onVote: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "← " + stringResource(Res.string.chat_back_to_ideas),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onBack),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            VoteBox(votes, myPubkey, onVote)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(topic.title.orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                AuthorLine(topic, commentCount)
                MarkdownView(topic.content)
            }
        }
        Text(
            stringResource(Res.string.chat_comments).uppercase() + " · $commentCount",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = AXIS_COLOR,
            modifier = Modifier.padding(top = 8.dp),
        )
        HorizontalDivider(color = outline())
    }
}

/** ▲ score ▼, your vote lit up. */
@Composable
private fun VoteBox(
    votes: Map<String, ChatVote>,
    myPubkey: String,
    onVote: (Int) -> Unit,
) {
    val mine = votes[myPubkey]?.value
    val s = score(votes)

    @Composable
    fun arrow(
        glyph: String,
        value: Int,
        color: Color,
    ) = Text(
        glyph,
        color = if (mine == value) color else AXIS_COLOR,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = mine != value) { onVote(value) }.padding(horizontal = 6.dp),
    )
    Column(
        Modifier.width(44.dp).border(1.dp, outline(), RoundedCornerShape(6.dp)).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        arrow("▲", 1, positiveColor)
        Text(
            s.toString(),
            style = MaterialTheme.typography.titleSmall,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            color = pnlColor(s.toDouble()) ?: MaterialTheme.colorScheme.onSurface,
        )
        arrow("▼", -1, negativeColor)
    }
}
