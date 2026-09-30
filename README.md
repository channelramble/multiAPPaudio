# Multi-App Audio

**An Android app that sets each app's volume, from 0 to 100%, right from the notification shade.**
It also lets several apps play at the same time and stops Android Auto from pausing YouTube. For
Pixels and other phones running stock Android 12 or newer. No root: a one-time setup over ADB
unlocks it, and from then on everything happens in the app.

## Features

* **Per-app volume.** Turn YouTube down to 40% while Spotify stays at 100%. Pull down the
  notification shade and the **App volume** notification lists what's playing right now:
  * expand it for **- / +** buttons (10% per tap, or 5, 20 or 25%),
  * tap it for **sliders** that open over whatever app you're using,
  * or use the **App volume Quick Settings tile**.

  Each app's level is remembered and applies whenever it plays. Need more than 100%? Turn on
  **Allow boost above 100%** for up to 200%, with a limiter so it can't clip.
* **Several apps at once** (Samsung-style multi-app audio). Music and videos stop pausing each
  other. Calls, alarms and navigation prompts still interrupt.
* **Android Auto fix.** Android Auto can't pause YouTube or swap in YouTube Music, and YouTube
  plays alongside your other audio in the car. Pick more apps in the app.
* **Mute any app completely.** Pick the apps in the app.
* **Pause and resume around calls.** Media picks up again when a call ends, which Android doesn't
  do on its own when several apps play at once.

Everything survives reboots and updates. The app has no internet permission.

## Install

