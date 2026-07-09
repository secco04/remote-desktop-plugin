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
- **RDP** — implemented (`rdp/RdpClient.kt`), backed by FreeRDP's own upstream Android JNI bridge
  (vendored, see below) plus prebuilt native libraries.
- **SPICE** — not implemented. Needs spice-gtk, a native runtime this plugin doesn't bundle.
  `RemoteDesktopSessionService.buildSession()` fails it with a clear error.

---

## Why VNC is a from-scratch client, not the vendored library

`remote-desktop-clients/` is still vendored here as a git submodule
(https://github.com/secco04/remote-desktop-clients, a fork of
[iiordanov/bVNC](https://github.com/iiordanov/bVNC)) — kept for reference and as a possible future
base for SPICE. See [LICENSE](LICENSE) for its license breakdown, and its own
`COPYRIGHT-bVNC`/`LICENSE` files.

Its VNC classes turned out to be unusable as a plain library, though: `RemoteCanvas` (the
framebuffer view) implements a 30-method `Viewable` interface covering pan/zoom/toolbar/toast
concerns, and is driven by `RemoteCanvasHandler` — a 700+-line Activity-oriented state machine.
The whole thing is built as a complete interactive Activity, not a headless engine, so adapting it
into a bound service with no window would have meant writing a large amount of glue code with no
way to compile-check it here. RFB (VNC's wire protocol, RFC 6143) is small and well-specified, so
implementing just what a "connect, decode framebuffer updates, draw them, send input" client needs
was the more honest path to something that actually works. See `vnc/VncClient.kt`'s class doc for
exactly what it does and doesn't support.

**Getting upstream updates to the submodule**: on GitHub, open
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

## Why RDP vendors FreeRDP's own JNI bridge, not bVNC's

Same reasoning as VNC (bVNC's own RDP UI classes are just as Activity-coupled), but RDP's wire
protocol is far too complex to reimplement from scratch (unlike RFB). FreeRDP itself, however,
publishes its own clean, headless-friendly Android JNI bridge
(`com.freerdp.freerdpcore.services.LibFreeRDP`, MPL-2.0) directly in its upstream repo — vendored
here at `app/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java` (package/class name
kept exactly as upstream — the native library's `JNI_OnLoad` registers against that specific
class, confirmed by inspecting the compiled `classes.dex` of a real release APK, not assumed).

The native libraries it calls into are **prebuilt binaries**, not built from source: extracted
from iiordanov/remote-desktop-clients' official "freeaRDP" GitHub Release APK (v6.4.5) for all 4
ABIs. Building FreeRDP + OpenSSL + ffmpeg from source needs a multi-hour native cross-compile
toolchain (Cerbero) this project doesn't set up. See [LICENSE](LICENSE) for full attribution
(FreeRDP: Apache-2.0, LibFreeRDP.java: MPL-2.0, OpenSSL: Apache-2.0).

**Certificate trust**: RDP's TLS layer means an unknown/changed server certificate needs a
decision on every new host — this mirrors the main app's own SSH host-key confirmation flow
(`HostKeyStore`): unknown certificates are rejected by default (not silently accepted), reported
back to the caller via a `CERT_UNTRUSTED|host|port|fingerprint` marker over the existing
`onProgress` callback, and only trusted once the main app calls `trustRdpCertificate()` (persisted
in `rdp/RdpCertStore.kt`) and retries.

**Known gap**: only single-byte PC/AT scancodes (Enter/Backspace/Tab/Escape/Space) are mapped for
non-printable keys — arrow keys, Delete, Home/End etc. need an extended (0xE0-prefixed) scancode
encoding this project has no confirmed source for, so they're deliberately left unmapped rather
than guessed at. Printable characters (the common case) go through `sendUnicodeKeyEvent`, which is
unambiguous per the RDP protocol spec.

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
- AIDL contract: `IRemoteDesktopSessionService.createSession(...)` → `IRemoteDesktopSession`
  (resize/sendPointerEvent/sendKeyEvent/destroy), plus `trustRdpCertificate(...)` for the RDP
  certificate-confirmation flow. The `android.view.Surface` is drawn onto directly by the plugin
  process — no per-frame pixel data crosses the Binder call.
- **VNC**: connects, authenticates (None or VNC-Auth/DES), renders Raw+CopyRect-encoded updates
  onto the Surface, forwards pointer and keyboard input.
- **RDP**: connects via vendored FreeRDP JNI + prebuilt native libs, NLA security by default,
  certificate confirmation flow, renders via `OnGraphicsResize`/`OnGraphicsUpdate` callbacks,
  forwards pointer input and (printable + a handful of control) keyboard input.
- **Neither has been tested against a real server yet** — no way to run a build here. Expect the
  first real connection attempt to surface bugs, same as every other piece of this app that needed
  on-device iteration.
- **SPICE**: `buildSession()` throws a clear `UnsupportedOperationException` rather than
  attempting anything.
