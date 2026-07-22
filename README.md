# Remote Desktop Plugin for LobiShell

This is a **standalone Android project** — open `remote-desktop-plugin/` as its own Android
Studio project. It is NOT a module of the main LobiShell app.

---

## What this is

A remote-desktop plugin for LobiShell's `REMOTE_DESKTOP` connection type, exposed via a single
AIDL bound service (protocol selected per connection).

**applicationId:** `de.lobianco.saftssh.remotedesktop`

**Protocol support:**
- **VNC** — implemented (`vnc/VncClient.kt`), from scratch, not derived from bVNC's source. Also
  connects over a WebSocket (`vnc/ProxmoxVncWebSocket.kt`) for Proxmox VE's noVNC-style console —
  see the SPICE section below for why that's needed and when it's used.
- **RDP** — implemented (`rdp/RdpClient.kt`), backed by FreeRDP's own upstream Android JNI bridge
  (vendored, see below) plus prebuilt native libraries.
- **SPICE** — implemented (`spice/SpiceClient.kt`), backed by a minimal from-scratch native bridge
  (`com/undatech/opaque/SpiceCommunicator.kt`) to vendored, prebuilt `libspice.so`/
  `libgstreamer_android.so` (see below).

---

## Why VNC is a from-scratch client, not the vendored library

