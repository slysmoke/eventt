package org.eventt.hotkey

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.interfaces.Introspectable
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.UInt64
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val BUS_NAME = "org.freedesktop.portal.Desktop"
private const val OBJECT_PATH = "/org/freedesktop/portal/desktop"
private const val SHORTCUT_ID = "cycle-queue"

// Public (not Kotlin `private`/file-private): both this interface (proxied via java.lang.reflect.Proxy,
// which tolerates non-public interfaces fine) and, more importantly, its nested Activated class
// (instantiated reflectively by dbus-java from a different package) need to be accessible - see
// X11HotkeyBackend.XKeyEvent for the confirmed cross-package reflection failure mode.
@DBusInterfaceName("org.freedesktop.portal.GlobalShortcuts")
interface GlobalShortcuts : DBusInterface {
    fun CreateSession(options: Map<String, Variant<*>>): DBusPath

    fun BindShortcuts(
        sessionHandle: DBusPath,
        shortcuts: List<ShortcutDescription>,
        parentWindow: String,
        options: Map<String, Variant<*>>,
    ): DBusPath

    // Body args are matched to these constructor params positionally by dbus-java's signal
    // deserializer (same convention as org.freedesktop.dbus.interfaces.Properties$PropertiesChanged) -
    // no @Position annotations needed here, only on Struct subclasses like ShortcutDescription below.
    class Activated(
        path: String,
        val sessionHandle: DBusPath,
        val shortcutId: String,
        val timestamp: UInt64,
        val options: Map<String, Variant<*>>,
    ) : DBusSignal(path, sessionHandle, shortcutId, timestamp, options)
}

// Wire shape for one entry of BindShortcuts' `a(sa{sv})` shortcuts array. Public (not Kotlin
// `private`/file-private, which compiles to package-private): dbus-java's Struct marshaling needs
// cross-package reflective field access (see X11HotkeyBackend.XKeyEvent for the confirmed
// JNA-side failure mode of the same underlying issue).
class ShortcutDescription(
    @field:Position(0) @JvmField val shortcutId: String,
    @field:Position(1) @JvmField val description: Map<String, Variant<*>>,
) : Struct()

@DBusInterfaceName("org.freedesktop.portal.Request")
interface PortalRequest : DBusInterface {
    class Response(
        path: String,
        val response: UInt32,
        val results: Map<String, Variant<*>>,
    ) : DBusSignal(path, response, results)
}

/**
 * Global Ctrl+Z hotkey via the Wayland xdg-desktop-portal GlobalShortcuts interface (D-Bus).
 *
 * Unlike the other backends, the *compositor* - not this app - decides which physical key combo
 * triggers the shortcut: it shows a system dialog letting the user assign one to "Cycle EVE
 * Trader order/trade queue" the first time BindShortcuts runs. This first version doesn't persist
 * the session (`restore_token`), so that assignment may need to be repeated on each app launch on
 * portal-supporting compositors - a known rough edge, not a bug.
 *
 * Only takes effect on compositors whose xdg-desktop-portal implementation ships GlobalShortcuts
 * (GNOME 45+, KDE Plasma 6+ as of writing) - `start()` introspects for the interface first and
 * returns false immediately if it's absent, so callers fall back to [X11HotkeyBackend] (which
 * works via XWayland on any Wayland session).
 */
class PortalHotkeyBackend : HotkeyBackend {
    private var connection: DBusConnection? = null
    private var sigHandlerCloser: AutoCloseable? = null

    override fun start(onTrigger: () -> Unit): Boolean {
        val conn =
            try {
                DBusConnectionBuilder.forSessionBus().build()
            } catch (e: Throwable) {
                println("[Hotkey][Portal] Could not connect to session bus: ${e.message}")
                return false
            }
        connection = conn

        return try {
            val introspectable = conn.getRemoteObject(BUS_NAME, OBJECT_PATH, Introspectable::class.java)
            if (!introspectable.Introspect().contains("org.freedesktop.portal.GlobalShortcuts")) {
                println("[Hotkey][Portal] Compositor's xdg-desktop-portal doesn't implement GlobalShortcuts")
                disconnect()
                return false
            }

            val globalShortcuts = conn.getRemoteObject(BUS_NAME, OBJECT_PATH, GlobalShortcuts::class.java)

            val sessionHandle =
                callAndAwaitResponse(conn, "session") { token ->
                    globalShortcuts.CreateSession(
                        mapOf(
                            "handle_token" to Variant(token),
                            "session_handle_token" to Variant(token),
                        ),
                    )
                }?.get("session_handle")?.value as? DBusPath

            if (sessionHandle == null) {
                println("[Hotkey][Portal] CreateSession did not return a session handle")
                disconnect()
                return false
            }

            val shortcut =
                ShortcutDescription(SHORTCUT_ID, mapOf("description" to Variant("Cycle EVE Night Trade Tools order/trade queue")))
            val bound =
                callAndAwaitResponse(conn, "bind") { token ->
                    globalShortcuts.BindShortcuts(sessionHandle, listOf(shortcut), "", mapOf("handle_token" to Variant(token)))
                }
            if (bound == null) {
                println("[Hotkey][Portal] BindShortcuts did not complete")
                disconnect()
                return false
            }

            val handler =
                DBusSigHandler<GlobalShortcuts.Activated> { signal ->
                    if (signal.sessionHandle == sessionHandle && signal.shortcutId == SHORTCUT_ID) {
                        onTrigger()
                    }
                }
            sigHandlerCloser = conn.addSigHandler(GlobalShortcuts.Activated::class.java, BUS_NAME, handler)

            println("[Hotkey][Portal] Global shortcut session bound - assign a key combo via the system dialog if prompted")
            true
        } catch (e: Throwable) {
            println("[Hotkey][Portal] Failed to set up GlobalShortcuts portal: ${e.message}")
            disconnect()
            false
        }
    }

    override fun stop() {
        runCatching { sigHandlerCloser?.close() }
        sigHandlerCloser = null
        disconnect()
    }

    private fun disconnect() {
        connection?.let { runCatching { it.close() } }
        connection = null
    }

    /** Invokes a portal method, then blocks briefly for its matching Request.Response signal. */
    private fun callAndAwaitResponse(
        conn: DBusConnection,
        label: String,
        invoke: (token: String) -> DBusPath,
    ): Map<String, Variant<*>>? {
        val token = "eventt_${label}_${System.nanoTime()}"
        val latch = CountDownLatch(1)
        var results: Map<String, Variant<*>>? = null

        val requestPath = invoke(token)
        val requestObj = conn.getRemoteObject(BUS_NAME, requestPath.path, PortalRequest::class.java)
        val closer =
            conn.addSigHandler(
                PortalRequest.Response::class.java,
                BUS_NAME,
                requestObj,
                DBusSigHandler<PortalRequest.Response> { signal ->
                    if (signal.response.toInt() == 0) results = signal.results
                    latch.countDown()
                },
            )
        try {
            latch.await(10, TimeUnit.SECONDS)
        } finally {
            runCatching { closer.close() }
        }
        return results
    }
}
