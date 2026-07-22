# TV File Bridge

Companion suite bridging an Android phone, Windows PC, and Android TV over ADB. See [BUILD_SPEC.md](BUILD_SPEC.md) for architecture, decisions, and gotchas.

## Apps in this repo

| App | Module | Platform | What it does |
|---|---|---|---|
| TV File Bridge | `app` | Android (phone) | Main app: everything below runs from here |
| TV Companion | `tv-companion` | Android TV | Draws the cursor overlay, serves the app list |
| Clipboard Bridge | `clipboard-bridge` | Android (phone) | Edge-panel shortcut to push clipboard to PC from apps with no Share/PROCESS_TEXT support |
| PC Companion | `pc-companion` | Windows (.NET/WPF) | Tray app for phone↔PC clipboard and file sync |
| Accessibility Watchdog | `accessibility-watchdog` | Android TV | Re-enables watched accessibility services if they get disabled |

## Features

| Feature | One-liner |
|---|---|
| File browser | Browse/search/filter the TV's storage, thumbnails, storage usage |
| File transfer | TV↔phone sync folder, Share-sheet push, progress/retry |
| Remote control | D-pad, keyboard, volume, transport, power — pure ADB, no companion needed |
| Cursor mode | Real on-screen cursor + touchpad drag, via the TV companion app |
| Apps tab | Launch installed TV apps from the phone |
| Now Playing | Title/artist/progress bar scraped from the on-screen player, seek by tapping the real seek bar |
| Clipboard sync | Bidirectional text/image sync between phone and PC |
| File sync (PC) | PC→phone file push (Explorer copy → auto-send) |
| Online/Offline toggle | Manually suspend the app's ADB connection so other tools (e.g. scrcpy) can use the TV |
| Wake-on-LAN | Wakes the TV from standby via Sony's IRCC-IP protocol |
| Screenshot | Captures the TV's current screen, saves into the configured PC-sync folder |
| Fix cursor | Re-enables the TV companion's accessibility service on demand |
| Accessibility Watchdog | Standalone TV app: auto re-enables chosen accessibility services on boot, screen wake, and every ~4 hours |
