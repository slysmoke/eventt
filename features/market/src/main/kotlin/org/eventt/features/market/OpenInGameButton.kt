package org.eventt.features.market

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eventt.core.database.AppState
import org.eventt.core.esi.EsiClient
import org.eventt.market.generated.resources.Res
import org.eventt.market.generated.resources.open_in_game
import org.eventt.ui.common.Tip
import org.jetbrains.compose.resources.stringResource

/**
 * Opens the item's market window in the running EVE client, as the selected character (it has to
 * be the one logged in there). Renders nothing without a character.
 */
@Composable
fun OpenInGameButton(
    typeId: Int,
    size: Dp = 26.dp,
) {
    val ctx by AppState.selectedContext.collectAsState()
    val charId = ctx?.actingCharId ?: return
    val scope = rememberCoroutineScope()
    Tip(stringResource(Res.string.open_in_game)) {
        IconButton(
            onClick = { scope.launch(Dispatchers.IO) { runCatching { EsiClient.openMarketWindow(charId, typeId) } } },
            modifier = Modifier.size(size),
        ) {
            Icon(
                Icons.Default.OpenInBrowser,
                stringResource(Res.string.open_in_game),
                Modifier.size(size * 0.6f),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
