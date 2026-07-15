package org.eventt.hotkey

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * Global hotkey via raw Xlib (XGrabKey), for Linux X11 sessions and Wayland sessions
 * running an X11 app through XWayland. Needs only libX11 at runtime (no libXtst/libXt/libXi,
 * unlike JNativeHook, since XGrabKey is core Xlib rather than the XTest extension).
 */
class X11HotkeyBackend : HotkeyBackend {
    private var display: Pointer? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    override fun start(
        key: HotkeyKey,
        onTrigger: () -> Unit,
    ): Boolean {
        val lib =
            try {
                X11Lib.INSTANCE
            } catch (e: Throwable) {
                println("[Hotkey][X11] libX11 not available: ${e.message}")
                return false
            }

        val disp = lib.XOpenDisplay(null)
        if (disp == null) {
            println("[Hotkey][X11] XOpenDisplay failed (no X11/XWayland display reachable)")
            return false
        }

        val root = lib.XDefaultRootWindow(disp)
        val keysym = lib.XStringToKeysym(key.x11KeyString)
        if (keysym.toLong() == 0L) {
            println("[Hotkey][X11] XStringToKeysym('${key.x11KeyString}') failed")
            lib.XCloseDisplay(disp)
            return false
        }
        val keycode = lib.XKeysymToKeycode(disp, keysym).toInt() and 0xFF
        if (keycode == 0) {
            println("[Hotkey][X11] XKeysymToKeycode failed for '${key.x11KeyString}'")
            lib.XCloseDisplay(disp)
            return false
        }

        val baseMask =
            (if (key.ctrl) CONTROL_MASK else 0) or
                (if (key.alt) MOD1_MASK else 0) or
                (if (key.shift) SHIFT_MASK else 0)

        // XGrabKey doesn't ignore lock modifiers (CapsLock/NumLock) by itself - grab every
        // combination so the hotkey doesn't silently stop firing when either is toggled on.
        for (lockBits in intArrayOf(0, LOCK_MASK, NUM_LOCK_MASK, LOCK_MASK or NUM_LOCK_MASK)) {
            lib.XGrabKey(disp, keycode, baseMask or lockBits, root, 0, GRAB_MODE_ASYNC, GRAB_MODE_ASYNC)
        }

        display = disp
        val eventBuf = Memory(256)
        running = true
        thread =
            Thread({
                while (running) {
                    if (lib.XPending(disp) > 0) {
                        lib.XNextEvent(disp, eventBuf)
                        val event = XKeyEvent(eventBuf)
                        if (event.type == KEY_PRESS && event.keycode == keycode) {
                            onTrigger()
                        }
                    } else {
                        Thread.sleep(30)
                    }
                }
            }, "x11-hotkey").apply {
                isDaemon = true
                start()
            }

        println("[Hotkey][X11] Global hotkey active: ${key.label} (X11/XWayland)")
        return true
    }

    override fun stop() {
        running = false
        thread?.join(200)
        thread = null
        display?.let { d -> runCatching { X11Lib.INSTANCE.XCloseDisplay(d) } }
        display = null
    }

    private companion object {
        const val SHIFT_MASK = 1 shl 0
        const val CONTROL_MASK = 1 shl 2
        const val MOD1_MASK = 1 shl 3 // Alt
        const val LOCK_MASK = 1 shl 1
        const val NUM_LOCK_MASK = 1 shl 4
        const val GRAB_MODE_ASYNC = 1
        const val KEY_PRESS = 2
    }
}

private interface X11Lib : Library {
    fun XOpenDisplay(displayName: String?): Pointer?

    fun XCloseDisplay(display: Pointer): Int

    fun XDefaultRootWindow(display: Pointer): NativeLong

    fun XStringToKeysym(string: String): NativeLong

    fun XKeysymToKeycode(
        display: Pointer,
        keysym: NativeLong,
    ): Byte

    fun XGrabKey(
        display: Pointer,
        keycode: Int,
        modifiers: Int,
        grabWindow: NativeLong,
        ownerEvents: Int,
        pointerMode: Int,
        keyboardMode: Int,
    ): Int

    fun XUngrabKey(
        display: Pointer,
        keycode: Int,
        modifiers: Int,
        grabWindow: NativeLong,
    ): Int

    fun XPending(display: Pointer): Int

    fun XNextEvent(
        display: Pointer,
        eventReturn: Pointer,
    ): Int

    companion object {
        val INSTANCE: X11Lib = Native.load("X11", X11Lib::class.java)
    }
}

// Mirrors Xlib's XKeyEvent (the union member XNextEvent fills in for KeyPress/KeyRelease).
// Field types/order must match the native struct exactly for JNA's default (GNU C) alignment
// to compute the same offsets the C compiler used.
// Must be public (not Kotlin `private`/file-private, which compiles to package-private): JNA's
// Structure.read() reflects into this class's fields from the com.sun.jna package and throws
// IllegalAccessException if the class itself isn't accessible cross-package - confirmed live.
@Structure.FieldOrder(
    "type",
    "serial",
    "sendEvent",
    "display",
    "window",
    "root",
    "subwindow",
    "time",
    "x",
    "y",
    "xRoot",
    "yRoot",
    "state",
    "keycode",
    "sameScreen",
)
class XKeyEvent(
    p: Pointer,
) : Structure(p) {
    @JvmField var type: Int = 0

    @JvmField var serial: NativeLong = NativeLong()

    @JvmField var sendEvent: Int = 0

    @JvmField var display: Pointer? = null

    @JvmField var window: NativeLong = NativeLong()

    @JvmField var root: NativeLong = NativeLong()

    @JvmField var subwindow: NativeLong = NativeLong()

    @JvmField var time: NativeLong = NativeLong()

    @JvmField var x: Int = 0

    @JvmField var y: Int = 0

    @JvmField var xRoot: Int = 0

    @JvmField var yRoot: Int = 0

    @JvmField var state: Int = 0

    @JvmField var keycode: Int = 0

    @JvmField var sameScreen: Int = 0

    init {
        read()
    }
}
