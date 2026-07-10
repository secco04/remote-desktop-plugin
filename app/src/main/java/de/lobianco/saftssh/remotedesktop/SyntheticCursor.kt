package de.lobianco.saftssh.remotedesktop

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * Draws a synthetic mouse-pointer arrow onto a Surface canvas at a given position — used by both
 * VncClient and RdpClient so the user always sees where the (virtual) pointer is, regardless of
 * whether the remote server renders a cursor itself.
 *
 * Why synthetic rather than the server's own cursor image: most VNC servers (UltraVNC included)
 * and FreeRDP's default client-side pointer mode do NOT bake the pointer into the framebuffer
 * pixels, and the separate cursor-shape channels (RFB Cursor pseudo-encoding / RDP pointer PDUs)
 * are inconsistent to rely on across servers. A locally-drawn arrow at the last position we told
 * the server the pointer was at is always visible and always tracks the finger — essential for
 * the trackpad-style CURSOR input mode, where the tap position is NOT where the finger is.
 */
object SyntheticCursor {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // Classic arrow outline with its tip at (0,0), in a roughly 12x19 unit box, scaled up for
    // visibility on high-DPI phone screens.
    private const val S = 2.4f

    /** Draws the arrow with its tip (hotspot) at surface pixel ([tipX], [tipY]). */
    fun draw(canvas: Canvas, tipX: Float, tipY: Float) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 16f * S)
            lineTo(4f * S, 12.5f * S)
            lineTo(7f * S, 19f * S)
            lineTo(9.5f * S, 18f * S)
            lineTo(6.5f * S, 11.5f * S)
            lineTo(11f * S, 11.5f * S)
            close()
            offset(tipX, tipY)
        }
        canvas.drawPath(path, stroke)
        canvas.drawPath(path, fill)
    }
}
