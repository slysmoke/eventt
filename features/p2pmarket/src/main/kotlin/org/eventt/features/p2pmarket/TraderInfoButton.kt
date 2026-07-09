package org.eventt.features.p2pmarket

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Small icon button next to a trader's name that opens the EVE client's Show Info window for them. */
@Composable
internal fun TraderInfoButton(characterName: String) {
    val scope = rememberCoroutineScope()
    IconButton(
        modifier = Modifier.size(20.dp),
        onClick = { scope.launch(Dispatchers.IO) { CharacterInfoLauncher.openShowInfo(characterName) } },
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = "Show character info in-game",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
