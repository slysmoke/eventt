package org.eventt

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.eventt.core.database.DatabaseManager
import org.eventt.core.http.EveHttpClient
import org.eventt.ui.EventtApp

fun main() {
    // ESI requires a User-Agent identifying the app on every request — must be set before
    // any ESI/SSO call, which is why this runs before anything else in main().
    val repoUrl = AppVersion.GITHUB_REPO.takeIf { it.isNotBlank() }?.let { "https://github.com/$it" }
    EveHttpClient.configure(
        "EventNightTradeTools/${AppVersion.NAME}" + (repoUrl?.let { " (+$it)" } ?: ""),
    )

    // Initialize database BEFORE UI starts — prevents race conditions
    println("[App] Initializing database...")
    try {
        DatabaseManager.initialize()
        println("[App] Database initialized successfully")
    } catch (e: Exception) {
        println("[App] Database init failed: ${e.stackTraceToString()}")
    }

    GlobalHotkeyService.start()

    application {
        Window(
            title = "EVE Night Trade Tools",
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
            icon = painterResource("icon.png"),
            onCloseRequest = {
                GlobalHotkeyService.stop()
                exitApplication()
            },
        ) {
            EventtApp()
        }
    }
}
