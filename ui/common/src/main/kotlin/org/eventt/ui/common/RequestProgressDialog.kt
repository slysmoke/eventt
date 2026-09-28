package org.eventt.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import org.eventt.common.generated.resources.*
import org.eventt.core.model.QueuedRequest
import org.eventt.core.model.RequestSource
import org.eventt.core.model.RequestStatus
import org.eventt.core.queue.RequestQueueManager
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

val EventtBlue = Color(0xFF4A90D9)

private enum class RequestFilter(
    val label: StringResource,
) {
    PENDING(Res.string.filter_active_failed),
    SERVER(Res.string.filter_server),
    CACHE(Res.string.filter_cache),
    ALL(Res.string.filter_all),
}

private val timeFormat =
    java.time.format.DateTimeFormatter
        .ofPattern("HH:mm:ss")

private fun formatTime(millis: Long?): String =
    millis?.let {
        java.time.Instant
            .ofEpochMilli(it)
            .atZone(java.time.ZoneId.systemDefault())
            .format(timeFormat)
    } ?: ""

@OptIn(FlowPreview::class)
@Composable
fun RequestProgressDialog(onDismiss: () -> Unit) {
    // A bulk analysis run can enqueue/complete thousands of requests within a few seconds —
    // collecting every single emission made this dialog redraw (and visibly jitter, since the
    // active-request list reshuffles on every change) many times a second. Sampling caps the
    // redraw rate to something the eye reads as smooth updates instead of a shaking window.
    val requests by RequestQueueManager.requests.sample(300).collectAsState(RequestQueueManager.requests.value)

    val active = requests.filter { it.status == RequestStatus.QUEUED || it.status == RequestStatus.IN_PROGRESS }
    val failed = requests.filter { it.status == RequestStatus.FAILED }
    val completed = requests.count { it.status == RequestStatus.COMPLETED }
    val cacheHits = requests.count { it.source == RequestSource.CACHE }
    var filter by remember { mutableStateOf(RequestFilter.PENDING) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    val total = requests.size
    val progress = RequestQueueManager.overallProgress

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 720.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active.isNotEmpty()) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CheckCircle, null, tint = positiveColor, modifier = Modifier.size(20.dp))
                }
                Text(stringResource(Res.string.esi_requests))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Progress bar
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())

                // Summary line
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SummaryChip(stringResource(Res.string.req_active, active.size), if (active.isNotEmpty()) EventtBlue else Color.Gray)
                    SummaryChip(stringResource(Res.string.req_done, completed), positiveColor)
                    if (cacheHits > 0) SummaryChip(stringResource(Res.string.req_from_cache, cacheHits), Color.Gray)
                    if (failed.isNotEmpty()) {
                        SummaryChip(stringResource(Res.string.req_failed, failed.size), negativeColor)
                    }
                    if (total > 0) {
                        Text(
                            "$completed / $total",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RequestFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(stringResource(f.label), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                // Newest first — the interesting entries are almost always the latest ones.
                val visible =
                    when (filter) {
                        RequestFilter.PENDING -> active + failed
                        RequestFilter.SERVER -> requests.filter { it.source == RequestSource.SERVER }
                        RequestFilter.CACHE -> requests.filter { it.source == RequestSource.CACHE }
                        RequestFilter.ALL -> requests
                    }.asReversed()
                if (visible.isEmpty() && filter == RequestFilter.PENDING && requests.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.CheckCircle, null, tint = positiveColor, modifier = Modifier.size(16.dp))
                            Text(stringResource(Res.string.all_requests_completed), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (visible.isNotEmpty()) {
                    // A fixed height (not heightIn(max=...)) keeps the dialog's own size constant
                    // while requests churn — otherwise the box itself grows and shrinks along with
                    // however many rows happen to be active at each redraw, which reads as the
                    // whole window jittering rather than just its contents updating.
                    LazyColumn(
                        modifier = Modifier.height(320.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(visible, key = { it.id }) { request ->
                            RequestRow(
                                request = request,
                                expanded = expandedId == request.id,
                                onToggle = { expandedId = if (expandedId == request.id) null else request.id },
                            )
                        }
                    }
                } else {
                    Text(
                        stringResource(Res.string.no_requests),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (requests.isNotEmpty()) {
                    TextButton(onClick = { RequestQueueManager.clearAll() }) {
                        Text(stringResource(Res.string.clear_all), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = { RequestQueueManager.clearCompleted() }) {
                    Text(stringResource(Res.string.clear_completed))
                }
            }
        },
    )
}

@Composable
private fun SummaryChip(
    label: String,
    color: Color,
) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun RequestRow(
    request: QueuedRequest,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val status = request.status
    val isCache = request.source == RequestSource.CACHE
    Surface(
        color =
            when (status) {
                RequestStatus.FAILED -> negativeColor.copy(alpha = 0.08f)
                RequestStatus.IN_PROGRESS -> EventtBlue.copy(alpha = 0.06f)
                else -> Color.Transparent
            },
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.clickable(onClick = onToggle),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (status) {
                    RequestStatus.QUEUED -> Icon(Icons.Default.Schedule, null, Modifier.size(13.dp), tint = Color.Gray)
                    RequestStatus.IN_PROGRESS -> Icon(Icons.Default.Sync, null, Modifier.size(13.dp), tint = EventtBlue)
                    RequestStatus.FAILED -> Icon(Icons.Default.Error, null, Modifier.size(13.dp), tint = negativeColor)
                    RequestStatus.COMPLETED -> Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = positiveColor)
                }
                Text(
                    formatTime(request.endTime ?: request.startTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
                Text(
                    request.description,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                request.httpCode?.let {
                    Text("$it", style = MaterialTheme.typography.labelSmall, color = negativeColor)
                }
                Text(
                    request.source.name.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCache) Color.Gray else EventtBlue.copy(alpha = 0.8f),
                )
            }
            if (status == RequestStatus.IN_PROGRESS) {
                LinearProgressIndicator(
                    progress = { request.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(2.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            if (!expanded) {
                request.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = negativeColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                RequestDetails(request)
            }
        }
    }
}

@Composable
private fun RequestDetails(request: QueuedRequest) {
    val duration =
        if (request.startTime != null && request.endTime != null) "${request.endTime!! - request.startTime!!} ms" else null
    SelectionContainer {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            DetailLine("URL", request.endpoint)
            request.httpCode?.let { DetailLine("HTTP", "$it") }
            duration?.let { DetailLine(stringResource(Res.string.detail_time), it) }
            request.error?.let { DetailLine(stringResource(Res.string.detail_error), it, negativeColor) }
            request.responseBody?.let { body ->
                Text(stringResource(Res.string.response_body), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                ) {
                    Text(
                        body.ifEmpty { stringResource(Res.string.empty_paren) },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailLine(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(80.dp),
        )
        Text(value, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
