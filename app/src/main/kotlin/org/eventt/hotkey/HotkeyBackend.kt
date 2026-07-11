package org.eventt.hotkey

/**
 * One physical global hotkey combo, expressed in whatever form each native backend needs to
 * register it. Ctrl is the only modifier used anywhere in this app, so it's baked into each
 * backend rather than threaded through here.
 */
data class HotkeyKey(
    val label: String,
    val win32VkCode: Int,
    val x11KeyString: String,
    val macVkCode: Int,
    // Distinguishes native hotkey IDs / portal shortcut IDs between the two keys this app
    // registers, so binding both at once (Ctrl+Z and Ctrl+M) can't collide.
    val id: Int,
) {
    companion object {
        val CTRL_Z = HotkeyKey(label = "Ctrl+Z", win32VkCode = 0x5A, x11KeyString = "z", macVkCode = 0x06, id = 1)
        val CTRL_M = HotkeyKey(label = "Ctrl+M", win32VkCode = 0x4D, x11KeyString = "m", macVkCode = 0x2E, id = 2)
    }
}

/**
 * A platform-specific mechanism for grabbing a single global hotkey.
 *
 * Implementations own whatever native resources/threads they need and must make `stop()` safe
 * to call even if `start()` never successfully registered anything. One instance registers one
 * [HotkeyKey] — grabbing two different keys means creating two instances.
 */
interface HotkeyBackend {
    /** Attempts to register [key]; returns true if it was grabbed successfully. */
    fun start(
        key: HotkeyKey,
        onTrigger: () -> Unit,
    ): Boolean

    /** Releases the hotkey and any native resources. No-op if never started. */
    fun stop()
}
