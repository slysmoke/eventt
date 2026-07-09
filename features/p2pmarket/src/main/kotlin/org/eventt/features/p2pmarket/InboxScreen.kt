package org.eventt.features.p2pmarket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.database.NostrReceiptDao
import org.eventt.core.database.NostrReservationDao
import org.eventt.core.database.NostrReservationModel
import org.eventt.core.nostr.NostrRelayEvent
import org.eventt.core.nostr.NostrRelayManager

/**
 * Trades I've marked completed — split by whether the counterparty's own receipt has landed yet.
 * A trade only counts toward reputation ([org.eventt.core.nostr.ReputationAggregator]) once it
 * moves from "awaiting" to "confirmed" here, so this is also where a stuck one-sided completion
 * becomes visible.
 */
@Composable
fun InboxScreen() {
    var awaiting by remember { mutableStateOf<List<NostrReservationModel>>(emptyList()) }
    var confirmed by remember { mutableStateOf<List<NostrReservationModel>>(emptyList()) }

    suspend fun reload() {
        val completed =
            withContext(Dispatchers.IO) {
                NostrReservationDao.listForRole("buyer", listOf("completed")) +
                    NostrReservationDao.listForRole("seller", listOf("completed"))
            }
        val mutual = withContext(Dispatchers.IO) { completed.associateWith { NostrReceiptDao.hasMutualReceipt(it.tradeId) } }
        awaiting = completed.filter { mutual[it] == false }.sortedByDescending { it.requestedAt }
        confirmed = completed.filter { mutual[it] == true }.sortedByDescending { it.requestedAt }
    }

    LaunchedEffect(Unit) {
        reload()
        NostrRelayManager.events.collect { event -> if (event is NostrRelayEvent.ReservationActivity) reload() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (awaiting.isEmpty() && confirmed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No completed trades yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (awaiting.isNotEmpty()) {
                    item { Text("Awaiting counterparty confirmation", style = MaterialTheme.typography.titleMedium) }
                    items(awaiting, key = { it.tradeId }) { InboxRow(it, isConfirmed = false) }
                }
                if (confirmed.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text("Confirmed", style = MaterialTheme.typography.titleMedium)
                    }
                    items(confirmed, key = { it.tradeId }) { InboxRow(it, isConfirmed = true) }
                }
            }
        }
    }
}

@Composable
private fun InboxRow(
    reservation: NostrReservationModel,
    isConfirmed: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Qty ${reservation.qty} · order ${reservation.orderUuid.take(8)}… (as ${reservation.role})",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (isConfirmed) "Both sides confirmed — counted toward reputation" else "Waiting for the other side to confirm",
                style = MaterialTheme.typography.bodySmall,
                color = if (isConfirmed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
