// IRemoteDesktopSessionCallback.aidl
// AIDL contract — must stay byte-for-byte identical to the main app's copy.
package de.lobianco.saftssh.remotedesktop;

// oneway: fire-and-forget, never blocks the plugin's connection/render thread waiting on the
// main app to process a callback — same reasoning as the Linux Plugin's progress callback.
oneway interface IRemoteDesktopSessionCallback {
    /** Setup/connection progress line (e.g. "Negotiating VNC security type…"). */
    void onProgress(String line);

    /** Fired once the remote-desktop handshake completes and the first frame is drawable. */
    void onConnected();

    /** Fired when the session ends, whether cleanly (user disconnect) or on error. */
    void onDisconnected(String reason);

    /** Reports the remote framebuffer's pixel size: once when the session's framebuffer is first
     *  allocated, and again whenever the remote side resizes it (VNC DesktopSize, RDP
     *  OnGraphicsResize, SPICE onSettingsChanged). The app needs the real picture extent to bound
     *  pinch-zoom panning; without it it can only guess from the zoom factor, which let the whole
     *  image be pushed off-screen at high zoom.
     *
     *  Appended LAST on purpose. AIDL method order defines the Binder transaction codes, so adding
     *  at the end keeps both directions compatible: an old plugin paired with a new app simply
     *  never sends it (the app keeps its previous, looser bound), and a new plugin paired with an
     *  old app has the oneway call silently dropped by the old stub. */
    void onRemoteSize(int width, int height);
}
