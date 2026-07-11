package de.lobianco.saftssh.remotedesktop.spice

import android.view.KeyCharacterMap
import android.view.KeyEvent

// PC/AT Scan Code Set 1 — the "hardware_keycode" SpiceCommunicator.SpiceKeyEvent expects.
// E0-extended keys are encoded as (baseCode | EXTENDED), the same convention the vendored
// SpiceCommunicator.java's own inline modifier constants use (LCONTROL=29, but RCONTROL=285 =
// 0x1D|0x100; LALT=56, RALT=312=0x38|0x100; LWIN=347=0x5B|0x100, RWIN=348=0x5C|0x100 — cross-checked
// against those literal values, not assumed) — confirmed this is standard Set 1, not guessed.
private const val EXTENDED = 0x100

private const val SC_ESC = 1
private const val SC_BACKSPACE = 14
private const val SC_TAB = 15
private const val SC_ENTER = 28
private const val SC_LCTRL = 29
private const val SC_LSHIFT = 42
private const val SC_RSHIFT = 54
private const val SC_LALT = 56
private const val SC_SPACE = 57
private const val SC_CAPSLOCK = 58
private const val SC_F1 = 59 // F1..F10 = 59..68
private const val SC_F11 = 87
private const val SC_F12 = 88
private const val SC_RCTRL = 97 or EXTENDED
private const val SC_RALT = 100 or EXTENDED
private const val SC_HOME = 0x47 or EXTENDED
private const val SC_UP = 0x48 or EXTENDED
private const val SC_PAGEUP = 0x49 or EXTENDED
private const val SC_LEFT = 0x4B or EXTENDED
private const val SC_RIGHT = 0x4D or EXTENDED
private const val SC_END = 0x4F or EXTENDED
private const val SC_DOWN = 0x50 or EXTENDED
private const val SC_PAGEDOWN = 0x51 or EXTENDED
private const val SC_INSERT = 0x52 or EXTENDED
private const val SC_DELETE = 0x53 or EXTENDED
private const val SC_LWIN = 0x5B or EXTENDED
private const val SC_RWIN = 0x5C or EXTENDED

/**
 * Android KeyEvent -> PC/AT Set-1 scancode, for [SpiceCommunicator.SpiceKeyEvent] — unlike VNC
 * (X11 keysyms) or RDP (a separate Unicode PDU alongside its VK path), SPICE's key input is
 * scancode-only: there is no "just send this character" shortcut, so producing a shifted symbol
 * genuinely requires bracketing a synthetic Shift press/release around the base key, exactly like
 * a real keyboard would — [SpiceClient] handles that bracketing using [needsShift].
 */
object SpiceKeycode {
    /** Direct Android-keyCode -> scancode for non-printable/modifier/function keys. */
    fun scancodeForKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_ESCAPE -> SC_ESC
        KeyEvent.KEYCODE_DEL -> SC_BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> SC_DELETE
        KeyEvent.KEYCODE_TAB -> SC_TAB
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> SC_ENTER
        KeyEvent.KEYCODE_SPACE -> SC_SPACE
        KeyEvent.KEYCODE_CAPS_LOCK -> SC_CAPSLOCK
        KeyEvent.KEYCODE_CTRL_LEFT -> SC_LCTRL
        KeyEvent.KEYCODE_CTRL_RIGHT -> SC_RCTRL
        KeyEvent.KEYCODE_ALT_LEFT -> SC_LALT
        KeyEvent.KEYCODE_ALT_RIGHT -> SC_RALT
        KeyEvent.KEYCODE_SHIFT_LEFT -> SC_LSHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> SC_RSHIFT
        KeyEvent.KEYCODE_META_LEFT -> SC_LWIN
        KeyEvent.KEYCODE_META_RIGHT -> SC_RWIN
        KeyEvent.KEYCODE_DPAD_LEFT -> SC_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> SC_RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> SC_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> SC_DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> SC_HOME
        KeyEvent.KEYCODE_MOVE_END -> SC_END
        KeyEvent.KEYCODE_PAGE_UP -> SC_PAGEUP
        KeyEvent.KEYCODE_PAGE_DOWN -> SC_PAGEDOWN
        KeyEvent.KEYCODE_INSERT -> SC_INSERT
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F10 -> SC_F1 + (keyCode - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_F11 -> SC_F11
        KeyEvent.KEYCODE_F12 -> SC_F12
        else -> null
    }

