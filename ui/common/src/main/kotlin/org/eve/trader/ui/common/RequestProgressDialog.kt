package org.eve.trader.ui.common

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
import androidx.compose.ui.unit.dp
import org.eve.trader.core.model.QueuedRequest
import org.eve.trader.core.model.RequestStatus
import org.eve.trader.core.queue.RequestQueueManager

/**
 * Progress dialog showing all in-flight ESI requests.
 */
@Composable
fun RequestProgressDialog(onDismiss: () -> Unit) {
    val requests by RequestQueueManager.requests.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("ESI Requests")
            }
        },
        text = {
            Column {
                // Overall progress
                val progress = RequestQueueManager.overallProgress
                val total = RequestQueueManager.totalRequests
                val completed = RequestQueueManager.completedRequests
                val failed = RequestQueueManager.failedRequests

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )

                Text(
                    text = "$completed / $total completed${if (failed > 0) " ($failed failed)" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Request list
                if (requests.isEmpty()) {
                    Text(
                        text = "No active requests",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(requests) { request ->
                            RequestItemRow(request)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                RequestQueueManager.clearCompleted()
            }) {
                Text("Clear Completed")
            }
        },
    )
}

@Composable
private fun RequestItemRow(request: QueuedRequest) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status icon
                when (request.status) {
                    RequestStatus.QUEUED -> Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp),
                    )
                    RequestStatus.IN_PROGRESS -> Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        tint = EveTraderBlue,
                        modifier = Modifier.size(16.dp),
                    )
                    RequestStatus.COMPLETED -> Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.Green,
                        modifier = Modifier.size(16.dp),
                    )
                    RequestStatus.FAILED -> Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = Color.Red,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Endpoint
                Text(
                    text = request.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )

                // Source badge
                Spacer(modifier = Modifier.width(4.dp))
                Surface(
                    color = if (request.source == org.eve.trader.core.model.RequestSource.CACHE)
                        Color.Green.copy(alpha = 0.2f) else Color.Blue.copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = request.source.name.lowercase(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        color = if (request.source == org.eve.trader.core.model.RequestSource.CACHE)
                            Color.Green else Color.Blue,
                    )
                }
            }

            // Progress bar for in-progress requests
            if (request.status == RequestStatus.IN_PROGRESS) {
                LinearProgressIndicator(
                    progress = { request.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }

            // Error message
            request.error?.let { errorMsg ->
                Text(
                    text = errorMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Red,
                    maxLines = 1,
                )
            }
        }
    }
}

val EveTraderBlue = Color(0xFF4A90D9)
