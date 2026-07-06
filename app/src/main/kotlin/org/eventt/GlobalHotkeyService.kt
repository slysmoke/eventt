package org.eventt

import org.eventt.features.market.InterRegionQueue
import org.eventt.features.market.MarketAnalysisRouter
import org.eventt.features.market.StationTradingQueue
import org.eventt.features.orders.PendingOrdersQueue
import org.eventt.hotkey.HotkeyBackend
import org.eventt.hotkey.HotkeySupport
import org.eventt.ui.AppScreen

/**
 * Registers a process-wide Ctrl+Z hotkey so the order-cycling action works even when EVE Night
 * Trade Tools is not the focused window.
 *
 * Tries [HotkeySupport.candidates] in order (platform/session-appropriate native backends - JNA
 * calls on Windows/macOS/X11, D-Bus xdg-desktop-portal on Wayland) and keeps whichever one first
 * grabs the hotkey successfully.
 */
object GlobalHotkeyService {

    var isRegistered: Boolean = false
        private set

    // Every visited screen stays mounted in the background (see ScreenContent in EventtApp.kt),
    // so both Orders and Analysis can be actively refreshing their own queues at once. This tracks
    // which screen tab is actually on-screen right now, so Ctrl+Z acts on the one the user is
    // looking at rather than whichever queue happened to update most recently in the background.
    // EventtApp updates this whenever the selected tab changes.
    @Volatile var activeScreen: AppScreen = AppScreen.ORDERS

    private var backend: HotkeyBackend? = null

    fun start() {
        val onTrigger: () -> Unit = {
            when (activeScreen) {
                AppScreen.ANALYSIS -> if (MarketAnalysisRouter.activeTab == 0) StationTradingQueue.processNext() else InterRegionQueue.processNext()
                else -> PendingOrdersQueue.processNext()
            }
        }

        for (candidate in HotkeySupport.candidates()) {
            val ok = try {
                candidate.start(onTrigger)
            } catch (e: Throwable) {
                println("[Hotkey] ${candidate::class.simpleName} failed: ${e::class.simpleName}: ${e.message}")
                false
            }
            if (ok) {
                backend = candidate
                isRegistered = true
                return
            }
        }

        println("[Hotkey] Could not register a global hotkey with any available backend.")
    }

    fun stop() {
        backend?.stop()
        backend = null
        isRegistered = false
    }
}
