# Getting Started

## 1. Enable ADB on the TV

Android TV / Google TV: Settings → Device Preferences → About → click
"Android TV OS build" 7 times to unlock Developer Options, then Developer
Options → turn on **USB debugging** and **Network debugging** (or just "ADB
debugging" on older Sony TVs). No pairing code needed - this repo uses
classic ADB-over-TCP on port 5555, not Wi-Fi pairing.

## 2. Install the phone app

From the [latest release](https://github.com/sudharsan-ak/TV-File-Bridge/releases/latest),
install `TV-File-Bridge.apk` on your phone - the main app, and the only one
you install manually. Open it, enter the TV's IP address, and connect. From
inside the app (Install apps for TV) it can then push the two TV-side APKs
for you:

- `TV-Bridge-Cursor.apk` - on-screen cursor overlay for Cursor mode
- `Accessibility-Watchdog.apk` - keeps that overlay's accessibility service enabled

Only install `Clipboard-Bridge.apk` yourself if you want the standalone
clipboard shortcut without the full app.

## 3. Install PC Companion (optional, for phone↔PC clipboard/file sync)

Download `PC-Companion.zip` from the same release, extract it somewhere
permanent, and run `PC-Companion.exe`. It needs the `Assets` folder next to
it - that's where its bundled `adb` and `scrcpy` live, so nothing else to
install. It starts minimized to the tray; left-click the tray icon to reopen
it.

## 4. Pair phone and PC

In the phone app's PC Sync tab, enter the PC's LAN IP address (`ipconfig` on
the PC) and the port shown in PC Companion's Settings tab, then approve the
pairing prompt that appears on the PC. Once paired, clipboard and file sync
work both directions.
