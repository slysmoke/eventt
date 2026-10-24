package org.eventt.features.characters

import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
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
import org.eventt.core.model.CharacterModel
import org.eventt.ui.common.*
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

@Composable
fun CharacterManagementScreen() {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<Pair<List<CharRow>, List<CorpRow>>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var characterToRemove by remember { mutableStateOf<CharacterModel?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch { rows = loadCharacters() }
    }

    LaunchedEffect(Unit) { reload() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(Res.string.chars_corps_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Segment("+ " + stringResource(Res.string.add_character), true) { showAuthDialog = true }
            }
            val (chars, corps) = rows ?: (emptyList<CharRow>() to emptyList())
            if (rows != null && chars.isEmpty() && corps.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Person,
                    title = stringResource(Res.string.no_characters_added),
                    description = stringResource(Res.string.no_characters_desc),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                CharactersBody(
                    chars,
                    corps,
                    Modifier.weight(1f),
                    onRefresh = { c ->
                        scope.launch {
                            withContext(Dispatchers.IO) { refreshCharacter(c.id) }
                            reload()
                        }
                    },
                    onRemove = { characterToRemove = it },
                    onToggleTrack = { corp ->
                        scope.launch(Dispatchers.IO) {
                            if (corp.tracked) CorporationDao.untrack(corp.id) else CorporationDao.track(corp.id)
                            withContext(Dispatchers.Main) {
                                reload()
                                AppState.refreshCharacters()
                            }
                        }
                    },
                )
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

/** KPI strip plus the character and corporation tables — no ESI here. */
@Composable
internal fun CharactersBody(
    chars: List<CharRow>,
    corps: List<CorpRow>,
    modifier: Modifier = Modifier,
    onRefresh: (CharacterModel) -> Unit = {},
    onRemove: (CharacterModel) -> Unit = {},
    onToggleTrack: (CorpRow) -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val expired = chars.count { !it.tokenValid }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.dp, outline(), MaterialTheme.shapes.extraSmall)) {
            Kpi(stringResource(Res.string.kpi_characters), chars.size.toString(), null, null, null)
            Kpi(
                stringResource(Res.string.kpi_tokens),
                "${chars.size - expired} / ${chars.size}",
                if (expired > 0) stringResource(Res.string.kpi_expired_n, expired.toString()) else null,
                negativeColor,
                null,
                Res.string.tip_tokens,
                valueColor = negativeColor.takeIf { expired > 0 },
            )
            Kpi(
                stringResource(Res.string.corporations),
                "${corps.count { it.tracked }} / ${corps.size}",
                stringResource(Res.string.kpi_tracked),
                null,
                null,
            )
            Kpi(
                stringResource(Res.string.kpi_orders),
                (chars.sumOf { it.sellOrders + it.buyOrders } + corps.sumOf { it.orders }).toString(),
                stringResource(
                    Res.string.kpi_orders_split,
                    chars.sumOf { it.sellOrders }.toString(),
                    chars.sumOf { it.buyOrders }.toString(),
                    corps.sumOf { it.orders }.toString(),
                ),
                null,
                null,
            )
            Kpi(
                stringResource(Res.string.kpi_trades_30d),
                (chars.sumOf { it.trades30d } + corps.sumOf { it.trades30d }).toString(),
                null,
                null,
                null,
                last = true,
            )
        }
        Panel(stringResource(Res.string.characters), Modifier.fillMaxWidth().weight(1f)) { CharacterTable(chars, onRefresh, onRemove) }
        Panel(
            stringResource(Res.string.corporations),
            Modifier.fillMaxWidth().weight(0.7f),
            header = {
                Text(
                    stringResource(Res.string.corporations_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = AXIS_COLOR,
                    maxLines = 1,
                )
            },
        ) { CorpTable(corps, onToggleTrack) }
    }
}

@Composable
private fun CharacterTable(
    rows: List<CharRow>,
    onRefresh: (CharacterModel) -> Unit,
    onRemove: (CharacterModel) -> Unit,
) {
    val cols =
        listOf<Col<CharRow>>(
            Col(stringResource(Res.string.h_character), 2.2f, end = false) { it.c.name.lowercase() },
            Col(stringResource(Res.string.h_corporation), 1.8f, end = false) { it.c.corporationName?.lowercase() },
            Col(stringResource(Res.string.h_token), 1f, end = false) { it.tokenValid },
            Col(stringResource(Res.string.h_sell), 0.6f) { it.sellOrders },
            Col(stringResource(Res.string.h_buy), 0.6f) { it.buyOrders },
            Col(stringResource(Res.string.h_corp_orders), 0.9f) { it.corpOrders },
            Col(stringResource(Res.string.kpi_trades_30d), 0.9f) { it.trades30d },
            Col(stringResource(Res.string.h_last_trade), 1.2f) { it.lastTrade },
            Col(stringResource(Res.string.h_fees), 1.1f) { it.salesTaxPct + it.brokerFeePct },
            Col("", 0.7f),
        )
    Table(cols = cols, rows = rows, empty = stringResource(Res.string.no_characters_desc), defaultSort = 0, defaultDesc = false, key = {
        it.c.id
    }) { r ->
        val denied = r.deniedCorp.takeIf { it.isNotEmpty() }
        listOf(
            Cell(r.c.name) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CharacterPortrait(r.c.id, size = 28.dp)
                    Text(r.c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            },
            Cell(r.c.corporationName.orEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CorporationLogo(r.c.corporationId, size = 18.dp)
                    Text(r.c.corporationName ?: "—", style = MaterialTheme.typography.bodySmall, color = AXIS_COLOR, maxLines = 1)
                    // Corp endpoints ESI refused for this character (missing roles).
                    denied?.let { d ->
                        Tip(stringResource(Res.string.tip_denied, d.joinToString { it.name.lowercase() })) {
                            Icon(Icons.Default.Lock, null, Modifier.size(12.dp), tint = warningColor)
                        }
                    }
                }
            },
            Cell(
                stringResource(if (r.tokenValid) Res.string.token_valid else Res.string.token_expired),
                if (r.tokenValid) positiveColor else negativeColor,
            ),
            Cell(r.sellOrders.toString()),
            Cell(r.buyOrders.toString()),
            Cell(r.corpOrders.toString(), AXIS_COLOR),
            Cell(r.trades30d.toString()),
            Cell(r.lastTrade?.take(16)?.replace("T", " ") ?: "—", AXIS_COLOR),
            Cell(String.format(Locale.US, "%.2f%% · %.2f%%", r.salesTaxPct, r.brokerFeePct), AXIS_COLOR),
            Cell("") {
                Row {
                    IconButton(onClick = { onRefresh(r.c) }, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Default.Refresh, stringResource(Res.string.refresh), Modifier.size(16.dp), tint = AXIS_COLOR)
                    }
                    IconButton(onClick = { onRemove(r.c) }, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Default.Delete, stringResource(Res.string.remove), Modifier.size(16.dp), tint = negativeColor)
                    }
                }
            },
        )
    }
}

