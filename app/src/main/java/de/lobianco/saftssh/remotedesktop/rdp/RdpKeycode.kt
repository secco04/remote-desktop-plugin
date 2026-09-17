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
 *    `GetVirtualScanCodeFromVirtualKeyCode(keycode, 4)` and derives KBD_FLAGS_EXTENDED from the
 *    RESULT. (An earlier version that tried to pass raw PC/AT scancodes here was wrong — the native
 *    side reinterpreted them as VK codes, so e.g. Enter's 0x1C became VK_CONVERT.)
 *
 *    IMPORTANT — extended keys must carry [KBDEXT] themselves. WinPR picks which scancode table to
 *    search from the flag on the VK code it is HANDED, not from what it finds:
 *      `if (vkcode & KBDEXT) search KBD4X else search KBD4T`   (winpr/libwinpr/input/scancode.c)
 *    and those tables are scancode→VK, looked up in reverse. VK_UP, VK_LEFT, VK_HOME, VK_DELETE …
 *    exist ONLY in the extended table — the main table holds VK_NUMPAD8 etc. at those slots
 *    (`#define KBD4_T48 VK_NUMPAD8 /* VK_UP */` vs `#define KBD4_X48 VK_UP`). So handing over a
 *    BARE VK_UP made the reverse lookup search the main table, find nothing, and return scancode
 *    0 — the native side then sent a nil scancode and the key silently did nothing. Only the keys
 *    that live in the main table (ESC, TAB, ENTER, BACKSPACE, F1–F12, the modifiers) ever worked,
 *    which is exactly the reported "die meisten Specialkeybar-Tasten funktionieren nicht, nur ESC
 *    und Tab". With the flag set, WinPR returns `scancode | KBDEXT` and the native code turns that
 *    into KBD_FLAGS_EXTENDED on the wire, which is what it was written to expect all along.
 */
object RdpKeycode {
    sealed class Mapped {
        data class Unicode(val codepoint: Int) : Mapped()
        data class VirtualKey(val vk: Int) : Mapped()
        object None : Mapped()
    }

    /** WinPR's "extended key" flag (KBDEXT in winpr/input.h) — see the class doc for why every
     *  extended-only VK below must be OR'd with this before it reaches sendKeyEvent. */
    private const val KBDEXT = 0x0100

    /** Marks a VK code as living in the EXTENDED scancode table (arrows, the nav cluster, Win). */
    private fun ext(vk: Int): Int = vk or KBDEXT

    // Windows Virtual-Key codes (stable OS constants).
    private const val VK_BACK = 0x08
    private const val VK_TAB = 0x09
    private const val VK_RETURN = 0x0D
    // NOT VK_SHIFT/VK_CONTROL/VK_MENU (the generic 0x10/0x11/0x12 constants) — WinPR's scancode
    // tables (winpr/include/winpr/input.h's KBD4_T*/KBD4_X* macros) only ever store the L/R-specific
    // variants at their scancode slots (e.g. `KBD4_T1D VK_LCONTROL`, never plain VK_CONTROL). Handing
    // the generic constant to GetVirtualScanCodeFromVirtualKeyCode's reverse lookup finds nothing and
    // silently returns scancode 0 — the exact same "no-op" failure mode as the missing-KBDEXT bug
    // above, just for every Ctrl/Alt/Shift press instead of the nav cluster. This is why a latched
    // Ctrl never actually reached the remote: the Ctrl-down event did nothing, so only the following
    // letter's keystroke landed (reported as "Strg+C schreibt nur C").
    private const val VK_LSHIFT = 0xA0
    private const val VK_RSHIFT = 0xA1
    private const val VK_LCONTROL = 0xA2
    private const val VK_RCONTROL = 0xA3
    private const val VK_LMENU = 0xA4 // Left Alt
    private const val VK_RMENU = 0xA5 // Right Alt (AltGr on most non-US layouts)
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
        // ext(...) = extended-only keys — see the class doc; without KBDEXT these resolve to
        // scancode 0 and do nothing at all.
        KeyEvent.KEYCODE_FORWARD_DEL -> ext(VK_DELETE)
        KeyEvent.KEYCODE_TAB -> VK_TAB
        KeyEvent.KEYCODE_ESCAPE -> VK_ESCAPE
        KeyEvent.KEYCODE_DPAD_LEFT -> ext(VK_LEFT)
        KeyEvent.KEYCODE_DPAD_UP -> ext(VK_UP)
        KeyEvent.KEYCODE_DPAD_RIGHT -> ext(VK_RIGHT)
        KeyEvent.KEYCODE_DPAD_DOWN -> ext(VK_DOWN)
        KeyEvent.KEYCODE_PAGE_UP -> ext(VK_PRIOR)
        KeyEvent.KEYCODE_PAGE_DOWN -> ext(VK_NEXT)
        KeyEvent.KEYCODE_MOVE_HOME -> ext(VK_HOME)
        KeyEvent.KEYCODE_MOVE_END -> ext(VK_END)
        KeyEvent.KEYCODE_INSERT -> ext(VK_INSERT)
        KeyEvent.KEYCODE_CAPS_LOCK -> VK_CAPITAL
        // Right Ctrl/Alt live in the EXTENDED table (KBD4_X1D/KBD4_X38) — need ext() like the nav
        // keys above. Right Shift is, oddly, in the MAIN table (KBD4_T36) — no ext() there.
        KeyEvent.KEYCODE_CTRL_LEFT -> VK_LCONTROL
        KeyEvent.KEYCODE_CTRL_RIGHT -> ext(VK_RCONTROL)
        KeyEvent.KEYCODE_ALT_LEFT -> VK_LMENU
        KeyEvent.KEYCODE_ALT_RIGHT -> ext(VK_RMENU)
        KeyEvent.KEYCODE_SHIFT_LEFT -> VK_LSHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> VK_RSHIFT
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> ext(VK_LWIN)
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
