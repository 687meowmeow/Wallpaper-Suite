# WallpaperSuite

> A complete personal Wallpaper Engine for Android: browse 3M+ Steam Workshop items, download them with your own Steam account, **and render them as live wallpapers directly on your phone** — powered by the official WE engine. **No PC required.**

**Status: v0.8.3-alpha.** The app integrates the official Wallpaper Engine renderer (scenes, videos, images, GIFs) as a live wallpaper service. The home screen is the wallpaper library; the preview screen matches the official client's PreviewActivity (fullscreen renderer, swipe-up properties drawer, parallax/motion input, audio-responsive effects, apply button with the lockscreen notice and revert flow); per-wallpaper properties are the official property sheets with proper localizations, and logging is the official WEERR/applog.txt pipeline. The workshop browser, mpkg packer and pairing proxy are all still here — pairing is **opt-in** behind a Settings toggle.

[![Build APK](https://github.com/687meowmeow/Wallpaper-Suite/actions/workflows/build.yml/badge.svg)](https://github.com/687meowmeow/Wallpaper-Suite/actions/workflows/build.yml)

---

## ⚠️ Vibe coded notice

This project is **vibe coded**. It was built largely with AI assistance, by prompting, testing on a phone, and prompting again.

## Why this exists

Because I'm too lazy to turn my PC on.

---

## What this is

WallpaperSuite is an Android app (Kotlin + Jetpack Compose, min Android 10 / API 29, target SDK 35) that lets you browse the Steam Workshop for Wallpaper Engine (app 431960), inspect items, and download the actual wallpaper packs to your device — where the app's **integrated Wallpaper Engine** (the official `libscenejni.so` renderer, ported 1:1 from the free WE Android client) plays them as live wallpapers.

Downloads run through **on-device JavaSteam**. Your Steam credentials stay on your phone and JavaSteam talks directly to Steam's auth and content servers. No PC, no server, no third-party service. Steam Guard 2FA is supported, and refresh tokens are saved so you can skip 2FA on later sessions.

The app can also act as a **pairing proxy** for the official Wallpaper Engine Android app (WepWawet-style "fake PC" mode). This is **off by default** — enable it with the **Settings → "Send to phone"** toggle, which starts the pairing service and brings the send-to-phone button back. While the toggle is off, nothing pairing-related runs at all: no foreground service, no discovery broadcasts, no boot restart.

## Features

### The engine (official parity)
- **Official renderer**: the real `libscenejni.so` + the ported `io.wallpaperengine.*` layer (views, service, properties, parallax, touch) — scenes, videos, images and GIFs play exactly like the official client.
- **Wallpaper library home screen**: every wallpaper the engine knows (bundled official WE wallpapers, workshop downloads, imported files) in a square-thumbnail grid with overlaid titles, **search**, **sorting** (newest / oldest / alphabetical) and **pin to top**.
- **Live preview, like the real app**: almost-fullscreen renderer behind a semi-clear top bar; **drag to align** the wallpaper, **tilt for parallax** ("React to motion"), audio-responsive effects with the permission flow; static preview bitmap that fades into the live scene; battery-saver banner.
- **Swipe-up properties drawer**: the official properties panel rebuilt — localized labels (e.g. *React to motion*, *Motion strength*, *Show color options*), bool switches, precision sliders that apply live, combo radio dialogs, a full **color picker**, text inputs and user-texture pickers; dependency visibility rules; **Reset Wallpaper Settings**; changes apply live and persist on leave.
- **Apply flow**: apply button in the top bar (long-press for the official Apply / Revert-to-system dialog), lockscreen notice on first apply, playlist-aware.

### Workshop & downloads
- **Browse & search**: scroll through Wallpaper Engine workshop items, sort them, search by text, and filter by the full Steam tag sheet.
- **Steam login**: sign in with your own account, including Steam Guard / 2FA. Your login is remembered.
- **Download**: one button downloads the real `.pkg` via JavaSteam, packs it into the `.mpkg` the engine plays (byte-exact PC packing parity), and imports it straight into the app's wallpaper library. No PC, no pairing required.
- **Send to phone (opt-in)**: the PC-pairing subsystem for the official WE Android app, behind a Settings toggle.
- **Manual import**: open `.mpkg` / video / image / GIF files from any file manager, or pick them in-app.

### Engine settings & logging
- **General settings parity**: FPS limit, pause while saving battery, wallpaper touch input, **Wallpaper Colors integration** (all five official modes) and restore-official-wallpapers.
- **Playlists**: random / sorted / time-of-day / day-of-week playlists with per-item durations.
- **Official logging**: the real client's `WEERR` logcat tag and `filesDir/logs/applog.txt` file (same timestamp format, 1 MiB truncation), shared with the native engine's own log output — enabled by the **Write Errors to Log** setting, exportable via **Save Error Log**. Crash reports append the engine log tail, so wallpaper issues are diagnosable from one file.
- **In-app logs**: structured multi-domain logging with the verbose toggle, plus crash capture.

## Tech stack

| Area | Tech |
|------|------|
| Language / UI | Kotlin 2.2, Jetpack Compose, Navigation Compose |
| Renderer | Official `libscenejni.so` (from the free WE Android client) + the ported `io.wallpaperengine.*` Java layer |
| Networking | OkHttp, Retrofit, kotlinx.serialization |
| Steam access | [JavaSteam](https://github.com/Longi94/JavaSteam) via a patched fork (`com.github.687meowmeow:JavaSteam`, `1.8.0-wepwawet10`) |
| Images | Coil |
| Background work | WorkManager, foreground services |
| Native | C++ / CMake, etc2comp (ETC2 encoding) |
| Compression | lz4-java, zstd-jni, XZ |
| Build | Gradle (Kotlin DSL), AGP 8.7, GitHub Actions |

The JavaSteam fork exists because upstream skips some Wallpaper Engine workshop items (it doesn't recognize their file type), which made downloads hang forever.

## Repo layout

| Path | Purpose |
|------|---------|
| `app/` | Android app module |
| `app/src/main/java/com/wepwawet/ui/` | Compose UI: screens, components, theme, nav host |
| `app/src/main/java/com/wepwawet/data/` | Steam API service, repository, models, download manager |
| `app/src/main/java/com/wepwawet/pairing/` | JavaSteam downloader, pairing server, discovery, `.pkg`/`.mpkg` handling, ETC2 encoder |
| `app/src/main/java/com/wepwawet/engine/` | Engine library bridge: enumerate / import / delete wallpapers |
| `app/src/main/java/io/wallpaperengine/` | The ported official client layer (JNI ABI — names are load-bearing) |
| `app/src/main/java/com/wepwawet/auth/` | Steam login activity |
| `app/src/main/java/com/wepwawet/log/` | Logger, the official WEERR/applog pipeline, crash handler, log persistence |
| `app/src/main/cpp/` | JNI + bundled etc2comp |
| `app/src/main/assets/` | The engine's own assets (fonts, locale, scripts, shaders, materials) |
| `app/proguard-rules.pro` | R8 rules |
| `app/debug.keystore` | Stable debug keystore so all builds share a signing key |
| `.github/workflows/build.yml` | CI: build the APK and attach it to a release |

## Device requirements

- **Android:** 10 (API 29) or higher
- **ABI:** arm64-v8a only
- **SoC:** Snapdragon 8 Gen 2 or newer is the tested target
- **RAM:** 6 GB+ recommended
- **Storage:** 1 GB+ free for the app and downloaded packs
- **Network:** internet access for Steam APIs
- **Optional:** a gyroscope enables *React to motion* (Device Motion parallax); a microphone enables audio-responsive effects

## Building

### Local

Requirements: JDK 17 and the Android SDK with NDK + CMake.

```bash
./gradlew :app:assembleRelease
```

## Disclaimer

Not affiliated with Valve, Steam, or Wallpaper Engine / Kristjan Skutta. You need your own Steam account. Wallpaper Engine's Android client is free; its engine binary is used here unmodified, as distributed at wallpaperengine.io/android.

## License

TBD. Likely Apache 2.0 or GPL 3, depending on what's simplest for third-party compliance. A `LICENSE` file will be added once decided.
