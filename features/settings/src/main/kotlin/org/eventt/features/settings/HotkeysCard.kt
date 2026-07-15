package org.eventt.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.database.StaticDataDao
import org.eventt.core.model.HotkeyBindings

/**
 * Rebind the two global hotkeys. Ctrl is fixed (the only modifier the native backends register);
 * the letter is the configurable part. Changes are saved and re-registered immediately.
 */
@Composable
internal fun HotkeysCard() {
    var queueLetter by remember { mutableStateOf(HotkeyBindings.DEFAULT_QUEUE_LETTER) }
    var overlayLetter by remember { mutableStateOf(HotkeyBindings.DEFAULT_OVERLAY_LETTER) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            queueLetter =
                HotkeyBindings.letterOrDefault(
                    StaticDataDao.getSetting(HotkeyBindings.QUEUE_KEY_SETTING),
                    HotkeyBindings.DEFAULT_QUEUE_LETTER,
                )
            overlayLetter =
                HotkeyBindings.letterOrDefault(
                    StaticDataDao.getSetting(HotkeyBindings.OVERLAY_KEY_SETTING),
                    HotkeyBindings.DEFAULT_OVERLAY_LETTER,
                )
        }
    }

    suspend fun save(
        settingKey: String,
        letter: Char,
    ) {
        withContext(Dispatchers.IO) { StaticDataDao.setSetting(settingKey, letter.toString()) }
        HotkeyBindings.applyChange?.invoke()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Global Hotkeys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "System-wide — they trigger even while the EVE client has focus. Ctrl is always the modifier.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HotkeyRow(
                label = "Cycle order/trade queue",
                letter = queueLetter,
                takenLetter = overlayLetter,
                onPick = { queueLetter = it },
                settingKey = HotkeyBindings.QUEUE_KEY_SETTING,
                save = ::save,
            )
            HotkeyRow(
                label = "Toggle Trade Calc overlay",
                letter = overlayLetter,
                takenLetter = queueLetter,
                onPick = { overlayLetter = it },
                settingKey = HotkeyBindings.OVERLAY_KEY_SETTING,
                save = ::save,
            )
        }
    }
}

@Composable
private fun HotkeyRow(
    label: String,
    letter: Char,
    takenLetter: Char,
    onPick: (Char) -> Unit,
    settingKey: String,
    save: suspend (String, Char) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<Char?>(null) }

    LaunchedEffect(pendingSave) {
        pendingSave?.let {
            save(settingKey, it)
            pendingSave = null
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Column {
            OutlinedButton(onClick = { expanded = true }) { Text("Ctrl+$letter") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (candidate in 'A'..'Z') {
                    if (candidate == takenLetter) continue
                    DropdownMenuItem(
                        text = { Text("Ctrl+$candidate") },
                        onClick = {
                            expanded = false
                            if (candidate != letter) {
                                onPick(candidate)
                                pendingSave = candidate
                            }
                        },
                    )
                }
            }
        }
    }
}
