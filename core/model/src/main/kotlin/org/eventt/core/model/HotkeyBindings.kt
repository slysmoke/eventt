package org.eventt.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared view of the two configurable global hotkeys (Ctrl is always the modifier; only the
 * letter is configurable). The app shell registers the native hotkeys and publishes the actual
 * labels here; feature screens read them for display, and the Settings screen calls
 * [applyChange] after saving new letters so the bindings take effect without a restart.
 */
object HotkeyBindings {
    const val QUEUE_KEY_SETTING = "hotkey.queue_key"
    const val OVERLAY_KEY_SETTING = "hotkey.overlay_key"
    const val DEFAULT_QUEUE_LETTER = 'Z'
    const val DEFAULT_OVERLAY_LETTER = 'M'

    private val _queueLabel = MutableStateFlow("Ctrl+$DEFAULT_QUEUE_LETTER")
    val queueLabel: StateFlow<String> = _queueLabel.asStateFlow()

    private val _overlayLabel = MutableStateFlow("Ctrl+$DEFAULT_OVERLAY_LETTER")
    val overlayLabel: StateFlow<String> = _overlayLabel.asStateFlow()

    fun publishLabels(
        queue: String,
        overlay: String,
    ) {
        _queueLabel.value = queue
        _overlayLabel.value = overlay
    }

    // Wired by the app shell at startup (re-registers the native hotkeys); the Settings screen
    // invokes it after persisting new letters. Lives here because features can't see the app module.
    @Volatile var applyChange: (() -> Unit)? = null

    /** Normalize a stored setting to a single A–Z letter, or fall back to [default]. */
    fun letterOrDefault(
        raw: String?,
        default: Char,
    ): Char =
        raw
            ?.trim()
            ?.uppercase()
            ?.firstOrNull()
            ?.takeIf { it in 'A'..'Z' } ?: default
}
