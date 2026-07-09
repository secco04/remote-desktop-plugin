package de.lobianco.saftssh.remotedesktop.rdp

import android.content.Context

/**
 * Persists trusted RDP server certificate fingerprints, mirroring the main app's SSH
 * HostKeyStore: unknown/changed certificate → reject once, surface it to the caller, wait for an
 * explicit [trust] call before the next connection attempt accepts it. Simpler than HostKeyStore
 * (no HMAC-tamper-protection layer) since this only lives inside the plugin's own private storage
 * and RDP connections are opt-in per-network already — proportionate for a first version.
 */
class RdpCertStore(context: Context) {
    private val prefs = context.getSharedPreferences("rdp_known_certs", Context.MODE_PRIVATE)

    private fun key(host: String, port: Int) = "$host:$port"

    /** True if [fingerprint] is the one already trusted for [host]:[port]. False for both
     *  "never seen this host" and "fingerprint changed" — the caller can't distinguish those
     *  from this alone, matching how OnVerifiyCertificateEx / OnVerifyChangedCertificateEx are
     *  two different native callbacks anyway. */
    fun isTrusted(host: String, port: Int, fingerprint: String): Boolean =
        prefs.getString(key(host, port), null) == fingerprint

    fun trust(host: String, port: Int, fingerprint: String) {
        // commit() (synchronous): the trust decision must be on disk before the caller retries
        // the connection, or a crash between "user confirmed" and "write finished" would silently
        // re-prompt forever.
        prefs.edit().putString(key(host, port), fingerprint).commit()
    }
}
