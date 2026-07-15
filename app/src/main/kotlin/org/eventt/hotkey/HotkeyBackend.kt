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

        /** Ctrl + a single letter — the only combo shape this app registers. */
        fun ctrlLetter(
            letter: Char,
            id: Int,
        ): HotkeyKey {
            val u = letter.uppercaseChar()
            require(u in 'A'..'Z') { "hotkey letter must be A-Z, got '$letter'" }
            return HotkeyKey(
                label = "Ctrl+$u",
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