    /** Standard US-QWERTY physical-key layout: which scancode produces [c], and whether Shift
     *  must be held to get it. Covers printable ASCII — the range every soft-keyboard tap,
     *  clipboard paste, and special-key-bar symbol in this app's remote-desktop UI sends. */
    private val usLayout: Map<Char, Pair<Int, Boolean>> = buildMap {
        val row1 = "qwertyuiop"; val row1Codes = intArrayOf(16, 17, 18, 19, 20, 21, 22, 23, 24, 25)
        val row2 = "asdfghjkl"; val row2Codes = intArrayOf(30, 31, 32, 33, 34, 35, 36, 37, 38)
        val row3 = "zxcvbnm"; val row3Codes = intArrayOf(44, 45, 46, 47, 48, 49, 50)
        for (i in row1.indices) { put(row1[i], row1Codes[i] to false); put(row1[i].uppercaseChar(), row1Codes[i] to true) }
        for (i in row2.indices) { put(row2[i], row2Codes[i] to false); put(row2[i].uppercaseChar(), row2Codes[i] to true) }
        for (i in row3.indices) { put(row3[i], row3Codes[i] to false); put(row3[i].uppercaseChar(), row3Codes[i] to true) }
        // Digits row + its shifted symbols.
        val digitScancodes = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11) // 1..9, 0
        val digitShifted = "!@#$%^&*()"
        for (i in 0..8) { put(('1' + i), digitScancodes[i] to false); put(digitShifted[i], digitScancodes[i] to true) }
        put('0', digitScancodes[9] to false); put(digitShifted[9], digitScancodes[9] to true)
        put('-', 12 to false); put('_', 12 to true)
        put('=', 13 to false); put('+', 13 to true)
        put('[', 26 to false); put('{', 26 to true)
        put(']', 27 to false); put('}', 27 to true)
        put(';', 39 to false); put(':', 39 to true)
        put('\'', 40 to false); put('"', 40 to true)
        put('`', 41 to false); put('~', 41 to true)
        put('\\', 43 to false); put('|', 43 to true)
        put(',', 51 to false); put('<', 51 to true)
        put('.', 52 to false); put('>', 52 to true)
        put('/', 53 to false); put('?', 53 to true)
        put(' ', SC_SPACE to false)
    }

    /** Scancode + whether Shift is needed to type [c] on a US-QWERTY layout, or null if it isn't
     *  representable (non-Latin scripts aren't supported — SPICE has no Unicode input path). */
    fun scancodeForChar(c: Char): Pair<Int, Boolean>? = usLayout[c]

    /** For a synthesized tap (keyCode=0, only a unicodeChar — e.g. clipboard paste or the
     *  special-key bar) where [scancodeForChar] doesn't directly cover it: falls back to Android's
     *  own [KeyCharacterMap] to resolve which physical key + Shift state would produce it, mirroring
     *  the vendored RemoteKeyboard.sendUnicode()'s approach (verified against that source, not
     *  guessed) — then maps the resolved keyCode back through [scancodeForKeyCode]/[scancodeForChar]. */
    fun scancodeForUnicode(unicodeChar: Int): Pair<Int, Boolean>? {
        val c = unicodeChar.toChar()
        scancodeForChar(c)?.let { return it }
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(charArrayOf(c))
            ?: KeyCharacterMap.load(KeyCharacterMap.FULL).getEvents(charArrayOf(c))
            ?: return null
        val evt = events.firstOrNull { it.action == KeyEvent.ACTION_DOWN } ?: return null
        val shift = (evt.metaState and KeyEvent.META_SHIFT_ON) != 0
        val sc = scancodeForKeyCode(evt.keyCode) ?: scancodeForChar(evt.unicodeChar.toChar())?.first ?: return null
        return sc to shift
    }
}
