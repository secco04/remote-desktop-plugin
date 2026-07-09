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
}
