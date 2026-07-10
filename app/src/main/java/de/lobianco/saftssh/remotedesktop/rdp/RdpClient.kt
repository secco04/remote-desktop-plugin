package de.lobianco.saftssh.remotedesktop.rdp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import com.freerdp.freerdpcore.services.LibFreeRDP
import de.lobianco.saftssh.remotedesktop.SyntheticCursor

private const val TAG = "RdpClient"

// RDP pointer-event flags (MS-RDPBCGR TS_POINTER_FLAG constants — stable protocol spec values,
// verified against include/freerdp/input.h rather than assumed, since RDP's BUTTON1/2/3 numbering
// does NOT follow the same left/middle/right order as VNC's buttonMask bit order below).
private const val PTR_FLAGS_MOVE = 0x0800
private const val PTR_FLAGS_DOWN = 0x8000
private const val PTR_FLAGS_BUTTON1 = 0x1000 // left
private const val PTR_FLAGS_BUTTON2 = 0x2000 // right
private const val PTR_FLAGS_BUTTON3 = 0x4000 // middle
private const val PTR_FLAGS_WHEEL = 0x0200
// Wheel flags for one notch, taken verbatim from FreeRDP 2.11.7's reference Mouse.getScrollEvent
// (up = WHEEL | 0x0078, down = WHEEL | WHEEL_NEGATIVE(0x0100) | 0x0088).
private const val WHEEL_UP = PTR_FLAGS_WHEEL or 0x0078
private const val WHEEL_DOWN = PTR_FLAGS_WHEEL or 0x0100 or 0x0088

/** Maps a VNC-convention buttonMask (bit0=left, bit1=middle, bit2=right — see
 *  IRemoteDesktopSession.sendPointerEvent's doc) to the one RDP button flag it represents. Our
 *  touch UI only ever sets one bit at a time (single-finger vs. two-finger-tap are mutually
 *  exclusive gestures), so "one bit -> one flag" is all that's needed; 0 = no button. */
private fun rdpButtonFlag(vncButtonMask: Int): Int = when {
    vncButtonMask and 0x01 != 0 -> PTR_FLAGS_BUTTON1
    vncButtonMask and 0x04 != 0 -> PTR_FLAGS_BUTTON2
    vncButtonMask and 0x02 != 0 -> PTR_FLAGS_BUTTON3
    else -> 0
}

/**
 * Thin wrapper around the vendored [LibFreeRDP] JNI bridge (FreeRDP's own upstream Android
 * client code — see that file's class doc for why it's vendored as-is rather than reimplemented).
 * Mirrors [de.lobianco.saftssh.remotedesktop.vnc.VncClient]'s shape: connect, render onto a
 * Surface, forward input — so RemoteDesktopSessionService can treat both protocols uniformly.
 *
 * The native libraries backing this (libfreerdp-android.so and friends) are prebuilt binaries
 * extracted from iiordanov/remote-desktop-clients' official "freeaRDP" GitHub Release APK
 * (v6.4.5) — building FreeRDP from source needs a multi-hour native cross-compile toolchain
 * (Cerbero) this project doesn't set up. See this repo's LICENSE for attribution.
 *
 * Certificate trust: unlike VNC (no certificates involved), RDP's TLS layer means every unknown
 * server certificate needs a decision — mirrors the main app's own SSH host-key confirmation flow
 * (HostKeyStore): an unknown/changed certificate is REJECTED (not silently accepted — that was
 * flagged and corrected during development), reported to the caller via [onProgress] using the
 * marker "CERT_UNTRUSTED|host|port|fingerprint", and the caller must call
 * [RemoteDesktopSessionService]'s trustRdpCertificate() + retry the connection before it succeeds.
 */
