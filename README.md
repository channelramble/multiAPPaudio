# Multi-App Audio

Samsung-style multi-app audio for stock Android (Pixel), per-app volume like One UI's Sound
Assistant, and a fix for Android Auto pausing YouTube and starting YouTube Music.

**ADB only.** No root and no Shizuku. You run a few `adb shell` commands **once**. Android stores
each result and re-applies it at every boot, so you set it and forget it. The optional companion
app adds per-app volume sliders and small helpers, using permissions you also grant once over ADB.

> **Status:** built from the AOSP Android 16 and 17 source. The app compiles for both versions,
> but it hasn't been tested on a device yet. The in-app **Diagnostics → Copy report** shows what
> your phone actually does.

## Quick start (just ADB)

Enable USB or wireless debugging, connect, then:

```sh
# 1. Multi-app audio: media apps play at the same time. Reboot once; it stays on forever.
adb shell settings put system multi_audio_focus_enabled 1
adb reboot

# 2. Android Auto fix: YouTube never takes audio focus, so Android Auto can't pause it or swap in
#    YouTube Music, and it plays alongside other apps (in the car too).
adb shell cmd appops set com.google.android.youtube TAKE_AUDIO_FOCUS ignore

# 3. Optional: mute an app completely.
adb shell cmd appops set com.instagram.android PLAY_AUDIO ignore
```

That's the whole core setup. Each command survives reboots and app updates, and nothing needs to
keep running.

Or use the helper script (macOS/Linux, or Windows via Git Bash/WSL):

```sh
./adb/multiappaudio.sh enable --reboot
./adb/multiappaudio.sh passthrough com.google.android.youtube
./adb/multiappaudio.sh mute com.instagram.android
./adb/multiappaudio.sh status com.google.android.youtube
```

### What each command does

| Command | Effect | Undo |
|---|---|---|
| `settings put system multi_audio_focus_enabled 1` + reboot | Android's built-in multi audio focus mode. Music and video apps stop pausing each other. Calls, alarms and navigation prompts still interrupt. | `… multi_audio_focus_enabled 0` + reboot |
| `cmd appops set <pkg> TAKE_AUDIO_FOCUS ignore` | That app never takes audio focus. It doesn't pause other apps, isn't paused by them, and Android Auto doesn't see it start. | `… TAKE_AUDIO_FOCUS default` |
| `cmd appops set <pkg> PLAY_AUDIO ignore` | Silences that app at the system level. | `… PLAY_AUDIO default` |

Things to know:

* **Stuck-quiet music.** With multi-app audio on, Android 16/17 has a bug: after a navigation
  prompt, music can stay quieter until you pause and play it, and after a call, paused apps don't
  resume by themselves. The companion app resumes after calls. Google's own fix is behind a flag
  you can *try* to switch on. Newer builds may refuse it without root, which is harmless:
  `adb shell device_config override media_audio android.media.audio.audio_focus_desktop true`,
  then reboot.
* **Apps that won't start.** Some players refuse to start when they're denied audio focus. If a
  pass-through app won't play, run its undo command.
* **Which apps `TAKE_AUDIO_FOCUS` affects.** On Android 16 it only applies to apps targeting
  Android 15+. On Android 17 it covers almost everything.
* **Android Auto settings.** Also worth doing once: in Android Auto's settings, turn off starting
  media automatically. In YouTube Music, turn off letting external devices start playback.

## Companion app (optional)

Adds what a command alone can't do:

* **Per-app volume, from the notification shade.** Each app gets its own level, from 0% (silent)
  to 200%. Expand the **App volume** notification for - and + buttons on whatever is playing, or
  tap it for a panel of sliders over the app you're in. An **App volume** Quick Settings tile opens
  the same panel. It attaches a volume effect to each app's audio, the way equalizer apps do.
* **Pause/resume around calls.** It resumes what was playing when a call ends (working around the
  bug above) and pauses pass-through apps during calls.
* **Undo automatic source switches.** While Android Auto is connected, if a pass-through app gets
  replaced by another app within 15 s of starting, it switches back. At most twice a minute.
* **Status, commands and diagnostics.** It shows whether each setting is active and gives
  copy-ready commands for the apps you pick. The event log and a `dumpsys audio` focus snapshot
  are in **Copy report**.

Setup:

1. Install the APK. Take it from [Releases](https://github.com/channelramble/multiAPPaudio/releases/latest),
   or grab the `multi-app-audio-debug-apk` artifact from the latest *Build APK* run in Actions.
2. Grant it two things, once:
   ```sh
   adb shell pm grant io.github.channelramble.multiappaudio android.permission.DUMP
   adb shell cmd notification allow_listener io.github.channelramble.multiappaudio/io.github.channelramble.multiappaudio.MediaListener
   ```
   Or run `./adb/multiappaudio.sh setup-app`. What each grant is for:
   * `DUMP` lets the app see which app owns each audio stream, for per-app volume and diagnostics.
   * Notification access lets it pause and resume media. Notifications themselves are never read.
   * Both grants survive reboots and app updates.
3. Pull down the notification shade and use the **App volume** notification. To add the Quick
   Settings tile, tap **Add Quick Settings tile** in the app.

The app runs a small background service whose notification is the **App volume** control. It
restarts after boots and updates on its own. If you switch the notification off in the app, the
service only runs while per-app volume or the call and Android Auto helpers need it.

Why each piece works the way it does, with AOSP source references, is in
[`docs/RESEARCH.md`](docs/RESEARCH.md).

### Verifying the APK

Releases are signed with the project's release key. Its certificate SHA-256 is:

```
3bf35b33f7ec1bb42634e2dfa4fb144447701f2a5f57801f218b780324a86ee0
```

The same value is in [`.github/signing-cert.sha256`](.github/signing-cert.sha256), and the release
workflow refuses to publish an APK signed with any other key. To check a download, run
`apksigner verify --print-certs MultiAppAudio-*.apk`, or use an app such as AppVerifier.

## Undo everything

```sh
adb shell settings put system multi_audio_focus_enabled 0
adb shell cmd appops set <pkg> TAKE_AUDIO_FOCUS default   # for each pass-through app
adb shell cmd appops set <pkg> PLAY_AUDIO default         # for each muted app
adb reboot
```

Uninstalling the app removes its grants. Set every app back to 100% first: Android keeps a volume
effect on an app that is playing at that moment, at its last level, until that app closes its audio.

## How it's built

* `adb/multiappaudio.sh` is a thin wrapper around the commands above. It validates package names,
  and every value is passed as a separate argument.
* `app/` is a plain Android app with no dependencies and public APIs only:
  * `core/AudioDump.kt` reads `dumpsys audio` in-process via `Debug.dumpService` (needs `DUMP`).
    Android 17 locks that dump behind signature permissions, so there it reads the track list from
    `dumpsys media.audio_flinger` instead, which still only needs `DUMP`.
  * `core/AppVolumes.kt` attaches `DynamicsProcessing` to other apps' audio sessions.
  * `core/ActiveApps.kt` works out which apps are playing, paused or recently played.
  * `MixerNotification.kt`, `MixerActivity.kt` and `MixerTileService.kt` are the App volume
    notification, slider panel and Quick Settings tile.
  * `core/CarWatcher.kt` detects Android Auto through its public car-connection provider.
  * `core/SessionWatcher.kt` and `core/AutoSwitchGuard.kt` control media sessions via
    notification access.
  * `AudioControlService.kt` is the foreground service that hosts all of the above.
