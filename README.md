# LobiShell Remote Desktop Plugin

This is a **standalone Android project** — open `remote-desktop-plugin/` as its own Android
Studio project. It is NOT a module of the main LobiShell app.

---

## What this is

A remote-desktop plugin for LobiShell's `REMOTE_DESKTOP` connection type, exposed via a single
AIDL bound service (protocol selected per connection).

**applicationId:** `de.lobianco.saftssh.remotedesktop`

**Protocol support:**
- **VNC** — implemented (`vnc/VncClient.kt`), from scratch, not derived from bVNC's source.
- **RDP / SPICE** — not implemented. Both need a native runtime (FreeRDP / spice-gtk) requiring a
  real NDK cross-compile toolchain, which can't be set up or verified without hands-on Android
  Studio work. `RemoteDesktopSessionService.buildSession()` fails these with a clear error rather
  than a broken half-implementation.

---

## Why VNC is a from-scratch client, not the vendored library

`remote-desktop-clients/` is still vendored here as a git submodule
(https://github.com/secco04/remote-desktop-clients, a fork of
[iiordanov/bVNC](https://github.com/iiordanov/bVNC)) — kept for reference and as the eventual
base for RDP/SPICE, since FreeRDP/spice-gtk's native build scripts live there. See
[LICENSE](LICENSE) for its license breakdown, and its own `COPYRIGHT-bVNC`/`LICENSE` files.

Its VNC classes turned out to be unusable as a plain library, though: `RemoteCanvas` (the
framebuffer view) implements a 30-method `Viewable` interface covering pan/zoom/toolbar/toast
concerns, and is driven by `RemoteCanvasHandler` — a 700+-line Activity-oriented state machine.
The whole thing is built as a complete interactive Activity, not a headless engine, so adapting it
into a bound service with no window would have meant writing a large amount of glue code with no
way to compile-check it here. RFB (VNC's wire protocol, RFC 6143) is small and well-specified, so
implementing just what a "connect, decode framebuffer updates, draw them, send input" client needs
was the more honest path to something that actually works. See `vnc/VncClient.kt`'s class doc for
exactly what it does and doesn't support.

**Getting upstream updates to the submodule** (for future RDP/SPICE work): on GitHub, open
https://github.com/secco04/remote-desktop-clients and click **"Sync fork"**, then bump the
submodule pointer here:
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
`remote-desktop-plugin/`). The plugin APK must be installed on the device alongside the main
LobiShell app. The main app binds the service using the action
`de.lobianco.saftssh.remotedesktop.BIND_SESSION_SERVICE`.

## Bind authorization

No custom Android permission — a `dangerous`-protectionLevel custom permission's grant dialog
renders as a generic, alarming "perform an unknown action" on modern Android regardless of its
`android:label` (confirmed against AOSP's PermissionController source; same finding applies to
the Linux Plugin). `RemoteDesktopSessionService`'s AIDL methods instead check the calling
package's identity directly (`Binder.getCallingUid()` inside the AIDL method body — NOT in
`onBind()`, which doesn't run inside a live incoming transaction and would just see this
process's own uid).

## Status

- `app/` module: manifest, bound service, `InfoActivity`. Builds standalone.
- AIDL contract: `IRemoteDesktopSessionService.createSession(protocol, host, port, user, pass,
  surface, w, h, callback)` → `IRemoteDesktopSession` (resize/sendPointerEvent/sendKeyEvent/
  destroy). The `android.view.Surface` is drawn onto directly by the plugin process — no
  per-frame pixel data crosses the Binder call.
- **VNC**: connects, authenticates (None or VNC-Auth/DES), renders Raw+CopyRect-encoded updates
  onto the Surface, forwards pointer and keyboard input. Not yet verified on a real device/server
  (no way to run a build here) — expect the first real test pass to surface bugs, same as every
  other piece of this app.
- **RDP/SPICE**: `buildSession()` throws a clear `UnsupportedOperationException` naming the
  reason (no native runtime bundled) rather than attempting anything.
