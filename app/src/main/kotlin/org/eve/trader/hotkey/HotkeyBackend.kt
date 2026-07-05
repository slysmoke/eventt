package org.eve.trader.hotkey

/**
 * A platform-specific mechanism for grabbing a single global Ctrl+Z hotkey.
 *
 * Implementations own whatever native resources/threads they need and must make `stop()` safe
 * to call even if `start()` never successfully registered anything.
 */
interface HotkeyBackend {
    /** Attempts to register the hotkey; returns true if it was grabbed successfully. */
    fun start(onTrigger: () -> Unit): Boolean

    /** Releases the hotkey and any native resources. No-op if never started. */
    fun stop()
}
