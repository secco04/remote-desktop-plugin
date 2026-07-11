package de.lobianco.saftssh.remotedesktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import de.lobianco.saftssh.remotedesktop.rdp.RdpCertStore
import de.lobianco.saftssh.remotedesktop.rdp.RdpClient
import de.lobianco.saftssh.remotedesktop.spice.SpiceClient
import de.lobianco.saftssh.remotedesktop.vnc.AndroidKeysym
import de.lobianco.saftssh.remotedesktop.vnc.VncClient

private const val TAG = "RemoteDesktopSession"
private const val NOTIF_CHANNEL_ID = "remote_desktop_session"
private const val NOTIF_ID = 2001

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
 * SPICE is backed by [SpiceClient], a thin wrapper around [com.undatech.opaque.SpiceCommunicator]
 * (this plugin's own minimal native bridge to the vendored `libspice.so` — see that class's doc
 * for why it's a from-scratch rewrite, not a copy, of iiordanov/remote-desktop-clients' own class
 * of the same name/package).
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

    // A plain bound service has no priority protection: once BOTH the main app and this plugin
    // are backgrounded (or the screen turns off, which backgrounds the main app's Activity the
    // same way), the OS kills this process under memory pressure — confirmed as the cause of the
    // "connection drops when backgrounded/screen off" report, since without this the plugin
    // process had nothing keeping its priority up while the client sockets/render threads it owns
    // needed to keep running. Same pattern as LinuxSessionService.promoteToForeground.

    private fun ensureNotifChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL_ID, "Remote desktop session", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Keeps the remote desktop session running in the background" }
            )
        }
    }

    private fun promoteToForeground() {
        ensureNotifChannel()
        val notification = Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Remote desktop session running")
            .setContentText("Tap to return to LobiShell")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setOngoing(true)
            .apply {
                packageManager.getLaunchIntentForPackage("de.lobianco.saftssh")?.let { launch ->
                    setContentIntent(
                        PendingIntent.getActivity(
                            this@RemoteDesktopSessionService, 0, launch,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
            }
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
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
            fastQuality: Boolean, overrideWidth: Int, overrideHeight: Int,
            tlsPort: Int, proxy: String?, caCert: String?, hostSubject: String?,
            vncWsUrl: String?, vncWsCookie: String?,
        ): IRemoteDesktopSession? {
            if (!isCallerAuthorized()) return null
            return try {
                requireNotNull(protocol) { "protocol is required (\"vnc\" | \"rdp\" | \"spice\")" }
                requireNotNull(host) { "host is required" }
                requireNotNull(surface) { "surface is required" }
                val session = buildSession(
                    protocol, host, port, username, password, surface, width, height, callback,
                    fastQuality, overrideWidth, overrideHeight,
                    tlsPort, proxy, caCert, hostSubject,
                    vncWsUrl, vncWsCookie,
                )
                openSessions.add(session)
                promoteToForeground()
                Log.i(TAG, "createSession: protocol=$protocol host=$host:$port fast=$fastQuality override=${overrideWidth}x$overrideHeight")
                session
            } catch (e: Throwable) {
                // Throwable, not Exception: a native library failing to load (e.g. libspice.so /
                // libgstreamer_android.so, ~65MB and genuinely untested on real device hardware so
                // far) throws UnsatisfiedLinkError, an Error subtype that a plain `catch (Exception)`
                // would let propagate straight past this AIDL Binder call and likely kill the whole
                // plugin process instead of just reporting a clean connection failure.
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
        fastQuality: Boolean, overrideWidth: Int, overrideHeight: Int,
        tlsPort: Int, proxy: String?, caCert: String?, hostSubject: String?,
        vncWsUrl: String?, vncWsCookie: String?,
    ): SessionImpl {
        val session = SessionImpl()
        when (protocol) {
            "vnc" -> {
                val resolvedPort = if (port > 0) port else 5900
                val client = VncClient(
                    host = host,
                    port = resolvedPort,
                    password = password,
                    fastQuality = fastQuality,
                    onProgress = { line -> runCatching { callback?.onProgress(line) } },
                    onConnected = { w, h ->
                        runCatching { callback?.onConnected() }
                        Log.i(TAG, "VNC connected: ${w}x$h")
                    },
                    onDisconnected = { reason ->
                        runCatching { callback?.onDisconnected(reason) }
                        session.destroyInternal()
                    },
                    // Proxmox VE fallback (SPICE not available on the VM) — see VncClient's own
                    // doc for why vncWsUrl selects a WebSocket transport instead of a raw socket.
                    // caCert is reused here to carry the pinned cert fingerprint, not a PEM (see
                    // this AIDL method's doc for why).
                    wsUrl = vncWsUrl,
                    wsCookie = vncWsCookie,
                    wsCertFingerprint = caCert,
                )
                client.targetSurface = surface
                session.vncClient = client
                client.start()
            }
            "rdp" -> {
                val resolvedPort = if (port > 0) port else 3389
                // RDP alone lets the client request a specific session resolution — an explicit
                // override (from the connection-settings menu) takes precedence over the Surface's
                // own viewport size.
                val requestedWidth = if (overrideWidth > 0) overrideWidth else width
                val requestedHeight = if (overrideHeight > 0) overrideHeight else height
                val client = RdpClient(
                    context = this,
                    host = host,
                    port = resolvedPort,
                    username = username,
                    password = password,
                    initialWidth = requestedWidth,
                    initialHeight = requestedHeight,
                    fastQuality = fastQuality,
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
                try {
                    client.start()
                } catch (e: Exception) {
                    // start() throws on a synchronous setup failure (e.g. malformed connection
                    // parameters) before the connect thread ever runs — free the native FreeRDP
                    // instance it already allocated instead of leaking it, then let the caller
                    // (createSession's own try/catch) turn this into a clean null return.
                    runCatching { client.stop() }
                    throw e
                }
            }
            "spice" -> {
                val resolvedPort = if (port > 0) port else 5900
                val client = SpiceClient(
                    context = this,
                    host = host,
                    port = resolvedPort,
                    password = password,
                    onProgress = { line -> runCatching { callback?.onProgress(line) } },
                    onConnected = { w, h ->
                        runCatching { callback?.onConnected() }
                        Log.i(TAG, "SPICE connected: ${w}x$h")
                    },
                    onDisconnected = { reason ->
                        runCatching { callback?.onDisconnected(reason) }
                        session.destroyInternal()
                    },
                    tlsPort = tlsPort,
                    proxy = proxy,
                    caCert = caCert,
                    hostSubject = hostSubject,
                )
                client.targetSurface = surface
                session.spiceClient = client
                try {
                    client.start()
                } catch (e: Exception) {
                    // start() throws synchronously if another SPICE session is already active in
                    // this process (see SpiceCommunicator's class doc) — nothing was allocated in
                    // that case, but stop() is still safe/idempotent to call.
                    runCatching { client.stop() }
                    throw e
                }
            }
            else -> throw UnsupportedOperationException(
                "${protocol.uppercase()} isn't a recognized Remote Desktop protocol. Use vnc, rdp, or spice."
            )
        }
        return session
    }

    // ── Session implementation ────────────────────────────────────────────

    private inner class SessionImpl : IRemoteDesktopSession.Stub() {
        var vncClient: VncClient? = null
        var rdpClient: RdpClient? = null
        var spiceClient: SpiceClient? = null
        // Diagnostic: log only the first pointer/key event that reaches the plugin, so we can
        // confirm input actually crosses the Binder and reaches a client without spamming the log
        // on every touch move.
        @Volatile private var loggedFirstPointer = false
        @Volatile private var loggedFirstKey = false

        override fun resize(width: Int, height: Int) {
            // Neither backend supports live mid-session resize yet — VNC's RFB profile here has
            // none, and RDP's negotiated resolution comes from OnGraphicsResize instead. Both
            // blitToSurface() implementations already scale to whatever size the Surface
            // currently is, so a caller-side resize just needs the next frame to notice.
        }

        override fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
            if (!loggedFirstPointer) {
                loggedFirstPointer = true
                Log.i(TAG, "First pointer event reached plugin: ($x,$y) mask=$buttonMask vnc=${vncClient != null} rdp=${rdpClient != null} spice=${spiceClient != null}")
            }
            try {
                vncClient?.sendPointerEvent(x, y, buttonMask)
                rdpClient?.sendPointerEvent(x, y, buttonMask)
                spiceClient?.sendPointerEvent(x, y, buttonMask)
            } catch (e: Exception) {
                Log.w(TAG, "sendPointerEvent handling failed", e)
            }
        }

        override fun setZoom(scale: Float, panX: Float, panY: Float) {
            try {
                vncClient?.setZoom(scale, panX, panY)
                rdpClient?.setZoom(scale, panX, panY)
                spiceClient?.setZoom(scale, panX, panY)
            } catch (e: Exception) {
                Log.w(TAG, "setZoom handling failed", e)
            }
        }

        override fun sendScroll(steps: Int) {
            try {
                vncClient?.sendScroll(steps)
                rdpClient?.sendScroll(steps)
                spiceClient?.sendScroll(steps)
            } catch (e: Exception) {
                Log.w(TAG, "sendScroll handling failed", e)
            }
        }

        override fun sendKeyEvent(keyCode: Int, unicodeChar: Int, down: Boolean, metaState: Int) {
            if (!loggedFirstKey) {
                loggedFirstKey = true
                Log.i(TAG, "First key event reached plugin: keyCode=$keyCode unicode=$unicodeChar down=$down")
            }
            try {
                vncClient?.let { it.sendKeyEvent(AndroidKeysym.map(keyCode, unicodeChar), down) }
                rdpClient?.sendKeyEvent(keyCode, unicodeChar, down)
                spiceClient?.sendKeyEvent(keyCode, unicodeChar, down)
            } catch (e: Exception) {
                Log.w(TAG, "sendKeyEvent handling failed", e)
            }
        }

        override fun isAlive(): Boolean =
            vncClient?.isAlive() ?: rdpClient?.isAlive() ?: spiceClient?.isAlive() ?: false

        override fun destroy() {
            destroyInternal()
        }

        fun destroyInternal() {
            val wasSpice = spiceClient != null
            openSessions.remove(this)
            demoteFromForegroundIfIdle()
            runCatching { vncClient?.stop() }
            runCatching { rdpClient?.stop() }
            runCatching { spiceClient?.stop() }
            if (wasSpice && openSessions.isEmpty()) {
                // libspice.so doesn't fully reset its own native globals when a session ends via
                // StartSessionFromVvFile — confirmed by reading android-service.c directly: unlike
                // Java_..._SpiceClientConnect's own JNI wrapper, which explicitly nulls out
                // jvm/jni_connector_class/the cached method IDs at the end, the
                // StartSessionFromVvFile wrapper does NOT. Combined with SpiceClientDisconnect()
                // only SCHEDULING its teardown on the native glib main loop
                // (g_main_context_invoke) rather than completing it synchronously before
                // returning, a second SPICE connect arriving before that teardown has actually
                // drained races on shared native state (global_display and friends). This is
                // exactly what produced an on-device SIGSEGV when connecting to a second VM
                // shortly after a first SPICE session ended — confirmed via logcat: the crash
                // landed mid channel_destroy, immediately after "Starting main loop". The prebuilt
                // .so can't be patched, so the safe mitigation is: never let two SPICE sessions
                // share one process — kill this one now so the next createSession() (any protocol)
                // starts against a freshly loaded native library. Harmless for VNC/RDP: this only
                // runs once ALL sessions (not just this one) are closed.
                Thread({
                    // Give the oneway destroy() call's Binder transaction and this log line time
                    // to actually flush before the process disappears out from under them.
                    Thread.sleep(300)
                    Log.i(TAG, "Restarting plugin process after a SPICE session (see destroyInternal's doc)")
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, "SpiceProcessRestart").apply { isDaemon = true; start() }
            }
        }
    }
}
