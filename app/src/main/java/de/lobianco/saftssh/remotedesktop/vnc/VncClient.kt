package de.lobianco.saftssh.remotedesktop.vnc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.Surface
import de.lobianco.saftssh.remotedesktop.SyntheticCursor
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

// RFB encodings (RFC 6143 §7.6/§7.7). Negative numbers are "pseudo-encodings": not pixel data,
// but a signal about cursor shape / desktop size / etc. Every mainstream server supports these.
private const val ENCODING_RAW = 0
private const val ENCODING_COPYRECT = 1
private const val ENCODING_CURSOR = -239 // §7.7.2: cursor shape (we consume it but draw our own)
private const val ENCODING_DESKTOP_SIZE = -223 // §7.7.1: framebuffer resolution changed

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
 * CopyRect(1) encodings, the DesktopSize(-223) pseudo-encoding (server-initiated resolution
 * change — e.g. many Windows VNC servers switch backing resolution for the lock/login screen,
 * which previously desynced this client's stream parsing and surfaced as an EOFException). The
 * pointer is drawn locally via [SyntheticCursor] (the Cursor(-239) pseudo-encoding is requested
 * and consumed so the server keeps the pointer out of the framebuffer pixels, but its shape is
 * ignored — a synthetic arrow is always drawn instead, which is reliable across servers and
 * essential for the trackpad-style input mode). Does NOT support: Tight/Hextile/ZRLE encodings
 * (servers that refuse to offer Raw will fail — most do offer it), clipboard sync.
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
     *  caller may not have a sized Surface yet at construction time). */
    @Volatile var targetSurface: Surface? = null

    // Combined (base letterbox * pinch-zoom) render geometry from the most recent blit — incoming
    // touch coordinates are Surface pixels and must be inverse-transformed back to framebuffer
    // pixels before being sent as pointer events, and the synthetic cursor is drawn using it too.
    @Volatile private var renderScale = 1f
    @Volatile private var renderOffsetX = 0f
    @Volatile private var renderOffsetY = 0f

    // Pinch-zoom state set by RemoteDesktopScreen via setZoom(): zoomScale relative to the base
    // letterbox fit (1.0 = none), panX/panY Surface-local pixel offsets. See blitToSurface.
    @Volatile private var zoomScale = 1f
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f

    // Last pointer position we sent to the server, in framebuffer pixels — where the synthetic
    // cursor is drawn. Initialised to the framebuffer centre on connect so a cursor is visible
    // immediately, before the user touches anything.
    @Volatile private var pointerFbX = 0
    @Volatile private var pointerFbY = 0

    // lockCanvas/unlockCanvasAndPost must not be entered from two threads at once (the protocol
    // thread after a framebuffer update, and a Binder thread on a pointer move) — serialise them.
    private val renderLock = Any()

    fun setZoom(scale: Float, newPanX: Float, newPanY: Float) {
        zoomScale = scale.coerceAtLeast(0.1f)
        panX = newPanX
        panY = newPanY
        blitToSurface() // reflect the new zoom immediately, even without a fresh server frame
    }

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

    /** RFB PointerEvent (message type 5). [x]/[y] are Surface-local pixels; mapped back through
     *  the current letterbox+zoom geometry to framebuffer pixels here. [buttonMask]: bit0=left,
     *  bit1=middle, bit2=right (RFC 6143 convention). */
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val out = output ?: return
        val fbX = ((x - renderOffsetX) / renderScale).toInt().coerceIn(0, fbWidth.coerceAtLeast(1) - 1)
        val fbY = ((y - renderOffsetY) / renderScale).toInt().coerceIn(0, fbHeight.coerceAtLeast(1) - 1)
        pointerFbX = fbX
        pointerFbY = fbY
        try {
            synchronized(out) {
                out.writeByte(5)
                out.writeByte(buttonMask)
                out.writeShort(fbX)
                out.writeShort(fbY)
                out.flush()
            }
        } catch (e: IOException) {
            Log.w(TAG, "sendPointerEvent failed: ${e.message}")
        }
        // The server won't push a frame just because our locally-drawn cursor moved — redraw so
        // the cursor tracks the finger smoothly between real framebuffer updates.
        blitToSurface()
    }

    /** Mouse wheel via RFB PointerEvent button bits (RFC 6143: bit 3 = wheel up, bit 4 = wheel
     *  down). Each notch is a press+release of the wheel "button" at the current pointer position.
     *  [steps] > 0 = up, < 0 = down; magnitude = notch count. */
    fun sendScroll(steps: Int) {
        val out = output ?: return
        if (steps == 0) return
        val bit = if (steps > 0) 0x08 else 0x10
        val count = if (steps > 0) steps else -steps
        try {
            synchronized(out) {
                repeat(count) {
                    out.writeByte(5); out.writeByte(bit); out.writeShort(pointerFbX); out.writeShort(pointerFbY)
                    out.writeByte(5); out.writeByte(0); out.writeShort(pointerFbX); out.writeShort(pointerFbY)
                }
                out.flush()
            }
        } catch (e: IOException) {
            Log.w(TAG, "sendScroll failed: ${e.message}")
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
            pointerFbX = w / 2
            pointerFbY = h / 2
            framebuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            onProgress("Connected — ${w}x${h}")
            onConnected(w, h)

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
                    1 -> { // SetColourMapEntries — not used (we request true-colour), drain it.
                        inp.skipBytes(1)
                        inp.readUnsignedShort() // first-colour
                        val numColours = inp.readUnsignedShort()
                        inp.skipBytes(numColours * 6)
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
        out.write("RFB 003.008\n".toByteArray())
        out.flush()
    }

    private fun negotiateSecurity(inp: DataInputStream, out: DataOutputStream) {
        val numTypes = inp.readUnsignedByte()
        if (numTypes == 0) {
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
            hasVncAuth -> 2
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
            val reason = try {
                val len = inp.readInt()
                if (len in 0..4096) String(ByteArray(len).also { inp.readFully(it) }) else "authentication failed"
            } catch (_: Exception) { "authentication failed" }
            throw IOException("Authentication failed: $reason")
        }
    }

    /** VNC's DES challenge-response: the password (up to 8 bytes, zero-padded) is used as a DES
     *  key with each key byte's bits reversed — a long-standing RFB protocol quirk. */
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
        out.writeByte(1) // shared-flag
        out.flush()
    }

    private fun serverInit(inp: DataInputStream, out: DataOutputStream): Pair<Int, Int> {
        val width = inp.readUnsignedShort()
        val height = inp.readUnsignedShort()
        inp.skipBytes(16) // server PIXEL_FORMAT — overridden below
        val nameLen = inp.readInt()
        val name = ByteArray(nameLen).also { inp.readFully(it) }
        Log.i(TAG, "ServerInit: ${width}x$height name=${String(name)}")

        // SetPixelFormat: 32-bit true-colour, byte order matching ARGB_8888's in-memory layout so
        // Raw rectangles copy straight into the Bitmap with no per-pixel conversion.
        out.writeByte(0)
        out.write(byteArrayOf(0, 0, 0))
        out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1)
        out.writeShort(255); out.writeShort(255); out.writeShort(255)
        out.writeByte(0); out.writeByte(8); out.writeByte(16)
        out.write(byteArrayOf(0, 0, 0))
        out.flush()

        // SetEncodings: Raw + CopyRect for pixels, plus Cursor and DesktopSize pseudo-encodings.
        out.writeByte(2)
        out.writeByte(0)
        out.writeShort(4)
        out.writeInt(ENCODING_COPYRECT)
        out.writeInt(ENCODING_RAW)
        out.writeInt(ENCODING_CURSOR)
        out.writeInt(ENCODING_DESKTOP_SIZE)
        out.flush()

        return width to height
    }

    private fun requestFramebufferUpdate(out: DataOutputStream, incremental: Boolean) {
        out.writeByte(3)
        out.writeByte(if (incremental) 1 else 0)
        out.writeShort(0); out.writeShort(0)
        out.writeShort(fbWidth); out.writeShort(fbHeight)
        out.flush()
    }

    private fun handleFramebufferUpdate(inp: DataInputStream) {
        inp.skipBytes(1) // padding
        val numRects = inp.readUnsignedShort()
        repeat(numRects) {
            val x = inp.readUnsignedShort()
            val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort()
            val h = inp.readUnsignedShort()
            when (val encoding = inp.readInt()) {
                ENCODING_RAW -> {
                    val fb = framebuffer ?: return@repeat
                    val row = ByteArray(w * 4)
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
                ENCODING_COPYRECT -> {
                    val fb = framebuffer ?: return@repeat
                    val srcX = inp.readUnsignedShort()
                    val srcY = inp.readUnsignedShort()
                    val copy = Bitmap.createBitmap(fb, srcX, srcY, w, h)
                    Canvas(fb).drawBitmap(copy, null, Rect(x, y, x + w, y + h), null)
                    copy.recycle()
                }
                ENCODING_CURSOR -> {
                    // Consume the cursor shape so the stream stays in sync, but ignore it — we
                    // draw our own SyntheticCursor. Payload: w*h pixels (4 bytes each) + a
                    // 1-bit-per-pixel mask (ceil(w/8) bytes per row).
                    if (w > 0 && h > 0) {
                        inp.skipBytes(w * h * 4)
                        inp.skipBytes(((w + 7) / 8) * h)
                    }
                }
                ENCODING_DESKTOP_SIZE -> {
                    // No pixel data — w/h are the new full framebuffer size. Reallocate; the next
                    // blit's letterbox fit picks up the new aspect ratio automatically.
                    Log.i(TAG, "DesktopSize changed: ${fbWidth}x$fbHeight -> ${w}x$h")
                    fbWidth = w
                    fbHeight = h
                    pointerFbX = pointerFbX.coerceIn(0, w - 1)
                    pointerFbY = pointerFbY.coerceIn(0, h - 1)
                    framebuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                }
                else -> throw IOException("Unsupported encoding $encoding — server ignored our SetEncodings")
            }
        }
    }

    private fun blitToSurface() {
        val fb = framebuffer ?: return
        val surface = targetSurface ?: return
        if (!surface.isValid) return
        synchronized(renderLock) {
            try {
                val canvas = surface.lockCanvas(null) ?: return
                try {
                    // Base letterbox fit (aspect-preserving, centred) * pinch-zoom on top. The
                    // combined scale/offset are stored for sendPointerEvent's inverse mapping.
                    val sw = canvas.width
                    val sh = canvas.height
                    val scale = minOf(sw.toFloat() / fb.width, sh.toFloat() / fb.height) * zoomScale
                    val dw = fb.width * scale
                    val dh = fb.height * scale
                    val ox = (sw - dw) / 2f + panX
                    val oy = (sh - dh) / 2f + panY
                    renderScale = scale
                    renderOffsetX = ox
                    renderOffsetY = oy
                    canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(fb, null, RectF(ox, oy, ox + dw, oy + dh), null)
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
