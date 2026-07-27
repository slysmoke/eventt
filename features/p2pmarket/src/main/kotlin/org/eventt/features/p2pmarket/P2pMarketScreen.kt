package org.eventt.features.p2pmarket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private enum class P2pMarketTab(
    val label: String,
) {
    BROWSE("Browse"),
    MY_ORDERS("My Orders"),
    INCOMING_REQUESTS("Incoming Requests"),
    MY_REQUESTS("My Requests"),
    INBOX("Inbox"),
}

/** Small numeric pill shown next to a tab/nav label — hidden entirely when there's nothing to flag. */
@Composable
fun CountBadge(count: Int) {
    if (count <= 0) return
    Box(
        modifier =
            Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
        )
    }
}

@Composable
fun P2pMarketScreen() {
    var tab by remember { mutableStateOf(P2pMarketTab.BROWSE) }
    var showGuide by remember { mutableStateOf(false) }
    val pendingIncoming = rememberReservationCount("seller", listOf("sent"))
    val awaitingCompletion = rememberReservationCount("buyer", listOf("accepted"))

    if (showGuide) {
        P2pMarketGuideDialog(onDismiss = { showGuide = false })
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.weight(1f)) {
                P2pMarketTab.entries.forEach { t ->
                    val badgeCount =
                        when (t) {
                            P2pMarketTab.INCOMING_REQUESTS -> pendingIncoming
                            P2pMarketTab.MY_REQUESTS -> awaitingCompletion
                            else -> 0
                        }
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(t.label)
                                CountBadge(badgeCount)
                            }
                        },
                        icon = {
                            Icon(
                                when (t) {
                                    P2pMarketTab.BROWSE -> Icons.Default.Search
                                    P2pMarketTab.MY_ORDERS -> Icons.AutoMirrored.Filled.List
                                    P2pMarketTab.INCOMING_REQUESTS -> Icons.Default.MoveToInbox
                                    P2pMarketTab.MY_REQUESTS -> Icons.AutoMirrored.Filled.Send
                                    P2pMarketTab.INBOX -> Icons.Default.Inbox
                                },
                                null,
                                Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
            IconButton(onClick = { showGuide = true }) {
                Icon(Icons.Default.HelpOutline, contentDescription = "How P2P Market trading works")
            }
        }
        when (tab) {
            P2pMarketTab.BROWSE -> BrowseScreen()
            P2pMarketTab.MY_ORDERS -> MyOrdersScreen()
            P2pMarketTab.INCOMING_REQUESTS -> IncomingRequestsScreen()
            P2pMarketTab.MY_REQUESTS -> MyRequestsScreen()
            P2pMarketTab.INBOX -> InboxScreen()
        }
    }
}

@Composable
private fun P2pMarketGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How P2P Market trading works") },
        text = {
            Column(modifier = Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This app only negotiates the deal over Nostr — Browse, requests, and Inbox track who " +
                        "agreed to what, but no ISK or items ever move through the app itself. Once a " +
                        "request is accepted, you still have to do the trade in EVE: meet up for a direct " +
                        "trade, or use a contract for anything you can't hand over in person.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Attribute trades to a character or corp",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "ESI has no visibility into a player trade or contract at all, so unlike a market " +
                        "order, a P2P deal never counts toward cost-basis/margin (Orders, Dashboard) unless " +
                        "you tell the app who it belongs to.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "• My Orders → \"Attribute new trades to:\" sets the default every trade you complete " +
                        "afterward starts out booked to.\n" +
                        "• Inbox → \"Attributed to\" changes it for any single trade, any time — including " +
                        "trades from before you set a default.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "This is separate from which character negotiated the deal — attribution only decides " +
                        "whose cost-basis ledger the ISK/item counts toward (e.g. trading personally but " +
                        "booking it as a corp trade).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}
