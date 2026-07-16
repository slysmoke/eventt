package org.eventt.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.database.StaticDataDao
import org.eventt.core.model.HotkeyBindings
import org.eventt.core.model.HotkeyCombo

/**
 * Rebind the two global hotkeys by pressing the wanted combination: any mix of Ctrl/Alt/Shift
 * plus a letter (at least one modifier — a bare letter grabbed system-wide would swallow normal
 * typing). Changes are saved and re-registered immediately.
 */
@Composable
internal fun HotkeysCard() {
    var queueCombo by remember { mutableStateOf(HotkeyCombo.QUEUE_DEFAULT) }
    var overlayCombo by remember { mutableStateOf(HotkeyCombo.OVERLAY_DEFAULT) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            queueCombo =
                HotkeyCombo.parse(StaticDataDao.getSetting(HotkeyBindings.QUEUE_KEY_SETTING))
                    ?: HotkeyCombo.QUEUE_DEFAULT
            overlayCombo =
                HotkeyCombo.parse(StaticDataDao.getSetting(HotkeyBindings.OVERLAY_KEY_SETTING))
                    ?: HotkeyCombo.OVERLAY_DEFAULT
        }
    }

    suspend fun save(
        settingKey: String,
        combo: HotkeyCombo,
    ) {
        withContext(Dispatchers.IO) { StaticDataDao.setSetting(settingKey, combo.serialize()) }
        HotkeyBindings.applyChange?.invoke()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Global Hotkeys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "System-wide — they trigger even while the EVE client has focus. Click a binding, then " +
                    "press the combination you want (Ctrl/Alt/Shift + a letter, at least one modifier). " +
                    "Extra mouse buttons: bind them to this combination in your mouse software " +
                    "(Logitech G HUB, Razer Synapse, input-remapper on Linux).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HotkeyCaptureRow(
                label = "Cycle order/trade queue",
                combo = queueCombo,
                takenCombo = overlayCombo,
                onPick = { queueCombo = it },
                settingKey = HotkeyBindings.QUEUE_KEY_SETTING,
                save = ::save,
            )
            HotkeyCaptureRow(
                label = "Toggle Trade Calc overlay",
                combo = overlayCombo,
                takenCombo = queueCombo,
                onPick = { overlayCombo = it },
                settingKey = HotkeyBindings.OVERLAY_KEY_SETTING,
                save = ::save,
            )
        }
    }
}

// Compose desktop Keys for A..Z, index 0 = 'A'.
private val LETTER_KEYS =
    listOf(
        Key.A,
        Key.B,
        Key.C,
        Key.D,
        Key.E,
        Key.F,
        Key.G,
        Key.H,
        Key.I,
        Key.J,
        Key.K,
        Key.L,
        Key.M,
        Key.N,
        Key.O,
        Key.P,
        Key.Q,
        Key.R,
        Key.S,
        Key.T,
        Key.U,
        Key.V,
        Key.W,
        Key.X,
        Key.Y,
        Key.Z,
    )

@Composable
private fun HotkeyCaptureRow(
    label: String,
    combo: HotkeyCombo,
    takenCombo: HotkeyCombo,
    onPick: (HotkeyCombo) -> Unit,
    settingKey: String,
    save: suspend (String, HotkeyCombo) -> Unit,
) {
    var capturing by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<HotkeyCombo?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(pendingSave) {
        pendingSave?.let {
            save(settingKey, it)
            pendingSave = null
        }
    }
    LaunchedEffect(capturing) {
        if (capturing) focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            hint?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        OutlinedButton(
            onClick = {
                capturing = true
                hint = null
            },
            modifier =
                Modifier
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (!it.isFocused) capturing = false }
                    .onPreviewKeyEvent { event ->
                        if (!capturing) return@onPreviewKeyEvent false
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                        val idx = LETTER_KEYS.indexOf(event.key)
                        when {
                            event.key == Key.Escape -> {
                                capturing = false
                            }

                            idx == -1 -> {
                                // Modifier keydowns pass silently while the user forms the chord;
                                // anything else that isn't a letter can't be bound.
                                val isModifier =
                                    event.key == Key.CtrlLeft ||
                                        event.key == Key.CtrlRight ||
                                        event.key == Key.AltLeft ||
                                        event.key == Key.AltRight ||
                                        event.key == Key.ShiftLeft ||
                                        event.key == Key.ShiftRight
                                if (!isModifier) hint = "Press Ctrl/Alt/Shift + a letter (A–Z)"
                            }

                            else -> {
                                val picked =
                                    HotkeyCombo(
                                        ctrl = event.isCtrlPressed,
                                        alt = event.isAltPressed,
                                        shift = event.isShiftPressed,
                                        letter = 'A' + idx,
                                    )
                                when {
                                    !picked.ctrl && !picked.alt && !picked.shift -> {
                                        hint = "Hold at least one modifier (Ctrl/Alt/Shift)"
                                    }

                                    picked == takenCombo -> {
                                        hint = "${picked.label} is already used by the other hotkey"
                                    }

                                    else -> {
                                        onPick(picked)
                                        pendingSave = picked
                                        hint = null
                                        capturing = false
                                    }
                                }
                            }
                        }
                        true // swallow everything while capturing
                    },
        ) {
            Text(if (capturing) "Press keys…" else combo.label)
        }
    }
}
