package org.eventt.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import org.eventt.core.model.RequestStatus
import org.eventt.core.queue.RequestQueueManager
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor

val EventtBlue = Color(0xFF4A90D9)

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
    val total = requests.size
    val progress = RequestQueueManager.overallProgress

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 560.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active.isNotEmpty()) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CheckCircle, null, tint = positiveColor, modifier = Modifier.size(20.dp))
                }
                Text("ESI Requests")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Progress bar
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())

                // Summary line
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SummaryChip("${active.size} active", if (active.isNotEmpty()) EventtBlue else Color.Gray)
                    SummaryChip("$completed done", positiveColor)
                    if (failed.isNotEmpty()) {
                        SummaryChip("${failed.size} failed", negativeColor)
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

                // Active + failed list (not showing completed — just counted above)
                val visible = active + failed
                if (visible.isEmpty() && requests.isNotEmpty()) {
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
                            Text("All requests completed", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (visible.isNotEmpty()) {
                    // A fixed height (not heightIn(max=...)) keeps the dialog's own size constant
                    // while requests churn — otherwise the box itself grows and shrinks along with
                    // however many rows happen to be active at each redraw, which reads as the
                    // whole window jittering rather than just its contents updating.
                    LazyColumn(
                        modifier = Modifier.height(260.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(visible, key = { it.id }) { request ->
                            RequestRow(
                                status = request.status,
                                description = request.description,
                                source = request.source.name.lowercase(),
                                progress = request.progress,
                                error = request.error,
                            )
                        }
                    }
                } else {
                    Text(
                        "No active requests",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (requests.isNotEmpty()) {
                    TextButton(onClick = { RequestQueueManager.clearAll() }) {
                        Text("Clear All", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = { RequestQueueManager.clearCompleted() }) {
                    Text("Clear Completed")
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
    status: RequestStatus,
    description: String,
    source: String,
    progress: Float,
    error: String?,
) {
    Surface(
        color =
            when (status) {
                RequestStatus.FAILED -> negativeColor.copy(alpha = 0.08f)
                RequestStatus.IN_PROGRESS -> EventtBlue.copy(alpha = 0.06f)
                else -> Color.Transparent
            },
        shape = MaterialTheme.shapes.extraSmall,
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
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (source.isNotEmpty()) {
                    Text(
                        source,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (source == "cache") positiveColor.copy(alpha = 0.8f) else EventtBlue.copy(alpha = 0.8f),
                    )
                }
            }
            if (status == RequestStatus.IN_PROGRESS) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(2.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = negativeColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
