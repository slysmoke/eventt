package org.eventt.features.p2pmarket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val pendingIncoming = rememberReservationCount("seller", listOf("sent"))
    val awaitingCompletion = rememberReservationCount("buyer", listOf("accepted"))

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
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
        when (tab) {
            P2pMarketTab.BROWSE -> BrowseScreen()
            P2pMarketTab.MY_ORDERS -> MyOrdersScreen()
            P2pMarketTab.INCOMING_REQUESTS -> IncomingRequestsScreen()
            P2pMarketTab.MY_REQUESTS -> MyRequestsScreen()
            P2pMarketTab.INBOX -> InboxScreen()
        }
    }
}
