package org.eventt

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.eventt.core.database.DatabaseManager
import org.eventt.ui.EventtApp

fun main() {
    // Initialize database BEFORE UI starts — prevents race conditions
    println("[App] Initializing database...")
    try {
        DatabaseManager.initialize()
        println("[App] Database initialized successfully")
    } catch (e: Exception) {
        println("[App] Database init failed: ${e.message}")
        e.printStackTrace()
    }

    GlobalHotkeyService.start()

    application {
        Window(
            title = "EVE Night Trade Tools",
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
            onCloseRequest = {
                GlobalHotkeyService.stop()
                exitApplication()
            },
        ) {
            EventtApp()
        }
    }
}
