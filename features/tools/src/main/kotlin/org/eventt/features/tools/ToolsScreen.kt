package org.eventt.features.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.eventt.core.database.ViewContext
import org.eventt.features.tools.pricing.PricingScreen
import org.eventt.features.tools.splitter.SplitterScreen

private enum class ToolsTab(
    val label: String,
) {
    SPLITTER("Cargo Splitter"),
    PRICING("Sell Pricing"),
}

@Composable
fun ToolsScreen(context: ViewContext?) {
    var tab by remember { mutableStateOf(ToolsTab.SPLITTER) }
    Column {
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            ToolsTab.entries.forEach { t ->
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) })
            }
        }
        when (tab) {
            ToolsTab.SPLITTER -> SplitterScreen(context)
            ToolsTab.PRICING -> PricingScreen(context)
        }
    }
}
