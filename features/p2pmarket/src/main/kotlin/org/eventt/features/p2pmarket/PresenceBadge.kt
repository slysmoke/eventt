package org.eventt.features.p2pmarket

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.eventt.core.nostr.PresenceService

private const val TICK_MILLIS = 30_000L

/**
 * NIP-38 presence for a counterparty: "Online" while their latest status hasn't expired, "Seen
 * … ago" after. Shows nothing at all for a pubkey we've never seen a status from — most of the
 * network predates presence (or has it idle-suppressed), and a blanket "offline" would read as
 * "not coming", which we can't actually claim.
 */
@Composable
internal fun PresenceBadge(pubkey: String) {
    val presenceMap by PresenceService.presence.collectAsState()
    // Local clock tick so "Online" decays to "Seen … ago" even when no new event arrives.
    var nowSec by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MILLIS)
            nowSec = System.currentTimeMillis() / 1000
        }
    }

    val presence = presenceMap[pubkey] ?: return
    if (presence.isOnline(nowSec)) {
        Text("● Online", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
    } else {
        Text(
            "○ Seen ${formatDurationShort(nowSec - presence.lastSeen)} ago",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
