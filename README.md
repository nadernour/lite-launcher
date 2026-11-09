# Lite Launcher

A minimal home screen for Android TV. It is plain Java against the Android
framework with no libraries, and the APK is about 33 KB.

On the home screen, the D-pad moves between tiles and OK opens one. Holding OK
on a tile opens a menu:

- **Move:** the ring turns orange. Use the D-pad to move the tile, then press OK.
- **Hide:** removes the tile.
- **Choose apps:** a checklist of every app. Press Back when finished.
- **App info:** where an app can be uninstalled.

## Build

Android Studio can open this folder directly. From a terminal:

    JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleRelease

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

## Install on the TV

1. On the TV, open Settings > Device Preferences > About and press OK on
   "Build" seven times to unlock Developer options.
2. In Developer options, turn on USB debugging. Some TVs have no separate
   network debugging option but still accept the connection below.
3. From this computer, accept the prompt that appears on the TV after connecting:

       adb connect <TV-IP>:5555
       adb install -r app/build/outputs/apk/release/app-release.apk
       adb shell cmd package set-home-activity com.tvlauncher.lite/.LauncherActivity

4. Press Home on the remote.

## Go back to the stock launcher

On this TV, FLauncher has been uninstalled and Google's launcher is disabled, so
Lite Launcher is the only full home app. Enable Google's launcher first:

    adb shell pm enable com.google.android.tvlauncher
    adb uninstall com.tvlauncher.lite

## Background changes made on this TV

Each row can be undone with its command.

| Change | Undo |
|---|---|
| Window animations 1.0 to 0.5 | `adb shell settings put global window_animation_scale 1.0` |
| Transition animations 1.0 to 0.5 | `adb shell settings put global transition_animation_scale 1.0` |
| Animator duration 0.6 to 0.5 | `adb shell settings put global animator_duration_scale 0.6` |
| Screensaver off | `adb shell settings put secure screensaver_enabled 1` |
| Idle timer 5 min to 3 h | `adb shell settings put system screen_off_timeout 300000` |
| Backdrop screensaver disabled | `adb shell pm enable com.google.android.backdrop` |
| Play Movies disabled | `adb shell pm enable com.google.android.videos` |
| TV recommendations disabled | `adb shell pm enable com.google.android.tvrecommendations` |
| Vendor recommendations disabled | `adb shell pm enable com.smartdevice.recommendation` |
| Google Assistant and voice search disabled | `adb shell pm enable com.google.android.katniss` |
| Chromecast disabled | `adb shell pm enable com.google.android.apps.mediashell` |
| YouTube video previews off | In YouTube: Settings > Previews > On |

The screensaver and idle timer belong together. With the screensaver off, the
5-minute timer would put the TV to sleep after 5 idle minutes, so undo both or
neither.

## If Home still opens the stock launcher

Some TVs ignore the setting above. List the installed home apps:

    adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME

Then disable the stock one. It is usually `com.google.android.tvlauncher`, or
`com.google.android.apps.tv.launcherx` on Google TV:

    adb shell pm disable-user --user 0 <stock-launcher-package>

Undo that with:

    adb shell pm enable <stock-launcher-package>

Re-enable the stock launcher before uninstalling Lite Launcher, so the TV always
has a home app.
