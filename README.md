# WallpaperSuite

Browse the Steam Workshop for Wallpaper Engine, download wallpapers with your own Steam account, and play them as live wallpapers on your phone — no PC required.

[![Build APK](https://github.com/687meowmeow/Wallpaper-Suite/actions/workflows/build.yml/badge.svg)](https://github.com/687meowmeow/Wallpaper-Suite/actions/workflows/build.yml)

**Status: v0.8.x (alpha).** The app renders wallpapers itself, using the official Wallpaper Engine renderer for Android (scenes, videos, images, GIFs).

## What it does

- **Wallpaper library** — every wallpaper on the device in one grid: search, sort, pin, download progress, playlists.
- **Live preview** — the official client's preview: almost-fullscreen renderer, semi-clear top bar, drag to align, gyro parallax, audio-responsive effects.
- **Per-wallpaper properties** — the real property sheets with localized labels, live-applying sliders, combos, colors, text and picture inputs.
- **Workshop browser** — search, sort and filter 3M+ Wallpaper Engine items by the full Steam tag sheet.
- **Downloads** — sign in with your own Steam account (Steam Guard / 2FA supported; credentials stay on the phone), download `.pkg` files and import them into the library.
- **Engine settings** — FPS limit, battery-saver pausing, touch input, Wallpaper Colors integration, logging.

## How it works

Downloads run through an on-device JavaSteam client that talks directly to Steam's auth and content servers — no PC, no third-party server. The renderer is the official `libscenejni.so` from the free Wallpaper Engine Android client, driven by a port of its Java layer.

An optional **pairing mode** (off by default) lets the app act as a PC for the official Wallpaper Engine Android client. Enable it in Settings to show the "send to phone" button.

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

Not affiliated with Valve, Steam, or Wallpaper Engine. You need your own Steam account. The engine binary is used unmodified, as distributed at wallpaperengine.io/android.
