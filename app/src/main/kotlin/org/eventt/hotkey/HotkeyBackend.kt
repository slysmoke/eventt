package org.eventt.hotkey

import org.eventt.core.model.HotkeyCombo

/**
 * One physical global hotkey combo (any mix of Ctrl/Alt/Shift + a letter or F1-F12), expressed in
 * whatever form each native backend needs to register it.
 */
data class HotkeyKey(
    val label: String,
    val ctrl: Boolean,
    val alt: Boolean,
    val shift: Boolean,
    val win32VkCode: Int,
    val x11KeyString: String,
    val macVkCode: Int,
    // Distinguishes native hotkey IDs / portal shortcut IDs between the two keys this app
    // registers, so binding both at once can't collide.
    val id: Int,
) {
    companion object {
        // macOS virtual keycodes for letters follow no alphabetical order (ANSI layout codes).
        private val MAC_VK =
            mapOf(
                'A' to 0x00,
                'B' to 0x0B,
                'C' to 0x08,
                'D' to 0x02,
                'E' to 0x0E,
                'F' to 0x03,
                'G' to 0x05,
                'H' to 0x04,
                'I' to 0x22,
                'J' to 0x26,
                'K' to 0x28,
                'L' to 0x25,
                'M' to 0x2E,
                'N' to 0x2D,
                'O' to 0x1F,
                'P' to 0x23,
                'Q' to 0x0C,
                'R' to 0x0F,
                'S' to 0x01,
                'T' to 0x11,
                'U' to 0x20,
                'V' to 0x09,
                'W' to 0x0D,
                'X' to 0x07,
                'Y' to 0x10,
                'Z' to 0x06,
            )

        // macOS virtual keycodes for F1..F12 (kVK_F1..kVK_F12, Carbon HIToolbox Events.h), index 0 = F1.
        private val MAC_VK_F = listOf(0x7A, 0x78, 0x63, 0x76, 0x60, 0x61, 0x62, 0x64, 0x65, 0x6D, 0x67, 0x6F)

        // Win32 VK_F1 = 0x70, consecutive through VK_F12 = 0x7B.
        private const val WIN32_VK_F1 = 0x70

        fun fromCombo(
            combo: HotkeyCombo,
            id: Int,
        ): HotkeyKey {
            // HotkeyCombo only admits "A".."Z" and "F1".."F12", so any multi-char key is an F-key.
            if (combo.key.length > 1) {
                val fKey = combo.key.drop(1).toInt()
                require(fKey in 1..12) { "hotkey key must be A-Z or F1-F12, got '${combo.key}'" }
                return HotkeyKey(
                    label = combo.label,
                    ctrl = combo.ctrl,
                    alt = combo.alt,
                    shift = combo.shift,
                    win32VkCode = WIN32_VK_F1 + (fKey - 1),
                    x11KeyString = "F$fKey",
                    macVkCode = MAC_VK_F[fKey - 1],
                    id = id,
                )
            }
            val u = combo.key.singleOrNull()?.uppercaseChar()
            require(u != null && u in 'A'..'Z') { "hotkey key must be A-Z or F1-F12, got '${combo.key}'" }
            return HotkeyKey(
                label = combo.label,
                ctrl = combo.ctrl,
                alt = combo.alt,
                shift = combo.shift,
                win32VkCode = 0x41 + (u - 'A'),
                x11KeyString = u.lowercaseChar().toString(),
                macVkCode = MAC_VK.getValue(u),
                id = id,
            )
        }
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
