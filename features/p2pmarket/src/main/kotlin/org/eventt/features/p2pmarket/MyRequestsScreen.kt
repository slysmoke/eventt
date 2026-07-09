package org.eventt.features.p2pmarket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.NostrReservationDao
import org.eventt.core.database.NostrReservationModel
import org.eventt.core.nostr.NostrRelayEvent
import org.eventt.core.nostr.NostrRelayManager
import org.eventt.core.nostr.ReservationService
import java.time.Instant

// "Stale" is a pure client-side UI label — the seller may still answer after this, no protocol
// timeout exists. It just tells the buyer "this has been quiet a while," nothing more.
private const val STALE_AFTER_SECONDS = 48L * 3600

@Composable
fun MyRequestsScreen() {
    val scope = rememberCoroutineScope()
    var requests by remember { mutableStateOf<List<NostrReservationModel>>(emptyList()) }

    suspend fun reload() {
        requests = withContext(Dispatchers.IO) { NostrReservationDao.listForRole("buyer") }
    }

    LaunchedEffect(Unit) {
        reload()
        NostrRelayManager.events.collect { event ->
            if (event is NostrRelayEvent.ReservationActivity) reload()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (requests.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No requests sent yet — request a reservation from Browse", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(requests, key = { it.tradeId }) { reservation ->
                    RequestRow(reservation, onMarkCompleted = {
                        scope.launch(Dispatchers.IO) {
                            ReservationService.markCompleted(reservation)
                            reload()
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun RequestRow(
    reservation: NostrReservationModel,
    onMarkCompleted: () -> Unit,
) {
    val nowSec = System.currentTimeMillis() / 1000
    val isStale = reservation.status == "sent" && nowSec - reservation.requestedAt > STALE_AFTER_SECONDS
    val statusLabel =
        when {
            reservation.status == "sent" && isStale -> "Stale (no response yet)"
            reservation.status == "sent" -> "Sent — awaiting response"
            reservation.status == "accepted" -> "Accepted"
            reservation.status == "declined" -> "Declined"
            reservation.status == "completed" -> "Completed"
            reservation.status == "released" -> "Released by seller"
            else -> reservation.status
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Qty ${reservation.qty} · order ${reservation.orderUuid.take(8)}…", style = MaterialTheme.typography.bodyMedium)
            Text(
                statusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (reservation.status == "declined") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (reservation.status == "accepted") {
                reservation.holdUntil?.let {
                    Text("Held until ${Instant.ofEpochSecond(it)}", style = MaterialTheme.typography.labelSmall)
                }
                if (reservation.contactChar.isNotBlank()) {
                    Text("Contact in-game: ${reservation.contactChar}", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = onMarkCompleted) { Text("I completed this trade") }
            }
        }
    }
}
