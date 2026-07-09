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

- `app/` module skeleton: `build.gradle.kts`, `AndroidManifest.xml` (dangerous permission +
  bound service declaration), launcher `InfoActivity`. Builds standalone.
- AIDL contract designed and written (both here and mirrored in the main app repo):
  `IRemoteDesktopSessionService.createSession(protocol, host, port, user, pass, surface, w, h,
  callback)` → `IRemoteDesktopSession` (resize/sendPointerEvent/sendKeyEvent/destroy). The
  `android.view.Surface` is drawn onto directly by the plugin process — no per-frame pixel data
  crosses the Binder call.
- `RemoteDesktopSessionService.kt` — service/session scaffolding in place, but
  **`buildSession()` throws `UnsupportedOperationException`** — the actual vendored-library
  integration isn't wired yet. See the TODOs in that file for the concrete plan (host a
  `RemoteCanvas` offscreen, blit its `Bitmap` onto the `Surface` on every `reDraw()`, dispatch
  pointer/key events into its existing input handling).

### Wiring the vendored library — needs Android Studio

`remote-desktop-clients/bVNC` and `remote-desktop-clients/remoteClientLib` are proper
`com.android.library` modules, but on **Groovy DSL + AGP 8.13.2**, while this project's own
`app/` module is **Kotlin DSL + AGP 9.2.1**. This mismatch needs to be resolved with a real
Gradle sync (not verifiable without one — same constraint as the Linux Plugin's native builds).
Two approaches to try, in `settings.gradle.kts`:

1. `includeBuild("remote-desktop-clients")` (composite build) + `dependencySubstitution` rules.
2. `include(":bvnc-lib")` + `project(":bvnc-lib").projectDir = file("remote-desktop-clients/bVNC")`
   (and the same for `remoteClientLib`) — simpler, but check whether the modules' own AGP-8-era
   config survives being built under AGP 9 unmodified.

Once resolved, `app/build.gradle.kts`'s `dependencies {}` block has the exact `implementation(...)`
lines to uncomment.