`remote-desktop-clients/` is still vendored here as a git submodule
(https://github.com/secco04/remote-desktop-clients, a fork of
[iiordanov/bVNC](https://github.com/iiordanov/bVNC)) — kept for reference (it was also the source
consulted, read-only, when building SPICE support's minimal native bridge — see below). See
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

**Keyboard**: `rdp/RdpKeycode.kt` maps non-printable/modifier keys to Windows Virtual-Key codes —
`sendKeyEvent` (despite the name) expects a VK code, not a scancode; FreeRDP derives the scancode
and the extended-key flag itself, so arrows/Home/End/etc. work by passing their plain VK code (see
that file's doc). Printable characters go through `sendUnicodeKeyEvent` instead, which is
unambiguous per the RDP protocol spec and independent of the negotiated keyboard layout.

---

## Why SPICE reimplements the native bridge minimally, instead of vendoring bVNC's

Same wire-protocol-too-complex-to-reimplement reasoning as RDP, but unlike FreeRDP, iiordanov/
remote-desktop-clients' own `com.undatech.opaque.SpiceCommunicator` (GPL-3.0) isn't a
headless-friendly bridge on its own — it extends the same Activity-coupled `RfbConnectable`/
`Viewable` framework VNC's own classes have (30-method `Viewable` interface, USB device permission
handling, oVirt session management), none of which a bound service with no window needs.

Reading `libspice.so`'s actual JNI surface (`android-service.c`/`android-io.c` in the vendored
`remote-desktop-clients` submodule) showed its `JNI_OnLoad` does NOT batch-register native methods
via `RegisterNatives()` the way FreeRDP's does — each native method is resolved lazily by the
classic `Java_com_undatech_opaque_SpiceCommunicator_*` JNI symbol convention. That means only the
package name (`com.undatech.opaque`) and class name (`SpiceCommunicator`) are load-bearing, not the
full original class — so `com/undatech/opaque/SpiceCommunicator.kt` here is a minimal, from-scratch
rewrite: only the native methods this plugin actually calls (`SpiceClientConnect`,
`SpiceClientDisconnect`, `SpiceButtonEvent`, `SpiceKeyEvent`, `UpdateBitmap`,
`SpiceRequestResolution`) plus the six static callbacks native code looks up by exact name+signature
(`OnSettingsChanged`, `OnGraphicsUpdate`, `OnMouseUpdate`, `OnMouseMode`, `ShowMessage`,
`OnRemoteClipboardChanged` — confirmed via `GetStaticMethodID` call sites, not guessed). See that
file's class doc for the full reasoning, and `spice/SpiceClient.kt`/`spice/SpiceKeycode.kt` for how
mouse buttons (verified against the vendored reference `RemoteSpicePointer.java`) and keyboard input
(PC/AT Scan Code Set 1 — SPICE has no separate Unicode input path like VNC/RDP, so producing a
shifted character requires explicitly bracketing a synthetic Shift press/release) are handled.

`libspice.so` requires GStreamer at runtime (`org/freedesktop/gstreamer/GStreamer.java`, vendored
verbatim — GStreamer's own public "copy this file into your Android project" boilerplate, LGPL-2.1)
even with audio disabled, since spice-gtk uses it for the video-streaming channel's decode too. The
native libraries (`libspice.so`, `libgstreamer_android.so` — the latter alone is ~45-65MB per ABI,
a real APK size cost) and GStreamer's required font/CA-certificate assets are prebuilt, unmodified,
extracted from iiordanov/remote-desktop-clients' official "freeaSPICE" GitHub Release APK (v6.4.5,
same release RDP's libraries came from) for arm64-v8a and armeabi-v7a (x86/x86_64 skipped — no real
phone ships those). See [LICENSE](LICENSE) for full attribution.

**Two connect paths**: a plain direct connect (`SpiceCommunicator.SpiceClientConnect` — no TLS, no
proxy, straight to host:port) for a directly-reachable SPICE server, and a TLS+proxy path for
Proxmox VE, which never exposes a static SPICE endpoint at all — every console session goes through
`spiceproxy`'s HTTP CONNECT tunnel (port 3128) using a fresh, short-lived, always-TLS-encrypted
per-VM ticket (confirmed against Proxmox's own API schema and wiki — "all traffic is fully
encrypted" — not assumed). Critically, `SpiceClientConnect`'s own C implementation hardcodes a NULL
proxy argument — reading `android-service.c` directly showed that's a dead end for Proxmox no
matter what's passed from Kotlin. The only entry point that honours a `proxy` property is
`StartSessionFromVvFile`, which parses a virt-viewer `.vv` config file (GLib GKeyFile/freedesktop
Desktop-Entry INI format); `SpiceClient` builds one on the fly (host/tls-port/password/proxy/ca from
a `de.lobianco.saftssh.data.remotedesktop.proxmox.ProxmoxApiClient`-fetched ticket, in the main
app) and connects through that instead when `tlsPort > 0`. The main app exposes this as a "Proxmox
VE" connection type (not a raw "SPICE" host/port entry): saving one prompts for the Proxmox API
address + `user@realm` + password, then browsing/picking a VM (`ProxmoxVmListScreen`) fetches a
fresh ticket and connects. The Proxmox API's own (typically self-signed) TLS certificate uses the
same trust-on-first-use pattern as RDP's `RdpCertStore` above (`ProxmoxCertStore`, main-app side).

**Relative mouse mode** (`SPICE_MOUSE_MODE_SERVER`, used by some fullscreen/game guests that grab
the pointer) isn't supported — this client always sends absolute coordinates; the server silently
rejects input while in that mode.

**Only one SPICE session at a time per plugin process** — `libspice.so`'s own state (the connected
display, JNI method-ID cache, etc.) is process-global, not per-instance, so a second concurrent
SPICE connect attempt is rejected outright by `SpiceCommunicator.attach()` rather than silently
corrupting the first session.

**The plugin process restarts itself after every SPICE session ends.** Confirmed on-device: a
second SPICE connect shortly after a first one ended could crash the native library outright
(SIGSEGV, logcat showed it mid `channel_destroy`, right after "Starting main loop"). Root cause,
confirmed by reading `android-service.c` directly: `Java_..._StartSessionFromVvFile` — unlike
`Java_..._SpiceClientConnect` — never resets its cached JNI globals
(`jvm`/`jni_connector_class`/method IDs) when a session ends, and `SpiceClientDisconnect()` only
*schedules* its teardown on the native glib main loop (`g_main_context_invoke`) rather than
completing it synchronously before the blocking connect call returns. A second connect arriving
before that scheduled teardown has actually drained races on shared native state. Can't patch the
prebuilt `.so`, so `RemoteDesktopSessionService.destroyInternal()` kills the whole plugin process
once a SPICE session's the last one open, so the next `createSession()` (any protocol) always
starts against a freshly loaded native library. Costs a cold-start delay on the next connect;
correctness over speed here.

**SPICE → VNC fallback (Proxmox VE only)**: not every VM has its Display set to SPICE (Proxmox
defaults to a different display type), and Proxmox's VNC console works regardless — so
`RemoteDesktopViewModel.connectViaProxmox` (main app) tries a spiceproxy ticket first and silently
falls back to a vncproxy ticket if that fails. Proxmox's VNC console has the exact same
no-raw-TCP-port architecture as SPICE's spiceproxy (everything tunnels through pveproxy's own
WebSocket upgrade on port 8006 — confirmed against Proxmox's API schema, not guessed), so this
needed a real WebSocket transport for `VncClient` — see `vnc/ProxmoxVncWebSocket.kt` (wraps an
OkHttp `WebSocket` as a blocking `InputStream`/`OutputStream` pair, so `VncClient`'s existing RFB
parsing — already written against `DataInputStream`/`DataOutputStream`, transport-agnostic — runs
over it completely unchanged). TLS is pinned to the exact certificate fingerprint the main app's
own Proxmox API login already validated (trust-on-first-use once, not twice — see that class's
doc), not a fresh separate trust prompt.

---

## Building

Open this directory as its own Android Studio project (File → Open → select
`remote-desktop-plugin/`). The plugin APK must be installed on the device alongside the main
LobiShell app. The main app binds the service using the action
`de.lobianco.saftssh.remotedesktop.BIND_SESSION_SERVICE`.

**ABI-split builds**: `assembleRelease` produces `app-arm64-v8a-release.apk` and
`app-armeabi-v7a-release.apk` (no universal APK) — the SPICE/GStreamer native libs alone made a
combined build ~146MB, so install whichever matches the target device (arm64-v8a for any real
phone from roughly the last 8 years) rather than shipping every ABI's copy in one file. x86/x86_64
aren't built at all — no real device ships them, only emulators.

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
  onto the Surface, forwards pointer and keyboard input. Also connects over a WebSocket
  (`vnc/ProxmoxVncWebSocket.kt`) for Proxmox VE's noVNC-style console, as a fallback when SPICE
  isn't available on a VM — see the SPICE section above.
- **RDP**: connects via vendored FreeRDP JNI + prebuilt native libs, NLA security by default,
  certificate confirmation flow, renders via `OnGraphicsResize`/`OnGraphicsUpdate` callbacks,
  forwards pointer input and (printable + a handful of control) keyboard input.
- **SPICE**: connects via the minimal `com.undatech.opaque.SpiceCommunicator` bridge + prebuilt
  native libs, renders via `OnGraphicsUpdate`/`UpdateBitmap`, forwards pointer input (SPICE's own
  button numbering) and keyboard input (PC/AT scancodes, with synthetic Shift-bracketing for
  symbols). Both plain direct connections and TLS+proxy Proxmox VE connections (via a `.vv` file
  and `StartSessionFromVvFile`) — see the SPICE section above.
- **SPICE against a real Proxmox VE server has been tested on-device** (this is how the process-
  restart-after-SPICE and spiceproxy-proxy-host-rewrite fixes above were found) — RDP, plain VNC,
  and the new VNC-over-WebSocket fallback have NOT. Expect the first real attempt at each to surface
  further bugs, same as everything else in this app that needed on-device iteration.
