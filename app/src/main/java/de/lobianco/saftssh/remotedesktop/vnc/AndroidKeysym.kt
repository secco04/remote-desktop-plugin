package de.lobianco.saftssh.remotedesktop.vnc

import android.view.KeyEvent

/**
 * Maps Android's [KeyEvent] keyCodes to X11 keysyms (RFC 6143's KeyEvent message uses X11
 * keysyms, not platform-native codes). Covers printable ASCII plus the common non-printable keys
 * a phone/hardware keyboard actually sends; anything else maps to 0 (ignored by [VncClient]).
 */
object AndroidKeysym {
    fun map(keyCode: Int, unicodeChar: Int): Int {
        // A resolved Unicode character (from KeyEvent.getUnicodeChar(), already accounting for
        // shift/caps-lock state) maps 1:1 onto its own codepoint for the printable Latin-1 range
        // — X11 keysyms are defined to equal the Unicode codepoint for 0x20..0xFF.
        if (unicodeChar in 0x20..0xFF) return unicodeChar

        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xFF0D
            KeyEvent.KEYCODE_TAB -> 0xFF09
            KeyEvent.KEYCODE_DEL -> 0xFF08 // Backspace
            KeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF // Delete
            KeyEvent.KEYCODE_ESCAPE -> 0xFF1B
            KeyEvent.KEYCODE_SPACE -> 0x0020
            KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
            KeyEvent.KEYCODE_DPAD_UP -> 0xFF52
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
            KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
            KeyEvent.KEYCODE_PAGE_UP -> 0xFF55
            KeyEvent.KEYCODE_PAGE_DOWN -> 0xFF56
            KeyEvent.KEYCODE_MOVE_HOME -> 0xFF50
            KeyEvent.KEYCODE_MOVE_END -> 0xFF57
            KeyEvent.KEYCODE_INSERT -> 0xFF63
            KeyEvent.KEYCODE_SHIFT_LEFT -> 0xFFE1
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xFFE2
            KeyEvent.KEYCODE_CTRL_LEFT -> 0xFFE3
            KeyEvent.KEYCODE_CTRL_RIGHT -> 0xFFE4
            KeyEvent.KEYCODE_ALT_LEFT -> 0xFFE9
            KeyEvent.KEYCODE_ALT_RIGHT -> 0xFFEA
            KeyEvent.KEYCODE_META_LEFT -> 0xFFEB  // Super/Windows key
            KeyEvent.KEYCODE_META_RIGHT -> 0xFFEC
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0xFFBE + (keyCode - KeyEvent.KEYCODE_F1)
            else -> 0
        }
    }
}
