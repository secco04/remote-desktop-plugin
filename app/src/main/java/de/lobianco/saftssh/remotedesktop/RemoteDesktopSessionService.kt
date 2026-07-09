package de.lobianco.saftssh.remotedesktop

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

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
 * Bound AIDL service exposing [IRemoteDesktopSessionService]. Each session drives a headless
 * instance of the vendored client library's connection + rendering pipeline (a fork of
 * iiordanov/bVNC — see ../../../../../README.md and LICENSE) and draws the remote framebuffer
 * directly onto the Surface the caller supplies.
 *
 * NOT YET WIRED to the vendored library — see the TODOs in [buildSession]. The vendored
 * `bVNC`/`remoteClientLib` modules aren't resolvable as a Gradle dependency from this module yet
 * (needs an Android Studio session to set up correctly — see the root README's "Wiring the
 * vendored library" section, same class of constraint as the Linux Plugin's native-lib builds).
 *
 * Session lifecycle design mirrors the Linux Plugin's LinuxSessionService: a foreground service
 * while any session is open (keeps the process alive while backgrounded), each session owns its
 * own resources and is torn down independently.
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
        // LinuxSessionService.promoteToForeground — deferred until a real session type exists to
        // promote/demote around.
    }

    private fun demoteFromForegroundIfIdle() {
        if (openSessions.isEmpty()) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ── AIDL stub ──────────────────────────────────────────────────────────

    private val serviceStub = object : IRemoteDesktopSessionService.Stub() {
        override fun createSession(
            protocol: String?, host: String?, port: Int, username: String?, password: String?,
            surface: android.view.Surface?, width: Int, height: Int,
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
    }

    // ── Session construction ──────────────────────────────────────────────

    private fun buildSession(
        protocol: String, host: String, port: Int, username: String?, password: String?,
        surface: android.view.Surface, width: Int, height: Int,
        callback: IRemoteDesktopSessionCallback?,
    ): SessionImpl {
        // TODO: this is the actual integration point with the vendored library, once it's
        // resolvable from this module:
        //   1. Instantiate the vendored library's connection class for [protocol] (VNC → RfbProto
        //      / RemoteVncConnection.kt, RDP/SPICE → their respective classes under
        //      com.iiordanov.bVNC — see remote-desktop-clients/bVNC/src/main/java/com/iiordanov/bVNC/).
        //   2. Host a RemoteCanvas (extends AppCompatImageView) — or a purpose-built subclass —
        //      OFFSCREEN inside this service (no Activity/window needed for an ImageView to
        //      render into an internal Bitmap; see RemoteCanvas.getBitmap()/reDraw()).
        //   3. On every reDraw() callback, blit RemoteCanvas.getBitmap() onto [surface] via
        //      surface.lockHardwareCanvas() / Canvas.drawBitmap() / surface.unlockCanvasAndPost()
        //      — this is what avoids sending framebuffer pixel data over Binder per frame.
        //   4. sendPointerEvent/sendKeyEvent (in SessionImpl below) should dispatch into the
        //      hosted RemoteCanvas's existing input handling (it already implements this for
        //      VNC/RDP/SPICE) rather than reimplementing pointer/key-to-protocol translation here.
        callback?.onProgress("Remote desktop plugin: $protocol backend not wired yet")
        throw UnsupportedOperationException(
            "RemoteDesktopSessionService.buildSession: vendored library integration not implemented yet"
        )
    }

    // ── Session implementation ────────────────────────────────────────────

    private inner class SessionImpl(
        // TODO: hold the hosted RemoteCanvas instance + vendored connection object here once
        // buildSession() constructs them.
    ) : IRemoteDesktopSession.Stub() {

        override fun resize(width: Int, height: Int) {
            // TODO: forward to the hosted RemoteCanvas / connection.
        }

        override fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
            // TODO: forward to the hosted RemoteCanvas's pointer-input handling.
        }

        override fun sendKeyEvent(keyCode: Int, down: Boolean, metaState: Int) {
            // TODO: forward to the hosted RemoteCanvas's key-input handling.
        }

        override fun isAlive(): Boolean = true // TODO: reflect actual connection state

        override fun destroy() {
            destroyInternal()
        }

        fun destroyInternal() {
            openSessions.remove(this)
            demoteFromForegroundIfIdle()
            // TODO: tear down the vendored connection + release the Surface.
        }
    }
}
