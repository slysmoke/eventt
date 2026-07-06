package org.eventt.hotkey

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser.MSG
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Global Ctrl+Z hotkey on Windows via jna-platform's User32.RegisterHotKey.
 *
 * Registering with hWnd = NULL binds the hotkey to the *calling thread's* message queue, so a
 * dedicated thread both registers it and runs the GetMessage loop that receives WM_HOTKEY.
 * `stop()` unblocks that loop with PostThreadMessage(WM_QUIT) rather than anything that could
 * leave GetMessage blocked forever.
 *
 * NOTE: cannot be built/run on this (Linux) dev machine - written to the documented Win32 API
 * contract; needs manual verification on real Windows before shipping.
 */
class Win32HotkeyBackend : HotkeyBackend {
    private var thread: Thread? = null

    @Volatile private var threadId: Int = 0

    @Volatile private var registered: Boolean = false

    override fun start(onTrigger: () -> Unit): Boolean {
        val user32 =
            try {
                User32.INSTANCE
            } catch (e: Throwable) {
                println("[Hotkey][Win32] user32 not available: ${e.message}")
                return false
            }

        val started = CountDownLatch(1)

        thread =
            Thread({
                try {
                    threadId = Kernel32.INSTANCE.GetCurrentThreadId()
                    registered = user32.RegisterHotKey(null, HOTKEY_ID, MOD_CONTROL, VK_Z)
                } catch (e: Throwable) {
                    println("[Hotkey][Win32] Failed to register hotkey: ${e.message}")
                    registered = false
                } finally {
                    started.countDown()
                }
                if (!registered) return@Thread

                val msg = MSG()
                while (true) {
                    val ret = user32.GetMessage(msg, null, 0, 0)
                    if (ret <= 0) break // WM_QUIT or error
                    if (msg.message == WM_HOTKEY && msg.wParam.toInt() == HOTKEY_ID) {
                        onTrigger()
                    }
                    user32.TranslateMessage(msg)
                    user32.DispatchMessage(msg)
                }
                user32.UnregisterHotKey(null, HOTKEY_ID)
            }, "win32-hotkey").apply {
                isDaemon = true
                start()
            }

        val gotResponse = started.await(2, TimeUnit.SECONDS)
        if (!gotResponse || !registered) {
            println("[Hotkey][Win32] RegisterHotKey failed (Ctrl+Z likely already bound by another app)")
            thread = null
            return false
        }

        println("[Hotkey][Win32] Global hotkey active: Ctrl+Z")
        return true
    }

    override fun stop() {
        val id = threadId
        if (id != 0) {
            runCatching { User32.INSTANCE.PostThreadMessage(id, WM_QUIT, WPARAM(0), LPARAM(0)) }
        }
        thread?.join(200)
        thread = null
        threadId = 0
    }

    private companion object {
        const val HOTKEY_ID = 0xC0DE
        const val MOD_CONTROL = 0x0002
        const val VK_Z = 0x5A
        const val WM_HOTKEY = 0x0312
        const val WM_QUIT = 0x0012
    }
}
