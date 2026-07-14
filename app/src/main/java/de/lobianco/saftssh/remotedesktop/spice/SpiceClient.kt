package de.lobianco.saftssh.remotedesktop.spice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import com.undatech.opaque.SpiceCommunicator
import de.lobianco.saftssh.remotedesktop.SyntheticCursor
import java.io.File
import java.util.UUID

private const val TAG = "SpiceClient"
// See blitToSurface's doc — how long to back off between retry attempts once lockCanvas() starts
// failing (e.g. display asleep), instead of retrying on every single incoming server frame.
private const val BLIT_RETRY_INTERVAL_MS = 2000L

// SPICE mouse-button numbering — verified against the vendored reference implementation
// (bVNC/src/.../input/RemoteSpicePointer.java), not guessed: 1=left, 2=middle, 3=right,
// 4/5=scroll-up/down sent as momentary clicks (there is no separate wheel message). ORed with
// POINTER_DOWN_MASK for a press; a release repeats the same button number without that bit.
private const val POINTER_DOWN_MASK = 0x8000
private const val BTN_MOVE = 0
private const val BTN_LEFT = 1
private const val BTN_MIDDLE = 2
private const val BTN_RIGHT = 3
private const val BTN_SCROLL_UP = 4
private const val BTN_SCROLL_DOWN = 5

/** Maps a VNC-convention buttonMask (bit0=left, bit1=middle, bit2=right — see
 *  IRemoteDesktopSession.sendPointerEvent's doc) to the one SPICE button number it represents. */
private fun spiceButton(vncButtonMask: Int): Int = when {
    vncButtonMask and 0x01 != 0 -> BTN_LEFT
    vncButtonMask and 0x04 != 0 -> BTN_RIGHT
    vncButtonMask and 0x02 != 0 -> BTN_MIDDLE
    else -> BTN_MOVE
}

/**
 * Thin wrapper around [SpiceCommunicator] (this plugin's own minimal native bridge — see that
 * class's doc for why it's a from-scratch rewrite, not a vendored copy). Mirrors
 * [de.lobianco.saftssh.remotedesktop.rdp.RdpClient]'s shape: connect, render onto a Surface,
 * forward input — so RemoteDesktopSessionService can treat all three protocols uniformly.
 *
 * The native libraries backing this (libspice.so, libgstreamer_android.so) are prebuilt binaries
 * extracted from iiordanov/remote-desktop-clients' official "freeaSPICE" GitHub Release APK
 * (v6.4.5, same release the RDP libraries came from) — see this repo's LICENSE for full
 * attribution and why (building spice-gtk + GStreamer from source needs the same infeasible
 * multi-hour Cerbero cross-compile toolchain RDP's doc already explains).
 *
 * No TLS support yet: [SpiceCommunicator.SpiceClientConnect] is always called with an empty TLS
 * port and null certificate params, i.e. a plain SPICE connection — the common case for
 * self-hosted setups (e.g. Proxmox VE's spiceproxy, which is usually unencrypted on the LAN/VPN).
 *
 * Relative mouse mode (SPICE_MOUSE_MODE_SERVER, used by some fullscreen/game guests that grab the
 * pointer) isn't supported — this client always sends absolute coordinates, same as VNC/RDP's
 * touch model here; the server will simply reject input while in that mode (see [onMouseMode]).
 *
 * Only one SPICE session can be active per plugin process at a time — see [SpiceCommunicator]'s
 * class doc for why (the native library's own global state, not a limitation introduced here).
 * [start] throws if another one is already running.
 *
 * Two connect modes, chosen automatically in [start] by whether [tlsPort] is set:
 *  - Plain (the default, [tlsPort] == 0): [SpiceCommunicator.SpiceClientConnect] straight to
 *    [host]:[port], no TLS, no proxy — for a directly-reachable SPICE server (e.g. plain
 *    `qemu -spice port=X`).
 *  - Proxmox VE ([tlsPort] > 0, plus [proxy]/[caCert]/[hostSubject] from a fresh spiceproxy
 *    ticket — see [de.lobianco.saftssh.data.remotedesktop.proxmox.ProxmoxApiClient] in the main
 *    app): writes a virt-viewer ".vv" file and connects via
 *    [SpiceCommunicator.StartSessionFromVvFile] instead — the only entry point that honours a
 *    proxy (see that method's doc for why [SpiceClientConnect] can't be used here at all).
 */
