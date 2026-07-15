package org.eventt.notify

import org.eventt.core.model.AppLog
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon

/**
 * System-tray notifications (currently: beaten-order alerts). The tray icon is added lazily on
 * first use and kept for the app's lifetime; if the platform has no tray support the
 * notification is dropped with an AppLog entry instead of throwing.
 */
object TrayNotifier {
    private val trayIcon: TrayIcon? by lazy {
        runCatching {
            if (!SystemTray.isSupported()) return@runCatching null
            val image = Toolkit.getDefaultToolkit().getImage(javaClass.getResource("/icon.png"))
            TrayIcon(image, "EVE Night Trade Tools").apply {
                isImageAutoSize = true
                SystemTray.getSystemTray().add(this)
            }
        }.onFailure { AppLog.warn("Notify", "system tray unavailable: ${it.message}") }.getOrNull()
    }

    fun notify(
        title: String,
        message: String,
    ) {
        trayIcon?.displayMessage(title, message, TrayIcon.MessageType.WARNING)
            ?: AppLog.warn("Notify", "$title: $message (no system tray)")
    }
}
