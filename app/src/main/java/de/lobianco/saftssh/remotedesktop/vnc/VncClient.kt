package de.lobianco.saftssh.remotedesktop.vnc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
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

// RFB pseudo-encodings (RFC 6143 §7.7 / the long-standing "TightVNC extensions" every real server
// and client supports) — negative encoding numbers signal "no pixel data follows in the usual
// sense, interpret x/y/w/h and any payload specially."
private const val ENCODING_COPYRECT = 1
private const val ENCODING_RAW = 0
private const val ENCODING_CURSOR = -239 // RFC 6143 §7.7.2: cursor shape, drawn locally
private const val ENCODING_DESKTOP_SIZE = -223 // RFC 6143 §7.7.1: framebuffer resolution changed

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
 * CopyRect(1) encodings, the Cursor(-239) pseudo-encoding (drawn locally — most servers don't
 * bake the pointer into the framebuffer pixels themselves), the DesktopSize(-223) pseudo-encoding
 * (server-initiated resolution change — e.g. many Windows VNC servers switch to a different
 * backing resolution for the lock/login screen, which previously desynced this client's stream
 * parsing and surfaced as an EOFException), pointer/key events. Does NOT support: Tight/Hextile/
 * ZRLE encodings (servers that refuse to offer Raw will fail — most do offer it, but some
 * hardened deployments don't), clipboard sync.
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

    // Render geometry from the most recent blit — the framebuffer is drawn letterboxed (aspect
    // ratio preserved) into the Surface, so incoming touch coordinates (which are in Surface
    // pixels) must be inverse-transformed back to framebuffer pixels before being sent as pointer
    // events. Without this, touches in the letterbox bars / beyond the scaled image clamp to the
    // edge and the remote cursor barely moves. These are the FINAL combined (base letterbox *
    // zoom) values applied to the most recent frame — sendPointerEvent only ever needs to invert
    // this one transform, whatever produced it.
    @Volatile private var renderScale = 1f
    @Volatile private var renderOffsetX = 0f
    @Volatile private var renderOffsetY = 0f

    // Pinch-zoom state set by RemoteDesktopScreen via setZoom(). zoomScale is relative to the
    // base letterbox fit (1.0 = no extra zoom); panX/panY are Surface-local pixel offsets applied
    // on top of the letterbox's own centering. See blitToSurface for how these combine.
    @Volatile private var zoomScale = 1f
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f

    // Cursor pseudo-encoding state (RFC 6143 §7.7.2): the server-provided cursor image + hotspot,
    // and the last framebuffer-space position we told the server the pointer was at — that's
    // where we draw this bitmap locally, since the server doesn't render it into the framebuffer.
    @Volatile private var cursorBitmap: Bitmap? = null
    @Volatile private var cursorHotspotX = 0
    @Volatile private var cursorHotspotY = 0
    @Volatile private var lastPointerFbX = 0
    @Volatile private var lastPointerFbY = 0

    fun setZoom(scale: Float, newPanX: Float, newPanY: Float) {
        zoomScale = scale.coerceAtLeast(0.1f)
        panX = newPanX
        panY = newPanY
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

    /** RFB PointerEvent (message type 5). [x]/[y] are Surface-local pixels (as delivered by the
     *  hosting SurfaceView's touch listener); they're mapped back through the current letterbox+
     *  zoom geometry to framebuffer pixels here. [buttonMask] bit 0/1/2 = left/middle/right. */
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val out = output ?: return
        val fbX = ((x - renderOffsetX) / renderScale).toInt().coerceIn(0, fbWidth.coerceAtLeast(1) - 1)
        val fbY = ((y - renderOffsetY) / renderScale).toInt().coerceIn(0, fbHeight.coerceAtLeast(1) - 1)
        lastPointerFbX = fbX
        lastPointerFbY = fbY
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
        // The server won't push a fresh frame just because the (locally-drawn) cursor needs to
        // move — redraw immediately so the cursor tracks the finger smoothly between real
        // framebuffer updates.
        if (cursorBitmap != null) blitToSurface()
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

        // SetEncodings (message type 2): Raw + CopyRect for pixel data, plus the Cursor and
        // DesktopSize pseudo-encodings — see the class doc for why both matter in practice.
        out.writeByte(2)
        out.writeByte(0) // padding
        out.writeShort(4) // number-of-encodings
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
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(fbWidth)
        out.writeShort(fbHeight)
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
            val encoding = inp.readInt()
            when (encoding) {
                ENCODING_RAW -> { // w*h pixels, 4 bytes each, in the SetPixelFormat layout we requested.
                    val fb = framebuffer ?: return@repeat
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
                ENCODING_COPYRECT -> { // 4 bytes giving the source x/y to copy FROM within our own framebuffer.
                    val fb = framebuffer ?: return@repeat
                    val srcX = inp.readUnsignedShort()
                    val srcY = inp.readUnsignedShort()
                    val srcRect = Rect(srcX, srcY, srcX + w, srcY + h)
                    val dstRect = Rect(x, y, x + w, y + h)
                    val canvas = Canvas(fb)
                    val copy = Bitmap.createBitmap(fb, srcRect.left, srcRect.top, srcRect.width(), srcRect.height())
                    canvas.drawBitmap(copy, null, dstRect, null)
                    copy.recycle()
                }
                ENCODING_CURSOR -> { // RFC 6143 §7.7.2: x,y = hotspot; w,h = cursor size; then
                    // w*h pixels (same PIXEL_FORMAT as Raw) followed by a 1-bit-per-pixel bitmask
                    // (ceil(w/8) bytes per row) marking which pixels are opaque.
                    if (w > 0 && h > 0) {
                        val pixelBytes = ByteArray(w * h * 4)
                        inp.readFully(pixelBytes)
                        val maskRowBytes = (w + 7) / 8
                        val maskBytes = ByteArray(maskRowBytes * h)
                        inp.readFully(maskBytes)
                        val argb = IntArray(w * h)
                        for (py in 0 until h) {
                            for (px in 0 until w) {
                                val o = (py * w + px) * 4
                                val r = pixelBytes[o].toInt() and 0xFF
                                val g = pixelBytes[o + 1].toInt() and 0xFF
                                val b = pixelBytes[o + 2].toInt() and 0xFF
                                val maskByte = maskBytes[py * maskRowBytes + px / 8].toInt()
                                val opaque = (maskByte shr (7 - (px % 8))) and 1 == 1
                                argb[py * w + px] = if (opaque) (0xFF shl 24) or (r shl 16) or (g shl 8) or b else 0
                            }
                        }
                        cursorBitmap = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
                        cursorHotspotX = x
                        cursorHotspotY = y
                    } else {
                        cursorBitmap = null // 0x0 cursor rect = server asking us to hide it
                    }
                }
                ENCODING_DESKTOP_SIZE -> {
                    // No pixel data — x/y are unused (0), w/h are the new full framebuffer size.
                    // Reallocate; RemoteDesktopScreen's letterbox fit picks up the new aspect
                    // ratio on the very next blit with no further plumbing needed.
                    Log.i(TAG, "DesktopSize changed: ${fbWidth}x$fbHeight -> ${w}x$h")
                    fbWidth = w
                    fbHeight = h
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
        try {
            val canvas = surface.lockCanvas(null) ?: return
            try {
                // Letterbox * zoom: base fit preserves aspect ratio (min of the two axis scales,
                // centered); zoomScale/panX/panY (from setZoom) then scale/shift that on top,
                // re-centering around the zoomed size so a pinch feels anchored rather than
                // jumping the image around. The combined values are what sendPointerEvent inverts.
                val sw = canvas.width
                val sh = canvas.height
                val baseScale = minOf(sw.toFloat() / fb.width, sh.toFloat() / fb.height)
                val scale = baseScale * zoomScale
                val dw = fb.width * scale
                val dh = fb.height * scale
                val ox = (sw - dw) / 2f + panX
                val oy = (sh - dh) / 2f + panY
                renderScale = scale
                renderOffsetX = ox
                renderOffsetY = oy
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(fb, null, RectF(ox, oy, ox + dw, oy + dh), null)

                // Locally-drawn cursor overlay (see ENCODING_CURSOR) at the last position we told
                // the server the pointer was at — most VNC servers (including UltraVNC) don't
                // draw the pointer into the framebuffer pixels themselves.
                cursorBitmap?.let { cursor ->
                    val cx = ox + (lastPointerFbX - cursorHotspotX) * scale
                    val cy = oy + (lastPointerFbY - cursorHotspotY) * scale
                    val cw = cursor.width * scale
                    val ch = cursor.height * scale
                    canvas.drawBitmap(cursor, null, RectF(cx, cy, cx + cw, cy + ch), null)
                }
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.w(TAG, "blitToSurface failed: ${e.message}")
        }
    }
}
