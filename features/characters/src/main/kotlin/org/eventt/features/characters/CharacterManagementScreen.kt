package org.eventt.features.characters

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.auth.SsoAuthManager
import org.eventt.core.database.AppState
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.CorporationDao
import org.eventt.core.esi.EsiClient
import org.eventt.ui.common.ConfirmDialog
import org.eventt.ui.common.EmptyState
import org.eventt.ui.common.LoadingOverlay

@Composable
fun CharacterManagementScreen() {
    val scope = rememberCoroutineScope()
    var characters by remember { mutableStateOf<List<org.eventt.core.model.CharacterModel>>(emptyList()) }
    var corporations by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var characterToRemove by remember { mutableStateOf<org.eventt.core.model.CharacterModel?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loadCharacters(characters = { characters = it }, corps = { corporations = it })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Characters & Corporations", style = MaterialTheme.typography.headlineMedium)
                Button(onClick = { showAuthDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Character")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (characters.isEmpty() && corporations.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Person,
                    title = "No Characters Added",
                    description = "Click 'Add Character' to authenticate with EVE Online SSO.",
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(characters) { char ->
                        CharacterCard(
                            character = char,
                            onRemove = { characterToRemove = char },
                            onRefresh = {
                                scope.launch {
                                    refreshCharacter(char.id)
                                    loadCharacters(characters = { characters = it }, corps = { corporations = it })
                                }
                            },
                        )
                    }

                    if (corporations.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Corporations", style = MaterialTheme.typography.titleLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        items(corporations) { corp ->
                            CorporationCard(corp)
                        }
                    }
                }
            }
        }

        LoadingOverlay(isLoading = isLoading, message = "Authenticating...")
    }

    // Auth dialog
    if (showAuthDialog) {
        AlertDialog(
            onDismissRequest = { showAuthDialog = false },
            title = { Text("Add Character") },
            text = {
                Column {
                    Text("This will open your browser to authenticate with EVE Online SSO.")
                    if (authError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(authError!!, color = Color.Red, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isLoading = true
                        authError = null
                        scope.launch(Dispatchers.IO) {
                            try {
                                val result = SsoAuthManager.startAuth()
                                // Populate the new character's corp affiliation right away so it's
                                // available to the character/corp switcher without a manual refresh.
                                result.character?.let { runCatching { refreshCharacter(it.id) } }
                                AppState.refreshCharacters()
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    if (result.success) {
                                        showAuthDialog = false
                                        loadCharacters(characters = { characters = it }, corps = { corporations = it })
                                    } else {
                                        authError = result.error
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    authError = e.message
                                }
                            }
                        }
                    },
                    enabled = !isLoading,
                ) {
                    Text("Open Browser")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAuthDialog = false }) { Text("Cancel") }
            },
        )
    }

    // Remove confirmation
    characterToRemove?.let { char ->
        ConfirmDialog(
            title = "Remove Character",
            message = "Remove '${char.name}'? This will also remove associated cached data.",
            onDismiss = { characterToRemove = null },
            onConfirm = {
                scope.launch(Dispatchers.IO) {
                    CharacterDao.delete(char.id)
                    AppState.refreshCharacters()
                    withContext(Dispatchers.Main) {
                        loadCharacters(characters = { characters = it }, corps = { corporations = it })
                    }
                }
            },
        )
    }
}

@Composable
private fun CharacterCard(
    character: org.eventt.core.model.CharacterModel,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val tokenExpired = character.tokenExpiry < now

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar placeholder
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(character.name, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(2.dp))

                // Token status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (tokenExpired) Icons.Default.Warning else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (tokenExpired) Color.Red else Color.Green,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (tokenExpired) "Token expired" else "Token valid",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tokenExpired) Color.Red else Color.Green,
                    )
                }

                // Corporation
                character.corporationName?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Corporation: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            // Actions
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.Red)
            }
        }
    }
}

@Composable
private fun CorporationCard(data: Map<String, Any?>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Business,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(data["name"]?.toString() ?: "Unknown", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = data["ticker"]?.toString() ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }

            Text(
                text = "ID: ${data["id"]}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

private suspend fun loadCharacters(
    characters: (List<org.eventt.core.model.CharacterModel>) -> Unit,
    corps: (List<Map<String, Any?>>) -> Unit,
) {
    try {
        withContext(Dispatchers.IO) {
            CharacterDao.getAll().forEach { char ->
                runCatching { SsoAuthManager.ensureTokenFresh(char.id) }
            }
        }
        characters(withContext(Dispatchers.IO) { CharacterDao.getAll() })
        corps(withContext(Dispatchers.IO) { CorporationDao.getAll() })
    } catch (e: Exception) {
        println("Error loading characters: ${e.message}")
    }
}

private suspend fun refreshCharacter(characterId: Int) {
    try {
        val charInfo = EsiClient.getCharacterInfo(characterId)
        val corpId = (charInfo["corporation_id"] as? Number)?.toInt()
        if (corpId != null) {
            val corpInfo = EsiClient.getCorporationInfo(corpId)
            val corpName = (corpInfo["name"] as? String) ?: ""
            CorporationDao.insert(
                id = corpId,
                name = corpName,
                ticker = (corpInfo["ticker"] as? String) ?: "",
                allianceId = (corpInfo["alliance_id"] as? Number)?.toInt(),
            )
            // Denormalized onto the character row too — this is what lets the app know which
            // corp a character belongs to without re-fetching ESI (e.g. to build the corp switcher).
            CharacterDao.getById(characterId)?.let { existing ->
                CharacterDao.insert(existing.copy(corporationId = corpId, corporationName = corpName))
            }
        }
    } catch (e: Exception) {
        println("Error refreshing character: ${e.message}")
    }
}
