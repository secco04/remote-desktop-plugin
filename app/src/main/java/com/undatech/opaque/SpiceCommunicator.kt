package com.undatech.opaque

import android.graphics.Bitmap

/**
 * Minimal native bridge to the vendored `libspice.so` (see this repo's LICENSE for where it came
 * from and full attribution). This is NOT a copy of iiordanov/remote-desktop-clients' own
 * `com.undatech.opaque.SpiceCommunicator` (GPL-3.0, extends the Activity-coupled
 * RfbConnectable/Viewable framework this plugin deliberately avoids — see
 * [de.lobianco.saftssh.remotedesktop.spice.SpiceClient]'s class doc for why) — it's a from-scratch,
 * headless rewrite that keeps only what the compiled native library actually requires:
 *
 * The package name `com.undatech.opaque` and class name `SpiceCommunicator` are NOT arbitrary —
 * `libspice.so`'s JNI entry points are compiled as `Java_com_undatech_opaque_SpiceCommunicator_*`
 * (classic auto-generated JNI symbol naming, confirmed by reading android-io.c/android-service.c in
 * the vendored `remote-desktop-clients` submodule, not guessed), and its `JNI_OnLoad` looks up this
 * exact class via `FindClass("com/undatech/opaque/SpiceCommunicator")` to resolve six required
 * static callback methods via `GetStaticMethodID` (see the companion object below) — renaming or
 * moving either would break both.
 *
 * Unlike FreeRDP's `RegisterNatives()`-based atomic method table (see LibFreeRDP.java's doc),
 * `android-service.c`'s `JNI_OnLoad` here does NOT batch-register native methods — each `external
 * fun` below is resolved lazily, independently, by the standard JNI symbol-naming convention. This
 * means only the methods actually declared here need to exist; the original upstream class's extra
 * native methods (CreateOvirtSession, USB attach/detach) are simply never resolved since nothing
 * calls them — omitting them is safe, not a compatibility risk. [StartSessionFromVvFile] IS kept
 * (unlike the others) because it's the only entry point whose underlying C function
 * (`spiceClientConnectVv` → `spice_session_setup_from_vv`) actually reads a "proxy" property off
 * the session — [SpiceClientConnect]'s own C implementation hardcodes a NULL proxy argument, so
 * Proxmox VE's spiceproxy CONNECT-tunnel (see [de.lobianco.saftssh.remotedesktop.spice.SpiceClient])
 * is reachable ONLY through the .vv-file path, confirmed by reading android-service.c's
 * `Java_..._SpiceClientConnect` vs. `Java_..._StartSessionFromVvFile` bodies directly, not guessed.
 *
 * Every `SpiceClientConnect`-driven session in this process shares ONE global native SPICE
 * connection (`global_display` and friends in the vendored C source are plain global/static
 * variables, not per-instance state, and the callback lookups below are static with no instance
 * disambiguation) — this native library only ever supports one concurrent SPICE session per
 * process. [de.lobianco.saftssh.remotedesktop.spice.SpiceClient] enforces this via [activeListener].
 */
class SpiceCommunicator {
    companion object {
        init {
            System.loadLibrary("gstreamer_android")
            System.loadLibrary("spice")
        }

        /** The one live session's callback sink — see the class doc for why only one can exist. */
        @Volatile private var activeListener: Listener? = null

        /** Returns false if another session is already active (caller must refuse to connect). */
        fun attach(listener: Listener): Boolean {
            if (activeListener != null) return false
            activeListener = listener
            return true
        }

        fun detach(listener: Listener) {
            if (activeListener === listener) activeListener = null
        }

        // ── Static callbacks native code resolves via GetStaticMethodID — exact method names and
        // JVM signatures below are load-bearing, not stylistic (see class doc). ──

        @JvmStatic
        fun OnSettingsChanged(inst: Int, width: Int, height: Int, bpp: Int) {
            activeListener?.onSettingsChanged(width, height, bpp)
        }

        @JvmStatic
        fun OnGraphicsUpdate(inst: Int, x: Int, y: Int, width: Int, height: Int) {
            activeListener?.onGraphicsUpdate(x, y, width, height)
        }

        @JvmStatic
        fun OnMouseUpdate(x: Int, y: Int) {
            activeListener?.onMouseUpdate(x, y)
        }

        @JvmStatic
        fun OnMouseMode(relative: Boolean) {
            activeListener?.onMouseMode(relative)
        }

        @JvmStatic
        fun ShowMessage(message: String) {
            activeListener?.onShowMessage(message)
        }

        @JvmStatic
        fun OnRemoteClipboardChanged(data: String) {
            activeListener?.onRemoteClipboardChanged(data)
        }

        /** Looked up (GetStaticMethodID) and called unconditionally by
         *  Java_..._StartSessionFromVvFile's own error path if the .vv file fails to parse —
         *  without this declared, that call site's method-ID lookup would return null and the
         *  subsequent CallStaticVoidMethod would crash the native process outright rather than
         *  cleanly failing the connection. Not part of the six-callback set [SpiceClientConnect]
         *  needs; only reachable via [StartSessionFromVvFile]. */
        @JvmStatic
        fun sendMessageWithText(messageId: Int, messageText: String) {
            activeListener?.onShowMessage("[$messageId] $messageText")
        }
    }

