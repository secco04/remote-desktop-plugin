package de.lobianco.saftssh.remotedesktop.vnc

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private const val TAG = "ProxmoxVncWebSocket"
private val CLOSE_MARKER = ByteArray(0)

/**
 * Wraps an OkHttp [WebSocket] as a blocking [InputStream]/[OutputStream] pair so [VncClient]'s
 * existing RFB parsing (written against `DataInputStream`/`DataOutputStream`, transport-agnostic —
 * see its class doc) can run over it completely unchanged. Needed because Proxmox VE's VNC console
 * has no raw TCP port reachable from outside at all — every connection goes through pveproxy's own
 * WebSocket upgrade at `.../qemu/{vmid}/vncwebsocket?port=...&vncticket=...` (confirmed against
 * Proxmox's own API schema, not guessed), tunneling the exact same RFB byte stream a raw socket
 * would carry.
 *
 * TLS: pins to [certFingerprintSha256] (a specific certificate, hex-colon SHA-256, computed by the
 * main app during its own already-completed trust-on-first-use flow — see
 * [de.lobianco.saftssh.data.remotedesktop.proxmox.ProxmoxApiClient] there) rather than doing its
 * own separate TOFU prompt in this process: the user already approved this exact server seconds
 * earlier for the SPICE/API connection, and pinning to that same fingerprint here is the same
 * security property (a changed cert is still rejected), just without asking twice.
 */
class ProxmoxVncWebSocket(
    private val url: String,
    private val cookieHeader: String,
    private val certFingerprintSha256: String?,
) {
    private val incoming = LinkedBlockingQueue<ByteArray>()
    private var webSocket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var failure: Throwable? = null

    private fun sha256Fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    private fun buildClient(): OkHttpClient {
        val pin = certFingerprintSha256
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                if (pin != null && sha256Fingerprint(chain[0]) != pin) {
                    throw java.security.cert.CertificateException(
                        "Proxmox VNC websocket certificate doesn't match the pinned API certificate"
                    )
                }
                // pin == null (shouldn't normally happen — the caller always has a fingerprint by
                // this point) falls through as accept, same fail-open-only-if-misused posture as
                // not pinning at all.
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, tm)
            .hostnameVerifier { _, _ -> true } // see class doc — fingerprint pin is the real check
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived stream, no idle read timeout
            .build()
    }

    /** Blocks until the WebSocket handshake completes (or fails/times out). */
    fun connectBlocking(timeoutMs: Long): Boolean {
        val client = buildClient()
        val request = Request.Builder().url(url).header("Cookie", cookieHeader).build()
        val openLatch = java.util.concurrent.CountDownLatch(1)
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openLatch.countDown()
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                incoming.put(bytes.toByteArray())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Proxmox's vncwebsocket is a pure binary RFB tunnel — a text frame isn't expected,
                // but forward its UTF-8 bytes rather than silently dropping them just in case.
                incoming.put(text.toByteArray(Charsets.UTF_8))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}", t)
                failure = t
                closed = true
                openLatch.countDown()
                incoming.put(CLOSE_MARKER)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed = true
                incoming.put(CLOSE_MARKER)
            }
        })
        val opened = openLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return opened && failure == null
    }

    fun close() {
        closed = true
        runCatching { webSocket?.close(1000, null) }
        incoming.put(CLOSE_MARKER) // unblock any in-progress read()
    }

    val inputStream: InputStream = object : InputStream() {
        private var currentChunk: ByteArray? = null
        private var chunkPos = 0

        private fun fill(): Boolean {
            if (currentChunk != null && chunkPos < currentChunk!!.size) return true
            if (closed && incoming.isEmpty()) return false
            val next = incoming.take()
            if (next === CLOSE_MARKER) return false
            currentChunk = next
            chunkPos = 0
            return true
        }

        override fun read(): Int {
            if (!fill()) return -1
            return currentChunk!![chunkPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val chunk = currentChunk!!
            val available = chunk.size - chunkPos
            val n = minOf(len, available)
            System.arraycopy(chunk, chunkPos, b, off, n)
            chunkPos += n
            return n
        }
    }

    val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            val ws = webSocket ?: throw IOException("WebSocket not connected")
            if (closed) throw IOException("WebSocket closed")
            val slice = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            if (!ws.send(ByteString.of(*slice))) throw IOException("WebSocket send failed (closed?)")
        }
    }
}