class SpiceClient(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val password: String?,
    private val onProgress: (String) -> Unit,
    private val onConnected: (width: Int, height: Int) -> Unit,
    private val onDisconnected: (reason: String) -> Unit,
    /** > 0 selects the Proxmox VE (.vv-file) connect path — see class doc. */
    private val tlsPort: Int = 0,
    /** Proxmox spiceproxy's CONNECT-tunnel address, e.g. "http://pve.example.com:3128". */
    private val proxy: String? = null,
    /** PEM certificate (real newlines OK — re-encoded to the .vv format's escaped form here). */
    private val caCert: String? = null,
    private val hostSubject: String? = null,
    /** Which physical keyboard layout to assume when resolving typed characters to scancodes —
     *  see [SpiceKeycode]'s class doc for why this is needed at all (SPICE has no Unicode input
     *  path, only scancodes, which are layout-dependent). Defaults to US, unchanged behavior.
     *  Mutable at runtime via [setKeyboardLayout] — it only affects the NEXT keystroke's mapping,
     *  so it can change live with no reconnect. */
    initialKeyboardLayout: SpiceKeyboardLayout = SpiceKeyboardLayout.US,
) : SpiceCommunicator.Listener {

    // Read on whatever Binder thread delivers a key event, written on whatever Binder thread
    // delivers a setKeyboardLayout() — @Volatile so a live switch is seen immediately.
    @Volatile private var keyboardLayout: SpiceKeyboardLayout = initialKeyboardLayout

    /** Live keyboard-layout switch (see the AIDL setKeyboardLayout doc) — no reconnect needed. */
    fun setKeyboardLayout(layout: SpiceKeyboardLayout) { keyboardLayout = layout }

    private val native = SpiceCommunicator()
    @Volatile private var bitmap: Bitmap? = null
    @Volatile private var connected = false
    private var thread: Thread? = null
    @Volatile private var surfaceRefreshThread: Thread? = null

    @Volatile var targetSurface: Surface? = null

    // Letterbox * zoom render geometry + cursor tracking — identical reasoning to
    // VncClient/RdpClient's own fields of the same name.
    @Volatile private var renderScale = 1f
    @Volatile private var renderOffsetX = 0f
    @Volatile private var renderOffsetY = 0f
    @Volatile private var zoomScale = 1f
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f
    @Volatile private var lastButton = BTN_MOVE
    @Volatile private var pointerFbX = 0
    @Volatile private var pointerFbY = 0
    @Volatile private var lastCursorBlitMs = 0L
    @Volatile private var relativeMode = false
    private val renderLock = Any()

    // See blitToSurface's doc — backoff state for when lockCanvas() is failing (e.g. display
    // asleep: "dequeueBuffer failed", which surface.isValid does NOT catch).
    @Volatile private var blitFailing = false
    @Volatile private var lastBlitAttemptMs = 0L

    fun setZoom(scale: Float, newPanX: Float, newPanY: Float) {
        zoomScale = scale.coerceAtLeast(0.1f)
        panX = newPanX
        panY = newPanY
        blitToSurface()
    }

    /** Swaps in a fresh Surface mid-session, with retried re-blits across the resize settle window
     *  — see VncClient.updateSurface's doc for the full reasoning (a single blit can race the
     *  reallocating buffer during an IME/rotation resize and, on a static remote screen, leave the
     *  view frozen with nothing to re-trigger a draw). */
    fun updateSurface(surface: Surface) {
        targetSurface = surface
        // See VncClient.updateSurface's identical reset — a fresh Surface shouldn't inherit the
        // old one's display-off backoff.
        blitFailing = false
        surfaceRefreshThread?.interrupt()
        surfaceRefreshThread = Thread {
            for (delayMs in longArrayOf(0, 60, 150, 300, 550)) {
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
                if (targetSurface !== surface) return@Thread
                blitToSurface()
            }
        }.apply { isDaemon = true; start() }
    }

    /** Throws if another SPICE session is already active, or the connect thread otherwise can't be
     *  started — mirrors RdpClient.start()'s doc: the caller must let this fail createSession()
     *  outright rather than handing back a session handle for a connection that already died. */
    fun start() {
        if (!SpiceCommunicator.attach(this)) {
            throw IllegalStateException("Another SPICE session is already active in this app")
        }
        onProgress(if (tlsPort > 0) "Connecting to $host via Proxmox VE…" else "Connecting to $host:$port…")
        // Both native calls block the calling thread for the whole session lifetime (libspice runs
        // its own main loop internally) — same reasoning as VncClient/RdpClient's own thread. Their
        // return (for ANY reason, including a clean disconnect) means the session is over; the
        // vendored reference SpiceThread.run() treats every return the same way, not guessed.
        var vvFile: File? = null
        thread = Thread({
            val result = runCatching {
                if (tlsPort > 0) {
                    val f = writeVvFile()
                    vvFile = f
                    native.StartSessionFromVvFile(f.absolutePath, false)
                } else {
                    native.SpiceClientConnect(host, port.toString(), "", password.orEmpty(), null, null, null, false)
                }
            }
            Log.i(TAG, "Spice connect returned: $result")
            runCatching { vvFile?.delete() } // belt-and-suspenders — see writeVvFile's doc
            SpiceCommunicator.detach(this)
            onDisconnected(if (connected) "Disconnected" else "Connection failed — check host/port")
        }, "SpiceClient-$host").apply { isDaemon = true; start() }
    }

    /** Builds a virt-viewer ".vv" file (GLib GKeyFile / freedesktop Desktop-Entry INI format —
     *  confirmed by reading virt-viewer-file.c's use of g_key_file_load_from_file, not guessed) in
     *  this process's own cache dir. "delete-this-file=1" asks the native side to remove it once
     *  parsed (matches real virt-viewer's own handling of ticket-bearing files); the caller in
     *  [start] also unlinks it defensively after the blocking connect call returns, in case parsing
     *  fails before that flag is honoured. */
    private fun writeVvFile(): File {
        // GKeyFile string values are single-line; a literal newline would truncate the value and
        // start a bogus key on the next line. Its own escaping convention (shared with the
        // freedesktop Desktop Entry spec) represents an embedded newline as the two literal
        // characters '\' 'n', which is what re-joining the PEM's lines with "\\n" produces below.
        val caEscaped = caCert?.replace("\r\n", "\n")?.trim('\n', ' ')?.split("\n")?.joinToString("\\n")
        val content = buildString {
            appendLine("[virt-viewer]")
            appendLine("type=spice")
            appendLine("host=$host")
            appendLine("tls-port=$tlsPort")
            appendLine("password=${password.orEmpty()}")
            if (!proxy.isNullOrEmpty()) appendLine("proxy=$proxy")
            if (!hostSubject.isNullOrEmpty()) appendLine("host-subject=$hostSubject")
            if (!caEscaped.isNullOrEmpty()) appendLine("ca=$caEscaped")
            appendLine("delete-this-file=1")
            appendLine("secure-attention=ctrl+alt+end")
            appendLine("release-cursor=shift+f12")
            appendLine("toggle-fullscreen=shift+f11")
        }
        val file = File(context.cacheDir, "spice-${UUID.randomUUID()}.vv")
        file.writeText(content)
        return file
    }

    fun stop() {
        // See VncClient.stop()'s doc for why this is cleared first, before any teardown call that
        // might itself take a moment — a fast disconnect+reconnect (e.g. the connection-settings
        // menu) can have a brand-new session's client already targeting the same reused Surface
        // before this old session's teardown has fully unwound.
        targetSurface = null
        surfaceRefreshThread?.interrupt()
        runCatching { native.SpiceClientDisconnect() }
        thread?.interrupt()
    }

    fun isAlive(): Boolean = connected

    // ── SpiceCommunicator.Listener — native callbacks; may arrive on a libspice-internal thread,
    // not the thread that called start() (see android-service.c's attachThreadToJvm). ──

    override fun onSettingsChanged(width: Int, height: Int, bpp: Int) {
        Log.i(TAG, "onSettingsChanged: ${width}x$height @${bpp}bpp")
        // SPICE's primary surface is BGRX (native UpdateBitmap does the BGRX->RGBA swizzle) — the
        // X byte is unused padding, not a real alpha channel, and libspice writes it as 0x00. Left
        // alone, ARGB_8888's default hasAlpha=true reads that 0x00 as fully transparent, so every
        // pixel composites as invisible over blitToSurface's black background: cursor and input
        // still work (SyntheticCursor draws straight onto the canvas, not through this bitmap) but
        // no actual picture ever appears. setHasAlpha(false) makes the canvas ignore that byte and
        // treat every pixel as opaque, matching what VncClient's manual pixel decode already does
        // explicitly (see its "opaque ARGB_8888 int" comment).
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
        pointerFbX = width / 2
        pointerFbY = height / 2
        if (!connected) {
            connected = true
            onProgress("Connected — ${width}x$height")
            onConnected(width, height)
        }
    }

    override fun onGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
        val bmp = bitmap ?: return
        native.UpdateBitmap(bmp, x, y, width, height)
        blitToSurface()
    }

    override fun onMouseUpdate(x: Int, y: Int) {
        pointerFbX = x
        pointerFbY = y
        blitToSurface()
    }

    override fun onMouseMode(relative: Boolean) {
        relativeMode = relative
        if (relative) Log.w(TAG, "Server switched to relative mouse mode — input won't work (see class doc)")
    }

    override fun onShowMessage(message: String) {
        Log.i(TAG, "ShowMessage: $message")
    }

    override fun onRemoteClipboardChanged(data: String) {
        // Not wired up — VNC/RDP don't implement remote->local clipboard sync either (see
        // RemoteDesktopScreen's onPaste, which only goes local->remote via synthesized keystrokes).
    }

    // ── Input ──

    /** [buttonMask] follows the VNC convention (bit0=left, bit1=middle, bit2=right) — see
     *  [spiceButton] for the mapping onto SPICE's own button numbering. */
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val bmp = bitmap
        val mx = if (bmp != null) ((x - renderOffsetX) / renderScale).toInt().coerceIn(0, bmp.width - 1) else x
        val my = if (bmp != null) ((y - renderOffsetY) / renderScale).toInt().coerceIn(0, bmp.height - 1) else y
        pointerFbX = mx
        pointerFbY = my
        val currentButton = spiceButton(buttonMask)
        // Same press/move/release distinction as RdpClient.sendPointerEvent — a fresh press is a
        // one-time DOWN-flagged event, continued movement while held is a plain move with no
        // button re-assertion, and release repeats the button number without the DOWN bit.
        when {
            currentButton != BTN_MOVE && currentButton != lastButton ->
                runCatching { native.SpiceButtonEvent(mx, my, 0, currentButton or POINTER_DOWN_MASK, false) }
            currentButton != BTN_MOVE ->
                runCatching { native.SpiceButtonEvent(mx, my, 0, BTN_MOVE, false) }
            lastButton != BTN_MOVE ->
                runCatching { native.SpiceButtonEvent(mx, my, 0, lastButton, false) }
            else ->
                runCatching { native.SpiceButtonEvent(mx, my, 0, BTN_MOVE, false) }
        }
        lastButton = currentButton
        val now = System.currentTimeMillis()
        if (now - lastCursorBlitMs >= 16L) {
            lastCursorBlitMs = now
            blitToSurface()
        }
    }

    /** Mouse wheel. [steps] > 0 = up, < 0 = down — sent as momentary scroll-button clicks at the
     *  current pointer position, SPICE has no dedicated wheel message. */
    fun sendScroll(steps: Int) {
        if (steps == 0) return
        val button = if (steps > 0) BTN_SCROLL_UP else BTN_SCROLL_DOWN
        repeat(if (steps > 0) steps else -steps) {
            runCatching {
                native.SpiceButtonEvent(pointerFbX, pointerFbY, 0, button or POINTER_DOWN_MASK, false)
                native.SpiceButtonEvent(pointerFbX, pointerFbY, 0, BTN_MOVE, false)
            }
        }
    }

    @Volatile private var shiftHeld = false
    @Volatile private var altGrHeld = false
    // Set when this class itself pressed Shift/AltGr to type a character the physical/virtual
    // keyboard didn't already indicate that modifier for (see sendKeyEvent) — released on the
    // matching key-up so a synthesized "type '!'"/"type '@'" tap doesn't leave a phantom modifier
    // stuck down.
    @Volatile private var syntheticShiftHeld = false
    @Volatile private var syntheticAltGrHeld = false

    fun sendKeyEvent(keyCode: Int, unicodeChar: Int, down: Boolean) {
        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> shiftHeld = down
            KeyEvent.KEYCODE_ALT_RIGHT -> altGrHeld = down
        }
        // Non-printable / modifier / function key -> direct scancode.
        SpiceKeycode.scancodeForKeyCode(keyCode)?.let { sc ->
            runCatching { native.SpiceKeyEvent(down, sc) }
            return
        }
        // Printable character: a real hardware key with a resolvable unicodeChar, or a synthesized
        // tap (keyCode=0, from clipboard paste / the special-key bar's symbol keys).
        val mapping = SpiceKeycode.scancodeForUnicode(unicodeChar, keyboardLayout) ?: return
        if (down) {
            if (mapping.shift && !shiftHeld) {
                syntheticShiftHeld = true
                SpiceKeycode.scancodeForKeyCode(KeyEvent.KEYCODE_SHIFT_LEFT)?.let {
                    runCatching { native.SpiceKeyEvent(true, it) }
                }
            }
            if (mapping.altGr && !altGrHeld) {
                syntheticAltGrHeld = true
                SpiceKeycode.scancodeForKeyCode(KeyEvent.KEYCODE_ALT_RIGHT)?.let {
                    runCatching { native.SpiceKeyEvent(true, it) }
                }
            }
            runCatching { native.SpiceKeyEvent(true, mapping.scancode) }
        } else {
            runCatching { native.SpiceKeyEvent(false, mapping.scancode) }
            if (syntheticAltGrHeld) {
                syntheticAltGrHeld = false
                SpiceKeycode.scancodeForKeyCode(KeyEvent.KEYCODE_ALT_RIGHT)?.let {
                    runCatching { native.SpiceKeyEvent(false, it) }
                }
            }
            if (syntheticShiftHeld) {
                syntheticShiftHeld = false
                SpiceKeycode.scancodeForKeyCode(KeyEvent.KEYCODE_SHIFT_LEFT)?.let {
                    runCatching { native.SpiceKeyEvent(false, it) }
                }
            }
        }
    }

    /** See VncClient.blitToSurface's doc for the display-off backoff reasoning — identical here. */
    private fun blitToSurface() {
        val bmp = bitmap ?: return
        val surface = targetSurface ?: return
        if (!surface.isValid) return
        val now = System.currentTimeMillis()
        if (blitFailing && now - lastBlitAttemptMs < BLIT_RETRY_INTERVAL_MS) return
        lastBlitAttemptMs = now
        synchronized(renderLock) {
            try {
                val canvas = surface.lockCanvas(null) ?: run { blitFailing = true; return }
                try {
                    val sw = canvas.width
                    val sh = canvas.height
                    val scale = minOf(sw.toFloat() / bmp.width, sh.toFloat() / bmp.height) * zoomScale
                    val dw = bmp.width * scale
                    val dh = bmp.height * scale
                    // Top-anchored vertically, not centred — see VncClient.blitToSurface's doc.
                    val ox = (sw - dw) / 2f + panX
                    val oy = 0f + panY
                    renderScale = scale
                    renderOffsetX = ox
                    renderOffsetY = oy
                    canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(bmp, null, RectF(ox, oy, ox + dw, oy + dh), null)
                    SyntheticCursor.draw(canvas, ox + pointerFbX * scale, oy + pointerFbY * scale)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                blitFailing = false
            } catch (e: Exception) {
                blitFailing = true
                Log.w(TAG, "blitToSurface failed: ${e.message}")
            }
        }
    }
}
