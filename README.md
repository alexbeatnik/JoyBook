# JoyBook

[![Release](https://github.com/alexbeatnik/JoyBook/actions/workflows/release.yml/badge.svg)](https://github.com/alexbeatnik/JoyBook/actions/workflows/release.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

An audiobook player for keypad Android phones with a joystick / D-pad — built for the
**Rongyue E5** (Android 13, 320×480) and usable entirely without the touchscreen, including from the
**lock screen**. Open it and it continues the book you were listening to; files stay out of the way.

Sibling app for music: **[JoyAmp](https://github.com/alexbeatnik/JoyAmp)**.

## Features

- **Player first:** the home screen is the open book — cover, title, author (from tags), chapter,
  chapter progress and whole-book progress with time left
- **Files hidden:** books, chapters, the folder and settings live behind `#` (Menu)
- Pick one books folder: every subfolder is a book, loose files in the root form one more book;
  chapters are the audio files inside (sub-folders like `CD1/` work), in natural order
- Every book remembers its own place (saved every 5 s and on pause / seek / chapter change);
  a finished book starts over
- Playback speed 0.8×–2×, sleep timer (15–60 min or end of chapter, fades out), skip step 10–60 s
- Resuming after a pause of a minute or more rewinds 5 s; rewinding past a chapter start continues
  in the previous chapter
- Speech-friendly audio focus: short interruptions pause instead of ducking; calls / alarms pause
  and resume afterwards; unplugging headphones pauses
- Cyrillic tags stored as cp1251 (`Ãåðîé…`) are shown correctly (`Герой…`)
- **Lock-screen control** through an Accessibility service, with a small on-screen pill confirming
  each press; the Android media card shows rewind · play · forward · close

## Keys

| Key | Player & lock screen |
|-----|----------------------|
| Left / Right | Rewind / forward by the skip step (15 s by default); hold to keep going |
| Up / Down | Next / previous chapter (Down restarts the chapter if more than 3 s in) |
| OK (center) | Play / pause |
| `1` / `3` | Slower / faster (player only) |
| `0` | Cycle the sleep timer (player only) |
| `#` or Menu | Open / close the menu |
| `*` | Left alone (flashlight on the E5) |

In the menu Up / Down move and OK selects. In the Books / Chapters lists: Up / Down move,
`2` / `8` page, `5` jumps to the current item, OK opens / plays, `#` or Back goes back.

## Lock screen

Turn on **Menu → Lock-screen joystick** (Android Accessibility → JoyBook). The stick is intercepted
only when **all** of these hold, otherwise it behaves normally:

- the lock screen is showing and the display is on;
- a JoyBook session is open (a chapter is loaded, playing *or* paused);
- JoyBook was the last app to take the audio, so it never fights JoyAmp for the stick;
- no call, alarm or ringtone, and no app holding audio focus briefly;
- on a PIN-protected phone you haven't just typed digits / Menu (15 s grace for the PIN pad).

With the display **fully off** Android hands the stick to no app, so light up the lock screen
first (Power / Menu). The Accessibility service only looks at the five stick keys and never logs
key presses.

If only one of JoyBook / [JoyAmp](https://github.com/alexbeatnik/JoyAmp) has its joystick service
enabled, that service also drives whatever the other app is playing through standard media keys
(Left / Right = previous / next, OK = play / pause), so the stick never ends up scrolling the lock
screen's media carousel. The player shows a warning while JoyBook's own service is off or not actually running (it can get stuck after an update; turning it off and on fixes it, and with the permission below the app does that by itself).

Some Unisoc builds drop Accessibility services after an update, and force-stopping an app always
disables its service. JoyBook can re-enable itself if you grant it permission to write secure
settings once:

```sh
adb shell pm grant com.local.joybook android.permission.WRITE_SECURE_SETTINGS
```

## Install

1. Download `JoyBook-x.y.z.apk` from [Releases](https://github.com/alexbeatnik/JoyBook/releases) and
   install it (allow installing from unknown sources).
2. Open JoyBook → `#` → **Books folder** and pick the folder with your audiobooks.
3. For lock-screen control: `#` → **Lock-screen joystick** → enable **JoyBook**.

## Build

Requirements: JDK 17+, Android SDK (compileSdk 34). Then:

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Releases

Push a tag such as `v1.2.0`, or run **Actions → Release → Run workflow** and enter the version.
The [release workflow](.github/workflows/release.yml) builds `assembleRelease`, derives the
version code from the version (`1.2.3` → `10203`) and publishes `JoyBook-1.2.0.apk` plus
`SHA256SUMS.txt` as a GitHub release. Versions with a suffix (`1.2.0-beta1`) become pre-releases.

### Signing

Android installs an update only if it is signed with the same key as the installed copy, so add
these repository secrets (**Settings → Secrets and variables → Actions**):

| Secret | Value |
|--------|-------|
| `SIGNING_KEYSTORE_BASE64` | The keystore file, base64-encoded |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |

Encode the keystore with `base64 -w0 release.jks` (Linux) or
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))` (PowerShell).
Without these secrets the workflow still publishes an APK, signed with a throwaway debug key
(fine for a fresh install, but it cannot update an existing one).

## License

Copyright 2026 Oleksii Poliakov

Licensed under the [Apache License, Version 2.0](LICENSE).
