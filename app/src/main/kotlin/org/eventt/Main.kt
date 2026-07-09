package org.eventt

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.eventt.core.database.DatabaseManager
import org.eventt.core.http.EveHttpClient
import org.eventt.core.marketlogs.MarketLogCleaner
import org.eventt.core.marketlogs.MarketLogWatcher
import org.eventt.core.model.AppPaths
import org.eventt.ui.EventtApp

fun main() {
    // ESI requires a User-Agent identifying the app on every request — must be set before
    // any ESI/SSO call, which is why this runs before anything else in main().
    val repoUrl = AppVersion.GITHUB_REPO.takeIf { it.isNotBlank() }?.let { "https://github.com/$it" }
    EveHttpClient.configure(
        "EventNightTradeTools/${AppVersion.NAME}" + (repoUrl?.let { " (+$it)" } ?: ""),
    )

    // One-time pickup of data from the old ~/.eve-trader / ~/.eventt home-dir locations, before
    // anything (DB, token key) reads/writes the new per-OS app-data directory.
    AppPaths.migrateLegacyData()

    // Initialize database BEFORE UI starts — prevents race conditions
    println("[App] Initializing database...")
    try {
        DatabaseManager.initialize()
        println("[App] Database initialized successfully")
    } catch (e: Exception) {
        println("[App] Database init failed: ${e.stackTraceToString()}")
    }

    // Wipe stale exports left over from a previous session before the watcher starts picking
    // up files — this folder holds nothing but transient EVE client exports.
    val cleaned = MarketLogCleaner.cleanOnStartup()
    if (cleaned > 0) println("[App] Cleared $cleaned stale marketlog file(s)")

    GlobalHotkeyService.start()
    MarketLogWatcher.start()

    application {
        Window(
            title = "EVE Night Trade Tools",
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
            icon = painterResource("icon.png"),
            onCloseRequest = {
                GlobalHotkeyService.stop()
                MarketLogWatcher.stop()
                exitApplication()
            },
        ) {
            EventtApp()
        }
    }
}
