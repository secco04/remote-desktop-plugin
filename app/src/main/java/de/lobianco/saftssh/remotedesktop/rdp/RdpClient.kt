package de.lobianco.saftssh.remotedesktop.rdp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.Surface
import com.freerdp.freerdpcore.services.LibFreeRDP

private const val TAG = "RdpClient"

// RDP pointer-event flags (MS-RDPBCGR TS_POINTER_FLAG constants — stable protocol spec values,
// not implementation details).
private const val PTR_FLAGS_MOVE = 0x0800
private const val PTR_FLAGS_DOWN = 0x8000
private const val PTR_FLAGS_BUTTON1 = 0x1000 // left

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

    fun start() {
        inst = LibFreeRDP.newInstance(context)

        LibFreeRDP.setUIEventListener(inst, object : LibFreeRDP.UIEventListener {
            override fun OnSettingsChanged(width: Int, height: Int, bpp: Int) {
                Log.i(TAG, "OnSettingsChanged: ${width}x$height @${bpp}bpp")
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
                blitToSurface()
            }

            override fun OnGraphicsResize(width: Int, height: Int, bpp: Int) {
                Log.i(TAG, "OnGraphicsResize: ${width}x$height @${bpp}bpp")
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap = bmp
                LibFreeRDP.updateGraphics(inst, bmp, 0, 0, width, height)
                if (!connected) {
                    connected = true
                    onProgress("Connected — ${width}x$height")
                    onConnected(width, height)
                }
            }

            override fun OnRemoteClipboardChanged(data: String?) {}
        })

        // Build the connection as a freerdp:// URI — see LibFreeRDP.setConnectionInfo(Uri)'s doc:
        // each query parameter becomes a `/key:value` (or `/key`/`-key`/`+key`) FreeRDP CLI flag.
        // Deliberately NOT passing "cert=ignore" — certificate trust is handled explicitly via
        // decideCertificateTrust()/RdpCertStore instead (see the class doc for why).
        val uriBuilder = Uri.Builder()
            .scheme("freerdp")
            .encodedAuthority(buildAuthority())
            .appendQueryParameter("size", "1280x800") // renegotiated via OnGraphicsResize anyway
            .appendQueryParameter("bpp", "32")
            .appendQueryParameter("kbd", "unicode:on")
            .appendQueryParameter("clipboard", "")
            .appendQueryParameter("sec", "nla") // most modern Windows hosts require NLA by default

        if (!password.isNullOrEmpty()) uriBuilder.appendQueryParameter("p", password)

        val uri = uriBuilder.build()
        onProgress("Connecting to $host:$port…")
        val ok = LibFreeRDP.setConnectionInfo(context, inst, uri)
        if (!ok) {
            onDisconnected("Failed to parse connection parameters")
            return
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

    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val flags = PTR_FLAGS_MOVE or if (buttonMask and 1 != 0) (PTR_FLAGS_DOWN or PTR_FLAGS_BUTTON1) else 0
        runCatching { LibFreeRDP.sendCursorEvent(inst, x, y, flags) }
    }

    fun sendKeyEvent(keyCode: Int, unicodeChar: Int, down: Boolean) {
        when (val mapped = RdpKeycode.map(keyCode, unicodeChar)) {
            is RdpKeycode.Mapped.Unicode -> runCatching { LibFreeRDP.sendUnicodeKeyEvent(inst, mapped.codepoint, down) }
            is RdpKeycode.Mapped.Scancode -> runCatching { LibFreeRDP.sendKeyEvent(inst, mapped.code, down) }
            RdpKeycode.Mapped.None -> {}
        }
    }

    private fun blitToSurface() {
        val bmp = bitmap ?: return
        val surface = targetSurface ?: return
        if (!surface.isValid) return
        try {
            val canvas = surface.lockCanvas(null) ?: return
            try {
                canvas.drawBitmap(bmp, null, Rect(0, 0, canvas.width, canvas.height), null)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.w(TAG, "blitToSurface failed: ${e.message}")
        }
    }
}
