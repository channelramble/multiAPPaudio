Samsung-style multi-app audio, per-app volume and Android Auto fixes for stock Android (Pixel).
**ADB only.** No root, no Shizuku. Every command is run once and persists across reboots.

> Tested on the Android 14 and Android 17 emulators. If something doesn't behave on your device,
> use **Diagnostics → Copy report** in the app and open an issue with it.

### New in 0.3.0

- **App volume from the notification shade.** It lists the apps playing right now. Expand the App
  volume notification for - and + buttons, or tap it for sliders over whatever you're using.
  There's also an App volume Quick Settings tile (**Add Quick Settings tile** in the app).
- **0-100% by default.** Turn on **Allow boost above 100%** for up to 200%. Each - / + tap changes
  the level by 10%; pick 5, 10, 20 or 25% in the app.
- **Per-app volume works on Android 17.** Android 17 no longer lets apps read `dumpsys audio`, so
  the app couldn't see what was playing. It now reads the audio engine's track list instead, with
  the same one-time DUMP grant.
- **Survives app updates.** Volume effects re-attach on their own after the app restarts.
- **Simpler main screen.** One card per feature with a status line; details under **Read more**.

> **Upgrading from v0.1.0:** uninstall it first. v0.1.0 was signed with a temporary key, so this
> release can't install over it. From this release on, every update installs over the last one.

### Core setup (no app needed)

```sh
adb shell settings put system multi_audio_focus_enabled 1              # multi-app audio
adb reboot                                                              # once
adb shell cmd appops set com.google.android.youtube TAKE_AUDIO_FOCUS ignore   # Android Auto fix
```

Mute an app: `adb shell cmd appops set <pkg> PLAY_AUDIO ignore`. Or use `adb/multiappaudio.sh`
from the repo.

### Companion app (optional)

Adds per-app volume (from the notification shade), pause/resume around calls, an Android Auto
auto-switch guard, and diagnostics.

1. Install `MultiAppAudio-*.apk` below.
2. Grant it once:
   ```sh
   adb shell pm grant io.github.channelramble.multiappaudio android.permission.DUMP
   adb shell cmd notification allow_listener io.github.channelramble.multiappaudio/io.github.channelramble.multiappaudio.MediaListener
   ```
3. Pull down the notification shade and use the **App volume** notification.

Requirements: Android 12+ (built for Android 16/17).

The findings behind each piece are in
[docs/RESEARCH.md](https://github.com/channelramble/multiAPPaudio/blob/HEAD/docs/RESEARCH.md).

### Undo

`adb shell settings put system multi_audio_focus_enabled 0`, then
`adb shell cmd appops set <pkg> TAKE_AUDIO_FOCUS default` (and `PLAY_AUDIO default`) for each app
you changed. Reboot, and uninstall the app.
