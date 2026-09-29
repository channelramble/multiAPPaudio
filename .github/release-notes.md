Samsung-style multi-app audio for stock Android (Pixel), plus Android Auto fixes. No root: uses
[Shizuku](https://shizuku.rikka.app/).

> First release. It's built from the Android 16 and 17 source and compiled against both, but it
> hasn't been tested on a device yet. If something doesn't behave, use **Diagnostics → Copy
> report** in the app and open an issue with it.

### Requirements

- Android 12 or newer (built for Android 16/17 Pixels).
- Shizuku installed and running. Wireless debugging is enough; no root.
- Focus isolation (the Android Auto pass-through) needs Android 17. On Android 16, use the
  AppOps option instead.

### Install

1. Install and start **Shizuku**. Optional: turn on Shizuku's *start on boot* so the helper comes
   back after reboots.
2. Download `MultiAppAudio-*.apk` below and install it. Allow installs from your browser or file
   manager if asked.
3. Open **Multi-App Audio** and tap **Allow access** for Shizuku.
4. Turn on **Let media apps play at the same time**. This stops the current playback once.
5. For Android Auto: pick your pass-through apps (YouTube is preselected). You can try it at home
   with **Diagnostics → Simulate an Android Auto session**.

### What's in it

- **Multi-app audio.** Turns on Android's built-in multi audio focus mode. Android stores it and
  re-applies it at every boot, so it keeps working without Shizuku running. Calls, alarms and
  navigation prompts still interrupt.
- **Focus repair.** Fixes the AOSP bug in that mode where music stays quiet or paused after a
  navigation prompt or call.
- **Android Auto.** Pass-through apps bypass Android Auto's focus rules (Android 17). Automatic
  source swaps get undone, and pass-through apps pause during calls.
- **AppOps fallback.** "Never take audio focus" per app, for Android 16.
- **Diagnostics.** Event log and a `dumpsys audio` focus snapshot.

The findings behind each feature are in
[docs/RESEARCH.md](https://github.com/channelramble/multiAPPaudio/blob/HEAD/docs/RESEARCH.md).

### Undo

Turn the switches off in the app, then uninstall. Or run
`adb shell settings put system multi_audio_focus_enabled 0` and reboot.
