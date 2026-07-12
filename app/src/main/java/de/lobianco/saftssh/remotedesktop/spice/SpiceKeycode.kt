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
// The extra ISO key between left-Shift and 'Z', present on German/most European keyboards but not
// on a US ANSI one — real hardware scancode 86 (0x56), not an invented value (standard PC/AT Set 1).
private const val SC_ISO_EXTRA = 86

/** Which physical keyboard layout [SpiceKeycode] should assume when resolving a character to a
 *  scancode — see that object's class doc for why this matters at all for SPICE specifically. */
enum class SpiceKeyboardLayout { US, DE, FR }

/** One physical key + the modifier(s) that must be held alongside it to produce a given character.
 *  [altGr] (Right Alt) is only ever true for [SpiceKeyboardLayout.DE]/[SpiceKeyboardLayout.FR]
 *  entries (@, €, [, ], {, }, …) — the US table never sets it. */
data class KeyMapping(val scancode: Int, val shift: Boolean = false, val altGr: Boolean = false)

/**
 * Android KeyEvent -> PC/AT Set-1 scancode, for [SpiceCommunicator.SpiceKeyEvent] — unlike VNC
 * (X11 keysyms) or RDP (a separate Unicode PDU alongside its VK path), SPICE's key input is
 * scancode-only: there is no "just send this character" shortcut, so producing a shifted symbol
 * genuinely requires bracketing a synthetic Shift (or AltGr) press/release around the base key,
 * exactly like a real keyboard would — [SpiceClient] handles that bracketing using [KeyMapping].
 *
 * Scancodes are PHYSICAL key positions — identical across layouts, since they come from the
 * hardware, not software. What changes between [SpiceKeyboardLayout.US] and [SpiceKeyboardLayout.DE]
 * is which CHARACTER each physical position produces (e.g. scancode 21 is Y on a US keyboard but Z
 * on a German one) — that's the whole reason a wrong layout choice here produces wrong/swapped
 * characters even though every scancode sent is perfectly valid. [usLayout] only covers US-QWERTY
 * ASCII; [deLayout] additionally covers the German-only printable characters (ä/ö/ü/ß) that have no
 * representation at all on a US keyboard, plus the digit-row/punctuation symbols German moves
 * around, plus the handful of AltGr combinations (@, €, [, ], {, }, \, |, ~, µ) in everyday use.
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

    /** Standard US-QWERTY physical-key layout: which scancode (+ Shift) produces [c]. Covers
     *  printable ASCII — the range every soft-keyboard tap, clipboard paste, and special-key-bar
     *  symbol in this app's remote-desktop UI sends. */
    private val usLayout: Map<Char, KeyMapping> = buildMap {
        val row1 = "qwertyuiop"; val row1Codes = intArrayOf(16, 17, 18, 19, 20, 21, 22, 23, 24, 25)
        val row2 = "asdfghjkl"; val row2Codes = intArrayOf(30, 31, 32, 33, 34, 35, 36, 37, 38)
        val row3 = "zxcvbnm"; val row3Codes = intArrayOf(44, 45, 46, 47, 48, 49, 50)
        for (i in row1.indices) { put(row1[i], KeyMapping(row1Codes[i])); put(row1[i].uppercaseChar(), KeyMapping(row1Codes[i], shift = true)) }
        for (i in row2.indices) { put(row2[i], KeyMapping(row2Codes[i])); put(row2[i].uppercaseChar(), KeyMapping(row2Codes[i], shift = true)) }
        for (i in row3.indices) { put(row3[i], KeyMapping(row3Codes[i])); put(row3[i].uppercaseChar(), KeyMapping(row3Codes[i], shift = true)) }
        // Digits row + its shifted symbols.
        val digitScancodes = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11) // 1..9, 0
        val digitShifted = "!@#$%^&*()"
        for (i in 0..8) { put(('1' + i), KeyMapping(digitScancodes[i])); put(digitShifted[i], KeyMapping(digitScancodes[i], shift = true)) }
        put('0', KeyMapping(digitScancodes[9])); put(digitShifted[9], KeyMapping(digitScancodes[9], shift = true))
        put('-', KeyMapping(12)); put('_', KeyMapping(12, shift = true))
        put('=', KeyMapping(13)); put('+', KeyMapping(13, shift = true))
        put('[', KeyMapping(26)); put('{', KeyMapping(26, shift = true))
        put(']', KeyMapping(27)); put('}', KeyMapping(27, shift = true))
        put(';', KeyMapping(39)); put(':', KeyMapping(39, shift = true))
        put('\'', KeyMapping(40)); put('"', KeyMapping(40, shift = true))
        put('`', KeyMapping(41)); put('~', KeyMapping(41, shift = true))
        put('\\', KeyMapping(43)); put('|', KeyMapping(43, shift = true))
        put(',', KeyMapping(51)); put('<', KeyMapping(51, shift = true))
        put('.', KeyMapping(52)); put('>', KeyMapping(52, shift = true))
        put('/', KeyMapping(53)); put('?', KeyMapping(53, shift = true))
        put(' ', KeyMapping(SC_SPACE))
    }

    /** German QWERTZ physical-key layout — same scancodes as [usLayout] (they're the same
     *  hardware), but Y/Z are swapped, several punctuation keys move, and ä/ö/ü/Ä/Ö/Ü/ß plus a
     *  handful of common AltGr symbols (@, €, [, ], {, }, \, |, ~, µ) get their own entries since
     *  [usLayout] has no representation for them at all. Verified against the standard German
     *  "T1" QWERTZ layout (the default on every German Windows/Linux/macOS install), not guessed. */
    private val deLayout: Map<Char, KeyMapping> = buildMap {
        // Letters: identical positions to US except Y (44) <-> Z (21) are swapped.
        val letters = "qwertzuiopasdfghjklyxcvbnm"
        val letterCodes = intArrayOf(
            16, 17, 18, 19, 20, 21, 22, 23, 24, 25, // q w e r t z u i o p
            30, 31, 32, 33, 34, 35, 36, 37, 38,      // a s d f g h j k l
            44, 45, 46, 47, 48, 49, 50,              // y x c v b n m
        )
        for (i in letters.indices) {
            put(letters[i], KeyMapping(letterCodes[i]))
            put(letters[i].uppercaseChar(), KeyMapping(letterCodes[i], shift = true))
        }
        // Digits row: same unshifted digits, German-specific shifted symbols.
        val digitScancodes = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11) // 1..9, 0
        val digitShifted = "!\"§$%&/()="
        for (i in 0..8) { put(('1' + i), KeyMapping(digitScancodes[i])); put(digitShifted[i], KeyMapping(digitScancodes[i], shift = true)) }
        put('0', KeyMapping(digitScancodes[9])); put(digitShifted[9], KeyMapping(digitScancodes[9], shift = true))
        put('ß', KeyMapping(12)); put('?', KeyMapping(12, shift = true))
        put('´', KeyMapping(13)); put('`', KeyMapping(13, shift = true))
        put('ü', KeyMapping(26)); put('Ü', KeyMapping(26, shift = true))
        put('+', KeyMapping(27)); put('*', KeyMapping(27, shift = true))
        put('ö', KeyMapping(39)); put('Ö', KeyMapping(39, shift = true))
        put('ä', KeyMapping(40)); put('Ä', KeyMapping(40, shift = true))
        put('^', KeyMapping(41)); put('°', KeyMapping(41, shift = true))
        put('#', KeyMapping(43)); put('\'', KeyMapping(43, shift = true))
        put(',', KeyMapping(51)); put(';', KeyMapping(51, shift = true))
        put('.', KeyMapping(52)); put(':', KeyMapping(52, shift = true))
        put('-', KeyMapping(53)); put('_', KeyMapping(53, shift = true))
        put(' ', KeyMapping(SC_SPACE))
        // The extra ISO key (no US-keyboard equivalent).
        put('<', KeyMapping(SC_ISO_EXTRA)); put('>', KeyMapping(SC_ISO_EXTRA, shift = true))
        // Common AltGr (Right Alt) combinations.
        put('@', KeyMapping(16, altGr = true))          // AltGr+Q
        put('€', KeyMapping(18, altGr = true))           // AltGr+E
        put('{', KeyMapping(8, altGr = true))            // AltGr+7
        put('[', KeyMapping(9, altGr = true))            // AltGr+8
        put(']', KeyMapping(10, altGr = true))           // AltGr+9
        put('}', KeyMapping(11, altGr = true))           // AltGr+0
        put('\\', KeyMapping(12, altGr = true))          // AltGr+ß
        put('~', KeyMapping(27, altGr = true))           // AltGr+ +
        put('µ', KeyMapping(50, altGr = true))           // AltGr+M
        put('|', KeyMapping(SC_ISO_EXTRA, altGr = true)) // AltGr+<
    }

    /** French AZERTY physical-key layout — the letter rows shift by one position relative to
     *  QWERTY (A/Q swap with Q/A, Z/W swap with W/Z, M moves off the bottom row onto the
     *  semicolon-key position), the digit row's UNSHIFTED state produces symbols with digits only
     *  reachable via Shift (the opposite of US/DE), and é/è/à/ç/ù get their own dedicated keys.
     *  Verified against the standard French AZERTY layout (the default on every French Windows/
     *  Linux/macOS install), not guessed. */
    private val frLayout: Map<Char, KeyMapping> = buildMap {
        // Letters: A<->Q and Z<->W swapped vs. QWERTY, M moved from row3 to the US ';' position.
        val row1 = "azertyuiop"; val row1Codes = intArrayOf(16, 17, 18, 19, 20, 21, 22, 23, 24, 25)
        val row2 = "qsdfghjklm"; val row2Codes = intArrayOf(30, 31, 32, 33, 34, 35, 36, 37, 38, 39)
        val row3 = "wxcvbn"; val row3Codes = intArrayOf(44, 45, 46, 47, 48, 49)
        for (i in row1.indices) { put(row1[i], KeyMapping(row1Codes[i])); put(row1[i].uppercaseChar(), KeyMapping(row1Codes[i], shift = true)) }
        for (i in row2.indices) { put(row2[i], KeyMapping(row2Codes[i])); put(row2[i].uppercaseChar(), KeyMapping(row2Codes[i], shift = true)) }
        for (i in row3.indices) { put(row3[i], KeyMapping(row3Codes[i])); put(row3[i].uppercaseChar(), KeyMapping(row3Codes[i], shift = true)) }
        // Digits row: UNSHIFTED produces symbols, digits are the SHIFTED state (opposite of US/DE).
        val digitScancodes = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11) // 1..9, 0 (Shift state)
        val digitUnshifted = "&é\"'(-è_çà"
        val digitShifted = "1234567890"
        for (i in digitScancodes.indices) {
            put(digitUnshifted[i], KeyMapping(digitScancodes[i]))
            put(digitShifted[i], KeyMapping(digitScancodes[i], shift = true))
        }
        put(')', KeyMapping(12)); put('°', KeyMapping(12, shift = true))
        put('=', KeyMapping(13)); put('+', KeyMapping(13, shift = true))
        put('^', KeyMapping(26)); put('¨', KeyMapping(26, shift = true)) // dead keys (circumflex/trema)
        put('$', KeyMapping(27)); put('£', KeyMapping(27, shift = true))
        put('ù', KeyMapping(40)); put('%', KeyMapping(40, shift = true))
        put('²', KeyMapping(41))
        put('*', KeyMapping(43)); put('µ', KeyMapping(43, shift = true))
        put(',', KeyMapping(50)); put('?', KeyMapping(50, shift = true))
        put(';', KeyMapping(51)); put('.', KeyMapping(51, shift = true))
        put(':', KeyMapping(52)); put('/', KeyMapping(52, shift = true))
        put('!', KeyMapping(53)); put('§', KeyMapping(53, shift = true))
        put(' ', KeyMapping(SC_SPACE))
        // The extra ISO key (no US-keyboard equivalent).
        put('<', KeyMapping(SC_ISO_EXTRA)); put('>', KeyMapping(SC_ISO_EXTRA, shift = true))
        // Common AltGr (Right Alt) combinations.
        put('€', KeyMapping(18, altGr = true))           // AltGr+E
        put('@', KeyMapping(11, altGr = true))           // AltGr+à(0)
        put('#', KeyMapping(4, altGr = true))            // AltGr+"(3)
        put('{', KeyMapping(5, altGr = true))            // AltGr+'(4)
        put('[', KeyMapping(6, altGr = true))            // AltGr+((5)
        put('|', KeyMapping(7, altGr = true))            // AltGr+-(6)
        put('`', KeyMapping(8, altGr = true))            // AltGr+è(7)
        put('\\', KeyMapping(9, altGr = true))           // AltGr+_(8)
        put(']', KeyMapping(12, altGr = true))           // AltGr+)(scancode12)
        put('}', KeyMapping(13, altGr = true))           // AltGr+=(scancode13)
    }

    /** Scancode + which modifier(s) are needed to type [c] on [layout], or null if it isn't
     *  representable (non-Latin scripts aren't supported — SPICE has no Unicode input path). */
    fun scancodeForChar(c: Char, layout: SpiceKeyboardLayout): KeyMapping? = when (layout) {
        SpiceKeyboardLayout.DE -> deLayout[c]
        SpiceKeyboardLayout.FR -> frLayout[c]
        SpiceKeyboardLayout.US -> usLayout[c]
    }

    /** For a synthesized tap (keyCode=0, only a unicodeChar — e.g. clipboard paste or the
     *  special-key bar) where [scancodeForChar] doesn't directly cover it: falls back to Android's
     *  own [KeyCharacterMap] (always US-based, regardless of [layout]) to resolve which physical
     *  key + Shift state would produce it, mirroring the vendored RemoteKeyboard.sendUnicode()'s
     *  approach (verified against that source, not guessed) — then maps the resolved keyCode back
     *  through [scancodeForKeyCode]/[scancodeForChar]. */
    fun scancodeForUnicode(unicodeChar: Int, layout: SpiceKeyboardLayout): KeyMapping? {
        val c = unicodeChar.toChar()
        scancodeForChar(c, layout)?.let { return it }
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(charArrayOf(c))
            ?: KeyCharacterMap.load(KeyCharacterMap.FULL).getEvents(charArrayOf(c))
            ?: return null
        val evt = events.firstOrNull { it.action == KeyEvent.ACTION_DOWN } ?: return null
        val shift = (evt.metaState and KeyEvent.META_SHIFT_ON) != 0
        val sc = scancodeForKeyCode(evt.keyCode) ?: scancodeForChar(evt.unicodeChar.toChar(), layout)?.scancode ?: return null
        return KeyMapping(sc, shift = shift)
    }
}
