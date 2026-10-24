package org.eventt.features.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.eventt.core.database.ViewContext
import org.eventt.features.tools.pricing.PricingScreen
import org.eventt.features.tools.splitter.SplitterScreen
import org.eventt.tools.generated.resources.*
import org.eventt.ui.common.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

private enum class ToolsTab(
    val label: StringResource,
) {
    SPLITTER(Res.string.tab_cargo_splitter),
    PRICING(Res.string.tab_sell_pricing),
}

@Composable
fun ToolsScreen(context: ViewContext?) {
    var tab by remember { mutableStateOf(ToolsTab.SPLITTER) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.tools_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        TabBar(ToolsTab.entries.map { TabItem(stringResource(it.label)) }, tab.ordinal, { tab = ToolsTab.entries[it] })
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                ToolsTab.SPLITTER -> SplitterScreen(context)
                ToolsTab.PRICING -> PricingScreen(context)
            }
        }
    }
}

/** Paste area shared by both tools: a monospace text box with a paste-from-clipboard button in the panel header. */
@Composable
internal fun PasteBox(
    title: String,
    text: String,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    Panel(
        title,
        modifier.fillMaxWidth(),
        header = {
            Spacer(Modifier.weight(1f))
            Segment(stringResource(Res.string.paste_from_clipboard), false) {
                runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String }
                    .getOrNull()
                    ?.let(onChange)
            }
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            modifier = Modifier.fillMaxSize(),
            placeholder = { Text(stringResource(Res.string.paste_inventory), style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
        )
    }
}
