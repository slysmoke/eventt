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
 * Fails gracefully on Wayland (no global input grabs) or when libXtst is
 * missing (NixOS without xorg.libXtst in the dev shell).
 */
object GlobalHotkeyService : NativeKeyListener {

    private const val HOTKEY_CODE = NativeKeyEvent.VC_SPACE
    private const val CTRL_MASK   = NativeInputEvent.CTRL_L_MASK or NativeInputEvent.CTRL_R_MASK
    private const val SHIFT_MASK  = NativeInputEvent.SHIFT_L_MASK or NativeInputEvent.SHIFT_R_MASK

    var isRegistered: Boolean = false
        private set

    fun start() {
        try {
            // Use a string literal instead of GlobalScreen::class.java.package.name.
            // Referencing GlobalScreen::class before the try block triggers <clinit>,
            // which loads the native lib and throws UnsatisfiedLinkError before we can catch it.
            Logger.getLogger("com.github.kwhat.jnativehook").apply {
                level = Level.OFF
                handlers.toList().forEach { it.level = Level.OFF }
            }
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(this)
            isRegistered = true
            println("[Hotkey] Global hotkey active: Ctrl+Shift+Space  (cycles orders: open market + copy price)")
        } catch (e: NativeHookException) {
            println("[Hotkey] Could not register global hotkey: ${e.message}")
            println("[Hotkey] On Wayland: run under XWayland, or use libei/portal.")
        } catch (e: UnsatisfiedLinkError) {
            println("[Hotkey] Missing native library: ${e.message}")
            println("[Hotkey] NixOS fix: add xorg.libXtst to devShell packages + LD_LIBRARY_PATH in flake.nix")
        } catch (e: Throwable) {
            println("[Hotkey] Could not start hotkey service: ${e::class.simpleName}: ${e.message}")
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
