package de.lobianco.saftssh.remotedesktop.rdp

import android.view.KeyEvent

/**
 * Maps Android [KeyEvent]s to what FreeRDP's [com.freerdp.freerdpcore.services.LibFreeRDP] needs.
 *
 * Two paths:
 *  - Printable characters → `sendUnicodeKeyEvent` (RDP's TS_UNICODE_KEYBOARD_EVENT PDU takes a raw
 *    UTF-16 code unit; independent of the negotiated keyboard layout).
 *  - Everything else → `sendKeyEvent`, which — despite the name — expects a **Windows Virtual-Key
 *    code** (VK_*), NOT a scancode: the native `jni_freerdp_send_key_event` calls
 *    `GetVirtualScanCodeFromVirtualKeyCode(keycode, 4)` and sets the KBD_FLAGS_EXTENDED flag from
 *    the result itself (verified in FreeRDP 2.11.7's android_freerdp.c, not guessed). So arrows,
 *    Home/End, etc. — which need an "extended" (0xE0-prefixed) scancode — just work by passing
 *    their plain VK code; FreeRDP does the scancode+extended-flag translation. (An earlier version
 *    that tried to pass raw PC/AT scancodes here was wrong — the native side reinterpreted them as
 *    VK codes, so e.g. Enter's 0x1C became VK_CONVERT.)
 */
object RdpKeycode {
    sealed class Mapped {
        data class Unicode(val codepoint: Int) : Mapped()
        data class VirtualKey(val vk: Int) : Mapped()
        object None : Mapped()
    }

    // Windows Virtual-Key codes (stable OS constants).
    private const val VK_BACK = 0x08
    private const val VK_TAB = 0x09
    private const val VK_RETURN = 0x0D
    private const val VK_SHIFT = 0x10
    private const val VK_CONTROL = 0x11
    private const val VK_MENU = 0x12 // Alt
    private const val VK_ESCAPE = 0x1B
    private const val VK_PRIOR = 0x21 // Page Up
    private const val VK_NEXT = 0x22 // Page Down
    private const val VK_END = 0x23
    private const val VK_HOME = 0x24
    private const val VK_LEFT = 0x25
    private const val VK_UP = 0x26
    private const val VK_RIGHT = 0x27
    private const val VK_DOWN = 0x28
    private const val VK_INSERT = 0x2D
    private const val VK_DELETE = 0x2E
    private const val VK_LWIN = 0x5B
    private const val VK_F1 = 0x70
    private const val VK_CAPITAL = 0x14 // Caps Lock

    /** The VK code for a non-printable / modifier key, or null if [keyCode] isn't one we map here
     *  (printables are handled by the caller via Unicode). */
    fun vkForKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> VK_RETURN
        KeyEvent.KEYCODE_DEL -> VK_BACK
        KeyEvent.KEYCODE_FORWARD_DEL -> VK_DELETE
        KeyEvent.KEYCODE_TAB -> VK_TAB
        KeyEvent.KEYCODE_ESCAPE -> VK_ESCAPE
        KeyEvent.KEYCODE_DPAD_LEFT -> VK_LEFT
        KeyEvent.KEYCODE_DPAD_UP -> VK_UP
        KeyEvent.KEYCODE_DPAD_RIGHT -> VK_RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN -> VK_DOWN
        KeyEvent.KEYCODE_PAGE_UP -> VK_PRIOR
        KeyEvent.KEYCODE_PAGE_DOWN -> VK_NEXT
        KeyEvent.KEYCODE_MOVE_HOME -> VK_HOME
        KeyEvent.KEYCODE_MOVE_END -> VK_END
        KeyEvent.KEYCODE_INSERT -> VK_INSERT
        KeyEvent.KEYCODE_CAPS_LOCK -> VK_CAPITAL
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> VK_CONTROL
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> VK_MENU
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> VK_SHIFT
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> VK_LWIN
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> VK_F1 + (keyCode - KeyEvent.KEYCODE_F1)
        else -> null
    }

    /** VK code for a printable letter/digit, used when a Ctrl/Alt/Win modifier is held so the key
     *  goes through the scancode path (which honours modifiers) instead of the Unicode path (which
     *  Windows does not combine with a separately-held modifier). 0 if not a mappable char. */
    fun vkForChar(codepoint: Int): Int {
        val c = codepoint.toChar()
        return when (c) {
            in 'a'..'z' -> c.uppercaseChar().code // VK_A..VK_Z == 'A'..'Z'
            in 'A'..'Z' -> c.code
            in '0'..'9' -> c.code // VK_0..VK_9 == '0'..'9'
            else -> 0
        }
    }

    fun map(keyCode: Int, unicodeChar: Int): Mapped {
        vkForKeyCode(keyCode)?.let { return Mapped.VirtualKey(it) }
        if (unicodeChar in 0x20..0xFFFF) return Mapped.Unicode(unicodeChar)
        return Mapped.None
    }
}
