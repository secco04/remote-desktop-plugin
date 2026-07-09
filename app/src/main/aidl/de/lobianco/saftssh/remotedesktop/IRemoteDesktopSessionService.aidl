// IRemoteDesktopSessionService.aidl
// AIDL contract — must stay byte-for-byte identical to the main app's copy.
package de.lobianco.saftssh.remotedesktop;

import de.lobianco.saftssh.remotedesktop.IRemoteDesktopSession;
import de.lobianco.saftssh.remotedesktop.IRemoteDesktopSessionCallback;

interface IRemoteDesktopSessionService {
    /**
     * Opens a remote-desktop connection and starts rendering it onto [surface].
     * [protocol] is "vnc" (from-scratch RFB client) or "rdp" (vendored FreeRDP JNI bridge);
     * "spice" is not implemented. One AIDL surface covers whichever is used — see this plugin's
     * README for why VNC and RDP ended up as separate implementations rather than a shared one.
     * [surface] is drawn onto directly by the plugin process — see IRemoteDesktopSession's doc
     * for why no pixel data crosses the Binder call itself.
     * [callback] receives connection progress/state (may be null).
     */
    IRemoteDesktopSession createSession(
        String protocol, String host, int port, String username, String password,
        in android.view.Surface surface, int width, int height,
        in IRemoteDesktopSessionCallback callback);

    /**
     * RDP only: marks [host]:[port]'s current TLS certificate ([fingerprint], as reported via
     * IRemoteDesktopSessionCallback.onProgress's "CERT_UNTRUSTED|host|port|fingerprint" marker)
     * as trusted, mirroring the main app's own SSH host-key confirmation flow (HostKeyStore) —
     * accept once, persist, never ask again unless the fingerprint changes. Call this, then retry
     * createSession(), after the user confirms an unknown-certificate prompt.
     */
    void trustRdpCertificate(String host, int port, String fingerprint);
}