    interface Listener {
        /** Fires once per connect with the negotiated desktop size — allocate the framebuffer here
         *  (mirrors RdpClient's OnSettingsChanged, same reasoning). */
        fun onSettingsChanged(width: Int, height: Int, bpp: Int)

        /** A dirty rect is ready: call [UpdateBitmap] to have native code paint it into your
         *  Bitmap, then present it. */
        fun onGraphicsUpdate(x: Int, y: Int, width: Int, height: Int)
        fun onMouseUpdate(x: Int, y: Int)
        fun onMouseMode(relative: Boolean)
        fun onShowMessage(message: String)
        fun onRemoteClipboardChanged(data: String)
    }

    /** Blocks the calling thread for the session's lifetime (runs libspice's own main loop
     *  internally) — call from a dedicated background thread, same as LibFreeRDP.connect().
     *  Returns a native status code; a non-zero-length run before this returns generally means the
     *  connection was rejected or dropped. [caFile]/[caCert]/[certSubj] are for TLS — null/empty for
     *  a plain (unencrypted) SPICE port, which is what most self-hosted setups (e.g. Proxmox VE's
     *  spiceproxy) use by default. */
    external fun SpiceClientConnect(
        ip: String, port: String, tport: String, password: String,
        caFile: String?, caCert: String?, certSubj: String?, sound: Boolean,
    ): Int

    external fun SpiceClientDisconnect()

    /** [pointerMask] follows SPICE's own button numbering (1=left, 2=middle, 3=right, 4=scroll-up,
     *  5=scroll-down as momentary clicks), OR'd with 0x8000 for "pressed" — see
     *  [de.lobianco.saftssh.remotedesktop.spice.SpiceClient] for the exact semantics, verified
     *  against the vendored `RemoteSpicePointer.java` reference, not guessed. */
    external fun SpiceButtonEvent(x: Int, y: Int, metaState: Int, pointerMask: Int, relative: Boolean)

    /** [hardwareKeycode] is a PC/AT Scan Code Set 1 code, with E0-extended keys encoded as
     *  (scancode | 0x100) — see [de.lobianco.saftssh.remotedesktop.spice.SpiceKeycode]. */
    external fun SpiceKeyEvent(keyDown: Boolean, hardwareKeycode: Int)

    /** Paints the dirty rect [x],[y],[width]x[height] directly into [bitmap]'s pixels (native code
     *  does the internal BGRX→RGBA channel swizzle itself) — [bitmap] must be ARGB_8888 and at
     *  least as large as the framebuffer allocated in response to onSettingsChanged. */
    external fun UpdateBitmap(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int)

    /** Best-effort resolution request — the server may ignore it. */
    external fun SpiceRequestResolution(width: Int, height: Int)

    /** Connects using a virt-viewer ".vv" config file at [fileName] (a real filesystem path —
     *  parsed via GLib's GKeyFile, standard `[virt-viewer]` INI format) instead of discrete
     *  host/port/password args. This is the ONLY path that honours a "proxy" entry (see class doc)
     *  — required for Proxmox VE's spiceproxy CONNECT-tunnel. Same blocking-call contract as
     *  [SpiceClientConnect]. */
    external fun StartSessionFromVvFile(fileName: String, sound: Boolean): Int
}
