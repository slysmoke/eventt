package org.eventt.features.p2pmarket

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FAILURE_TINT_MILLIS = 3000L

/**
 * Small icon button next to a trader's name that opens the EVE client's character info window for
 * them (via ESI, see [CharacterInfoLauncher]) — this only works while you're logged into a running
 * client as your own P2P Market character, so a failure is visible (icon flashes red briefly)
 * rather than silently doing nothing, which otherwise looks identical to a successful click.
 */
@Composable
internal fun TraderInfoButton(
    characterName: String,
    characterId: Int? = null,
) {
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    IconButton(
        modifier = Modifier.size(20.dp),
        onClick = {
            scope.launch(Dispatchers.IO) {
                val opened = CharacterInfoLauncher.openShowInfo(characterName, characterId)
                if (!opened) {
                    withContext(Dispatchers.Main) { failed = true }
                    delay(FAILURE_TINT_MILLIS)
                    failed = false
                }
            }
        },
    ) {
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = "Open $characterName in EVE client",
            modifier = Modifier.size(14.dp),
            tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
