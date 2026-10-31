# Endless Drive for Android

[Endless Drive](https://github.com/hhaider3/project-magellan), the web game, in a full-screen Android app. The only reason
for the app over the browser: it asks the display for its fastest refresh rate,
so a 120 Hz phone can run the game above 60 fps (Chrome keeps pages at 60 Hz
on many phones).

- The game is bundled: each build copies it from a checkout of the game
  repository (`gameDir` in `gradle.properties`, `../test1` by default) into the
  app's assets, without the Blender sources. Rebuild after changing the game.
- The app serves it from `https://appassets.androidplatform.net/`, so its ES
  modules, module workers and import map load as they do from a web server.
- On start it picks the display's fastest mode at the current resolution
  (`preferredDisplayModeId`), and on Android 15+ also asks for the high frame
  rate category, so a variable-rate panel can run at its top rate. (Some phones,
  OnePlus among them, keep the rate for apps they list as games at a lower cap
  for everything else; the app can't change that.)
- The game updates itself. When the app starts or comes back to the front it asks
  GitHub for the newest commit on the game repository's `main` branch; if that is
  not what the app is running, it downloads the game files of that commit in the
  background (about 40 MB, only on an unmetered network such as Wi-Fi, and at
  most one check every 15 minutes). The new version loads right away if you
  haven't started a drive yet, with a "Game updated" toast; otherwise the next
  time the app starts, so a drive (even a paused one) is never lost. Only a
  newer commit replaces the game: a build bundling a game commit that isn't on
  GitHub yet keeps it. Only the game updates
  this way; changes to the app itself still need a new APK. Installing a new APK
  drops any downloaded game and starts from the one bundled in it.
- Landscape only, full screen, screen kept on. Back pauses a drive; on the menu
  it sends the game to the background.

## Install

Download `endless-drive.apk` from the [latest release](https://github.com/hhaider3/endless-drive-android/releases/latest) on the phone and open it (allow installing from your browser when asked).

## Build

Needs Android Studio (its JDK and SDK). From this folder:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. With the phone connected
over USB (developer options → USB debugging):

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run.

To see the frame rate, turn on **FPS meter** in the game's menu. Debug builds can
be inspected from desktop Chrome at `chrome://inspect`.

If the phone still holds the app at 60 Hz, check its refresh rate setting
(OnePlus: Display → Screen refresh rate → High) and any battery saver or game
mode limits.