@Composable
private fun CorpTable(
    rows: List<CorpRow>,
    onToggleTrack: (CorpRow) -> Unit,
) {
    val cols =
        listOf<Col<CorpRow>>(
            Col(stringResource(Res.string.h_corporation), 2.2f, end = false) { it.name.lowercase() },
            Col(stringResource(Res.string.h_ticker), 0.7f, end = false) { it.ticker },
            Col(stringResource(Res.string.h_members), 2f, end = false) { it.members.size },
            Col(stringResource(Res.string.kpi_orders), 0.7f) { it.orders },
            Col(stringResource(Res.string.kpi_trades_30d), 0.9f) { it.trades30d },
            Col("", 1f),
        )
    Table(
        cols = cols,
        rows = rows,
        empty = stringResource(Res.string.no_corps),
        defaultSort = 0,
        defaultDesc = false,
        key = { it.id },
    ) { r ->
        listOf(
            Cell(r.name) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CorporationLogo(r.id, size = 24.dp)
                    Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
            },
            Cell("[${r.ticker}]", AXIS_COLOR),
            Cell(r.members.joinToString(), AXIS_COLOR),
            Cell(r.orders.toString()),
            Cell(r.trades30d.toString()),
            Cell("") {
                Segment(stringResource(if (r.tracked) Res.string.tracked else Res.string.track), r.tracked) { onToggleTrack(r) }
            },
        )
    }
}

private suspend fun loadCharacters(): Pair<List<CharRow>, List<CorpRow>> =
    withContext(Dispatchers.IO) {
        CharacterDao.getAll().forEach { char -> runCatching { SsoAuthManager.ensureTokenFresh(char.id) } }
        runCatching { loadRows() }.getOrElse {
            println("Error loading characters: ${it.message}")
            emptyList<CharRow>() to emptyList()
        }
    }

private fun refreshCharacter(characterId: Int) {
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
