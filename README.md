# tv-home

Minimal dark launcher for the Google TV Streamer: clock, five pinned apps, and rotating
Earth View landscapes (or Unsplash, if `unsplash.accessKey=...` is set in `local.properties`).

Pinned apps live in `PINNED_PACKAGES` in `app/src/main/java/dev/walker/tvhome/Apps.kt`.

## Build and install

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
# Compile ahead of time; the TV would otherwise run it interpreted/JIT until idle dexopt.
adb shell cmd package compile -m speed -f dev.walker.tvhome
adb shell am start -n dev.walker.tvhome/.MainActivity
```

Use the release build on the TV: the debug build runs Compose unoptimised and drops frames.

Weather location defaults to IP geolocation; override with `weather.location=lat,lon,Place`
in `local.properties`.

## How Home is taken over

Google TV's launcher (`launcherx`) declares its HOME filter at priority 2, so Android ignores the
default-home setting. `HomeRedirectService` (an accessibility service) brings this launcher forward
whenever `launcherx`'s `HomeActivity` appears. `launcherx` stays installed because long-press Home is
handled by SystemUI, which sends `TOGGLE_NOTIFICATION_HANDLER_PANEL` to `launcherx`'s
`DashboardHandler`. That panel (settings, quick settings) is unchanged.

One-time setup on the TV (or after a factory reset):

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

## Remote controls

- Hold OK on a row app: move it, replace it, or open app info.
- Left from the first app reaches the now-playing badge, then its play/pause and next buttons.
- After 3 minutes idle the screen fades to the photo; OK plays/pauses and Right skips without waking.
- Home from an app highlights that app; Home again highlights the middle one.

## Undo

```sh
adb shell settings delete secure enabled_accessibility_services
adb shell settings put global transition_animation_scale 1.0
adb uninstall dev.walker.tvhome
```
