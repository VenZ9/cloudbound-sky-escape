# CloudBound — Sky Escape

CloudBound is an offline native Android balloon-survival game built with **Kotlin**, **Jetpack Compose (Material 3)**, and a custom **hardware-accelerated native `Canvas`** renderer. It is entirely self-contained: no network connection, no real-money purchases, and no third-party backend services.

## Features

- **Core flight survival** — steer a hot-air balloon upward through procedurally generated hazard patterns (birds, storm clouds, rocks, telegraphed lightning, wind gusts, rock passages) with a dynamic difficulty ramp.
- **Five hand-crafted balloon skins** — Sunrise Classic (default), Azure Aeronaut, Golden Zephyr, Storm Chaser, and Aurora Dream, each with unique Canvas-drawn patterns.
- **Coin shop (Balloon Hangar)** — collect ✦ Sky Treasures during flights and spend them to unlock or equip skins, with purchase validation.
- **Sky Mystery Crate** — spend 40 ✦ for a lottery reward with transparent odds: 60% coin pouch (25–65 ✦), 25% jackpot (75–125 ✦), 15% rare skin (with a 100 ✦ consolation when all skins are already owned).
- **Procedural audio** — zero-asset sound engine synthesizing coin chimes, impact thuds, game-over motif, button taps, unlock fanfare, and looping ambient music via Android `AudioTrack` PCM synthesis.
- **Haptic feedback** — native `VibrationEffect` feedback for coins, collisions, game over, and taps.
- **Persistence** — best score, treasure total, unlocked/equipped skins, flight count, and all settings are saved on-device and restored automatically.
- **Visual polish** — parallax layered skies that tint with altitude, drifting clouds and floating islands, spinning 3D coins, dynamic particles, invulnerability glow/flicker, and telegraphed lightning warnings.

## Controls

- **Touch drag (default)** — drag anywhere on the screen to steer the balloon; finger offset keeps your view clear.
- **Tilt (optional)** — toggle accelerometer steering in Settings or on the main menu (only on devices with an accelerometer).
- **Pause button** in the flight HUD; resume, restart, or return to menu from the pause overlay.

## Android Requirements

- Minimum: **Android 8.0 (API 26)**
- Target/Compile: **Android 15 (API 35)**
- Portrait orientation; VIBRATE permission for haptics (no other permissions requested)

## Local Build

Requires **JDK 17** and an **Android SDK** with platform 35 + build tools installed (or `ANDROID_HOME` set).

```sh
# Run unit test suite
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install app-debug.apk`.

The Gradle wrapper (`gradlew`, `gradle/wrapper/`) is committed and pinned to **Gradle 8.9** — no local Gradle install is needed.

## Build the APK with GitHub Actions (no local SDK needed)

Every push to `main` and every manual run triggers the workflow `.github/workflows/android.yml`, which:

1. Sets up JDK 17 and the Android SDK (platform 35, build tools).
2. Runs the unit test suite (`testDebugUnitTest`).
3. Assembles the debug APK (`assembleDebug`).
4. Uploads the APK as a downloadable workflow artifact.

To download the APK:

1. Open the repository's **Actions** tab.
2. Select the latest successful **Build Android APK** run.
3. Under **Artifacts**, download **cloudbound-debug-apk** (contains `app-debug.apk`).
4. Unzip and sideload the APK onto your device (enable "Install unknown apps" if prompted).

## Project Structure

- **`GameEngine.kt`** — Normalized physics simulation, procedural obstacle spawner, dynamic difficulty ramp, coin magnetism, balloon skin catalog, shop purchase validation, mystery crate lottery, and lifecycle state machine (`MENU`, `PLAYING`, `PAUSED`, `GAME_OVER`).
- **`CloudGameView.kt`** — Hardware-accelerated native Android `View` with custom Canvas rendering: parallax skies, floating islands, balloon skins, animated hazards, spinning coins, particles, accelerometer tilt input, and touch-drag steering.
- **`AudioEngine.kt`** — Zero-asset procedural audio via `AudioTrack` PCM synthesis, with lifecycle-aware music pause/resume and full cleanup on release.
- **`HapticEngine.kt`** — Native vibration feedback with API 26+ fallbacks and permission-safe guards.
- **`MainActivity.kt`** — Jetpack Compose Material 3 UI: main menu with stats, flight HUD, hangar shop, settings, how-to-play, pause/game-over overlays, mystery crate result modal, and progress reset with confirmation.
- **`res/drawable/ic_cloudbound.xml`** — Vector app launcher icon and logo.
- **`app/src/test/.../GameEngineTest.kt`** — Unit test suite covering physics, safe bounds, scoring/timer, coin collection and magnetism, collisions and invulnerability, game over/best score, skin shop purchases, mystery crates, hint dismissal, difficulty progression, lightning telegraphs, and state callbacks.

## Progression and Data Persistence

All progression data is stored on-device in Android `SharedPreferences` (`cloudbound_progress`) and auto-saved whenever the score, coin total, shop state, or settings change:

- `best_score` — highest altitude score achieved
- `total_coins` — total treasures accumulated across flights
- `unlocked_skins` — set of unlocked balloon skin IDs
- `selected_skin` — currently equipped balloon skin
- `total_flights` — total number of flights launched
- `tilt` — accelerometer steering preference (with automatic touch fallback)
- `sound` / `music` / `haptics` — audio and tactile preferences

No real-money transactions, paid services, or tracking are included.
