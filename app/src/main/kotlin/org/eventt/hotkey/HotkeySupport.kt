package org.eventt.hotkey

/**
 * Picks which [HotkeyBackend] to try, and in what order, based on OS and (on Linux) session type.
 */
object HotkeySupport {
    /** Ordered candidates - the first one whose `start()` succeeds wins. */
    fun candidates(): List<HotkeyBackend> {
        val osName = System.getProperty("os.name", "").lowercase()
        return when {
            osName.contains("win") -> listOf(Win32HotkeyBackend())
            osName.contains("mac") || osName.contains("darwin") -> listOf(MacHotkeyBackend())
            isWayland() -> listOf(PortalHotkeyBackend(), X11HotkeyBackend())
            else -> listOf(X11HotkeyBackend())
        }
    }

    private fun isWayland(): Boolean {
        val sessionType = System.getenv("XDG_SESSION_TYPE")?.lowercase()
        return sessionType == "wayland" || (sessionType == null && !System.getenv("WAYLAND_DISPLAY").isNullOrBlank())
    }
}
