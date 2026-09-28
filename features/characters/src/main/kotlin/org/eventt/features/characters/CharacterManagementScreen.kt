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
import org.eventt.characters.generated.resources.*
import org.eventt.core.auth.SsoAuthManager
import org.eventt.core.database.AppState
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.CorporationDao
import org.eventt.core.esi.EsiClient
import org.eventt.ui.common.ConfirmDialog
import org.eventt.ui.common.EmptyState
import org.eventt.ui.common.LoadingOverlay
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.jetbrains.compose.resources.stringResource

@Composable
fun CharacterManagementScreen() {
    val scope = rememberCoroutineScope()
    var characters by remember { mutableStateOf<List<org.eventt.core.model.CharacterModel>>(emptyList()) }
    var corporations by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var trackedCorpIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var isLoading by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var characterToRemove by remember { mutableStateOf<org.eventt.core.model.CharacterModel?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loadCharacters(
                characters = { characters = it },
                corps = { corporations = it },
                tracked = { trackedCorpIds = it },
            )
        }
    }

    LaunchedEffect(Unit) { reload() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.chars_corps_title), style = MaterialTheme.typography.headlineMedium)
                Button(onClick = { showAuthDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(Res.string.add_character))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (characters.isEmpty() && corporations.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Person,
                    title = stringResource(Res.string.no_characters_added),
                    description = stringResource(Res.string.no_characters_desc),
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
                                    reload()
                                }
                            },
                        )
                    }

                    if (corporations.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(Res.string.corporations), style = MaterialTheme.typography.titleLarge)
                            Text(
                                stringResource(Res.string.corporations_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        items(corporations) { corp ->
                            val corpId = corp["id"] as? Int
                            CorporationCard(
                                data = corp,
                                isTracked = corpId in trackedCorpIds,
                                onToggleTrack = {
                                    corpId ?: return@CorporationCard
                                    scope.launch(Dispatchers.IO) {
                                        if (corpId in trackedCorpIds) CorporationDao.untrack(corpId) else CorporationDao.track(corpId)
                                        withContext(Dispatchers.Main) {
                                            reload()
                                            AppState.refreshCharacters()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        LoadingOverlay(isLoading = isLoading, message = stringResource(Res.string.authenticating))
    }

    // Auth dialog
    if (showAuthDialog) {
        AlertDialog(
            onDismissRequest = { showAuthDialog = false },
            title = { Text(stringResource(Res.string.add_character)) },
            text = {
                Column {
                    Text(stringResource(Res.string.sso_browser_hint))
                    if (authError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(authError!!, color = negativeColor, style = MaterialTheme.typography.bodySmall)
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
                                        reload()
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
                    Text(stringResource(Res.string.open_browser))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAuthDialog = false }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }

    // Remove confirmation
    characterToRemove?.let { char ->
        ConfirmDialog(
            title = stringResource(Res.string.remove_character),
            message = stringResource(Res.string.remove_character_msg, char.name),
            onDismiss = { characterToRemove = null },
            onConfirm = {
                scope.launch(Dispatchers.IO) {
                    CharacterDao.delete(char.id)
                    AppState.refreshCharacters()
                    withContext(Dispatchers.Main) { reload() }
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
                        tint = if (tokenExpired) negativeColor else positiveColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (tokenExpired) stringResource(Res.string.token_expired) else stringResource(Res.string.token_valid),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tokenExpired) negativeColor else positiveColor,
                    )
                }

                // Corporation
                character.corporationName?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(Res.string.corporation_label, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            // Actions
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.refresh))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.remove), tint = negativeColor)
            }
        }
    }
}

@Composable
private fun CorporationCard(
    data: Map<String, Any?>,
    isTracked: Boolean,
    onToggleTrack: () -> Unit,
) {
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
                Text(data["name"]?.toString() ?: stringResource(Res.string.unknown), style = MaterialTheme.typography.titleSmall)
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

            Spacer(modifier = Modifier.width(12.dp))

            if (isTracked) {
                OutlinedButton(onClick = onToggleTrack) {
                    Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(Res.string.untrack))
                }
            } else {
                Button(onClick = onToggleTrack) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(Res.string.track))
                }
            }
        }
    }
}

private suspend fun loadCharacters(
    characters: (List<org.eventt.core.model.CharacterModel>) -> Unit,
    corps: (List<Map<String, Any?>>) -> Unit,
    tracked: (Set<Int>) -> Unit,
) {
    try {
        withContext(Dispatchers.IO) {
            CharacterDao.getAll().forEach { char ->
                runCatching { SsoAuthManager.ensureTokenFresh(char.id) }
            }
        }
        characters(withContext(Dispatchers.IO) { CharacterDao.getAll() })
        corps(withContext(Dispatchers.IO) { CorporationDao.getAll() })
        tracked(withContext(Dispatchers.IO) { CorporationDao.getTrackedIds() })
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
