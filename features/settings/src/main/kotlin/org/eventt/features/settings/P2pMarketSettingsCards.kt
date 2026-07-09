package org.eventt.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Podcasts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.NostrRelayDao
import org.eventt.core.database.NostrRelayModel
import org.eventt.core.nostr.NostrIdentity
import org.eventt.core.nostr.NostrIdentityService

@Composable
internal fun NostrIdentityCard() {
    val scope = rememberCoroutineScope()
    var identity by remember { mutableStateOf<NostrIdentity?>(null) }
    var labelInput by remember { mutableStateOf("") }
    var importInput by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        identity = withContext(Dispatchers.IO) { NostrIdentityService.getActiveIdentity() }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("P2P Market Identity (Nostr)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Used to post/discover off-market orders. Not linked to your EVE character or ESI account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            val current = identity
            if (current != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        (current.label.ifBlank { "Identity" }) + " — " + current.pubkey.take(12) + "…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text("No identity yet — generate or import one below.", style = MaterialTheme.typography.bodySmall)
            }

            OutlinedTextField(
                value = labelInput,
                onValueChange = { labelInput = it },
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val created = NostrIdentityService.generateNew(labelInput)
                        identity = created
                    }
                }) { Text("Generate new identity") }
            }

            HorizontalDivider()
            Text("Or import an existing key", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = importInput,
                onValueChange = {
                    importInput = it
                    importError = false
                },
                label = { Text("nsec1… or hex private key") },
                singleLine = true,
                isError = importError,
                modifier = Modifier.fillMaxWidth(),
            )
            if (importError) {
                Text("Couldn't parse that as a private key.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    val imported = NostrIdentityService.importPrivateKey(importInput, labelInput)
                    if (imported != null) {
                        identity = imported
                    } else {
                        importError = true
                    }
                }
            }) { Text("Import") }
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

    LaunchedEffect(Unit) { reload() }

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
                    Text(relay.url, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(start = 4.dp))
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
                            NostrRelayDao.upsert(url)
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
