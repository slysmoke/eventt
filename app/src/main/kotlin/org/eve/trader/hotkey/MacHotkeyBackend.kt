package org.eve.trader.hotkey

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference

/**
 * Global Ctrl+Z hotkey on macOS via the Carbon Event Manager's RegisterEventHotKey - one of the
 * few Carbon APIs still functional on modern/Apple Silicon macOS, and (unlike a CGEventTap) it
 * does not require the Accessibility permission prompt.
 *
 * NOTE: cannot be built/run on this (Linux) dev machine - this is the highest-risk, least
 * verifiable backend of the three. Struct layouts/typedef sizes (ByteCount, OSType, OptionBits)
 * are based on documented Carbon headers but need manual verification on a real Mac before
 * shipping.
 */
class MacHotkeyBackend : HotkeyBackend {

    private var hotKeyRef: Pointer? = null
    private var handlerRef: Pointer? = null
    private var callback: HotKeyEventHandler? = null

    override fun start(onTrigger: () -> Unit): Boolean {
        val lib = try {
            CarbonLib.INSTANCE
        } catch (e: Throwable) {
            println("[Hotkey][macOS] Carbon framework not available: ${e.message}")
            return false
        }

        val eventTarget = lib.GetApplicationEventTarget()

        val handler = object : HotKeyEventHandler {
            override fun invoke(inCallRef: Pointer?, inEvent: Pointer?, inUserData: Pointer?): Int {
                if (inEvent != null) {
                    val out = Memory(8)
                    val status = lib.GetEventParameter(
                        inEvent, EVENT_PARAM_DIRECT_OBJECT, TYPE_EVENT_HOTKEY_ID,
                        null, NativeLong(8), null, out,
                    )
                    if (status == 0 && EventHotKeyID(out).id == HOTKEY_ID) {
                        onTrigger()
                    }
                }
                return 0 // noErr
            }
        }
        callback = handler

        val eventType = EventTypeSpec().apply {
            eventClass = EVENT_CLASS_KEYBOARD
            eventKind = EVENT_HOTKEY_PRESSED
            write()
        }
        val handlerOutRef = PointerByReference()
        val installStatus = lib.InstallEventHandler(eventTarget, handler, 1, eventType.pointer, null, handlerOutRef)
        if (installStatus != 0) {
            println("[Hotkey][macOS] InstallEventHandler failed: status=$installStatus")
            return false
        }
        handlerRef = handlerOutRef.value

        val hotKeyId = EventHotKeyID().apply {
            signature = HOTKEY_SIGNATURE
            id = HOTKEY_ID
        }
        val hotKeyOutRef = PointerByReference()
        val registerStatus = lib.RegisterEventHotKey(KEY_CODE_Z, MOD_CONTROL, hotKeyId, eventTarget, 0, hotKeyOutRef)
        if (registerStatus != 0) {
            println("[Hotkey][macOS] RegisterEventHotKey failed: status=$registerStatus")
            handlerRef?.let { lib.RemoveEventHandler(it) }
            handlerRef = null
            return false
        }
        hotKeyRef = hotKeyOutRef.value

        println("[Hotkey][macOS] Global hotkey active: Ctrl+Z")
        return true
    }

    override fun stop() {
        hotKeyRef?.let { runCatching { CarbonLib.INSTANCE.UnregisterEventHotKey(it) } }
        handlerRef?.let { runCatching { CarbonLib.INSTANCE.RemoveEventHandler(it) } }
        hotKeyRef = null
        handlerRef = null
        callback = null
    }

    private companion object {
        const val HOTKEY_SIGNATURE = 0x45564554 // 'EVET' - app-specific signature
        const val HOTKEY_ID = 1
        const val EVENT_CLASS_KEYBOARD = 0x6B657962 // 'keyb'
        const val EVENT_HOTKEY_PRESSED = 5
        const val EVENT_PARAM_DIRECT_OBJECT = 0x2D2D2D2D // '----'
        const val TYPE_EVENT_HOTKEY_ID = 0x686B6964 // 'hkid'
        const val MOD_CONTROL = 4096 // controlKey
        const val KEY_CODE_Z = 0x06 // kVK_ANSI_Z
    }
}

// Public for the same reason as the Structure subclasses below - JNA needs cross-package
// reflective access (see X11HotkeyBackend.XKeyEvent for the confirmed failure mode).
interface HotKeyEventHandler : Callback {
    fun invoke(inCallRef: Pointer?, inEvent: Pointer?, inUserData: Pointer?): Int
}

private interface CarbonLib : Library {
    fun GetApplicationEventTarget(): Pointer
    fun InstallEventHandler(inTarget: Pointer, inHandler: HotKeyEventHandler, inNumTypes: Int, inList: Pointer, inUserData: Pointer?, outRef: PointerByReference?): Int
    fun RemoveEventHandler(inHandlerRef: Pointer): Int
    fun RegisterEventHotKey(inHotKeyCode: Int, inHotKeyModifiers: Int, inHotKeyID: EventHotKeyID, inTarget: Pointer, inOptions: Int, outRef: PointerByReference): Int
    fun UnregisterEventHotKey(inHotKey: Pointer): Int
    fun GetEventParameter(inEvent: Pointer, inName: Int, inDesiredType: Int, outActualType: Pointer?, inBufferSize: NativeLong, outActualSize: Pointer?, outData: Pointer): Int

    companion object {
        val INSTANCE: CarbonLib = Native.load("/System/Library/Frameworks/Carbon.framework/Carbon", CarbonLib::class.java)
    }
}

@Structure.FieldOrder("signature", "id")
class EventHotKeyID() : Structure() {
    @JvmField var signature: Int = 0
    @JvmField var id: Int = 0

    constructor(p: Pointer) : this() {
        useMemory(p)
        read()
    }
}

@Structure.FieldOrder("eventClass", "eventKind")
class EventTypeSpec : Structure() {
    @JvmField var eventClass: Int = 0
    @JvmField var eventKind: Int = 0
}
