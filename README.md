# Orbit

A calm, dark home screen for the **Google TV Streamer**. The Earth drifts by behind you, your apps circle below, and the halo pulses to the bass.

Orbit replaces the stock Google TV launcher, with its rows of trailers, ads and "for you" tiles, with the few things you actually want when you press Home.

## What you get

- **Earth View backdrops.** Rotating satellite landscapes from Google Earth View, or Unsplash photos if you add a key.
- **Your apps in a ring.** Five pinned apps in a circular row. Hold OK to move or swap one.
- **The time and the weather.** A clock, today's conditions and a rain outlook, located automatically or set by you.
- **Now playing.** A badge for whatever's playing, with play/pause and skip, and a halo around it that reacts to the bass.
- **Idle and night modes.** After three minutes the screen fades to just the photo and a small clock, and from 22:30 to 06:30 the photo dims to a warm amber.
- **The Home button still works.** Pressing Home always brings you back here, and long-press Home still opens Google TV's settings panel.

Built in Kotlin with Jetpack Compose. It's a personal project for one TV, so expect to sideload it with `adb`.

## Install

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
# Compile ahead of time; the TV would otherwise run it interpreted/JIT until idle dexopt.
adb shell cmd package compile -m speed -f dev.walker.tvhome
adb shell am start -n dev.walker.tvhome/.MainActivity
```

Use the release build on the TV: the debug build runs Compose unoptimised and drops frames.

### Make it yours

Set these in `local.properties`:

| Setting | What it does |
|---|---|
| `unsplash.accessKey=...` | Use Unsplash photos instead of Earth View |
| `weather.location=lat,lon,Place` | Fix the weather location (it otherwise comes from your IP) |

The pinned apps are listed in `PINNED_PACKAGES` in `app/src/main/java/dev/walker/tvhome/Apps.kt`.

### One-time setup on the TV

Run these once, and again after a factory reset:

```sh
# Home button redirect
adb shell settings put secure enabled_accessibility_services dev.walker.tvhome/dev.walker.tvhome.HomeRedirectService
adb shell settings put secure accessibility_enabled 1
# Now playing badge (reads media sessions)
adb shell cmd notification allow_listener dev.walker.tvhome/dev.walker.tvhome.MediaListenerService 0
# Bass-reactive halo (global Visualizer; analyses levels only, records nothing)
adb shell pm grant dev.walker.tvhome android.permission.RECORD_AUDIO
# Highlight the app you were just in when you press Home
adb shell appops set dev.walker.tvhome GET_USAGE_STATS allow
# Hides the brief flash of the stock home before the redirect lands
adb shell settings put global transition_animation_scale 1.5
```

Don't `am force-stop` the app: Android then drops it from the enabled accessibility services.

## Using it with the remote

- **Hold OK** on an app to move it, replace it or open its app info.
- **Left** from the first app reaches the now-playing badge, then its play/pause and next buttons.
- **While idle**, OK plays or pauses and Right skips, without waking the screen.
- **Home** from an app highlights that app. Press Home again to highlight the middle one.

## How it takes over Home

Google TV's own launcher (`launcherx`) declares its HOME filter at priority 2, so Android ignores the default-home setting. Orbit's `HomeRedirectService`, an accessibility service, brings Orbit forward whenever `launcherx`'s `HomeActivity` appears.

`launcherx` stays installed because SystemUI handles long-press Home by sending `TOGGLE_NOTIFICATION_HANDLER_PANEL` to `launcherx`'s `DashboardHandler`. That panel (settings and quick settings) keeps working as before.

## Undo

```sh
adb shell settings delete secure enabled_accessibility_services
adb shell settings put global transition_animation_scale 1.0
adb uninstall dev.walker.tvhome
```

## Licence

[MIT](LICENSE)
