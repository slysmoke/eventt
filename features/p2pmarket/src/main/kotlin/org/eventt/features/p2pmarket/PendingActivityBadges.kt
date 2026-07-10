package org.eventt.features.p2pmarket

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.database.NostrReservationDao
import org.eventt.core.nostr.NostrRelayEvent
import org.eventt.core.nostr.NostrRelayManager

/**
 * Live count of local reservations matching [role]/[statuses], refreshed on every relay DM
 * activity — backs the nav sidebar's and the P2P Market tabs' badges so an incoming buy request
 * (or an accepted one awaiting completion) doesn't go unnoticed until you happen to open the tab.
 */
@Composable
fun rememberReservationCount(
    role: String,
    statuses: List<String>,
): Int {
    var count by remember(role, statuses) { mutableStateOf(0) }
    LaunchedEffect(role, statuses) {
        suspend fun reload() {
            count = withContext(Dispatchers.IO) { NostrReservationDao.listForRole(role, statuses).size }
        }
        reload()
        NostrRelayManager.events.collect { event ->
            if (event is NostrRelayEvent.ReservationActivity) reload()
        }
    }
    return count
}

/** Incoming buy requests still awaiting your response as seller — the one that matters most for noticing new activity. */
@Composable
fun rememberPendingBuyRequestCount(): Int = rememberReservationCount("seller", listOf("sent"))
