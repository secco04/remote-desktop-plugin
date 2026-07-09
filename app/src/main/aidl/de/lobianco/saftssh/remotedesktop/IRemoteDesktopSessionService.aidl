// IRemoteDesktopSessionService.aidl
// AIDL contract — must stay byte-for-byte identical to the main app's copy.
package de.lobianco.saftssh.remotedesktop;

import de.lobianco.saftssh.remotedesktop.IRemoteDesktopSession;
import de.lobianco.saftssh.remotedesktop.IRemoteDesktopSessionCallback;

interface IRemoteDesktopSessionService {
    /**
     * Opens a remote-desktop connection and starts rendering it onto [surface].
     * [protocol] is "vnc", "rdp", or "spice" — the plugin's vendored client library (a fork of
     * iiordanov/bVNC) already implements all three behind a shared connection abstraction, so
     * one AIDL surface covers all of them; which one is used is purely a per-connection choice
     * made by the caller.
     * [surface] is drawn onto directly by the plugin process — see IRemoteDesktopSession's doc
     * for why no pixel data crosses the Binder call itself.
     * [callback] receives connection progress/state (may be null).
     */
    IRemoteDesktopSession createSession(
        String protocol, String host, int port, String username, String password,
        in android.view.Surface surface, int width, int height,
        in IRemoteDesktopSessionCallback callback);
}
