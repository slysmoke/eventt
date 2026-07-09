package org.eventt.features.p2pmarket

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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

// My Requests / Inbox arrive in later phases as real sub-tabs — not stubbed here, since a tab row
// with dead tabs would be worse than a smaller-but-fully-working one.
private enum class P2pMarketTab(
    val label: String,
) {
    BROWSE("Browse"),
    MY_ORDERS("My Orders"),
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
                            if (t == P2pMarketTab.BROWSE) Icons.Default.Search else Icons.AutoMirrored.Filled.List,
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
        }
    }
}
