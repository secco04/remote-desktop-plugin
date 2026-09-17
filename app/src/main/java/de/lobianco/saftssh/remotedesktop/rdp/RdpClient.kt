package de.lobianco.saftssh.remotedesktop.rdp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import de.lobianco.saftssh.remotedesktop.data.logging.AppLog
import android.view.KeyEvent
import android.view.Surface
import com.freerdp.freerdpcore.services.LibFreeRDP
import de.lobianco.saftssh.remotedesktop.SyntheticCursor

private const val TAG = "RdpClient"
// See blitToSurface's doc — how long to back off between retry attempts once lockCanvas() starts
// failing (e.g. display asleep), instead of retrying on every single incoming server frame.
private const val BLIT_RETRY_INTERVAL_MS = 2000L

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
 * The native libraries backing this (libfreerdp-android.so and friends) are, as of 2026-09, built
 * from FreeRDP's own upstream source at tag 3.31.1 (previously: prebuilt binaries extracted from
 * iiordanov/remote-desktop-clients' "freeaRDP" release APK, built from the older 2.11.7 tag) —
 * rebuilt from source specifically to pick up real RDP cursor-shape support (see
 * [LibFreeRDP.UIEventListener.OnPointerSet] and this file's [cursorBitmap]). See this repo's
 * LICENSE for attribution.
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
     *  viewport instead of a fixed default — 0 falls back to a sane default. May instead be an
     *  explicit override from the connection-settings menu — the caller (RemoteDesktopSessionService)
     *  resolves which one this is before constructing this class. */
    private val initialWidth: Int,
    private val initialHeight: Int,
    /** false ("Balanced") = 32-bit colour (unchanged default). true ("Fast") = 16-bit — half the
     *  GDI pixel bandwidth FreeRDP has to push per update, at some banding on gradients. Fixed for
     *  the session's lifetime — FreeRDP negotiates colour depth once at connect, so changing this
     *  means reconnecting (see IRemoteDesktopSessionService.createSession's doc). */
    private val fastQuality: Boolean = false,
    /** FreeRDP's own "/network:" performance preset ("lan"/"auto"/"modem") — see
     *  IRemoteDesktopSessionService.createSession's doc for the full reasoning. Null/blank leaves
     *  it unset (FreeRDP's own default). */
    private val networkPreset: String? = null,
    /** Redirects the remote's system audio to this device — see IRemoteDesktopSessionService.
     *  createSession's [soundEnabled] doc for why this is binary on/off only, fixed for the
     *  session's lifetime same as [fastQuality]. */
    private val soundEnabled: Boolean = false,
    /** FreeRDP's "/multitransport" (MS-RDPEMT) flag — see IRemoteDesktopSessionService.
     *  createSession's [udpEnabled] doc; safe to always request, FreeRDP falls back to TCP-only
     *  on its own if the server/network doesn't support it. */
    private val udpEnabled: Boolean = false,
    /** "us" | "de" | "fr" — sets FreeRDP's own "/kbd:layout:" connect-time parameter, i.e. the
     *  KEYBOARD LAYOUT THE SERVER NEGOTIATES for this session (not a client-side scancode remap —
     *  RDP has no such thing; contrast SPICE's setKeyboardLayout). Requires a reconnect to change,
     *  same as [fastQuality]/[networkPreset]. Matters in exactly the case [unicodeSupported] exists
     *  for: once a server rejects Unicode input and printable characters fall back to the
     *  Virtual-Key path, the SERVER's active keyboard layout is what turns a VK code into an actual
     *  character — a mismatched layout there is the same "wrong key produces wrong character"
     *  problem any RDP client has against such a server. Null/blank leaves it unset (FreeRDP's own
     *  default, effectively US). */
    private val keyboardLayout: String? = null,
    private val onProgress: (String) -> Unit,
    private val onConnected: (width: Int, height: Int) -> Unit,
    private val onDisconnected: (reason: String) -> Unit,
    /** Fires whenever the remote framebuffer is (re)allocated, including the initial one — the
     *  main app bounds its pinch-zoom panning to the real picture extent, see
     *  IRemoteDesktopSessionCallback.onRemoteSize. Defaulted so nothing else has to supply it. */
    private val onRemoteSize: (width: Int, height: Int) -> Unit = { _, _ -> },
) {
    private val certStore = RdpCertStore(context)
    private var inst: Long = 0
    @Volatile private var bitmap: Bitmap? = null
    @Volatile private var connected = false
    private var thread: Thread? = null
    @Volatile private var surfaceRefreshThread: Thread? = null

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
    // Last pointer position we sent, in framebuffer pixels — where the cursor is drawn.
    @Volatile private var pointerFbX = 0
    @Volatile private var pointerFbY = 0

    // Real remote cursor shape (FreeRDP 3.x OnPointerSet et al.) — null falls back to
    // SyntheticCursor below, matching the pre-3.x behaviour when no real shape has arrived yet
    // (or the remote explicitly asked for the platform default via OnPointerSetDefault).
    @Volatile private var cursorBitmap: Bitmap? = null
    @Volatile private var cursorHotX = 0
    @Volatile private var cursorHotY = 0
    // Remote explicitly hid the cursor (OnPointerSetNull) — draw nothing at all, not even the
    // synthetic fallback, so it doesn't reappear where the real cursor was deliberately hidden.
    @Volatile private var cursorHidden = false
    // User-adjustable multiplier on the cursor's rendered size — see setCursorScale's doc. Default
    // above 1.0 because Windows cursor bitmaps (commonly 32x32 remote px) render very small on a
    // typical high-DPI phone screen at the raw base letterbox scale.
    @Volatile private var cursorScale = 2f
    // Whether the connected server accepted Unicode keyboard input — see
    // LibFreeRDP.isUnicodeInputSupported's doc for why this MUST be checked before ever calling
    // sendUnicodeKeyEvent: on a server that doesn't support it, that call's normal, documented
    // failure return is misread by the vendored native library as a fatal transport error and
    // tears down the whole connection — reported as "beim ersten Tippen reconnected/freezed es
    // komplett, keine Mausbewegung, kein Bildupdate". Optimistic default (most servers support it);
    // set for real right after OnSettingsChanged confirms the connection actually negotiated.
    @Volatile private var unicodeSupported = true
    // Throttles supplemental redraws — cursor-move tracking AND setZoom, below — to ~60fps, shared
    // between both. See VncClient's identical field for the full reasoning (touch delivers move
    // events far faster than that, the app's pinch-zoom/edge-pan can drive setZoom at a steady
    // 60Hz of its own, and every redraw takes renderLock — unthrottled, either source can starve
    // real OnGraphicsUpdate-driven redraws and look "laggy"; sharing one timestamp also coalesces
    // a tick that fires both, as the edge-pan loop's setZoom + sendPointerEvent does, into one blit).
    @Volatile private var lastSupplementalBlitMs = 0L
    // Serialises lockCanvas/unlock between the FreeRDP graphics-update thread and Binder threads
    // calling in on a pointer move (see VncClient for the same reasoning).
    private val renderLock = Any()

    // See blitToSurface's doc — backoff state for when lockCanvas() is failing (e.g. display
    // asleep: "dequeueBuffer failed", which surface.isValid does NOT catch).
    @Volatile private var blitFailing = false
    @Volatile private var lastBlitAttemptMs = 0L

    fun setZoom(scale: Float, newPanX: Float, newPanY: Float) {
        zoomScale = scale.coerceAtLeast(0.1f)
        panX = newPanX
        panY = newPanY
        // Throttled exactly like the cursor-move redraw below (see lastSupplementalBlitMs's doc) —
        // this used to blit unconditionally, and the app's edge-pan auto-scroll (holding the cursor
        // against a zoomed view's border) calls setZoom on a steady 16ms timer, which drove an
        // equally unthrottled stream of full-framebuffer redraws contending with FreeRDP's own
        // OnGraphicsUpdate-driven ones for renderLock. Reported as "randscrollen laggt noch sehr"
        // even after the timer itself was smoothed out — the remaining lag was this, not the pan
        // math, and RDP's typically-heavier update traffic is exactly why it showed up here first.
        val now = System.currentTimeMillis()
        if (now - lastSupplementalBlitMs >= 16L) {
            lastSupplementalBlitMs = now
            blitToSurface()
        }
    }

    /** RDP-only user-adjustable cursor size — see IRemoteDesktopSession.setCursorScale's AIDL doc.
     *  Clamped to a sane range so a bad value from the UI can't render an unusably huge or
     *  invisible cursor. */
    fun setCursorScale(scale: Float) {
        cursorScale = scale.coerceIn(0.5f, 4f)
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
            for (delayMs in longArrayOf(0, 60, 150, 300, 550, 900, 1500)) {
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
                // Clear the backoff before EVERY attempt, not once before the loop. The first
                // failure re-arms it (blitToSurface sets blitFailing on a failed lockCanvas), and
                // that method's own guard then swallows every remaining retry in this ladder —
                // they all fall well inside BLIT_RETRY_INTERVAL_MS. So the settle-window retry this
                // thread exists for was, in practice, a SINGLE attempt: if that one landed while
                // the buffer was still reallocating, a STATIC remote screen had nothing left to
                // re-trigger a draw and stayed black indefinitely. Reported after opening another
                // protocol's tab and switching back — starting that other session keeps the device
                // busy for about as long as this ladder used to run, which is why it showed up there.
                blitFailing = false
                blitToSurface(force = true)
                if (!blitFailing) return@Thread   // one landed — nothing left to retry
            }
        }.apply { isDaemon = true; start() }
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
                AppLog.i(TAG, "OnSettingsChanged: ${width}x$height @${bpp}bpp")
                allocateFramebuffer(width, height)
                pointerFbX = width / 2
                pointerFbY = height / 2
                unicodeSupported = LibFreeRDP.isUnicodeInputSupported(inst)
                if (!unicodeSupported) {
                    AppLog.w(TAG, "Server does not support Unicode keyboard input — falling back to Virtual-Key path for printable characters")
                }
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

            // FreeRDP 3.x's *Ex signatures (host/port/flags replace the old bare hostMismatch
            // boolean — see LibFreeRDP.java's class doc). host/port/flags aren't needed for our
            // trust decision (fingerprint-only, same as before), so they're unused here.
            override fun OnVerifyCertificateEx(
                host: String?, port: Long, commonName: String?, subject: String?, issuer: String?,
                fingerprint: String?, flags: Long,
            ): Int = decideCertificateTrust(fingerprint)

            override fun OnVerifyChangedCertificateEx(
                host: String?, port: Long, commonName: String?, subject: String?, issuer: String?,
                fingerprint: String?, oldSubject: String?, oldIssuer: String?,
                oldFingerprint: String?, flags: Long,
            ): Int = decideCertificateTrust(fingerprint)

            // Real remote cursor shape (FreeRDP 3.x, upstream PR #12786) — see cursorBitmap's doc.
            override fun OnPointerSet(pixels: IntArray, width: Int, height: Int, xPos: Int, yPos: Int) {
                cursorBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                cursorHotX = xPos
                cursorHotY = yPos
                cursorHidden = false
            }

            override fun OnPointerSetNull() {
                cursorBitmap = null
                cursorHidden = true
            }

            override fun OnPointerSetDefault() {
                cursorBitmap = null
                cursorHidden = false
            }

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
                AppLog.i(TAG, "OnGraphicsResize: ${width}x$height @${bpp}bpp")
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
        // /bpp only affects what colour depth the SERVER negotiates/encodes over the wire — our
        // own bitmap (allocateFramebuffer) is always ARGB_8888 regardless: FreeRDP's native GDI
        // layer always keeps its own internal primary buffer at RGBX32 (gdi_init in
        // android_post_connect hardcodes it) and jni_freerdp_update_graphics converts FROM that
        // TO whatever format our bitmap reports via AndroidBitmap_getInfo — so lowering this to 16
        // is a pure network-bandwidth win with no local pixel-format plumbing needed.
        val bppParam = if (fastQuality) "16" else "32"
        val uriBuilder = Uri.Builder()
            .scheme("freerdp")
            .encodedAuthority(buildAuthority())
            .appendQueryParameter("size", sizeParam)
            .appendQueryParameter("bpp", bppParam)
            .appendQueryParameter("clipboard", "")
            .appendQueryParameter("sec", "nla") // most modern Windows hosts require NLA by default
            // Lossless bulk (zlib) wire compression — pure bandwidth win, no visual quality cost,
            // so always on rather than exposed as its own toggle (same reasoning as clipboard/sec).
            .appendQueryParameter("compression", "")

        if (!networkPreset.isNullOrBlank()) uriBuilder.appendQueryParameter("network", networkPreset)
        if (soundEnabled) uriBuilder.appendQueryParameter("sound", "sys:opensles")
        if (udpEnabled) uriBuilder.appendQueryParameter("multitransport", "")
        if (!password.isNullOrEmpty()) uriBuilder.appendQueryParameter("p", password)
        // "unicode:on" is THE fix for characters arriving wrong (y/z swapped, no umlauts) on some
        // servers: FreeRDP's own command-line parser DISABLES Unicode keyboard input by default
        // ("/* Disable unicode input unless requested. */" in client/common/cmdline.c, right before
        // parse_command_line), and capabilities.c only consults the SERVER's advertised support
        // when the client already has it enabled ("If disabled in client pre_connect, it can
        // disable announcing the feature"). So without this, FreeRDP_UnicodeInput stays FALSE no
        // matter what the server supports, every sendUnicodeKeyEvent() is refused with the
        // misleading "Unicode input not supported by server", and printable characters fall back to
        // the Virtual-Key path — which the server then re-interprets through ITS keyboard layout,
        // producing exactly the reported "QWERTZ eingestellt, kommt trotzdem QWERTY raus".
        // With Unicode input actually on, characters transmit verbatim and no layout is involved.
        //
        // Both settings must go in ONE "kbd" query parameter, comma-separated: LibFreeRDP's
        // setConnectionInfo(Uri) iterates getQueryParameterNames() (a SET — one entry per name) and
        // reads getQueryParameter(), which returns only the FIRST value, so a second kbd= would be
        // silently dropped. FreeRDP's own parse_kbd_options splits the value on commas.
        //
        // Windows keyboard layout identifiers (KLIDs) — stable OS constants, not FreeRDP-specific.
        // The layout still matters for the Virtual-Key path (special/modifier keys, and the
        // fallback if a server really does refuse Unicode) — see [keyboardLayout]'s doc.
        val klid = when (keyboardLayout) {
            "de" -> "0x00000407"
            "fr" -> "0x0000040c"
            "us" -> "0x00000409"
            else -> null
        }
        val kbdOpts = listOfNotNull("unicode:on", klid?.let { "layout:$it" }).joinToString(",")
        uriBuilder.appendQueryParameter("kbd", kbdOpts)

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
                onDisconnected(lastErrorReason(instance, "Connection failed — check host/port/credentials"))
            }
            override fun OnDisconnecting(instance: Long) {}
            override fun OnDisconnected(instance: Long) {
                if (connected) onDisconnected(lastErrorReason(instance, "Disconnected"))
            }
        })

        // freerdp_connect() blocks the calling thread for the whole session lifetime (it runs the
        // client's own message loop internally) — same reasoning as VncClient's own thread.
        thread = Thread({
            val success = LibFreeRDP.connect(inst)
            if (!success) onDisconnected(lastErrorReason(inst, "Connection failed"))
        }, "RdpClient-$host:$port").apply { isDaemon = true; start() }
    }

    /**
     * FreeRDP's own reason for the last connect/disconnect failure — distinguishes an NLA/CredSSP
     * negotiation failure from a rejected password from a network timeout, none of which were
     * told apart before (every failure surfaced as the same generic string regardless of cause).
     * Falls back to [fallback] when the native call is unavailable or returns nothing useful, so a
     * missing reason never leaves the user with a blank message.
     */
    private fun lastErrorReason(instance: Long, fallback: String): String {
        val native = runCatching { LibFreeRDP.getLastErrorString(instance) }.getOrNull()
        return if (native.isNullOrBlank()) fallback else "$fallback ($native)"
    }

    /** (Re)allocates the framebuffer bitmap the native GDI buffer is copied into. Always
     *  ARGB_8888 — jni_freerdp_update_graphics maps that to PIXEL_FORMAT_RGBX32, a direct match
     *  for the GDI primary buffer (gdi_init uses RGBX32), so it works regardless of the remote's
     *  negotiated colour depth; no need for the reference client's RGB_565-for-16bpp branch. */
    private fun allocateFramebuffer(width: Int, height: Int) {
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // The single choke point for both the initial size (OnSettingsChanged) and a later
        // server-initiated resize (OnGraphicsResize), so reporting here covers both.
        onRemoteSize(width, height)
    }

    /** Returns 1 (accept) only if [fingerprint] matches what's already trusted for this host;
     *  otherwise rejects (0) and reports the pending decision via [onProgress] so the caller can
     *  prompt the user and retry — see the class doc's certificate-trust section. */
    private fun decideCertificateTrust(fingerprint: String?): Int {
        val fp = fingerprint ?: return 0
        if (certStore.isTrusted(host, port, fp)) return 1
        AppLog.w(TAG, "Untrusted RDP certificate for $host:$port (fingerprint=$fp) — rejecting, awaiting user confirmation")
        onProgress("CERT_UNTRUSTED|$host|$port|$fp")
        return 0
    }

    private fun buildAuthority(): String {
        val userPart = username?.let { Uri.encode(it) + "@" } ?: ""
        return "$userPart$host:$port"
    }

    fun stop() {
        // See VncClient.stop()'s doc for why this is cleared first, before any teardown call that
        // might itself take a moment — a fast disconnect+reconnect (e.g. the connection-settings
        // menu) can have a brand-new session's client already targeting the same reused Surface
        // before this old session's teardown has fully unwound.
        targetSurface = null
        surfaceRefreshThread?.interrupt()
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
        // Confirmed against FreeRDP's own reference Android client (Mouse.java /
        // SessionActivity.java's onTouchPointerMove/onTouchPointerLeftClick):
        //  - A button PRESS is its own one-time event: PTR_FLAGS_DOWN|BUTTONn alone, no MOVE bit.
        //  - Ongoing movement — INCLUDING while a button is held (dragging) — is reported as
        //    plain PTR_FLAGS_MOVE, with no button flag at all; the reference's onTouchPointerMove
        //    always sends just Mouse.getMoveEvent() regardless of button state. Re-asserting
        //    PTR_FLAGS_DOWN|BUTTONn on every move sample during a drag (an earlier version of
        //    this code did that) is a repeated "fresh press" the server has no reason to treat as
        //    a continuous drag, which is very likely why "everything except a single tap" (drag,
        //    and by extension anything depending on a held button) didn't work.
        //  - RELEASE is BUTTONn alone (no DOWN, no MOVE).
        val flags = when {
            currentFlag != 0 && currentFlag != lastButtonFlag -> PTR_FLAGS_DOWN or currentFlag // fresh press
            currentFlag != 0 -> PTR_FLAGS_MOVE // still held — just a position sample, no re-press
            lastButtonFlag != 0 -> lastButtonFlag // release (flag without DOWN = button-up)
            else -> PTR_FLAGS_MOVE // hover / move with no button
        }
        lastButtonFlag = currentFlag
        runCatching { LibFreeRDP.sendCursorEvent(inst, mx, my, flags) }
            .onSuccess { ok -> if (buttonMask != 0 || currentFlag != 0) AppLog.i(TAG, "sendCursorEvent($mx,$my,flags=0x${flags.toString(16)}) inst=$inst connected=$connected -> $ok") }
            .onFailure { e -> AppLog.w(TAG, "sendCursorEvent threw", e) }
        // Redraw so the synthetic cursor tracks the pointer between server frames. Throttled (see
        // lastSupplementalBlitMs's doc) so a fast drag can't starve real protocol-driven redraws.
        val now = System.currentTimeMillis()
        if (now - lastSupplementalBlitMs >= 16L) {
            lastSupplementalBlitMs = now
            blitToSurface()
        }
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
            is RdpKeycode.Mapped.Unicode -> {
                // See unicodeSupported's doc: sending this to a server that doesn't support it
                // kills the whole connection in the vendored native library, so it must never be
                // attempted at all — not even once, since the crash happens on the FIRST call.
                if (unicodeSupported) {
                    runCatching { LibFreeRDP.sendUnicodeKeyEvent(inst, mapped.codepoint, down) }
                } else {
                    val vk = RdpKeycode.vkForChar(mapped.codepoint)
                    if (vk != 0) runCatching { LibFreeRDP.sendKeyEvent(inst, vk, down) }
                    // Punctuation/symbols beyond a-z/A-Z/0-9 have no VK fallback here — an inherent
                    // limitation of a server that doesn't negotiate Unicode input at all, same as
                    // any other RDP client would face against it.
                }
            }
            is RdpKeycode.Mapped.VirtualKey -> runCatching { LibFreeRDP.sendKeyEvent(inst, mapped.vk, down) }
            RdpKeycode.Mapped.None -> {}
        }
    }

    /** See VncClient.blitToSurface's doc for the display-off backoff reasoning — identical here. */
    /** [force] bypasses the display-off backoff below — only updateSurface's bounded
     *  settle-window ladder uses it; see there for why the backoff would otherwise
     *  suppress its own retries. */
    private fun blitToSurface(force: Boolean = false) {
        val bmp = bitmap ?: return
        val surface = targetSurface ?: return
        if (!surface.isValid) return
        val now = System.currentTimeMillis()
        if (!force && blitFailing && now - lastBlitAttemptMs < BLIT_RETRY_INTERVAL_MS) return
        lastBlitAttemptMs = now
        synchronized(renderLock) {
            try {
                val canvas = surface.lockCanvas(null) ?: run { blitFailing = true; return }
                try {
                    // Letterbox * zoom — see VncClient.blitToSurface for the identical reasoning.
                    val sw = canvas.width
                    val sh = canvas.height
                    val baseScale = minOf(sw.toFloat() / bmp.width, sh.toFloat() / bmp.height)
                    val scale = baseScale * zoomScale
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
                    // Real remote cursor shape when we have one (FreeRDP 3.x) — same render
                    // pipeline as the framebuffer itself, so it stays in sync with pan/zoom.
                    // Falls back to the synthetic cursor when no real shape has arrived yet or
                    // the remote asked for the platform default (OnPointerSetDefault); drawn
                    // nothing at all when the remote explicitly hid it (OnPointerSetNull).
                    // Position uses the full scale (incl. pinch-zoom) so it stays anchored to the
                    // right spot on the picture; SIZE deliberately uses only baseScale — a real
                    // cursor doesn't visually balloon when the user pinch-zooms in, same as every
                    // other remote-desktop client (reported as "cursor wird groß" when this used
                    // the zoomed scale for both).
                    val cursor = cursorBitmap
                    if (!cursorHidden) {
                        if (cursor != null) {
                            val cx = ox + (pointerFbX - cursorHotX) * scale
                            val cy = oy + (pointerFbY - cursorHotY) * scale
                            val cursorSize = baseScale * cursorScale
                            canvas.drawBitmap(
                                cursor, null,
                                RectF(cx, cy, cx + cursor.width * cursorSize, cy + cursor.height * cursorSize),
                                null,
                            )
                        } else {
                            SyntheticCursor.draw(canvas, ox + pointerFbX * scale, oy + pointerFbY * scale)
                        }
                    }
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                blitFailing = false
            } catch (e: Exception) {
                blitFailing = true
                AppLog.w(TAG, "blitToSurface failed: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }
}
