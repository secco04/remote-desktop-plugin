// IRemoteDesktopSession.aidl
// AIDL contract — must stay byte-for-byte identical to the main app's copy.
package de.lobianco.saftssh.remotedesktop;

/**
 * One live remote-desktop connection. Unlike the Linux Plugin's PTY session (which streams raw
 * bytes both ways over a ParcelFileDescriptor), the framebuffer here is drawn directly onto the
 * android.view.Surface passed to createSession() — the plugin process renders straight into that
 * shared buffer queue, so no per-frame pixel data crosses the Binder call itself. Only control
 * (resize) and input (pointer/key events) go through explicit calls.
 */
interface IRemoteDesktopSession {
    /** Call when the hosting view's size changes (e.g. device rotation, window resize). */
    void resize(int width, int height);

    /**
     * Forwards a pointer/touch event. x/y are Surface-local pixels (the plugin maps them to
     * framebuffer coordinates via its current letterbox+zoom geometry, see [setZoom]).
     * buttonMask follows the RFC 6143 (VNC) convention every real VNC/RDP client uses: bit 0 =
     * left button, bit 1 = middle, bit 2 = right.
     *
     * oneway: high-frequency input must not block the caller's UI thread on a synchronous Binder
     * round-trip per touch move, and there's no return value to wait for — fire-and-forget.
     */
    oneway void sendPointerEvent(int x, int y, int buttonMask);

    /**
     * Sets the pinch-zoom scale and pan offset the plugin should apply on top of its base
     * letterbox fit when next drawing a frame, and use to inverse-map subsequent
     * [sendPointerEvent] coordinates. [scale] is relative to the base letterbox fit (1.0 = no
     * extra zoom); [panX]/[panY] are Surface-local pixel offsets. oneway for the same reason as
     * sendPointerEvent — sent continuously while the user's fingers move.
     */
    oneway void setZoom(float scale, float panX, float panY);

    /**
     * Sends mouse-wheel scroll notches at the current pointer position. [steps] > 0 scrolls up
     * (away from the user), < 0 scrolls down; the magnitude is the number of notches. oneway for
     * the same reason as sendPointerEvent.
     */
    oneway void sendScroll(int steps);

    /** Forwards a key event. keyCode/metaState follow android.view.KeyEvent's constants;
     *  unicodeChar is KeyEvent.getUnicodeChar()'s result (0 if none) — already accounts for the
     *  current shift/caps-lock state, which the plugin needs for correct printable-character
     *  mapping without reimplementing Android's own keymap logic.
     *  oneway for the same reason as sendPointerEvent. */
    oneway void sendKeyEvent(int keyCode, int unicodeChar, boolean down, int metaState);

    /** True if the underlying connection is still alive. */
    boolean isAlive();

    /** Tears down the connection and releases the Surface. */
    void destroy();
}
