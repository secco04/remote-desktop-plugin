// IRemoteDesktopSessionService.aidl
// AIDL contract — must stay byte-for-byte identical to the main app's copy.
package de.lobianco.saftssh.remotedesktop;

import de.lobianco.saftssh.remotedesktop.IRemoteDesktopSession;
import de.lobianco.saftssh.remotedesktop.IRemoteDesktopSessionCallback;

interface IRemoteDesktopSessionService {
    /**
     * Opens a remote-desktop connection and starts rendering it onto [surface].
     * [protocol] is "vnc" (from-scratch RFB client), "rdp" (vendored FreeRDP JNI bridge), or
     * "spice" (minimal native bridge to vendored libspice.so — see SpiceCommunicator's doc). One
     * AIDL surface covers whichever is used — see this plugin's README for why each protocol ended
     * up as a separate implementation rather than a shared one.
     * [surface] is drawn onto directly by the plugin process — see IRemoteDesktopSession's doc
     * for why no pixel data crosses the Binder call itself.
     * [callback] receives connection progress/state (may be null).
     *
     * [fastQuality]: false ("Balanced") = 32-bit colour, as before. true ("Fast") = 16-bit colour
     * (RDP) / RGB565 (VNC) — roughly half the pixel bandwidth, at some banding on gradients.
     * Neither backend supports changing this mid-session (it's negotiated once at connect), so
     * switching it means tearing down and recreating the session with a new createSession() call.
     *
     * [overrideWidth]/[overrideHeight]: RDP only — requests this exact session resolution instead
     * of [width]x[height] (the Surface's own viewport size). 0/0 means "use width/height" (the
     * previous, only, behavior). Meaningless for VNC, which has no concept of a client-requested
     * resolution — the server dictates its own screen size regardless of what we ask for.
     *
     * [tlsPort]/[proxy]/[caCert]/[hostSubject]: SPICE only, for a Proxmox VE connection — the main
     * app fetches these from a fresh Proxmox spiceproxy ticket (POST .../qemu/{vmid}/spiceproxy)
     * before calling this, and [host]/[password] carry that same ticket's own "host"/"password"
     * fields (NOT the Proxmox API host/credentials). [tlsPort] > 0 selects this path; 0 (with these
     * three left null) is a plain direct SPICE connect to [host]:[port] instead, same as before.
     *
     * [vncWsUrl]/[vncWsCookie]: VNC only, Proxmox VE's noVNC-style fallback (used when SPICE isn't
     * available on a VM) — the main app fetches a fresh vncproxy ticket (POST
     * .../qemu/{vmid}/vncproxy) and builds the full ".../vncwebsocket?port=...&vncticket=..." URL
     * before calling this; [password] carries that same ticket's RFB VncAuth password. [caCert] is
     * reused here (VNC context, not SPICE) to carry the Proxmox API certificate's SHA-256
     * fingerprint (hex-colon form) to pin the WebSocket's TLS connection against — see
     * ProxmoxVncWebSocket's class doc for why a second separate trust prompt isn't needed. Non-null
     * [vncWsUrl] selects this path; null is a plain direct VNC connect to [host]:[port] instead.
     */
    IRemoteDesktopSession createSession(
        String protocol, String host, int port, String username, String password,
        in android.view.Surface surface, int width, int height,
        in IRemoteDesktopSessionCallback callback,
        boolean fastQuality, int overrideWidth, int overrideHeight,
        int tlsPort, String proxy, String caCert, String hostSubject,
        String vncWsUrl, String vncWsCookie);

    /**
     * RDP only: marks [host]:[port]'s current TLS certificate ([fingerprint], as reported via
     * IRemoteDesktopSessionCallback.onProgress's "CERT_UNTRUSTED|host|port|fingerprint" marker)
     * as trusted, mirroring the main app's own SSH host-key confirmation flow (HostKeyStore) —
     * accept once, persist, never ask again unless the fingerprint changes. Call this, then retry
     * createSession(), after the user confirms an unknown-certificate prompt.
     */
    void trustRdpCertificate(String host, int port, String fingerprint);
}
