package org.eventt.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.NostrRelayDao
import org.eventt.core.database.NostrRelayModel
import org.eventt.core.model.CharacterModel
import org.eventt.core.nostr.NostrIdentity
import org.eventt.core.nostr.NostrIdentityService
import org.eventt.core.nostr.NostrRelayEvent
import org.eventt.core.nostr.NostrRelayManager
import java.io.File
import javax.swing.JFileChooser

/**
 * One P2P Market identity per EVE character, auto-generated the first time this card loads. Which
 * one is *active* is no longer a manual choice here — it always follows whichever character (or
 * acting character, if a corporation is selected) is currently picked in the app's main nav, kept
 * in sync by [org.eventt.core.nostr.NostrIdentityService.followAppCharacterSelection]. This card
 * is now just visibility into that (which key belongs to which character) plus export/import.
 */
@Composable
internal fun NostrIdentityCard() {
    val scope = rememberCoroutineScope()
    var characters by remember { mutableStateOf<List<CharacterModel>>(emptyList()) }
    var identitiesByCharacter by remember { mutableStateOf<Map<Int, NostrIdentity>>(emptyMap()) }
    var activePubkey by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val chars = withContext(Dispatchers.IO) { CharacterDao.getAll() }
        val identities = withContext(Dispatchers.IO) { NostrIdentityService.ensureIdentitiesForAllCharacters() }
        characters = chars
        identitiesByCharacter = identities.mapNotNull { id -> id.characterId?.let { it to id } }.toMap()
        activePubkey = withContext(Dispatchers.IO) { NostrIdentityService.getActiveIdentity()?.pubkey }
    }

    LaunchedEffect(Unit) { reload() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("P2P Market Identity (Nostr)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Each character gets its own P2P Market identity automatically. The active one always follows whichever " +
                    "character you have selected in the app (even via a corporation) — export/import moves a key to another machine.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            if (characters.isEmpty()) {
                Text("No EVE characters added yet — add one in the Characters tab first.", style = MaterialTheme.typography.bodySmall)
            } else {
                characters.forEach { character ->
                    val characterIdentity = identitiesByCharacter[character.id]
                    val isActive = characterIdentity != null && characterIdentity.pubkey == activePubkey
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Podcasts,
                            contentDescription = if (isActive) "Currently active" else null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isActive) MaterialTheme.colorScheme.tertiary else Color.Transparent,
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(character.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                characterIdentity?.pubkey?.take(12)?.plus("…") ?: "…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            val pubkey = characterIdentity?.pubkey ?: return@IconButton
                            val chooser =
                                JFileChooser().apply {
                                    dialogTitle = "Export Nostr key for ${character.name}"
                                    fileSelectionMode = JFileChooser.FILES_ONLY
                                    selectedFile = File("${character.name.replace(" ", "_")}_nostr_key.txt")
                                }
                            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                                val file = chooser.selectedFile
                                scope.launch(Dispatchers.IO) {
                                    val nsec = NostrIdentityService.exportPrivateKey(pubkey)
                                    if (nsec != null) {
                                        runCatching { file.writeText(nsec) }
                                    }
                                }
                            }
                        }) { Icon(Icons.Default.Download, "Export key", Modifier.size(16.dp)) }
                        IconButton(onClick = {
                            val chooser =
                                JFileChooser().apply {
                                    dialogTitle = "Import Nostr key for ${character.name}"
                                    fileSelectionMode = JFileChooser.FILES_ONLY
                                }
                            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                val file = chooser.selectedFile
                                scope.launch(Dispatchers.IO) {
                                    val content = runCatching { file.readText().trim() }.getOrNull()
                                    val imported = content?.let { NostrIdentityService.importPrivateKeyForCharacter(it, character) }
                                    withContext(Dispatchers.Main) {
                                        importError =
                                            if (imported == null) {
                                                "Couldn't import key for ${character.name} — file unreadable or not a valid key."
                                            } else {
                                                null
                                            }
                                    }
                                    reload()
                                }
                            }
                        }) { Icon(Icons.Default.Upload, "Import key", Modifier.size(16.dp)) }
                    }
                }
            }
            importError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun NostrRelaysCard() {
    val scope = rememberCoroutineScope()
    var relays by remember { mutableStateOf<List<NostrRelayModel>>(emptyList()) }
    var newRelayInput by remember { mutableStateOf("") }

    fun reload() {
        scope.launch(Dispatchers.IO) { relays = NostrRelayDao.getAll() }
    }

    LaunchedEffect(Unit) {
        reload()
        NostrRelayManager.events.collect { event -> if (event is NostrRelayEvent.RelayStatusChanged) reload() }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Podcasts, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("P2P Market Relays", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Public Nostr relays the app publishes/reads orders through — no relay of your own required.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            relays.forEach { relay ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = relay.enabled,
                        onCheckedChange = {
                            scope.launch(Dispatchers.IO) {
                                NostrRelayDao.setEnabled(relay.url, it)
                                reload()
                            }
                        },
                    )
                    Column(modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
                        Text(relay.url, style = MaterialTheme.typography.bodySmall)
                        RelayStatusLabel(relay)
                        RelayNipWarningLabel(relay)
                    }
                    IconButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            NostrRelayDao.remove(relay.url)
                            reload()
                        }
                    }) { Icon(Icons.Default.Delete, "Remove", Modifier.size(16.dp)) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newRelayInput,
                    onValueChange = { newRelayInput = it },
                    label = { Text("wss://relay.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(0.7f),
                )
                OutlinedButton(onClick = {
                    val url = newRelayInput.trim()
                    if (url.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            NostrRelayDao.upsert(NostrRelayManager.normalizeUrl(url))
                            reload()
                        }
                        newRelayInput = ""
                    }
                }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Text("Add")
                }
            }
        }
    }
}

/**
 * Warns when the relay's NIP-11 document says it's a poor fit for order storage: paid/restricted
 * writes mean our publishes are silently rejected; no NIP-40 means expired orders are served
 * forever (and in-memory relays like memlay advertise almost no NIPs at all). Nothing is shown
 * until NIP-11 has actually been fetched — an unreachable info document is not a problem per se.
 */
@Composable
private fun RelayNipWarningLabel(relay: NostrRelayModel) {
    if (relay.nip11FetchedAt == null) return
    val warning =
        when {
            relay.restrictedWrites -> "Paid/restricted writes — this relay likely rejects your orders"
            40 !in relay.supportedNips -> "No NIP-40 expiration — may store nothing or serve expired orders"
            else -> return
        }
    Text("⚠ $warning", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun RelayStatusLabel(relay: NostrRelayModel) {
    val (label, color) =
        when (relay.lastStatus) {
            "connected" -> "Connected" to MaterialTheme.colorScheme.tertiary
            "error" -> (relay.lastError?.let { "Error: $it" } ?: "Error") to MaterialTheme.colorScheme.error
            "disconnected" -> "Disconnected" to MaterialTheme.colorScheme.onSurfaceVariant
            else -> "Not yet connected" to MaterialTheme.colorScheme.onSurfaceVariant
        }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
}
