# WallpaperSuite

A self-contained **Wallpaper Engine client for Android**. Browse the Steam Workshop, download wallpapers with your own Steam account, and play them as live wallpapers on your phone — no PC required.

[![Build APK](https://github.com/687meowmeow/Wallpaper-Suite/actions/workflows/build.yml/badge.svg)](https://github.com/687meowmeow/Wallpaper-Suite/actions/workflows/build.yml)

## What it is

WallpaperSuite is a full Wallpaper Engine experience that runs entirely on the device:

- **Workshop browser** — search, sort (Top Rated, Most Popular by period, Most Recent, Most Up Votes/Subscriptions), and filter by the complete Steam tag sheet, with mobile-compatibility filtering and adjustable tile size.
- **On-device downloads** — sign in with your own Steam account (Steam Guard / 2FA supported; credentials never leave the phone), download `.pkg` files straight from Steam's content servers and import them into your library. Downloads continue in the background with a progress notification.
- **Live renderer** — a bundled OpenGL ES 3.0 scene engine (ported from Wallpaper Engine's Android runtime) plays scenes, videos, images and GIFs on your home screen: real per-wallpaper property sheets (sliders, combos, colors, text, picture inputs), gyro parallax, audio-responsive effects, touch input, and Wallpaper Colors integration.
- **Wallpapers tab** — your whole collection in one grid: search, sort, pin, tags, and device folders. Tag wallpapers and filter by tag; pick any folder of images and set one as your wallpaper with a tap.
- **Playlists** — as many saved, editable playlists as you like. Each holds any mix of animated and static wallpapers, rotates randomly or in a preset order on your chosen interval, and supports time rules — pin a single wallpaper, or a group, to a window of the day (e.g. 22:00–06:00).
- **Discover** — automatic wallpapers from anime image boards (Danbooru, safebooru, gelbooru, yande.re, konachan, Lolicon). Add strict and soft tags, pick a content tier (Pure / Mix / NSFW), apply to home, lock, or both (same image or two different ones), and let it refresh on a schedule.
- **Engine settings** — FPS limit, battery-saver pausing, touch input, Wallpaper Colors, and a working clear-cache.
- **Automatic logging** — every app and engine log line is captured in-app and persisted to storage; export the full bundle from the Logs screen.
- **Pairing mode** (optional, off by default) — lets the app act as a PC for the official Wallpaper Engine Android client: browse, download and "send to phone" from this app instead.

## How it works

Downloads run through an on-device JavaSteam client that talks directly to Steam's auth and content servers — no PC, no third-party server. The renderer is a self-contained OpenGL ES 3.0 scene engine bundled with the app, ported to follow the Wallpaper Engine project format.

The Steam Workshop and its contents are downloaded under your own account's subscription rights.

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

Not affiliated with Valve, Steam, or Wallpaper Engine / Kristjan Skutta. You need your own Steam account.
