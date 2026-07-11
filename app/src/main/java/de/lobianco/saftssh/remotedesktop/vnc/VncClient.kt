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
 * pointer is drawn locally: the Cursor(-239) pseudo-encoding is requested (so the server keeps
 * the pointer out of the framebuffer pixels) and its shape is decoded and rendered, so
 * context-sensitive cursors (I-beam over text, resize arrows over borders, hand over links)
 * appear. [SyntheticCursor]'s arrow is the fallback until the server first sends a shape, and is
 * essential for the trackpad-style input mode (where the tap position isn't the finger position).
 * (RDP can't do this — FreeRDP 2.11.7's prebuilt Android native lib discards all cursor-shape
 * updates in no-op Pointer callbacks, so it's synthetic-only there.) Does NOT support:
 * Tight/Hextile/ZRLE encodings (servers that refuse to offer Raw will fail — most do offer it),
 * clipboard sync.
 */
class VncClient(
    private val host: String,
    private val port: Int,
    private val password: String?,
    /** false ("Balanced") = 32-bit true-colour (unchanged default). true ("Fast") = 16-bit
     *  RGB565 — half the wire bytes per pixel for Raw rectangles, at the cost of some banding on
     *  gradients. Fixed for the session's lifetime; VNC has no live pixel-format renegotiation, so
     *  changing this means reconnecting (see IRemoteDesktopSessionService.createSession's doc). */
    private val fastQuality: Boolean = false,
    private val onProgress: (String) -> Unit,
    private val onConnected: (width: Int, height: Int) -> Unit,
    private val onDisconnected: (reason: String) -> Unit,
    /** Proxmox VE only — when set, connects via [ProxmoxVncWebSocket] to this full
     *  "https://host:port/api2/json/nodes/{node}/qemu/{vmid}/vncwebsocket?port=...&vncticket=..."
     *  URL instead of a raw [Socket] to [host]:[port] — see that class's doc for why Proxmox's VNC
     *  console has no other way to reach it. [host]/[port] above are still used for [onProgress]'s
     *  message but not for the actual connection in this mode. */
    private val wsUrl: String? = null,
    private val wsCookie: String? = null,
    private val wsCertFingerprint: String? = null,
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var proxmoxWs: ProxmoxVncWebSocket? = null
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

    // Last pointer position we sent to the server, in framebuffer pixels — where the cursor is
    // drawn. Initialised to the framebuffer centre on connect so a cursor is visible immediately,
    // before the user touches anything.
    @Volatile private var pointerFbX = 0
    @Volatile private var pointerFbY = 0

    // The server's own cursor shape, from the Cursor pseudo-encoding (RFC 6143 §7.7.2) — this is
    // what reflects context-sensitive shapes (I-beam over text fields, resize arrows over window
    // borders, hand over links, …). Null until the server sends one (or after it sends an empty
    // 0x0 cursor to hide it); [SyntheticCursor]'s arrow is the fallback so there's always
    // something visible. [cursorHotspotX]/[Y] are the click point within the shape, in cursor
    // pixels.
    @Volatile private var cursorBitmap: Bitmap? = null
    @Volatile private var cursorHotspotX = 0
    @Volatile private var cursorHotspotY = 0

    // Throttles the cursor-move-triggered redraw (below) to ~60fps. Touch delivers move events
    // much faster than that (often 100+/sec during a drag), and every blitToSurface() takes the
    // same renderLock the protocol thread uses to draw real server updates — without this, a fast
    // drag could keep the lock busy compositing cursor-only frames and visibly delay real
    // framebuffer updates from ever getting drawn, which read as "slow/laggy rendering" even
    // though the actual network transfer was fine.
    @Volatile private var lastCursorBlitMs = 0L

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
        runCatching { proxmoxWs?.close() }
        thread?.interrupt()
    }

    fun isAlive(): Boolean = running &&
        (socket?.let { it.isConnected && !it.isClosed } ?: (proxmoxWs != null))

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
        // the cursor tracks the finger smoothly between real framebuffer updates. Throttled (see
        // lastCursorBlitMs's doc) so a fast drag can't starve real protocol-driven redraws.
        val now = System.currentTimeMillis()
        if (now - lastCursorBlitMs >= 16L) {
            lastCursorBlitMs = now
            blitToSurface()
        }
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
            val inp: DataInputStream
            val out: DataOutputStream
            if (wsUrl != null) {
                onProgress("Connecting via Proxmox VE…")
                val ws = ProxmoxVncWebSocket(wsUrl, wsCookie.orEmpty(), wsCertFingerprint)
                proxmoxWs = ws
                if (!ws.connectBlocking(15_000)) throw IOException("Proxmox VNC websocket failed to connect")
                inp = DataInputStream(BufferedInputStream(ws.inputStream, 64 * 1024))
                out = DataOutputStream(BufferedOutputStream(ws.outputStream))
            } else {
                onProgress("Connecting to $host:$port…")
                val sock = Socket(host, port).apply { tcpNoDelay = true; soTimeout = 15_000 }
                socket = sock
                inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
                out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
            }
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
            socket?.soTimeout = 0 // block indefinitely on subsequent reads — the server paces updates
            // (the WebSocket path has no equivalent socket-level read timeout to clear — its
            // InputStream already blocks indefinitely via ProxmoxVncWebSocket's queue.take())
            while (running) {
                val messageType = inp.readUnsignedByte()
                when (messageType) {
                    0 -> { // FramebufferUpdate
                        // Pipelined, not request-decode-draw-THEN-request: handleFramebufferUpdate
                        // fires the next incremental request as soon as it's read the rectangle
                        // count (see its own doc), before decoding any pixel data. A strict
                        // request-then-wait-then-request loop caps the effective frame rate at
                        // 1/round-trip-time — over WiFi with even modest latency, a remote desktop
                        // updating faster than that visibly falls behind ("laggt nach"), since each
                        // update has to wait for a full network round trip after the previous one
                        // finished rendering instead of overlapping with it.
                        handleFramebufferUpdate(inp, out)
                        blitToSurface()
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
            runCatching { proxmoxWs?.close() }
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

        // SetPixelFormat. Balanced (default): 32-bit true-colour, byte order matching
        // ARGB_8888's in-memory layout so Raw rectangles copy straight into the Bitmap with no
        // per-pixel conversion. Fast: 16-bit RGB565 (bits 15-11=R, 10-5=G, 4-0=B, little-endian on
        // the wire — big-endian-flag=0) — half the Raw bytes/pixel; handleFramebufferUpdate does
        // the 5/6-bit-to-8-bit expansion per pixel to build the ARGB_8888 bitmap.
        out.writeByte(0)
        out.write(byteArrayOf(0, 0, 0))
        if (fastQuality) {
            out.writeByte(16); out.writeByte(16); out.writeByte(0); out.writeByte(1)
            out.writeShort(31); out.writeShort(63); out.writeShort(31)
            out.writeByte(11); out.writeByte(5); out.writeByte(0)
        } else {
            out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1)
            out.writeShort(255); out.writeShort(255); out.writeShort(255)
            out.writeByte(0); out.writeByte(8); out.writeByte(16)
        }
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

    /** Synchronized on [out] — sendPointerEvent/sendKeyEvent/sendScroll (called from Binder
     *  threads) write to this SAME stream under the same lock; without it here too, an
     *  interleaved write from either side could corrupt the RFB byte stream (e.g. half of this
     *  request mixed with half of a PointerEvent), which is exactly the kind of intermittent
     *  protocol-level corruption that would surface as unpredictable lag/stalls rather than a
     *  clean error. This method runs on the protocol thread only, so it doesn't race with itself. */
    private fun requestFramebufferUpdate(out: DataOutputStream, incremental: Boolean) {
        synchronized(out) {
            out.writeByte(3)
            out.writeByte(if (incremental) 1 else 0)
            out.writeShort(0); out.writeShort(0)
            out.writeShort(fbWidth); out.writeShort(fbHeight)
            out.flush()
        }
    }

    /** Bytes per pixel on the wire, matching whatever SetPixelFormat requested in serverInit(). */
    private fun bytesPerPixel(): Int = if (fastQuality) 2 else 4

    /** Decodes one pixel at byte offset [o] in [bytes] into an opaque ARGB_8888 int, per the
     *  current wire pixel format. Balanced: 4 bytes, R/G/B in bytes 0/1/2 (matches ARGB_8888
     *  directly). Fast: 2 bytes, little-endian RGB565 (big-endian-flag=0 was requested), expanded
     *  to 8 bits/channel via bit replication (5→8: (v<<3)|(v>>2); 6→8: (v<<2)|(v>>4)) rather than
     *  a plain left-shift, so e.g. 5-bit max (31) maps to 255, not 248 — avoids a visible ceiling
     *  on white/saturated colours. Shared by both Raw rectangles and the Cursor pseudo-encoding,
     *  which uses this same negotiated pixel format for its pixel data (RFC 6143 §7.7.2). */
    private fun decodePixel(bytes: ByteArray, o: Int): Int {
        return if (fastQuality) {
            val pixel16 = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
            val r5 = (pixel16 shr 11) and 0x1F
            val g6 = (pixel16 shr 5) and 0x3F
            val b5 = pixel16 and 0x1F
            val r8 = (r5 shl 3) or (r5 shr 2)
            val g8 = (g6 shl 2) or (g6 shr 4)
            val b8 = (b5 shl 3) or (b5 shr 2)
            (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
        } else {
            val r = bytes[o].toInt() and 0xFF
            val g = bytes[o + 1].toInt() and 0xFF
            val b = bytes[o + 2].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Reads and decodes one FramebufferUpdate. [out] is used to fire the NEXT incremental
     *  request immediately after the rectangle count is known — before decoding a single pixel —
     *  so the server can start preparing/sending the next update while we're still decoding and
     *  drawing this one, instead of that round trip only starting after we finish. See the call
     *  site's doc for why the non-pipelined version visibly fell behind. */
    private fun handleFramebufferUpdate(inp: DataInputStream, out: DataOutputStream) {
        inp.skipBytes(1) // padding
        val numRects = inp.readUnsignedShort()
        requestFramebufferUpdate(out, incremental = true)
        repeat(numRects) {
            val x = inp.readUnsignedShort()
            val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort()
            val h = inp.readUnsignedShort()
            when (val encoding = inp.readInt()) {
                ENCODING_RAW -> {
                    val fb = framebuffer ?: return@repeat
                    val bpp = bytesPerPixel()
                    val row = ByteArray(w * bpp)
                    val pixels = IntArray(w)
                    for (ry in 0 until h) {
                        inp.readFully(row)
                        for (rx in 0 until w) pixels[rx] = decodePixel(row, rx * bpp)
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
                    // RFC 6143 §7.7.2: x,y = hotspot; then w*h pixels in OUR CURRENT PIXEL FORMAT
                    // (bytesPerPixel() bytes each — this must track whatever SetPixelFormat
                    // requested, same as Raw rectangles, since the server encodes cursor pixels
                    // the same way) followed by a 1-bit-per-pixel mask (ceil(w/8) bytes per row,
                    // MSB first, 1 = opaque). Decode into an ARGB bitmap and use it as the cursor
                    // so context-sensitive shapes (I-beam, resize arrows, hand) show up.
                    if (w > 0 && h > 0) {
                        val bpp = bytesPerPixel()
                        val pixelBytes = ByteArray(w * h * bpp).also { inp.readFully(it) }
                        val maskStride = (w + 7) / 8
                        val maskBytes = ByteArray(maskStride * h).also { inp.readFully(it) }
                        val argb = IntArray(w * h)
                        for (cy in 0 until h) {
                            for (cx in 0 until w) {
                                val o = (cy * w + cx) * bpp
                                val maskBit = (maskBytes[cy * maskStride + (cx / 8)].toInt() shr (7 - (cx % 8))) and 1
                                argb[cy * w + cx] = if (maskBit == 1) decodePixel(pixelBytes, o) else 0
                            }
                        }
                        cursorBitmap = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
                        cursorHotspotX = x
                        cursorHotspotY = y
                    } else {
                        // Empty 0x0 cursor rect = server asking us to hide the pointer.
                        cursorBitmap = null
                        cursorHotspotX = 0
                        cursorHotspotY = 0
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
                    // Draw the server's actual cursor shape if we have one (reflects I-beam /
                    // resize / hand / etc.), positioned so its hotspot sits at the pointer; fall
                    // back to the synthetic arrow until the server first sends a shape. Both are
                    // scaled with the framebuffer so the cursor tracks the desktop's own pixel
                    // size under zoom.
                    val cursor = cursorBitmap
                    if (cursor != null) {
                        val cx = ox + (pointerFbX - cursorHotspotX) * scale
                        val cy = oy + (pointerFbY - cursorHotspotY) * scale
                        canvas.drawBitmap(cursor, null, RectF(cx, cy, cx + cursor.width * scale, cy + cursor.height * scale), null)
                    } else {
                        SyntheticCursor.draw(canvas, ox + pointerFbX * scale, oy + pointerFbY * scale)
                    }
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
            } catch (e: Exception) {
                Log.w(TAG, "blitToSurface failed: ${e.message}")
            }
        }
    }
}
