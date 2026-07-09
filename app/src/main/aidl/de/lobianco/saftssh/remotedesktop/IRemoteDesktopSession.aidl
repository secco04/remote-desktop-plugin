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
     * Forwards a pointer/touch event. x/y are in REMOTE framebuffer coordinates (the main app is
     * responsible for translating its own view-local touch coordinates first, since only it
     * knows the current scale/pan). buttonMask bit 0 = primary/left button/tap down.
     */
    void sendPointerEvent(int x, int y, int buttonMask);

    /** Forwards a key event. keyCode/metaState follow android.view.KeyEvent's constants;
     *  unicodeChar is KeyEvent.getUnicodeChar()'s result (0 if none) — already accounts for the
     *  current shift/caps-lock state, which the plugin needs for correct printable-character
     *  mapping without reimplementing Android's own keymap logic. */
    void sendKeyEvent(int keyCode, int unicodeChar, boolean down, int metaState);

    /** True if the underlying connection is still alive. */
    boolean isAlive();

    /** Tears down the connection and releases the Surface. */
    void destroy();
}
