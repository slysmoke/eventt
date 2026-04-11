package org.eve.trader

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.eve.trader.core.database.DatabaseManager
import org.eve.trader.ui.EveTraderApp

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

    application {
        Window(
            title = "EVE Trader",
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
            onCloseRequest = ::exitApplication,
        ) {
            EveTraderApp()
        }
    }
}
