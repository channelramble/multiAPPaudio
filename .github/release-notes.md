Samsung-style multi-app audio, per-app volume and Android Auto fixes for stock Android (Pixel).
**ADB only.** No root, no Shizuku. Every command is run once and persists across reboots.

> Built from the Android 16 and 17 source and compiled against both, but not yet tested on a
> device. If something doesn't behave, use **Diagnostics → Copy report** in the app and open an
> issue with it.

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

Adds per-app volume sliders, pause/resume around calls, an Android Auto auto-switch guard, and
diagnostics.

1. Install `MultiAppAudio-*.apk` below.
2. Grant it once:
   ```sh
   adb shell pm grant io.github.channelramble.multiappaudio android.permission.DUMP
   adb shell cmd notification allow_listener io.github.channelramble.multiappaudio/io.github.channelramble.multiappaudio.MediaListener
   ```
3. Open it and add apps under **Per-app volume**.

Requirements: Android 12+ (built for Android 16/17).

The findings behind each piece are in
[docs/RESEARCH.md](https://github.com/channelramble/multiAPPaudio/blob/HEAD/docs/RESEARCH.md).

### Undo

`adb shell settings put system multi_audio_focus_enabled 0`, then
`adb shell cmd appops set <pkg> TAKE_AUDIO_FOCUS default` (and `PLAY_AUDIO default`) for each app
you changed. Reboot, and uninstall the app.
