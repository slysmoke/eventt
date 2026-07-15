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
        val icon = trayIcon
        if (icon != null) {
            icon.displayMessage(title, message, TrayIcon.MessageType.WARNING)
            return
        }
        // No AWT tray (common on Linux Wayland/GNOME sessions) — libnotify's notify-send is the
        // standard desktop-notification path there and needs no tray at all.
        runCatching {
            ProcessBuilder("notify-send", "--app-name=EVE Night Trade Tools", "--urgency=normal", title, message).start()
        }.onFailure {
            AppLog.warn("Notify", "$title: $message (no system tray or notify-send)")
        }
    }
}
