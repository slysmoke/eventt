package org.eve.trader

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.NativeInputEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import org.eve.trader.features.orders.PendingOrdersQueue
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Registers a process-wide keyboard hook so the order-cycling action works
 * even when EVE Trader is not the focused window.
 *
 * Default hotkey: Ctrl+Shift+Space
 *
 * On Wayland the hook cannot be registered (Wayland forbids global input grabs
 * without elevated privileges). The failure is logged and the app continues
 * normally — all in-app hotkeys still work.
 */
object GlobalHotkeyService : NativeKeyListener {

    private const val HOTKEY_CODE = NativeKeyEvent.VC_SPACE
    private const val CTRL_MASK   = NativeInputEvent.CTRL_L_MASK or NativeInputEvent.CTRL_R_MASK
    private const val SHIFT_MASK  = NativeInputEvent.SHIFT_L_MASK or NativeInputEvent.SHIFT_R_MASK

    var isRegistered: Boolean = false
        private set

    fun start() {
        // JNativeHook logs verbosely to java.util.logging — silence it
        Logger.getLogger(GlobalScreen::class.java.`package`.name).apply {
            level = Level.OFF
            handlers.toList().forEach { it.level = Level.OFF }
        }

        try {
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(this)
            isRegistered = true
            println("[Hotkey] Global hotkey active: Ctrl+Shift+Space  (cycles orders, open market + copy price)")
        } catch (e: NativeHookException) {
            println("[Hotkey] Could not register global hotkey: ${e.message}")
            println("[Hotkey] Hint: on Wayland this requires a compatibility layer (XWayland or libei/portal).")
        }
    }

    fun stop() {
        if (!isRegistered) return
        runCatching {
            GlobalScreen.removeNativeKeyListener(this)
            GlobalScreen.unregisterNativeHook()
        }
        isRegistered = false
    }

    override fun nativeKeyPressed(e: NativeKeyEvent) {
        if (e.keyCode == HOTKEY_CODE &&
            (e.modifiers and CTRL_MASK)  != 0 &&
            (e.modifiers and SHIFT_MASK) != 0
        ) {
            PendingOrdersQueue.processNext()
        }
    }

    override fun nativeKeyReleased(e: NativeKeyEvent) {}
    override fun nativeKeyTyped(e: NativeKeyEvent) {}
}
