# Multi-App Audio

Plays audio from several apps at once on stock Android (Pixel), like the Galaxy Z Fold's
multi-app audio. It also works around Android Auto pausing YouTube and starting YouTube Music.
No root is needed: it uses [Shizuku](https://shizuku.rikka.app/) for ADB-level access.

> **Status:** I wrote this from the AOSP Android 16 and Android 17 source. It compiles against both
> framework versions, but I haven't run it on a Pixel yet. The in-app **Diagnostics** section and
> **Copy report** button are there so you can see what your device actually does.

## What it does

| Feature | How | Needs Shizuku running? | Survives reboot? |
|---|---|---|---|
| **Multi-app audio**: media apps play at the same time. Calls, alarms and navigation still interrupt. | Turns on AOSP's built-in *multi audio focus* mode (`Settings.System.multi_audio_focus_enabled`). | Only to switch it on or off. | **Yes.** Android re-applies it at every boot, without the app. |
| **Focus repair**: fixes the AOSP bug where music stays ducked (quiet) or paused after a navigation prompt or call. | The helper daemon watches focus. When the interruption ends, it clears the stuck duck and resumes what was playing. | Yes | Whenever the helper runs |
| **Android Auto pass-through**: YouTube (or any app you pick) plays alongside other apps in the car, and Android Auto can't pause it. | Android 17's per-app **focus isolation**. It runs *before* Android Auto's focus policy. | Yes | Whenever the helper runs |
| **Undo auto-switch**: if Android Auto replaces a pass-through app with another app seconds after it starts, the switch is undone. | The helper watches media sessions. At most 2 corrections per minute. | Yes | Whenever the helper runs |
| **Never take focus (AppOps)**: fallback for Android 16. Selected apps never pause or get paused by others. | `appops TAKE_AUDIO_FOCUS ignore` | Only to change it | **Yes** |
| **Diagnostics**: focus, media-session, playback and projection event log, plus a `dumpsys audio` focus snapshot. | Read-only focus follower | Yes | n/a |

Why the Android Auto features work the way they do, with source references, is in
[`docs/RESEARCH.md`](docs/RESEARCH.md).

## Install

1. Install and start **Shizuku** (Play Store or GitHub). Follow its wireless-debugging setup
   steps.
   * Optional but recommended: in Shizuku, turn on **start on boot**. Shizuku 13.6+ can do this
     without root on Android 13+ over Wi-Fi. It needs `WRITE_SECURE_SETTINGS` granted to Shizuku
     once from a PC. Shizuku's own docs have the steps. With this on, the helper comes back by
     itself after every reboot.
2. Download `MultiAppAudio-*.apk` from the latest
   [release](https://github.com/channelramble/multiAPPaudio/releases/latest) and install it.
   You can also build it yourself (`./gradlew assembleDebug`).
3. Open **Multi-App Audio**. Tap **Allow access** for Shizuku.
4. Turn on **Let media apps play at the same time**. This stops the current playback once.
5. Leave **Repair focus after interruptions** on.
6. Android Auto:
   * Pick your **pass-through apps** (YouTube is preselected).
   * Android 17: keep **Pass-through apps ignore Android Auto's focus rules** on.
   * Android 16: add the same apps under **Never take audio focus (AppOps)** instead.
   * In Android Auto's settings, turn off the option that starts media automatically on connect.
   * In YouTube Music, turn off letting external devices start playback.
   * You can try all of this at home with **Diagnostics → Simulate an Android Auto session**.

If something misbehaves in the car, open the app afterwards and tap **Copy report**. The event
log shows who asked for focus, who got paused, and what the helper did.

## Without the app (ADB only)

The core feature is a single setting:

```sh
adb shell settings put system multi_audio_focus_enabled 1   # then reboot
adb shell settings put system multi_audio_focus_enabled 0   # undo, then reboot
```

It persists across reboots. Without the helper you also get the AOSP bug: music may stay ducked
after a navigation prompt until you pause and resume it.

Per-app "never take audio focus". On Android 16 this only affects apps targeting Android 15+;
on Android 17 it covers almost everything. Some players then refuse to start:

```sh
adb shell cmd appops set com.google.android.youtube TAKE_AUDIO_FOCUS ignore
adb shell cmd appops set com.google.android.youtube TAKE_AUDIO_FOCUS default   # undo
```

## Uninstall / undo

Turn the switches off in the app, then uninstall. Nothing else is left behind: focus isolation
ends when the helper exits. If you set AppOps from the app, un-select the apps first, or run the
`default` command above.

## How it's built

* `app/…/daemon/` is the helper. Shizuku starts it as a *user service*, running as the shell uid
  in its own process. It keeps running after the app closes and exits when Shizuku stops. The
  permissions it relies on are all held by the ADB shell: `MODIFY_AUDIO_ROUTING`,
  `MODIFY_AUDIO_SETTINGS_PRIVILEGED`, `QUERY_AUDIO_STATE`, `MEDIA_CONTENT_CONTROL`,
  `READ_PROJECTION_STATE` and `DUMP`.
* Hidden framework calls use reflection inside the daemon. Hidden-API restrictions don't apply to
  `app_process`. The few `@SystemApi` classes it has to subclass are stubbed in `hidden-api/`.
  That module is `compileOnly` and is never packaged.
* The app itself only uses public APIs. It stores settings and pushes them to the daemon whenever
  it (re)connects.
* When Shizuku starts, it wakes every app that holds its permission. The app then restarts the
  daemon, so the helper comes back after a reboot as soon as Shizuku does.
