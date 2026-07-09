package de.lobianco.saftssh.remotedesktop.rdp

import android.view.KeyEvent

/**
 * Maps Android [KeyEvent]s to what FreeRDP's [com.freerdp.freerdpcore.services.LibFreeRDP] needs.
 *
 * Printable characters go through `sendUnicodeKeyEvent` (RDP's TS_UNICODE_KEYBOARD_EVENT PDU
 * takes a raw UTF-16 code unit directly — high confidence this is correct, it's the RDP protocol
 * spec, not an implementation detail).
 *
 * Non-printable keys go through `sendKeyEvent` with a PC/AT Set-1 scancode — but ONLY the small
 * set of keys that use a single-byte scancode (Enter/Backspace/Tab/Escape/Space). Extended keys
 * (arrows, Delete, Home/End, ...) need a 0xE0 prefix byte, and this project has no confirmed
 * source for how LibFreeRDP's native side expects that prefix to be encoded in a single int
 * parameter — rather than guess, those keys are left unmapped (Mapped.None) until that can be
 * verified against a real connection or FreeRDP's SessionView.java source.
 */
object RdpKeycode {
    sealed class Mapped {
        data class Unicode(val codepoint: Int) : Mapped()
        data class Scancode(val code: Int) : Mapped()
        object None : Mapped()
    }

    // PC/AT Set-1 scancodes — single-byte, non-extended keys only (see class doc).
    private const val SC_ENTER = 0x1C
    private const val SC_BACKSPACE = 0x0E
    private const val SC_TAB = 0x0F
    private const val SC_ESCAPE = 0x01
    private const val SC_SPACE = 0x39

    fun map(keyCode: Int, unicodeChar: Int): Mapped {
        if (unicodeChar in 0x20..0xFFFF) return Mapped.Unicode(unicodeChar)

        val scancode = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> SC_ENTER
            KeyEvent.KEYCODE_DEL -> SC_BACKSPACE
            KeyEvent.KEYCODE_TAB -> SC_TAB
            KeyEvent.KEYCODE_ESCAPE -> SC_ESCAPE
            KeyEvent.KEYCODE_SPACE -> SC_SPACE
            else -> null
        }
        return if (scancode != null) Mapped.Scancode(scancode) else Mapped.None
    }
}
