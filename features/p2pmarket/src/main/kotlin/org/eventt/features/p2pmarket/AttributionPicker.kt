package org.eventt.features.p2pmarket

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.CorporationDao
import org.eventt.core.database.TransactionAttribution
import org.eventt.core.model.CharacterModel

/**
 * Character/corp picker for a P2P trade's cost-basis attribution — used both per-trade (Inbox)
 * and as the "attribute new trades to" default (My Orders). Corporations are listed once per
 * distinct corp a local character belongs to, not once per member — unlike EventtApp's
 * character/corp switcher, attribution has no "acting character" concept to disambiguate.
 */
@Composable
internal fun AttributionPicker(
    current: TransactionAttribution?,
    onSelect: (TransactionAttribution) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var characters by remember { mutableStateOf<List<CharacterModel>>(emptyList()) }
    var corporations by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val chars = runCatching { CharacterDao.getAll() }.getOrDefault(emptyList())
            characters = chars
            val corpNames =
                runCatching { CorporationDao.getAll() }
                    .getOrDefault(emptyList())
                    .associate { (it["id"] as? Int) to (it["name"] as? String ?: "") }
            corporations =
                chars
                    .mapNotNull { it.corporationId }
                    .distinct()
                    .map { it to (corpNames[it] ?: "Corp #$it") }
        }
    }

    val label =
        when (current) {
            is TransactionAttribution.Character -> {
                characters.find { it.id == current.characterId }?.name ?: "Character #${current.characterId}"
            }

            is TransactionAttribution.Corporation -> {
                corporations.find { it.first == current.corporationId }?.second ?: "Corp #${current.corporationId}"
            }

            null -> {
                "Unattributed"
            }
        }

    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }, contentPadding = COMPACT_BUTTON_PADDING) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            characters.forEach { char ->
                DropdownMenuItem(
                    text = { Text(char.name) },
                    onClick = {
                        onSelect(TransactionAttribution.Character(char.id))
                        expanded = false
                    },
                )
            }
            if (corporations.isNotEmpty()) {
                HorizontalDivider()
                corporations.forEach { (corpId, corpName) ->
                    DropdownMenuItem(
                        text = { Text(corpName) },
                        onClick = {
                            onSelect(TransactionAttribution.Corporation(corpId))
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
