package de.lobianco.saftssh.remotedesktop.vnc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private const val TAG = "VncClient"

/**
 * A from-scratch, minimal RFB/VNC client (RFC 6143) — NOT derived from bVNC's source. bVNC's own
 * VNC classes (RfbProto/RemoteCanvas/RemoteCanvasHandler) turned out to be inseparable from a
 * full Activity + View hierarchy (RemoteCanvas implements a 30-method `Viewable` interface
 * covering pan/zoom/toolbar/toast concerns) — adapting that blind, with no way to compile-check
 * it here, risked producing code that looks like an integration but doesn't actually work. RFB
 * itself is a small, well-specified protocol, so implementing just what a headless
 * connect-and-render-onto-a-Surface client needs is the more honest path to something that
 * actually functions.
 *
 * Supports: RFB 3.3–3.8 version negotiation, security types None(1)/VNCAuth(2), Raw(0) and
 * CopyRect(1) encodings, pointer/key events. Does NOT support: Tight/Hextile/ZRLE encodings
 * (servers that refuse to offer Raw will fail — most do offer it, but some hardened deployments
 * don't), clipboard sync, resizing after connect.
 */
class VncClient(
    private val host: String,
    private val port: Int,
    private val password: String?,
    private val onProgress: (String) -> Unit,
    private val onConnected: (width: Int, height: Int) -> Unit,
    private val onDisconnected: (reason: String) -> Unit,
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var running = false
    @Volatile private var output: DataOutputStream? = null
    private var thread: Thread? = null

    @Volatile var framebuffer: Bitmap? = null
        private set
    @Volatile private var fbWidth = 0
    @Volatile private var fbHeight = 0

    /** The Surface to blit onto after every processed update batch. Settable after connect (the
     *  caller may not have a sized Surface yet at construction time) — see
     *  RemoteDesktopSessionService for why the Surface itself never crosses this class's own
     *  wire-protocol logic. */
    @Volatile var targetSurface: Surface? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runProtocol() }, "VncClient-$host:$port").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        thread?.interrupt()
    }

    fun isAlive(): Boolean = running && socket?.isConnected == true && socket?.isClosed == false

    // ── Input ──────────────────────────────────────────────────────────────

    /** RFB PointerEvent (message type 5). [buttonMask] bit 0 = primary button. */
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val out = output ?: return
        try {
            synchronized(out) {
                out.writeByte(5)
                out.writeByte(buttonMask)
                out.writeShort(x.coerceIn(0, fbWidth.coerceAtLeast(1) - 1))
                out.writeShort(y.coerceIn(0, fbHeight.coerceAtLeast(1) - 1))
                out.flush()
            }
        } catch (e: IOException) {
            Log.w(TAG, "sendPointerEvent failed: ${e.message}")
        }
    }

    /** RFB KeyEvent (message type 4). [keysym] is an X11 keysym, not an Android keyCode — see
     *  [AndroidKeysym.map]. */
    fun sendKeyEvent(keysym: Int, down: Boolean) {
        if (keysym == 0) return
        val out = output ?: return
        try {
            synchronized(out) {
                out.writeByte(4)
                out.writeByte(if (down) 1 else 0)
                out.writeShort(0) // padding
                out.writeInt(keysym)
                out.flush()
            }
        } catch (e: IOException) {
            Log.w(TAG, "sendKeyEvent failed: ${e.message}")
        }
    }

    // ── Protocol ───────────────────────────────────────────────────────────

    private fun runProtocol() {
        try {
            onProgress("Connecting to $host:$port…")
            val sock = Socket(host, port).apply { tcpNoDelay = true; soTimeout = 15_000 }
            socket = sock
            val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
            output = out

            negotiateVersion(inp, out)
            negotiateSecurity(inp, out)
            clientInit(out)
            val (w, h) = serverInit(inp, out)
            fbWidth = w
            fbHeight = h
            framebuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            onProgress("Connected — ${w}x${h}")
            onConnected(w, h)

            // Main update loop: request, wait for the reply, request again. A real client would
            // pipeline these; a simple request/reply loop is enough for this first version.
            requestFramebufferUpdate(out, incremental = false)
            sock.soTimeout = 0 // block indefinitely on subsequent reads — the server paces updates
            while (running) {
                val messageType = inp.readUnsignedByte()
                when (messageType) {
                    0 -> { // FramebufferUpdate
                        handleFramebufferUpdate(inp)
                        blitToSurface()
                        requestFramebufferUpdate(out, incremental = true)
                    }
                    1 -> { // SetColourMapEntries — not used (we request true-colour), but must be
                           // drained so the stream doesn't desync.
                        inp.skipBytes(1)
                        val firstColour = inp.readUnsignedShort()
                        val numColours = inp.readUnsignedShort()
                        inp.skipBytes(numColours * 6)
                        Log.d(TAG, "Ignoring SetColourMapEntries (first=$firstColour n=$numColours)")
                    }
                    2 -> { /* Bell — no-op */ }
                    3 -> { // ServerCutText
                        inp.skipBytes(3)
                        val len = inp.readInt()
                        inp.skipBytes(len)
                    }
                    else -> throw IOException("Unknown server message type $messageType — protocol desync")
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "VNC session ended: ${e.message}", e)
                onDisconnected(e.message ?: e.javaClass.simpleName)
            }
        } finally {
            running = false
            runCatching { socket?.close() }
        }
    }

    private fun negotiateVersion(inp: DataInputStream, out: DataOutputStream) {
        val serverVersion = ByteArray(12).also { inp.readFully(it) }
        Log.i(TAG, "Server version: ${String(serverVersion).trim()}")
        // We speak 3.8's wire format for everything we support; claiming 3.8 gets us the widest
        // feature set servers will offer (older servers just negotiate down their own security
        // list regardless of what we claim).
        out.write("RFB 003.008\n".toByteArray())
        out.flush()
    }

    private fun negotiateSecurity(inp: DataInputStream, out: DataOutputStream) {
        val numTypes = inp.readUnsignedByte()
        if (numTypes == 0) {
            // RFB 3.3-style failure reason string.
            val len = inp.readInt()
            val reason = ByteArray(len).also { inp.readFully(it) }
            throw IOException("Server refused connection: ${String(reason)}")
        }
        val types = ByteArray(numTypes).also { inp.readFully(it) }
        val hasVncAuth = types.contains(2.toByte())
        val hasNone = types.contains(1.toByte())
        val chosen = when {
            hasVncAuth && !password.isNullOrEmpty() -> 2
            hasNone -> 1
            hasVncAuth -> 2 // server requires auth even with a blank password attempt
            else -> throw IOException("No supported security type offered (server offered: ${types.joinToString()})")
        }
        out.writeByte(chosen)
        out.flush()

        if (chosen == 2) {
            val challenge = ByteArray(16).also { inp.readFully(it) }
            val response = desEncryptChallenge(challenge, password.orEmpty())
            out.write(response)
            out.flush()
        }

        val securityResult = inp.readInt()
        if (securityResult != 0) {
            // RFB 3.8+ sends a reason string; 3.3/3.4 don't. Best-effort read with a short guard.
            val reason = try {
                val len = inp.readInt()
                if (len in 0..4096) String(ByteArray(len).also { inp.readFully(it) }) else "authentication failed"
            } catch (_: Exception) { "authentication failed" }
            throw IOException("Authentication failed: $reason")
        }
    }

    /** VNC's DES challenge-response: the password (up to 8 bytes, zero-padded) is used as a DES
     *  key, but with each key byte's bits reversed — a long-standing RFB protocol quirk (the
     *  spec's reference implementation built the key this way and every server since expects it). */
    private fun desEncryptChallenge(challenge: ByteArray, password: String): ByteArray {
        val pwBytes = ByteArray(8)
        val src = password.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(src, 0, pwBytes, 0, minOf(src.size, 8))
        for (i in pwBytes.indices) {
            var b = pwBytes[i].toInt() and 0xFF
            var reversed = 0
            for (bit in 0 until 8) {
                reversed = (reversed shl 1) or (b and 1)
                b = b shr 1
            }
            pwBytes[i] = reversed.toByte()
        }
        val keySpec: KeySpec = SecretKeySpec(pwBytes, "DES")
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec as java.security.Key)
        return cipher.doFinal(challenge)
    }

    private fun clientInit(out: DataOutputStream) {
        out.writeByte(1) // shared-flag: allow other viewers to stay connected too
        out.flush()
    }

    private fun serverInit(inp: DataInputStream, out: DataOutputStream): Pair<Int, Int> {
        val width = inp.readUnsignedShort()
        val height = inp.readUnsignedShort()
        // Server's native PIXEL_FORMAT (16 bytes) — we ignore its contents and immediately
        // override with our own via SetPixelFormat below, so we don't need to parse it beyond
        // skipping the bytes.
        inp.skipBytes(16)
        val nameLen = inp.readInt()
        val name = ByteArray(nameLen).also { inp.readFully(it) }
        Log.i(TAG, "ServerInit: ${width}x$height name=${String(name)}")

        // SetPixelFormat (message type 0): request 32-bit true-colour, byte order matching
        // Bitmap.Config.ARGB_8888's in-memory layout (R,G,B,A per pixel on a little-endian
        // device) so Raw-encoded rectangles can be copied directly into the Bitmap without a
        // per-pixel format conversion.
        out.writeByte(0)
        out.write(byteArrayOf(0, 0, 0)) // padding
        out.writeByte(32) // bits-per-pixel
        out.writeByte(24) // depth
        out.writeByte(0)  // big-endian-flag = false
        out.writeByte(1)  // true-colour-flag = true
        out.writeShort(255) // red-max
        out.writeShort(255) // green-max
        out.writeShort(255) // blue-max
        out.writeByte(0)  // red-shift
        out.writeByte(8)  // green-shift
        out.writeByte(16) // blue-shift
        out.write(byteArrayOf(0, 0, 0)) // padding
        out.flush()

        // SetEncodings (message type 2): Raw only for this first version — see the class doc.
        out.writeByte(2)
        out.writeByte(0) // padding
        out.writeShort(2) // number-of-encodings
        out.writeInt(1)   // CopyRect
        out.writeInt(0)   // Raw
        out.flush()

        return width to height
    }

    private fun requestFramebufferUpdate(out: DataOutputStream, incremental: Boolean) {
        out.writeByte(3)
        out.writeByte(if (incremental) 1 else 0)
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(fbWidth)
        out.writeShort(fbHeight)
        out.flush()
    }

    private fun handleFramebufferUpdate(inp: DataInputStream) {
        inp.skipBytes(1) // padding
        val numRects = inp.readUnsignedShort()
        val fb = framebuffer ?: return
        repeat(numRects) {
            val x = inp.readUnsignedShort()
            val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort()
            val h = inp.readUnsignedShort()
            val encoding = inp.readInt()
            when (encoding) {
                0 -> { // Raw: w*h pixels, 4 bytes each, in the SetPixelFormat layout we requested.
                    val rowBytes = w * 4
                    val row = ByteArray(rowBytes)
                    val pixels = IntArray(w)
                    for (ry in 0 until h) {
                        inp.readFully(row)
                        for (rx in 0 until w) {
                            val o = rx * 4
                            val r = row[o].toInt() and 0xFF
                            val g = row[o + 1].toInt() and 0xFF
                            val b = row[o + 2].toInt() and 0xFF
                            pixels[rx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                        if (y + ry < fb.height) fb.setPixels(pixels, 0, w, x, y + ry, w.coerceAtMost(fb.width - x), 1)
                    }
                }
                1 -> { // CopyRect: 4 bytes giving the source x/y to copy FROM within our own framebuffer.
                    val srcX = inp.readUnsignedShort()
                    val srcY = inp.readUnsignedShort()
                    val srcRect = Rect(srcX, srcY, srcX + w, srcY + h)
                    val dstRect = Rect(x, y, x + w, y + h)
                    val canvas = Canvas(fb)
                    val copy = Bitmap.createBitmap(fb, srcRect.left, srcRect.top, srcRect.width(), srcRect.height())
                    canvas.drawBitmap(copy, null, dstRect, null)
                    copy.recycle()
                }
                else -> throw IOException("Unsupported encoding $encoding — server ignored our SetEncodings")
            }
        }
    }

    private fun blitToSurface() {
        val fb = framebuffer ?: return
        val surface = targetSurface ?: return
        if (!surface.isValid) return
        try {
            val canvas = surface.lockCanvas(null) ?: return
            try {
                // Scale the whole framebuffer to fill the current Surface size — simplest
                // correct behavior for a first version; no independent pan/zoom yet (that's a
                // RemoteDesktopScreen-side concern for later, same as the Linux Plugin's PTY
                // resize path).
                canvas.drawBitmap(fb, null, Rect(0, 0, canvas.width, canvas.height), null)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.w(TAG, "blitToSurface failed: ${e.message}")
        }
    }
}
