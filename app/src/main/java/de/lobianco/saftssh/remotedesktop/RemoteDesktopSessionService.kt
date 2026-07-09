package de.lobianco.saftssh.remotedesktop

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.view.Surface
import de.lobianco.saftssh.remotedesktop.rdp.RdpCertStore
import de.lobianco.saftssh.remotedesktop.rdp.RdpClient
import de.lobianco.saftssh.remotedesktop.vnc.AndroidKeysym
import de.lobianco.saftssh.remotedesktop.vnc.VncClient

private const val TAG = "RemoteDesktopSession"

/** Only the main LobiShell app may use this service. NOT enforced via a custom Android
 *  permission — see the Linux Plugin's identical guard for why a "dangerous" custom permission's
 *  grant dialog renders as a generic, alarming "perform an unknown action" on modern Android
 *  regardless of our own android:label. Checking the caller's package identity directly avoids
 *  that dialog while still blocking unrelated apps.
 *
 *  IMPORTANT: this check must live in the AIDL Stub's method bodies (createSession below), NOT in
 *  Service.onBind() — onBind() is a local lifecycle callback dispatched by this process's own
 *  ActivityThread, not a live incoming Binder transaction, so Binder.getCallingUid() there just
 *  returns THIS process's own uid (confirmed on-device: it resolved to this plugin's own package,
 *  never the caller's — silently rejecting every real client). AIDL Stub methods, in contrast,
 *  genuinely execute inside the calling transaction, where getCallingUid() is meaningful. */
private val ALLOWED_CALLER_PACKAGES = setOf("de.lobianco.saftssh")

/**
 * Bound AIDL service exposing [IRemoteDesktopSessionService].
 *
 * VNC is backed by [VncClient], a from-scratch minimal RFB implementation — NOT the vendored
 * `remote-desktop-clients` submodule. That fork's VNC classes turned out to be inseparable from a
 * full Activity + View hierarchy (its `RemoteCanvas` implements a 30-method `Viewable` interface
 * covering pan/zoom/toolbar/toast concerns that only make sense with a real window), which made
 * blind adaptation into a headless bound service too likely to produce code that looks wired up
 * but silently doesn't work. RFB itself is small and well-specified (RFC 6143), so a minimal
 * from-scratch client was the more honest path to something that actually connects and renders.
 *
 * RDP is backed by [RdpClient], a thin wrapper around FreeRDP's own vendored upstream JNI bridge
 * (com.freerdp.freerdpcore.services.LibFreeRDP) — see that file's doc for why its native
 * libraries are prebuilt binaries rather than built from source here.
 *
 * SPICE is NOT implemented — it needs spice-gtk, a native runtime this plugin doesn't bundle.
 * [buildSession] fails it with a clear, honest error rather than pretending to connect.
 *
 * Session lifecycle mirrors the Linux Plugin's LinuxSessionService: a foreground service while
 * any session is open, each session owns its own resources and is torn down independently.
 */
class RemoteDesktopSessionService : Service() {

    private val openSessions = java.util.concurrent.CopyOnWriteArrayList<SessionImpl>()

    override fun onBind(intent: Intent?): IBinder = serviceStub.asBinder()

    /** True if the app on the other end of the CURRENT incoming Binder transaction is an
     *  authorized caller. Must only be called from inside an AIDL Stub method body. */
    private fun isCallerAuthorized(): Boolean {
        val callingUid = Binder.getCallingUid()
        val callerPackages = packageManager.getPackagesForUid(callingUid) ?: arrayOf()
        val authorized = callerPackages.any { it in ALLOWED_CALLER_PACKAGES }
        if (!authorized) {
            Log.w(TAG, "Rejected call from unauthorized caller uid=$callingUid packages=${callerPackages.joinToString()}")
        }
        return authorized
    }

    override fun onDestroy() {
        openSessions.forEach { runCatching { it.destroyInternal() } }
        openSessions.clear()
        super.onDestroy()
    }

    private fun promoteToForeground() {
        // TODO: same NotificationChannel + startForeground(specialUse) pattern as
        // LinuxSessionService.promoteToForeground — deferred until this is validated on-device;
        // not required for the plugin process to survive a short foreground session/testing pass.
    }

