package org.eve.trader

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.eve.trader.ui.EveTraderApp

fun main() = application {
    Window(
        title = "EVE Trader",
        state = rememberWindowState(width = 1200.dp, height = 800.dp),
        onCloseRequest = ::exitApplication
    ) {
        EveTraderApp()
    }
}
