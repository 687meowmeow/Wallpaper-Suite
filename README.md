# WallpaperSuite

Browse the Steam Workshop for Wallpaper Engine, download wallpapers with your own Steam account, and play them as live wallpapers on your phone — no PC required.

[![Build APK](https://github.com/687meowmeow/Wallpaper-Suite/actions/workflows/build.yml/badge.svg)](https://github.com/687meowmeow/Wallpaper-Suite/actions/workflows/build.yml)

**Status: v0.8.x (alpha).** Browse, download, and live-render wallpapers on-device.

## What it does

- **Wallpaper library** — every wallpaper on the device in one grid: search, sort, pin, download progress, playlists.
- **Live preview** — almost-fullscreen renderer, semi-clear top bar, drag to align, gyro parallax, audio-responsive effects.
- **Per-wallpaper properties** — real property sheets with localized labels, live-applying sliders, combos, colors, text and picture inputs.
- **Workshop browser** — search, sort and filter 3M+ Wallpaper Engine items by the full Steam tag sheet.
- **Downloads** — sign in with your own Steam account (Steam Guard / 2FA supported; credentials stay on the phone), download `.pkg` files and import them into the library. Downloads continue in the background with a progress notification.
- **Engine settings** — FPS limit, battery-saver pausing, touch input, Wallpaper Colors integration, logging.
- **Automatic logging** — all app + engine logs are captured to an in-app log viewer and auto-persisted to internal storage; no manual export needed.

## How it works

Downloads run through an on-device JavaSteam client that talks directly to Steam's auth and content servers — no PC, no third-party server. The renderer is a self-contained OpenGL ES 3.0 scene engine bundled with the app, ported to follow the Wallpaper Engine project format (scenes, videos, images, GIFs).

An optional **pairing mode** (off by default) lets the app act as a PC for the Wallpaper Engine Android client. Enable it in Settings to show the "send to phone" button.

## Requirements

- Android 10 (API 29)+, arm64-v8a
- Gyroscope for *React to motion*, microphone for audio-responsive effects (both optional)
- ~1 GB free storage

## Building

JDK 17 + Android SDK (NDK 27, CMake):

```bash
./gradlew :app:assembleRelease
```

CI builds the APK on every push and attaches it to release tags.

## Disclaimer

Not affiliated with Valve, Steam, or Wallpaper Engine / Kristjan Skutta. You need your own Steam account. Workshop content is downloaded from Steam under your own account's subscription rights.