You need a computer with `adb` and your phone with USB debugging on
([first-time ADB setup](#first-time-adb-setup)). Plug the phone in and run these once:

```sh
# Download and install the app
curl -LO https://github.com/channelramble/multiAPPaudio/releases/latest/download/MultiAppAudio.apk
adb install -r MultiAppAudio.apk

# One-time setup for the app
adb shell pm grant io.github.channelramble.multiappaudio android.permission.POST_NOTIFICATIONS
adb shell pm grant io.github.channelramble.multiappaudio android.permission.DUMP
adb shell cmd notification allow_listener io.github.channelramble.multiappaudio/io.github.channelramble.multiappaudio.MediaListener
adb shell cmd statusbar add-tile io.github.channelramble.multiappaudio/.MixerTileService
adb shell cmd appops set com.google.android.youtube TAKE_AUDIO_FOCUS ignore
adb shell settings put system multi_audio_focus_enabled 1
adb reboot
```

After the reboot, pull down the notification shade and start something playing. That's the whole
setup. The next section explains each line, and the app's **Setup** card shows what's granted.

**Windows:** in PowerShell, type `curl.exe` instead of `curl`. Or download
[`MultiAppAudio.apk`](https://github.com/channelramble/multiAPPaudio/releases/latest/download/MultiAppAudio.apk)
in a browser and run the rest from the same folder.

**Updating:** install the new APK the same way (`adb install -r MultiAppAudio.apk`), or open it on
the phone. The setup commands don't need to be run again.

### What the setup commands do

| Command | What it's for |
|---|---|
| `pm grant … android.permission.POST_NOTIFICATIONS` | Lets the App volume notification show. The app also asks for this the first time you open it. |
| `pm grant … android.permission.DUMP` | Lets the app read Android's list of audio streams, so it knows which app is playing each one. Per-app volume needs it. |
| `cmd notification allow_listener …` | Lets the app pause and resume media players, for the call and Android Auto helpers. It never reads your notifications. |
| `cmd statusbar add-tile …` | Adds the **App volume** tile to Quick Settings. You can also add it from the app. |
| `cmd appops set com.google.android.youtube TAKE_AUDIO_FOCUS ignore` | The Android Auto fix for YouTube: it never takes audio focus, so it can't be paused or swapped out, and it plays alongside other apps. Skip it if you don't want that. |
| `settings put system multi_audio_focus_enabled 1` + `reboot` | Turns on Android's own multi-app audio mode, so media apps stop pausing each other. |

Android stores each of these and re-applies it at every boot.

### First-time ADB setup

1. On the phone: **Settings > About phone**, tap **Build number** 7 times. Then **Settings >
   System > Developer options** and turn on **USB debugging**.
2. On the computer, install ADB (Android's platform tools):
   * macOS: `brew install android-platform-tools`
   * Windows: `winget install Google.PlatformTools`
   * Linux: `sudo apt install adb` (or your distro's package)
   * Or download [platform-tools](https://developer.android.com/tools/releases/platform-tools).
3. Plug the phone in, run `adb devices`, and tap **Allow** on the phone.

## Using the app

**App volume.** Pull down the notification shade:

* The collapsed **App volume** notification shows each playing app and its level, like
  `YouTube 40% · Spotify 100%`.
* Expand it for **-** and **+** on each app.
* Tap it for sliders. Tap an app's speaker icon to mute it, or its percentage to reset it to 100%.
* The Quick Settings tile opens the same sliders.

Only apps playing right now are listed. An app drops off a few seconds after it stops, and its
level comes back when it plays again.

**In the Multi-App Audio app:**

* **App volume:** **Allow boost above 100%** (up to 200%), **Each - / + tap changes volume by**
  (5, 10, 20 or 25%), **Keep the App volume notification**, and **Reset all to 100%**.
* **Multi-app audio:** shows whether it's on, and **Resume media after calls**.
* **Android Auto:** **Choose apps** to add more pass-through apps besides YouTube, plus **Undo
  automatic source switches** and **Pause pass-through apps during calls**.
* **Mute apps:** **Choose apps** to silence completely.
* **Setup** and **Diagnostics:** what's granted, and **Copy report** if something doesn't work.

Adding pass-through or muted apps is the one thing Android only allows over ADB. The app gives you
a ready-to-copy command for each app you pick; run it once, like the setup.

## Things to know

* **Some apps can't be adjusted.** Games with low-latency audio can't take the volume effect. If
  an app shows **Can't adjust**, close and reopen it.
* **Music stays quieter after a navigation prompt.** This is an Android 16/17 bug in multi-app
  audio mode; pause and play to fix it. The app's **Multi-app audio > Read more** has Google's own
  fix, a flag you can try.
* **A pass-through app won't start.** Some players refuse to start without audio focus. The app's
  **Android Auto > Read more** has the command to undo it for that app.
* **Which apps the Android Auto fix covers.** On Android 16 it only applies to apps targeting
  Android 15 or newer; on Android 17 it covers almost everything. Also worth doing once: in
  Android Auto's settings, turn off starting media automatically, and in YouTube Music, turn off
  letting external devices start playback.
* **Media control from Settings.** Instead of the `allow_listener` command you can turn on
  notification access in Settings. Sideloaded apps may first need **Allow restricted settings**
  on the app's info page.

Tested on a Pixel 10 Pro Fold (Android 17) and on the Android 14 and 17 emulators. If something
doesn't behave, send **Diagnostics > Copy report** in an
[issue](https://github.com/channelramble/multiAPPaudio/issues).

## Uninstall

In the app, tap **Reset all to 100%** first: Android keeps the volume effect on an app that's
playing at that moment until that app closes its audio. Then undo the setup and remove the app:

```sh
adb shell settings put system multi_audio_focus_enabled 0
adb shell cmd appops set com.google.android.youtube TAKE_AUDIO_FOCUS default
adb uninstall io.github.channelramble.multiappaudio
adb reboot
```

If you added pass-through or muted apps, the app's **Read more** sections list their undo commands;
run those before uninstalling.

## Verify the download

Releases are signed with the project's release key. Its certificate SHA-256 is:

```
3bf35b33f7ec1bb42634e2dfa4fb144447701f2a5f57801f218b780324a86ee0
```

The same value is in [`.github/signing-cert.sha256`](.github/signing-cert.sha256), and the release
workflow refuses to publish an APK signed with any other key. To check a download, run
`apksigner verify --print-certs MultiAppAudio.apk`, or use an app such as AppVerifier.

## How it works

* **Per-app volume** works like an equalizer app: it attaches Android's `DynamicsProcessing`
  effect to each app's audio session and sets its gain. The `DUMP` grant lets it read which app
  owns each session. That's `dumpsys audio` up to Android 16, and AudioFlinger's track list on
  Android 17, which locks the former away from apps.
* **Multi-app audio, pass-through and mute** are Android's own settings and app-ops. They're set
  over ADB because Android doesn't let apps change them, and the app shows their state.
* **The helper** is a small foreground service, and its notification is the App volume control.
  It restarts after reboots and updates.

The details, with AOSP source references, are in [`docs/RESEARCH.md`](docs/RESEARCH.md).

**Building from source:** `./gradlew assembleRelease` with JDK 17 or 21 and the Android SDK.
`app/` is a plain Android app with no dependencies:

* `core/AudioDump.kt` reads the audio stream list (`dumpsys audio`, or `media.audio_flinger` on
  Android 17).
* `core/AppVolumes.kt` attaches `DynamicsProcessing` to other apps' audio sessions.
* `core/ActiveApps.kt` works out which apps are playing right now.
* `MixerNotification.kt`, `MixerActivity.kt` and `MixerTileService.kt` are the App volume
  notification, slider panel and Quick Settings tile.
* `core/CarWatcher.kt`, `core/SessionWatcher.kt` and `core/AutoSwitchGuard.kt` are the Android
  Auto and call helpers.
* `AudioControlService.kt` is the foreground service that hosts all of the above.
