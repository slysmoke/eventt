package org.eventt

import org.eventt.features.market.InterRegionQueue
import org.eventt.features.market.MarketAnalysisRouter
import org.eventt.features.market.StationTradingQueue
import org.eventt.features.orders.PendingOrdersQueue
import org.eventt.features.overlay.OverlayController
import org.eventt.hotkey.HotkeyBackend
import org.eventt.hotkey.HotkeyKey
import org.eventt.hotkey.HotkeySupport
import org.eventt.ui.AppScreen

/**
 * Registers two process-wide hotkeys so they work even when EVE Night Trade Tools is not the
 * focused window: Ctrl+Z cycles the order/trade queue, Ctrl+M opens the Trade Calc overlay at the
 * cursor.
 *
 * For each key, tries [HotkeySupport.candidates] in order (platform/session-appropriate native
 * backends - JNA calls on Windows/macOS/X11, D-Bus xdg-desktop-portal on Wayland) and keeps
 * whichever one first grabs it successfully. The two keys are registered independently — a fresh
 * backend instance per key — since one backend instance only ever grabs one hotkey.
 */
object GlobalHotkeyService {
    var isRegistered: Boolean = false
        private set
    var isOverlayHotkeyRegistered: Boolean = false
        private set

    // Every visited screen stays mounted in the background (see ScreenContent in EventtApp.kt),
    // so both Orders and Analysis can be actively refreshing their own queues at once. This tracks
    // which screen tab is actually on-screen right now, so Ctrl+Z acts on the one the user is
    // looking at rather than whichever queue happened to update most recently in the background.
    // EventtApp updates this whenever the selected tab changes.
    @Volatile var activeScreen: AppScreen = AppScreen.ORDERS

    private var queueBackend: HotkeyBackend? = null
    private var overlayBackend: HotkeyBackend? = null

    fun start() {
        val onQueueTrigger: () -> Unit = {
            when (activeScreen) {
                AppScreen.ANALYSIS ->
                    if (MarketAnalysisRouter.activeTab ==
                        0
                    ) {
                        StationTradingQueue.processNext()
                    } else {
                        InterRegionQueue.processNext()
                    }
                else -> PendingOrdersQueue.processNext()
            }
        }

        queueBackend = registerFirst(HotkeyKey.CTRL_Z, onQueueTrigger)
        isRegistered = queueBackend != null

        overlayBackend = registerFirst(HotkeyKey.CTRL_M, OverlayController::openAtMouse)
        isOverlayHotkeyRegistered = overlayBackend != null
    }

    private fun registerFirst(
        key: HotkeyKey,
        onTrigger: () -> Unit,
    ): HotkeyBackend? {
        for (candidate in HotkeySupport.candidates()) {
            val ok =
                try {
                    candidate.start(key, onTrigger)
                } catch (e: Throwable) {
                    println("[Hotkey] ${candidate::class.simpleName} (${key.label}) failed: ${e::class.simpleName}: ${e.message}")
                    false
                }
            if (ok) return candidate
        }
        println("[Hotkey] Could not register ${key.label} with any available backend.")
        return null
    }

    fun stop() {
        queueBackend?.stop()
        queueBackend = null
        isRegistered = false
        overlayBackend?.stop()
        overlayBackend = null
        isOverlayHotkeyRegistered = false
    }
}
