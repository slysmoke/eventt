package org.eventt.features.p2pmarket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.model.CharacterModel
import org.eventt.core.nostr.LeaderboardEntry
import org.eventt.core.nostr.LeaderboardService
import org.eventt.core.nostr.NostrIdentityService
import org.eventt.p2pmarket.generated.resources.*
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class LeaderboardWindow(
    val label: StringResource,
    val pnl: (LeaderboardEntry) -> Double,
) {
    WEEK(Res.string.lb_7_days, LeaderboardEntry::pnl7d),
    MONTH(Res.string.lb_30_days, LeaderboardEntry::pnl30d),
    YEAR(Res.string.lb_365_days, LeaderboardEntry::pnl365d),
}

/**
 * Opt-in trader leaderboard (issue #17), its own top-level page. Two independent halves:
 * stringResource(Res.string.publish_as) below picks which single local character's identity signs and publishes *this
 * machine's* combined P&L (every local character + corp, see LeaderboardPublisher in
 * features/orders); the ranked list below that is a pure view over every trader's published entry
 * ([LeaderboardService.entries]), including everyone else's, regardless of what's picked here.
 */
@Composable
fun LeaderboardScreen() {
    val scope = rememberCoroutineScope()
    var characters by remember { mutableStateOf<List<CharacterModel>>(emptyList()) }
    var publisherCharId by remember { mutableStateOf<Int?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    suspend fun reload() {
        characters = withContext(Dispatchers.IO) { CharacterDao.getAll() }
        publisherCharId = withContext(Dispatchers.IO) { StaticDataDao.getLeaderboardPublisherCharId() }
    }

    LaunchedEffect(Unit) { reload() }

    fun select(newCharId: Int?) {
        val previousCharId = publisherCharId
        publisherCharId = newCharId
        menuExpanded = false
        scope.launch(Dispatchers.IO) {
            StaticDataDao.setLeaderboardPublisherCharId(newCharId)
            // Switching publisher (or turning off) leaves the old identity's entry stale forever
            // otherwise — the sweep only ever publishes the *current* choice, never cleans up a
            // previous one. Tombstone here is what makes the old entry disappear right away.
            if (previousCharId != null && previousCharId != newCharId) {
                NostrIdentityService.listIdentities().find { it.characterId == previousCharId }?.let {
                    LeaderboardService.publishTombstone(it)
                }
            }
        }
    }

    val entries by LeaderboardService.entries.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(Res.string.trader_leaderboard),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(Res.string.lb_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (characters.isEmpty()) {
                    Text(
                        stringResource(Res.string.no_characters_yet),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(Res.string.publish_as),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box {
                            OutlinedButton(onClick = { menuExpanded = true }) {
                                Text(characters.find { it.id == publisherCharId }?.name ?: stringResource(Res.string.off))
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(text = { Text(stringResource(Res.string.off)) }, onClick = { select(null) })
                                characters.forEach { character ->
                                    DropdownMenuItem(text = { Text(character.name) }, onClick = { select(character.id) })
                                }
                            }
                        }
                    }
                }
            }
        }

        Text(
            stringResource(Res.string.self_reported),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (entries.isEmpty()) {
            Text(
                stringResource(Res.string.no_one_opted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LeaderboardWindow.entries.forEach { w ->
                    LeaderboardTable(w, entries.values.sortedByDescending(w.pnl), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LeaderboardTable(
    window: LeaderboardWindow,
    ranked: List<LeaderboardEntry>,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column {
            Text(
                stringResource(window.label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(16.dp),
            )
            HorizontalDivider()
            LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                items(ranked, key = { it.pubkey }) { entry ->
                    LeaderboardRow(ranked.indexOf(entry) + 1, entry, window.pnl(entry))
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(
    rank: Int,
    entry: LeaderboardEntry,
    pnl: Double,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("#$rank", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 4.dp))
        Text(
            entry.traderChar.ifBlank { entry.pubkey.take(12) + "…" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TraderInfoButton(entry.traderChar, entry.traderCharId)
        Text(
            (if (pnl > 0) "+" else "") +
                org.eventt.ui.common
                    .formatIsk(pnl) + " ISK",
            style = MaterialTheme.typography.bodyMedium,
            color = if (pnl >= 0) positiveColor else negativeColor,
        )
    }
}
