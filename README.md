# LobiShell Remote Desktop Plugin

This is a **standalone Android project** — open `remote-desktop-plugin/` as its own Android
Studio project. It is NOT a module of the main LobiShell app.

---

## What this is

A combined remote-desktop plugin for LobiShell's future `REMOTE_DESKTOP` connection type,
covering VNC, RDP, and SPICE from a single AIDL bound service (protocol selected per
connection) — mirroring how the vendored client library itself is organized.

**applicationId:** `de.lobianco.saftssh.remotedesktop`

---

## Vendored client library

`remote-desktop-clients/` is a **git submodule** pointing at
https://github.com/secco04/remote-desktop-clients, a fork of Iordan Iordanov's
[remote-desktop-clients](https://github.com/iiordanov/bVNC) (bVNC/aRDP/aSPICE/Opaque) —
established, actively-maintained, GPL-3.0 VNC/RDP/SPICE Android clients, plus Proxmox/oVirt VM
discovery. See [LICENSE](LICENSE) for the full license breakdown.

The submodule is **unmodified** — no patches applied directly to the vendored source. All
LobiShell-specific integration (AIDL service, permission model, connection-metadata plumbing)
lives in this repo's own `app/` module and calls into the vendored library's classes.

**Getting upstream updates:** on GitHub, open
https://github.com/secco04/remote-desktop-clients and click **"Sync fork"** — no local git
commands needed. Afterwards, bump the submodule pointer here:
```
cd remote-desktop-clients
git pull origin master
cd ..
git add remote-desktop-clients
git commit -m "Sync remote-desktop-clients submodule"
```

---

## Building

Open this directory as its own Android Studio project (File → Open → select
`remote-desktop-plugin/`). Clone with `--recurse-submodules`, or run
`git submodule update --init` after a plain clone — the `remote-desktop-clients/` submodule
won't be populated otherwise.

The plugin APK must be installed on the device alongside the main LobiShell app. The main app
binds the service using the action `de.lobianco.saftssh.remotedesktop.BIND_SESSION_SERVICE`.

---

## Permission model

Same pattern as the LobiShell Linux Plugin: a custom permission
(`de.lobianco.saftssh.remotedesktop.READ_BINARY`), `protectionLevel="dangerous"` — requested at
runtime by the main app, not install-time — since a "normal" permission would silently never get
granted for anyone who installs the main app before this plugin (see the Linux Plugin's own
history for why).

## Status

Scaffolding only — AIDL contract and app module not yet designed/implemented.
