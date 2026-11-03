package org.eventt.features.p2pmarket

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichText
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.nostr.CHAT_TITLE_MAX_LENGTH
import org.eventt.core.nostr.CHAT_TOPIC_MAX_LENGTH
import org.eventt.core.nostr.ChatService
import org.eventt.core.nostr.NostrIdentityService
import org.eventt.p2pmarket.generated.resources.*
import org.eventt.ui.common.AXIS_COLOR
import org.eventt.ui.common.ActionButton
import org.eventt.ui.common.outline
import org.jetbrains.compose.resources.stringResource

/** Link and code colours that fit our theme. */
@Composable
private fun RichTextState.themed(): RichTextState {
    val primary = MaterialTheme.colorScheme.primary
    val code = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    LaunchedEffect(primary) {
        config.linkColor = primary
        config.codeSpanBackgroundColor = code
        config.codeSpanStrokeColor = Color.Transparent
    }
    return this
}

/** Read-only render of a suggestion's Markdown. */
@Composable
internal fun MarkdownView(markdown: String) {
    val state = rememberRichTextState().themed()
    LaunchedEffect(markdown) { state.setMarkdown(markdown) }
    BasicRichText(
        state,
        Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
    )
}

/** WYSIWYG editor for a new suggestion; the body goes out as Markdown so any Nostr client can read it. */
@Composable
internal fun IdeaEditor(
    modifier: Modifier,
    onCancel: () -> Unit,
    onPublish: (title: String, markdown: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    val state = rememberRichTextState().themed()
    val ink = MaterialTheme.colorScheme.onSurface
    val body = state.toMarkdown()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.chat_new_idea), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Box(Modifier.fillMaxWidth().border(1.dp, outline(), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (title.isEmpty()) {
                Text(
                    stringResource(Res.string.chat_idea_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = AXIS_COLOR,
                )
            }
            BasicTextField(
                title,
                { title = it.take(CHAT_TITLE_MAX_LENGTH) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = ink, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(Modifier.fillMaxWidth().weight(1f).border(1.dp, outline(), RoundedCornerShape(8.dp))) {
            Toolbar(state)
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
                if (state.annotatedString.text.isEmpty()) {
                    Text(stringResource(Res.string.chat_idea_body), style = MaterialTheme.typography.bodyMedium, color = AXIS_COLOR)
                }
                BasicRichTextEditor(
                    state = state,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 200.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink),
                    cursorBrush = SolidColor(ink),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.chat_markdown_note),
                style = MaterialTheme.typography.bodySmall,
                color = AXIS_COLOR,
                modifier = Modifier.weight(1f),
            )
            ActionButton(stringResource(Res.string.chat_cancel), onClick = onCancel)
            ActionButton(
                stringResource(Res.string.chat_post_idea),
                enabled = title.isNotBlank() && body.isNotBlank() && body.length <= CHAT_TOPIC_MAX_LENGTH,
            ) { onPublish(title, body) }
        }
    }
}

@Composable
private fun Toolbar(state: RichTextState) {
    val span = state.currentSpanStyle
    val heading = state.currentHeadingStyle

    @Composable
    fun button(
        label: String,
        on: Boolean,
        style: SpanStyle = SpanStyle(),
        onClick: () -> Unit,
    ) {
        val accent = MaterialTheme.colorScheme.primary
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (on) accent.copy(alpha = 0.15f) else Color.Transparent)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(style)
                    append(label)
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (on) accent else MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    @Composable
    fun divider() = VerticalDivider(Modifier.height(18.dp).padding(horizontal = 4.dp), color = outline())

    fun heading(h: HeadingStyle) = state.setHeadingStyle(if (heading == h) HeadingStyle.Normal else h)
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f),
            ).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        button("B", span.fontWeight == FontWeight.Bold, SpanStyle(fontWeight = FontWeight.Bold)) {
            state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        }
        button("I", span.fontStyle == FontStyle.Italic, SpanStyle(fontStyle = FontStyle.Italic)) {
            state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
        }
        val strike = span.textDecoration?.contains(TextDecoration.LineThrough) == true
        button("S", strike, SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
        }
        button("</>", state.isCodeSpan, SpanStyle(fontFamily = FontFamily.Monospace)) { state.toggleCodeSpan() }
        divider()
        button("H1", heading == HeadingStyle.H1) { heading(HeadingStyle.H1) }
        button("H2", heading == HeadingStyle.H2) { heading(HeadingStyle.H2) }
        button("H3", heading == HeadingStyle.H3) { heading(HeadingStyle.H3) }
        divider()
        button("•", state.isUnorderedList) { state.toggleUnorderedList() }
        button("1.", state.isOrderedList) { state.toggleOrderedList() }
    }
}

/** Unread @mentions of any of our identities, for the nav item. */
@Composable
fun ChatNavBadge() {
    val messages by ChatService.messages.collectAsState()
    val seenAt by ChatService.mentionsSeenAt.collectAsState()
    var mine by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) {
        ChatService.loadMentionsSeenAt()
        mine = withContext(Dispatchers.IO) { NostrIdentityService.listIdentities().map { it.pubkey }.toSet() }
    }
    val since = seenAt ?: return
    CountBadge(messages.values.count { m -> m.createdAt > since && m.pubkey !in mine && m.mentions.any { it in mine } })
}
