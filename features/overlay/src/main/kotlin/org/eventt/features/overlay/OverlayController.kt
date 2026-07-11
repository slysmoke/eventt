package org.eventt.features.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.MouseInfo

/**
 * Single source of truth for whether the Trade Calc overlay window is shown, shared between the
 * top-bar toggle button (app module) and the global Ctrl+M hotkey (also app module, since that's
 * where the native hotkey backends live) — neither of those has direct access to the other's UI
 * state, so this is the thing they both read/write instead.
 */
object OverlayController {
    private val _isOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    // Where to place the window on its *next* open, in screen pixels — only set by [openAtMouse],
    // so the top-bar button still reopens wherever the window was last dragged to (its own
    // Preferences-backed position) instead of jumping to a stale cursor location.
    private var pendingOpenPosition: Pair<Int, Int>? = null

    /** Toolbar button: plain open/close, keeps whatever position the window was last left at. */
    fun toggle() {
        _isOpen.value = !_isOpen.value
    }

    /** Global hotkey: opens at the current cursor position, or closes if already open. */
    fun openAtMouse() {
        if (_isOpen.value) {
            _isOpen.value = false
            return
        }
        pendingOpenPosition = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()?.let { it.x to it.y }
        _isOpen.value = true
    }

    fun close() {
        _isOpen.value = false
    }

    /** Read-and-clear: consumed once by OverlayWindow at the moment it composes its WindowState. */
    fun consumePendingOpenPosition(): Pair<Int, Int>? {
        val pos = pendingOpenPosition
        pendingOpenPosition = null
        return pos
    }
}
