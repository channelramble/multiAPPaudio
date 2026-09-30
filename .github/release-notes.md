**Set each app's volume, 0-100%, right from the notification shade.** Multi-App Audio also lets
several apps play at the same time and stops Android Auto from pausing YouTube. For Pixels and
other phones running stock Android 12 or newer. No root: a one-time setup over ADB unlocks it.

### New in 0.3.0

- **App volume from the notification shade.** It lists the apps playing right now. Expand the App
  volume notification for - and + buttons, or tap it for sliders over whatever you're using.
  There's also an App volume Quick Settings tile.
- **0-100% by default.** Turn on **Allow boost above 100%** for up to 200%. Each - / + tap changes
  the level by 10%; pick 5, 10, 20 or 25% in the app.
- **Per-app volume works on Android 17.** Android 17 no longer lets apps read `dumpsys audio`, so
  the app couldn't see what was playing. It now reads the audio engine's track list instead, with
  the same one-time DUMP grant.
- **Survives app updates.** Volume effects re-attach on their own after the app restarts.
- **Simpler main screen.** One card per feature with a status line; details under **Read more**.

> **Upgrading from v0.1.0:** uninstall it first. v0.1.0 was signed with a temporary key, so this
> release can't install over it. From v0.2.0 on, every update installs over the last one.

### Install

With USB debugging on and the phone plugged in, run these once:

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

After the reboot, pull down the notification shade and start something playing. Already on
v0.2.0 or later? Just install the new APK; the setup carries over.

What each line does, first-time ADB setup, and uninstalling are in the
[README](https://github.com/channelramble/multiAPPaudio#readme). Tested on a Pixel 10 Pro Fold
(Android 17) and the Android 14 and 17 emulators. If something doesn't behave, use
**Diagnostics > Copy report** in the app and open an issue with it.
