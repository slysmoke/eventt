package org.eventt.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One global hotkey combination: any mix of Ctrl/Alt/Shift plus a letter. At least one modifier
 * is required — a bare letter as a system-wide grab would swallow normal typing everywhere.
 */
data class HotkeyCombo(
    val ctrl: Boolean,
    val alt: Boolean,
    val shift: Boolean,
    val letter: Char,
) {
    val label: String =
        buildString {
            if (ctrl) append("Ctrl+")
            if (alt) append("Alt+")
            if (shift) append("Shift+")
            append(letter)
        }

    /** Stable settings form, e.g. "CTRL+ALT+Z". */
    fun serialize(): String = label.uppercase()

    companion object {
        val QUEUE_DEFAULT = HotkeyCombo(ctrl = true, alt = false, shift = false, letter = 'Z')
        val OVERLAY_DEFAULT = HotkeyCombo(ctrl = true, alt = false, shift = false, letter = 'M')

        /**
         * Parse a stored combo ("CTRL+ALT+Z"). A bare letter (the pre-combo settings format)
         * is read as Ctrl+letter. Null for anything malformed or modifier-less.
         */
        fun parse(raw: String?): HotkeyCombo? {
            val parts =
                raw
                    ?.trim()
                    ?.uppercase()
                    ?.split("+")
                    ?.filter { it.isNotBlank() } ?: return null
            val letter = parts.lastOrNull()?.singleOrNull()?.takeIf { it in 'A'..'Z' } ?: return null
            val mods = parts.dropLast(1).toSet()
            if (mods.isEmpty()) return HotkeyCombo(ctrl = true, alt = false, shift = false, letter = letter)
            if (!mods.all { it == "CTRL" || it == "ALT" || it == "SHIFT" }) return null
            return HotkeyCombo(ctrl = "CTRL" in mods, alt = "ALT" in mods, shift = "SHIFT" in mods, letter = letter)
        }
    }
}

/**
 * Shared view of the two configurable global hotkeys. The app shell registers the native
 * hotkeys and publishes the actual labels here; feature screens read them for display, and the
 * Settings screen calls [applyChange] after saving new combos so they take effect immediately.
 */
object HotkeyBindings {
    const val QUEUE_KEY_SETTING = "hotkey.queue_key"
    const val OVERLAY_KEY_SETTING = "hotkey.overlay_key"

    private val _queueLabel = MutableStateFlow(HotkeyCombo.QUEUE_DEFAULT.label)
    val queueLabel: StateFlow<String> = _queueLabel.asStateFlow()

    private val _overlayLabel = MutableStateFlow(HotkeyCombo.OVERLAY_DEFAULT.label)
    val overlayLabel: StateFlow<String> = _overlayLabel.asStateFlow()

    fun publishLabels(
        queue: String,
        overlay: String,
    ) {
        _queueLabel.value = queue
        _overlayLabel.value = overlay
    }

    // Wired by the app shell at startup (re-registers the native hotkeys); the Settings screen
    // invokes it after persisting new combos. Lives here because features can't see the app module.
    @Volatile var applyChange: (() -> Unit)? = null
}