    private fun demoteFromForegroundIfIdle() {
        if (openSessions.isEmpty()) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ── AIDL stub ──────────────────────────────────────────────────────────

    private val serviceStub = object : IRemoteDesktopSessionService.Stub() {
        override fun createSession(
            protocol: String?, host: String?, port: Int, username: String?, password: String?,
            surface: Surface?, width: Int, height: Int,
            callback: IRemoteDesktopSessionCallback?,
        ): IRemoteDesktopSession? {
            if (!isCallerAuthorized()) return null
            return try {
                requireNotNull(protocol) { "protocol is required (\"vnc\" | \"rdp\" | \"spice\")" }
                requireNotNull(host) { "host is required" }
                requireNotNull(surface) { "surface is required" }
                val session = buildSession(protocol, host, port, username, password, surface, width, height, callback)
                openSessions.add(session)
                promoteToForeground()
                Log.i(TAG, "createSession: protocol=$protocol host=$host:$port")
                session
            } catch (e: Exception) {
                Log.e(TAG, "createSession failed", e)
                runCatching { callback?.onProgress("Error: ${e.message}") }
                null
            }
        }

        override fun trustRdpCertificate(host: String?, port: Int, fingerprint: String?) {
            if (!isCallerAuthorized()) return
            if (host == null || fingerprint == null) return
            RdpCertStore(this@RemoteDesktopSessionService).trust(host, port, fingerprint)
            Log.i(TAG, "Trusted RDP certificate for $host:$port")
        }
    }

    // ── Session construction ──────────────────────────────────────────────

    private fun buildSession(
        protocol: String, host: String, port: Int, username: String?, password: String?,
        surface: Surface, width: Int, height: Int,
        callback: IRemoteDesktopSessionCallback?,
    ): SessionImpl {
        val session = SessionImpl()
        when (protocol) {
            "vnc" -> {
                val resolvedPort = if (port > 0) port else 5900
                val client = VncClient(
                    host = host,
                    port = resolvedPort,
                    password = password,
                    onProgress = { line -> runCatching { callback?.onProgress(line) } },
                    onConnected = { w, h ->
                        runCatching { callback?.onConnected() }
                        Log.i(TAG, "VNC connected: ${w}x$h")
                    },
                    onDisconnected = { reason ->
                        runCatching { callback?.onDisconnected(reason) }
                        session.destroyInternal()
                    },
                )
                client.targetSurface = surface
                session.vncClient = client
                client.start()
            }
            "rdp" -> {
                val resolvedPort = if (port > 0) port else 3389
                val client = RdpClient(
                    context = this,
                    host = host,
                    port = resolvedPort,
                    username = username,
                    password = password,
                    onProgress = { line -> runCatching { callback?.onProgress(line) } },
                    onConnected = { w, h ->
                        runCatching { callback?.onConnected() }
                        Log.i(TAG, "RDP connected: ${w}x$h")
                    },
                    onDisconnected = { reason ->
                        runCatching { callback?.onDisconnected(reason) }
                        session.destroyInternal()
                    },
                )
                client.targetSurface = surface
                session.rdpClient = client
                client.start()
            }
            else -> throw UnsupportedOperationException(
                "${protocol.uppercase()} isn't available in this build — it needs a native " +
                    "runtime (spice-gtk) this plugin doesn't currently bundle. VNC and RDP work."
            )
        }
        return session
    }

    // ── Session implementation ────────────────────────────────────────────

    private inner class SessionImpl : IRemoteDesktopSession.Stub() {
        var vncClient: VncClient? = null
        var rdpClient: RdpClient? = null

        override fun resize(width: Int, height: Int) {
            // Neither backend supports live mid-session resize yet — VNC's RFB profile here has
            // none, and RDP's negotiated resolution comes from OnGraphicsResize instead. Both
            // blitToSurface() implementations already scale to whatever size the Surface
            // currently is, so a caller-side resize just needs the next frame to notice.
        }

        override fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
            vncClient?.sendPointerEvent(x, y, buttonMask)
            rdpClient?.sendPointerEvent(x, y, buttonMask)
        }

        override fun sendKeyEvent(keyCode: Int, unicodeChar: Int, down: Boolean, metaState: Int) {
            vncClient?.let { it.sendKeyEvent(AndroidKeysym.map(keyCode, unicodeChar), down) }
            rdpClient?.sendKeyEvent(keyCode, unicodeChar, down)
        }

        override fun isAlive(): Boolean = vncClient?.isAlive() ?: rdpClient?.isAlive() ?: false

        override fun destroy() {
            destroyInternal()
        }

        fun destroyInternal() {
            openSessions.remove(this)
            demoteFromForegroundIfIdle()
            runCatching { vncClient?.stop() }
            runCatching { rdpClient?.stop() }
        }
    }
}