class RdpClient(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val username: String?,
    private val password: String?,
    /** The hosting Surface's actual size at connect time, used as the requested RDP session
     *  resolution (see the /size query param below) so the remote desktop fills the visible
     *  viewport instead of a fixed default — 0 falls back to a sane default. */
    private val initialWidth: Int,
    private val initialHeight: Int,
    private val onProgress: (String) -> Unit,
    private val onConnected: (width: Int, height: Int) -> Unit,
    private val onDisconnected: (reason: String) -> Unit,
) {
    private val certStore = RdpCertStore(context)
    private var inst: Long = 0
    @Volatile private var bitmap: Bitmap? = null
    @Volatile private var connected = false
    private var thread: Thread? = null

    @Volatile var targetSurface: Surface? = null

    // Letterbox * zoom render geometry (see VncClient for the same reasoning) — used to
    // inverse-map Surface-pixel touch coordinates back to remote-desktop pixels in
    // sendPointerEvent. zoomScale/panX/panY are set by RemoteDesktopScreen via setZoom().
    @Volatile private var renderScale = 1f
    @Volatile private var renderOffsetX = 0f
    @Volatile private var renderOffsetY = 0f
    @Volatile private var zoomScale = 1f
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f
    // Tracks which single RDP button flag (if any) was down on the previous pointer event, so a
    // press→release transition can emit an explicit RDP button-up (the same PTR_FLAGS_BUTTONn
    // without PTR_FLAGS_DOWN) — RDP has no "no buttons" release, the button flag must be
    // repeated on the up event.
    @Volatile private var lastButtonFlag = 0
    // Last pointer position we sent, in framebuffer pixels — where SyntheticCursor is drawn.
    @Volatile private var pointerFbX = 0
    @Volatile private var pointerFbY = 0
    // Serialises lockCanvas/unlock between the FreeRDP graphics-update thread and Binder threads
    // calling in on a pointer move (see VncClient for the same reasoning).
    private val renderLock = Any()

    fun setZoom(scale: Float, newPanX: Float, newPanY: Float) {
        zoomScale = scale.coerceAtLeast(0.1f)
        panX = newPanX
        panY = newPanY
        blitToSurface() // reflect the new zoom immediately, even without a fresh server frame
    }

    /** Throws if the connection parameters are rejected before the connect thread even starts
     *  (e.g. a malformed URI) — the caller (RemoteDesktopSessionService.buildSession) must let
     *  this fail createSession() outright rather than handing back a session handle for a
     *  connection that already died, which would otherwise race the AIDL onDisconnected callback
     *  against createSession()'s own return and could leave the UI showing "Connected" for a
     *  session that never actually started. */
    fun start() {
        inst = LibFreeRDP.newInstance(context)

        LibFreeRDP.setUIEventListener(inst, object : LibFreeRDP.UIEventListener {
            override fun OnSettingsChanged(width: Int, height: Int, bpp: Int) {
                // Fires ONCE on the initial connect (from android_post_connect in FreeRDP
                // 2.11.7's android_freerdp.c) with the negotiated desktop size. THIS — not
                // OnGraphicsResize — is where the framebuffer bitmap must be allocated:
                // OnGraphicsResize only fires on a *later* server-initiated desktop resize
                // (android_desktop_resize), so relying on it left the initial connect with no
                // bitmap and a permanently blank screen. Confirmed against the actual native
                // source + the reference SessionActivity, not guessed.
                Log.i(TAG, "OnSettingsChanged: ${width}x$height @${bpp}bpp")
                allocateFramebuffer(width, height)
                pointerFbX = width / 2
                pointerFbY = height / 2
                if (!connected) {
                    connected = true
                    onProgress("Connected — ${width}x$height")
                    onConnected(width, height)
                }
            }

            override fun OnAuthenticate(
                usernameOut: StringBuilder, domainOut: StringBuilder, passwordOut: StringBuilder,
            ): Boolean {
                // Credentials are already supplied via the connection URI (see start() below);
                // this fires if the server still wants them confirmed. DOMAIN\user is split if
                // present, matching standard RDP username syntax.
                val u = username.orEmpty()
                val parts = u.split("\\", limit = 2)
                if (parts.size == 2) { domainOut.append(parts[0]); usernameOut.append(parts[1]) }
                else usernameOut.append(u)
                passwordOut.append(password.orEmpty())
                return true
            }

            override fun OnGatewayAuthenticate(
                usernameOut: StringBuilder, domainOut: StringBuilder, passwordOut: StringBuilder,
            ): Boolean = OnAuthenticate(usernameOut, domainOut, passwordOut)

            // Note the misspelled method name ("Verifiy") — matches FreeRDP 2.11.7's
            // UIEventListener interface exactly, since that's the version the vendored
            // native libraries were built from (see LibFreeRDP.java's class doc). No certPort/
            // flags params either — that's a newer-FreeRDP addition this build predates.
            override fun OnVerifiyCertificate(
                commonName: String?, subject: String?, issuer: String?, fingerprint: String?,
                mismatch: Boolean,
            ): Int = decideCertificateTrust(fingerprint)

            override fun OnVerifyChangedCertificate(
                commonName: String?, subject: String?, issuer: String?, fingerprint: String?,
                oldSubject: String?, oldIssuer: String?, oldFingerprint: String?,
            ): Int = decideCertificateTrust(fingerprint)

            override fun OnGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
                // Pull the freshly-painted region out of FreeRDP's native GDI buffer into our
                // bitmap (updateGraphics must be called per-update — it's what actually copies
                // pixels; without it the bitmap never receives any content), then draw.
                val bmp = bitmap ?: return
                LibFreeRDP.updateGraphics(inst, bmp, x, y, width, height)
                blitToSurface()
            }

            override fun OnGraphicsResize(width: Int, height: Int, bpp: Int) {
                // Later server-initiated desktop resize only — replace the bitmap with the new
                // size. Initial allocation happens in OnSettingsChanged (see there).
                Log.i(TAG, "OnGraphicsResize: ${width}x$height @${bpp}bpp")
                allocateFramebuffer(width, height)
            }

            override fun OnRemoteClipboardChanged(data: String?) {}
        })

        // Build the connection as a freerdp:// URI — see LibFreeRDP.setConnectionInfo(Uri)'s doc:
        // each query parameter becomes a `/key:value` (or `/key`/`-key`/`+key`) FreeRDP CLI flag.
        // Deliberately NOT passing "cert=ignore" — certificate trust is handled explicitly via
        // decideCertificateTrust()/RdpCertStore instead (see the class doc for why).
        // Deliberately no /kbd query param: FreeRDP 2.11.7's /kbd expects a numeric keyboard
        // layout ID or a known layout name (see client/common/cmdline.c's "kbd" case, which
        // rejects anything else with COMMAND_LINE_ERROR_UNEXPECTED_VALUE — a value like
        // "unicode:on" made freerdp_parse_arguments() fail outright, so setConnectionInfo()
        // returned false and the connect thread never started). There's no CLI flag needed to
        // "enable" Unicode keyboard input in this version — sendUnicodeKeyEvent() works
        // independently of the negotiated keyboard layout, which only matters for scancodes.
        val sizeParam = if (initialWidth > 0 && initialHeight > 0) "${initialWidth}x$initialHeight" else "1280x800"
        val uriBuilder = Uri.Builder()
            .scheme("freerdp")
            .encodedAuthority(buildAuthority())
            .appendQueryParameter("size", sizeParam)
            .appendQueryParameter("bpp", "32")
            .appendQueryParameter("clipboard", "")
            .appendQueryParameter("sec", "nla") // most modern Windows hosts require NLA by default

        if (!password.isNullOrEmpty()) uriBuilder.appendQueryParameter("p", password)

        val uri = uriBuilder.build()
        onProgress("Connecting to $host:$port…")
        val ok = LibFreeRDP.setConnectionInfo(context, inst, uri)
        if (!ok) {
            throw IllegalStateException("Failed to parse connection parameters")
        }

        LibFreeRDP.setEventListener(object : LibFreeRDP.EventListener {
            override fun OnPreConnect(instance: Long) { onProgress("Negotiating…") }
            override fun OnConnectionSuccess(instance: Long) { /* graphics arrive via OnGraphicsResize */ }
            override fun OnConnectionFailure(instance: Long) {
                onDisconnected("Connection failed — check host/port/credentials")
            }
            override fun OnDisconnecting(instance: Long) {}
            override fun OnDisconnected(instance: Long) {
                if (connected) onDisconnected("Disconnected")
            }
        })

        // freerdp_connect() blocks the calling thread for the whole session lifetime (it runs the
        // client's own message loop internally) — same reasoning as VncClient's own thread.
        thread = Thread({
            val success = LibFreeRDP.connect(inst)
            if (!success) onDisconnected("Connection failed")
        }, "RdpClient-$host:$port").apply { isDaemon = true; start() }
    }

    /** (Re)allocates the framebuffer bitmap the native GDI buffer is copied into. Always
     *  ARGB_8888 — jni_freerdp_update_graphics maps that to PIXEL_FORMAT_RGBX32, a direct match
     *  for the GDI primary buffer (gdi_init uses RGBX32), so it works regardless of the remote's
     *  negotiated colour depth; no need for the reference client's RGB_565-for-16bpp branch. */
    private fun allocateFramebuffer(width: Int, height: Int) {
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    /** Returns 1 (accept) only if [fingerprint] matches what's already trusted for this host;
     *  otherwise rejects (0) and reports the pending decision via [onProgress] so the caller can
     *  prompt the user and retry — see the class doc's certificate-trust section. */
    private fun decideCertificateTrust(fingerprint: String?): Int {
        val fp = fingerprint ?: return 0
        if (certStore.isTrusted(host, port, fp)) return 1
        Log.w(TAG, "Untrusted RDP certificate for $host:$port (fingerprint=$fp) — rejecting, awaiting user confirmation")
        onProgress("CERT_UNTRUSTED|$host|$port|$fp")
        return 0
    }

    private fun buildAuthority(): String {
        val userPart = username?.let { Uri.encode(it) + "@" } ?: ""
        return "$userPart$host:$port"
    }

    fun stop() {
        runCatching { LibFreeRDP.disconnect(inst) }
        runCatching { LibFreeRDP.removeUIEventListener(inst) }
        runCatching { LibFreeRDP.freeInstance(inst) }
        thread?.interrupt()
    }

    fun isAlive(): Boolean = connected

    /** [buttonMask] follows the VNC convention (bit0=left, bit1=middle, bit2=right) — see
     *  [rdpButtonFlag] for the (non-trivial) mapping onto RDP's own BUTTON1/2/3 numbering. */
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val bmp = bitmap
        val mx = if (bmp != null) ((x - renderOffsetX) / renderScale).toInt().coerceIn(0, bmp.width - 1) else x
        val my = if (bmp != null) ((y - renderOffsetY) / renderScale).toInt().coerceIn(0, bmp.height - 1) else y
        pointerFbX = mx
        pointerFbY = my
        val currentFlag = rdpButtonFlag(buttonMask)
        val flags = when {
            currentFlag != 0 -> PTR_FLAGS_MOVE or PTR_FLAGS_DOWN or currentFlag // press or drag
            lastButtonFlag != 0 -> lastButtonFlag // release (flag without DOWN = button-up)
            else -> PTR_FLAGS_MOVE // hover / move with no button
        }
        lastButtonFlag = currentFlag
        runCatching { LibFreeRDP.sendCursorEvent(inst, mx, my, flags) }
        // Redraw so the synthetic cursor tracks the pointer between server frames.
        blitToSurface()
    }

    // Which of Ctrl/Alt/Win are currently held (tracked from the key events themselves). When one
    // is held, printable letters/digits must go through the Virtual-Key (scancode) path so the
    // remote applies the modifier — Windows does NOT combine a held modifier with a char delivered
    // via the separate Unicode keyboard PDU. Shift is excluded: shift+letter is just an uppercase
    // char, which the Unicode path already carries.
    @Volatile private var ctrlHeld = false
    @Volatile private var altHeld = false
    @Volatile private var winHeld = false

    /** Mouse wheel. [steps] > 0 = up, < 0 = down; magnitude = notch count. Sent at the current
     *  pointer position (FreeRDP applies the wheel wherever the cursor currently is). */
    fun sendScroll(steps: Int) {
        if (steps == 0) return
        val flags = if (steps > 0) WHEEL_UP else WHEEL_DOWN
        repeat(if (steps > 0) steps else -steps) {
            runCatching { LibFreeRDP.sendCursorEvent(inst, pointerFbX, pointerFbY, flags) }
        }
    }

    fun sendKeyEvent(keyCode: Int, unicodeChar: Int, down: Boolean) {
        when (keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> ctrlHeld = down
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> altHeld = down
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> winHeld = down
        }
        if (ctrlHeld || altHeld || winHeld) {
            val vk = RdpKeycode.vkForChar(unicodeChar)
            if (vk != 0) { runCatching { LibFreeRDP.sendKeyEvent(inst, vk, down) }; return }
        }
        when (val mapped = RdpKeycode.map(keyCode, unicodeChar)) {
            is RdpKeycode.Mapped.Unicode -> runCatching { LibFreeRDP.sendUnicodeKeyEvent(inst, mapped.codepoint, down) }
            is RdpKeycode.Mapped.VirtualKey -> runCatching { LibFreeRDP.sendKeyEvent(inst, mapped.vk, down) }
            RdpKeycode.Mapped.None -> {}
        }
    }

    private fun blitToSurface() {
        val bmp = bitmap ?: return
        val surface = targetSurface ?: return
        if (!surface.isValid) return
        synchronized(renderLock) {
            try {
                val canvas = surface.lockCanvas(null) ?: return
                try {
                    // Letterbox * zoom — see VncClient.blitToSurface for the identical reasoning.
                    val sw = canvas.width
                    val sh = canvas.height
                    val scale = minOf(sw.toFloat() / bmp.width, sh.toFloat() / bmp.height) * zoomScale
                    val dw = bmp.width * scale
                    val dh = bmp.height * scale
                    val ox = (sw - dw) / 2f + panX
                    val oy = (sh - dh) / 2f + panY
                    renderScale = scale
                    renderOffsetX = ox
                    renderOffsetY = oy
                    canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(bmp, null, RectF(ox, oy, ox + dw, oy + dh), null)
                    SyntheticCursor.draw(canvas, ox + pointerFbX * scale, oy + pointerFbY * scale)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
            } catch (e: Exception) {
                Log.w(TAG, "blitToSurface failed: ${e.message}")
            }
        }
    }
}
