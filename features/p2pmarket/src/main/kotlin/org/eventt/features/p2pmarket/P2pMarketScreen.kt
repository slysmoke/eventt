package org.eventt.features.p2pmarket

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Inbox arrives in a later phase as a real sub-tab — not stubbed here, since a tab row with a
// dead tab would be worse than a smaller-but-fully-working one.
private enum class P2pMarketTab(
    val label: String,
) {
    BROWSE("Browse"),
    MY_ORDERS("My Orders"),
    MY_REQUESTS("My Requests"),
}

@Composable
fun P2pMarketScreen() {
    var tab by remember { mutableStateOf(P2pMarketTab.BROWSE) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab.ordinal) {
            P2pMarketTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(t.label) },
                    icon = {
                        Icon(
                            when (t) {
                                P2pMarketTab.BROWSE -> Icons.Default.Search
                                P2pMarketTab.MY_ORDERS -> Icons.AutoMirrored.Filled.List
                                P2pMarketTab.MY_REQUESTS -> Icons.AutoMirrored.Filled.Send
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
            P2pMarketTab.MY_REQUESTS -> MyRequestsScreen()
        }
    }
}
