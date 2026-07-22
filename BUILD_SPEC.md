# TV File Bridge - Build Specification

A personal Android app that connects to my Sony TV over home Wi-Fi using the ADB
protocol: browse the TV's storage, transfer files, remote-control it, and sync
clipboard with a Windows PC. Four phases, all built and confirmed working.
This is a reference of what exists and why - architecture decisions and
gotchas, not a screen-by-screen spec.

---

## 1. Context & hardware

- **Phone:** Samsung Galaxy S22 Ultra. **TV:** Sony KD-50X80J, Google TV on
  Android 10 - classic ADB-over-TCP on port 5555, no wireless-debugging pairing
  codes. Same home Wi-Fi. Personal app, no Play Store.

## 2. Architecture decision

**The phone app speaks the ADB client protocol directly to the TV's built-in
ADB server on port 5555.** Nothing installed on the TV for file access.
Library: **[dadb](https://github.com/mobile-dev-inc/dadb)**, pure-Kotlin ADB
client, no adb binary needed. Rejected a sideloaded HTTP-server companion app -
unnecessary, and Android TV aggressively kills background apps anyway. ADB also
gives input injection for phase 2, which a normal TV app can't.

## 2.5 Visual design

Fixed brand palette (deep teal/cyan + amber accent, `#0b1615`/`#122321`/
`#37c6b0`/`#f0a94e`), no dynamic/Material You color - tried it, wallpaper-driven
theming made the app blend into "whatever the OS looks like today," which
defeats having a deliberate identity. Custom adaptive launcher icon, "Portal
Ring": broken ring in teal-to-amber gradient with an amber dot in the gap, dark
radial-glass background - reused across the PC companion and clipboard-bridge
apps for brand consistency.

## 3. Tech stack

Kotlin, Jetpack Compose (Material 3), multi-module Gradle (`app`,
`tv-companion`, `clipboard-bridge`). `dev.mobile:dadb` for ADB. Coroutines/Flow,
Coil for images, DataStore for settings. minSdk 26. No backend, no analytics.

## 4. Shared connection layer

`AdbConnectionManager` (singleton, app-lifetime, owns the dadb instance + RSA
keypair, one live connection at a time) sits above the UI - both the file
browser and phase 2's remote control reuse it. Keypair persists in app-private
storage forever, generated once on first run; this is what makes the TV's
"always allow" checkbox stick across sessions.

## 5. Phase 1 - file access (built)

Saved multi-device list (not a single IP) with auto-discovery (subnet TCP
probe), auto-connect on launch, on-demand reconnect on any command while
disconnected. File browser with shortcuts, search, type filter, thumbnails,
storage usage (`df`/`du`). One configurable sync folder pair (TV↔phone,
diff-based). Transfers via ADB sync protocol with progress/retry/redo, a
foreground service, and a Share-sheet target ("Send to TV").

**Sony gotcha:** after a TV power cycle the ADB TCP listener sometimes drops;
distinguish connection-refused from timeout and hint at toggling Developer
options → Network debugging.

## 6. Phase 2 - remote control (built)

Sub-tabs under one "Remote" nav entry: D-pad, Keyboard, Cursor, Apps. D-pad and
Keyboard are pure ADB (`input keyevent`/`input text`, keyboard diffs+debounces
keystrokes to the TV live). Cursor and Apps require a TV-side companion app
(below) since Android has no way to show a real OS cursor from injected ADB
events alone.

- **Cursor tab click is tap-only** (`input tap x y` over ADB), not also
  `DPAD_CENTER` - a live-TV player's paused overlay toggled play/pause on the
  tap, then immediately back off on the followup keyevent, making clicks look
  like no-ops. D-pad-confirm apps use the D-pad tab instead.
- **Touchpad gesture handling** uses one `pointerInput`/`awaitEachGesture` loop,
  not separate drag/tap detectors - two separate `pointerInput` modifiers race
  each other and one starves the other's recognition.
- **`dispatchGesture()` (AccessibilityService) doesn't work** for a synthesized
  single-point tap on this TV's OS build - cancelled instantly regardless of
  stroke shape. Clicking is done phone-side over ADB instead.

### 6.5 TV-side companion app (`tv-companion` module)
Confirmed: no OS-level cursor sprite renders for injected ADB pointer events
regardless of source flag, only for an actually-enumerated pointer-class
device. So `tv-companion` runs an AccessibilityService + `SYSTEM_ALERT_WINDOW`
overlay that draws its own cursor bitmap, driven by a plain line-based TCP
protocol (port 7912) reached via `AdbConnectionManager.tcpForward`.

- Built and bundled into the phone app's assets at build time
  (`copyCompanionApk` Gradle task); phone installs it onto the TV via
  `dadb.install()`.
- **One persistent, mutex-serialized socket** over the port-forward, not one
  socket per command - an earlier per-command version flooded dadb's internal
  thread pool during a fast touchpad drag badly enough to crash the phone app
  via an uncaught background-thread exception.
- Accessibility + overlay permission are both manual TV-side grants, wiped on
  every reinstall of `tv-companion` (Android's per-APK-signature grant model,
  not a bug) - re-grant after every companion-app code change.

## 7. App shell - navigation

Hamburger drawer, not a bottom bar with everything crammed in: Files/
Transfers/Remote stay as primary bottom-nav destinations, Settings and
Clipboard live in the drawer. Shared `AppHeader` component owns the hamburger
+ title across every screen - pulled out because per-screen top bars kept
drifting out of alignment as screens were added.

## 8. Phase 3 - background connection persistence (built)

Goal: keep the ADB connection (and the clipboard receiver, phase 4) alive
outside the foreground, without draining battery when not wanted.

- **Battery-optimization exemption card** (Settings) - a backgrounded process
  is a suspension target regardless of any foreground service without this.
- **`ConnectionForegroundService`**: user-opt-in, holds the OS's "don't
  suspend a foreground service" guarantee. Doesn't own the connection itself
  (`AdbConnectionManager` does) - just keeps the process alive for it.
- **`ConnectionTileService`**: one Quick Settings tile ("TV Connection")
  toggles the service. Deliberately the *only* tile - phase 4's clipboard
  receiving was folded into this same tile/service rather than adding a
  second one.
- **`requestAddTileService()`** (API 33+) used to prompt adding the tile -
  Samsung's own QS editor doesn't list third-party tiles at all on this
  device/OS, confirmed via testing.
- **VectorDrawable inflation bug (this test device)**: mismatched physical/
  override display density crashes (`viewportWidth > 0`) on *any* vector
  drawable inflated via `ContextCompat.getDrawable`/`Icon.createWithResource`,
  even when the compiled resource is verified correct. Worked around via raw
  `Canvas`/`Paint` drawing and `Icon.createWithBitmap()` instead of resource-ID
  based icon APIs, everywhere. Bake this in - don't rediscover it.

## 9. Phase 4 - phone↔PC clipboard sync (built)

Bidirectional, text + images, both directions. Exists because Android hard-
blocks silent background clipboard *reads* (`getPrimaryClip()` returns null
for any unfocused app, Android 10+, no permission bypass) - every phone-side
entry point below is a different way to legitimately grab focus for a moment.

**Wire protocol** (shared, symmetric): `[4-byte little-endian length][UTF-8
JSON header][payload bytes]`. Header: `type` (`text`/`image`), `deviceName`,
`text`, `imageByteLength`. Same shape phone→PC and PC→phone.
**Gotcha:** `System.Text.Json` is case-*sensitive* by default - needs
`PropertyNameCaseInsensitive = true` or every field deserializes blank against
Kotlin's camelCase JSON.

**Ports:** PC's pairing/command server 58821 (configurable). Phone's clipboard
receiver fixed at 58822.

### Phone → PC (three entry points, by app capability)
1. **Share sheet** (`CopyToPcActivity`) - any app with a Share button.
   Multi-image share pushes sequentially (each still lands in Win+V history).
2. **`ACTION_PROCESS_TEXT`** (`ProcessTextCopyToPcActivity`) - adds "Copy to
   PC" to the text-selection toolbar. **Confirmed dead end on Samsung/One
   UI** - its proprietary toolbar doesn't surface third-party handlers even
   though the filter registers correctly (verified via `dumpsys package`).
   Left in for stock Android; not relied on here.
3. **`clipboard-bridge` module** (separate app/APK, `com.tvfilebridge.
   clipboardbridge`) - the actual fix for apps with neither of the above
   (WhatsApp's own selection menu). One invisible launcher activity, added to
   Samsung Edge Panel's Apps edge: tap it after a native Copy, it reads
   clipboard, pushes to primary PC, toasts, finishes.
   - Clipboard read happens in `onWindowFocusChanged(true)`, not `onCreate`/
     `onResume` - those can fire before the OS actually grants window focus
     (the real gate `getPrimaryClip()` checks), reading too early returns null.
   - Can't read the main app's `PcDeviceStore` directly (private per-app
     storage) - main app exposes a read-only `PrimaryPcDeviceProvider`
     ContentProvider gated by a `signature`-level permission, so only an app
     signed with the same key can query the primary PC.
   - All three transient/invisible activities use `Theme.Translucent.
     NoTitleBar` as their theme parent, not a hand-rolled transparent color -
     a plain transparent `windowBackground` still let Samsung/Android paint a
     black backdrop for the activity's alive-but-invisible duration (e.g.
     waiting on a real network push). `Theme.Translucent` is AOSP's actual
     mechanism for this and holds for the full lifetime, not just at launch.

### PC → Phone
`ClipboardWatcher.cs` watches via `AddClipboardFormatListener`
(`WM_CLIPBOARDUPDATE`, non-polling), pushes to the primary phone, gated by two
*independent* toggles (images/text split so each can be enabled separately by
sensitivity). Phone's `ClipboardReceiverServer` writes straight to
`setPrimaryClip()`, started from **both** the foreground service (reliable,
tile-gated) **and** `AppContainer.init` (best-effort, runs whenever the
process is alive at all, independent of the tile) - deliberate, so it's not
gated on the tile alone.

**Gotchas:**
- `file_provider_paths.xml` needs an explicit `<cache-path>` entry per cache
  subdirectory used with `FileProvider`, or "Failed to find configured root."
- PC-side: disposing the `TcpClient` immediately after `WriteAsync` completes
  can send a TCP RST instead of a clean FIN if the phone hasn't finished
  reading (`WriteAsync` completing ≠ remote has read) - fixed with a 300ms
  delay before disposal.

### Pairing & PC companion app
One-time accept/deny per new phone (PC side), never overwrite a user-renamed
device name on a later push. Single primary device on each side, enforced
mutually exclusive, lets every phone→PC entry point skip the picker screen.
PC companion (`pc-companion/`, C#/.NET 8 WPF): tray-icon app
(`NotifyIcon`, forcing `UseWindowsForms=true` alongside `UseWPF=true` and
explicit `using X = System.Windows.X` aliases to resolve namespace clashes),
custom dark UI (no stock `MessageBox`), `ShutdownMode="OnExplicitShutdown"` so
closing the window doesn't quit the tray app, launch-at-startup via registry.

## 10. Known gotchas - ADB/file access

- ADB shell on the TV is the `shell` user: full `/sdcard` access, no other
  apps' private data.
- Only one ADB TCP client at a time on some devices - if the laptop's
  connected, the phone may fail; surface a clear error, don't hang.
- `ls` output parsing is fragile across builds; prefer dadb's sync STAT/LIST,
  handle filenames with spaces/unicode.
- Keep the ADB keypair in app-private files dir, not cache, or the TV re-prompts.
- Never pull a file twice - cache thumbnails and "Open" downloads keyed by
  path+size+mtime.

## 11. Non-goals

- No iOS, no Play Store release, no multi-user, no cloud anything.
- No TV-side app for file access itself - stays pure ADB. The `tv-companion`
  app is a deliberate, scoped exception for cursor/apps only (#6.5).
- No root, no system-partition access.
