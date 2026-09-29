# Research notes: multi-app audio and Android Auto on stock Android

Sources: AOSP `frameworks/base` for Android 16 QPR1 (`aosp-mirror` main), Android 16 QPR2
(LineageOS 23.2), Android 17 (LineageOS 24.0), crDroid 16.0, and the Android 16/17 framework jars
(hidden APIs included). Paths below are relative to `frameworks/base`.

Each claim is tagged:
* **[source]** Read directly in AOSP code.
* **[inferred]** Deduced from code plus observed behaviour, not confirmed on a device.

The project is **ADB-only**: every system-level change is a one-time `adb shell` command that
Android persists, and the companion app only uses permissions granted once over ADB. Mechanisms
that need a live shell-privileged process (sections 2.2 and 3.1) are documented because they are
what a Shizuku- or root-based tool would use, but this project does not use them.

## 1. Why a Pixel pauses the other app

**[source]** All focus arbitration happens in
`services/core/java/com/android/server/audio/MediaFocusControl.java` (`requestAudioFocus`). A new
`AUDIOFOCUS_GAIN` request calls `propagateFocusLossFromGain_syncAf`. That sends
`AUDIOFOCUS_LOSS` to the previous owner, and well-behaved players pause. Since Android 12 the
framework also fades out the loser's players (`FocusRequester#frameworkHandleFocusLoss`), so even
apps that ignore the loss go quiet.

## 2. AOSP already contains a multi-app audio mode

**[source]** `MediaFocusControl` has a second path, gated by `mMultiAudioFocusEnabled`:

```java
if (mMultiAudioFocusEnabled && (focusChangeHint == AudioManager.AUDIOFOCUS_GAIN)) {
    if (isForCall) { /* calls still take focus from everyone in the list */ }
    else {
        mMultiAudioFocusList.add(nfr);                 // no loss sent to anyone
        nfr.handleFocusGainFromRequest(AUDIOFOCUS_REQUEST_GRANTED);
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }
}
```

* Plain `GAIN` requests (music, video) all coexist.
* Transient requests (navigation prompts, assistant) and calls still go through the normal stack.
  They still duck or pause everything in the multi list.
* The mode comes from `Settings.System.MULTI_AUDIO_FOCUS_ENABLED` (`"multi_audio_focus_enabled"`,
  `@hide @Readable`), read when AudioService starts (`AudioService#isMultiFocus` on A17). That means
  **the setting is applied at every boot with no process of ours running**.
* **[source]** SettingsProvider lets the shell uid write any `Settings.System` key
  (`SettingsProvider#enforceRestrictedSystemSettingsMutationForCallingPackage`), so
  `adb shell settings put system multi_audio_focus_enabled 1` works without root. It takes effect
  at the next boot and then stays on.
* **[source]** A regular app can't write this key, even with `WRITE_SECURE_SETTINGS` granted over
  ADB. The same check exempts only system, shell, root and privileged apps; for anything else it
  throws for keys outside `Settings.System.PUBLIC_SETTINGS`. That's why switching it on is an ADB
  command and the app only reads it (reading is allowed because the key is `@Readable`).
* It can also be toggled live with `IAudioService#setMultiAudioFocusEnabled(boolean)`, which needs
  `MODIFY_AUDIO_ROUTING`. The shell holds that permission, but only a running shell process could
  use it. Not needed here: the setting plus one reboot does the same.
* Custom ROMs expose this same mode as a toggle (crDroid 12's "multi-media focus"; AxionOS's
  "Multi-Audio Focus").
* **[inferred]** Samsung's feature changes focus arbitration at this same layer. Whether it uses
  this exact code path, I can't tell from AOSP.

### 2.1 The AOSP bug (and Google's fix, which is off on phones)

**[source]** When an interruption ends, `MediaFocusControl#notifyTopOfAudioFocusStack` only gives
focus back to multi-list members that are *locked* owners:

```java
for (FocusRequester multifr : mMultiAudioFocusList) {
    if (isLockedFocusOwner(multifr)) { multifr.handleFocusGain(AUDIOFOCUS_GAIN); }
}
```

Normal media apps are never locked owners, so they never get `AUDIOFOCUS_GAIN` back. The framework
un-ducks a player only in `FocusRequester#handleFocusGain` / `handleFocusGainFromRequest` (via
`PlaybackActivityMonitor#restoreVShapedPlayers`). The results:

* After a Maps prompt, music **stays ducked** until the app pauses and resumes.
* After a call, apps that paused don't resume on their own.

**[source]** Google fixed this in Android 16 QPR2 / 17. The fix sits behind the read-write aconfig
flag `android.media.audio.audio_focus_desktop` (namespace `media_audio`, "audio focus for desktop
behaviors"). That flag is meant for desktop devices and is off on phones:

```java
if (audioFocusDesktop()) {
    if ((canReassignAudioFocus && multifr.toAudioFocusInfo().isLossReceivedTransient())
            || isLockedFocusOwner(multifr)) { multifr.handleFocusGain(AUDIOFOCUS_GAIN); }
}
```

Turning that flag on from the shell is uncertain:

* A17's `device_config` CLI refuses aconfig writes without root (`DeviceConfigService`,
  `Flags.checkRootAndReadOnly()`).
* Newer builds limit which namespaces the shell may write (`protect_device_config_flags`).

The README and `adb/multiappaudio.sh fix-flag on` offer the command. If it's refused, nothing is
changed.

### 2.2 Repairing it needs a live shell process (not used here)

A shell-privileged helper (Shizuku, root) could register a read-only focus follower (`AudioPolicy`
with an `AudioPolicyFocusListener`; needs `MODIFY_AUDIO_ROUTING`). Once the focus stack is empty
again and the audio mode is back to `MODE_NORMAL`, it could do two things:

1. **Un-ducks stuck players.** It reads `IAudioService#getFocusDuckedUidsForTest()`
   (`QUERY_AUDIO_STATE`). For each ducked uid it issues a test-API `AUDIOFOCUS_GAIN` request
   attributed to that uid (`requestAudioFocusForTest`, `AUDIOFOCUS_FLAG_TEST`) and abandons it
   immediately.
   * In multi-focus mode such a request is granted *without propagating any loss*.
   * It runs `handleFocusGainFromRequest`, the exact framework code path that un-ducks the uid.
   * It only runs when multi focus is on and no external focus policy is installed. Otherwise the
     request would be a real focus grab.
2. **Resumes apps that paused themselves.** For apps that got a notified transient loss while
   playing, it sends `play()` through their MediaSession. That is what `AUDIOFOCUS_GAIN` would have
   triggered.

The ADB-only edition can do the second half for **calls**: with notification access it can see and
control media sessions, and `AudioManager#addOnModeChangedListener` (public) says when a call starts
and ends. Stuck ducks after a navigation prompt can't be cleared without `QUERY_AUDIO_STATE`, which
can't be granted to an app. Pausing and playing the affected app clears it.

## 3. Why multi-app audio stops working in Android Auto

**[source]** An audio policy can register as the **external focus policy**
(`AudioPolicy.Builder#setIsAudioFocusPolicy`). In `MediaFocusControl#requestAudioFocus` that path
runs **before** the multi-focus path:

```java
if (mFocusPolicy != null) {
    if (notifyExtFocusPolicyFocusRequest_syncAf(afiForExtPolicy, fd, cb)) {
        return AudioManager.AUDIOFOCUS_REQUEST_WAITING_FOR_EXT_POLICY;
    } ...
}
// multi audio focus handling only happens below this point
```

With an external focus policy installed, every request is handed to that policy, which decides
alone. `mMultiAudioFocusEnabled` is never consulted.

**[inferred]** Android Auto (`com.google.android.projection.gearhead`) must keep phone-side focus
in sync with the car's head unit, so it installs such a policy while projecting. This matches:

* multi-app audio not working under Android Auto on the Galaxy, and
* Android Auto deciding which app keeps playing (the YouTube → YouTube Music swap).

To check on your phone: while connected, the app's Android Auto card shows
*External focus policy installed: true*. You can also run `adb shell dumpsys audio | grep -i
"focus policy"`.

### 3.1 Focus isolation (Android 17; needs a live shell process, not used here)

**[source]** Android 17 added **focus isolation**
(`AudioManager#enterFocusIsolation(uid)` → `IAudioService#enterFocusIsolation(int, IBinder)`,
permission `MODIFY_AUDIO_SETTINGS_PRIVILEGED`, which the shell holds):

* An isolated uid's requests are always granted.
* They don't affect other apps, and other apps don't affect them.
* The isolation check runs *before* the external-policy check. The Javadoc says "Isolated uids are
  not impacted by external policy based focus requests."
* The client-side `AudioManager` wrapper is gated by the `audio_focus_isolation` flag. The
  server-side `AudioService#enterFocusIsolation` is not, so the helper calls the binder method
  directly.
* The isolation is tied to a binder token held by the caller, and ends when that process dies.

That last point rules it out for an ADB-only setup: a one-shot `adb shell` command exits
immediately, there is no shell command for it, and a normal app can't be granted
`MODIFY_AUDIO_SETTINGS_PRIVILEGED`.

### 3.2 AppOps "pass-through" (what this project uses)

**[source]** `IAudioService#requestAudioFocus` calls `HardeningEnforcer#blockFocusMethod`
*before* `MediaFocusControl`, so this also happens before the external focus policy.

* If `OP_TAKE_AUDIO_FOCUS` isn't allowed for the app, the request fails with
  `AUDIOFOCUS_REQUEST_FAILED`.
* Who is affected depends on the version:
  * **Android 16:** only apps with `targetSdk >= 35`. Older targets are waved through.
  * **Android 17:** broader. The deny is honoured for any non-privileged app, except apps
    targeting < 35 while the `hardening_partial` flag is off. "Privileged" means holding
    `MODIFY_AUDIO_ROUTING`, `MODIFY_AUDIO_SETTINGS_PRIVILEGED` or `MODIFY_PHONE_STATE`, which
    YouTube and YouTube Music don't.
* The app therefore never enters anyone's focus arbitration: it can't pause others, can't be
  paused by them, and Android Auto never sees it.
* Downside: players that refuse to start without focus (for example ExoPlayer with
  `handleAudioFocus=true`) won't play at all. If a pass-through app won't start, set it back to
  `default`.
* These apps also don't get focus losses for calls. Outside Android Auto the framework mutes media
  during calls anyway (`MediaFocusControl` → `mutePlayersForCall`). The companion app also pauses
  them while a call is ringing or active and resumes them afterwards.
* AppOps modes are persisted by the system: `cmd appops set <pkg> TAKE_AUDIO_FOCUS ignore` once,
  and it survives reboots with nothing running.

## 4. The YouTube → YouTube Music swap in Android Auto

**[inferred]** YouTube isn't an Android Auto media source. Android Auto's media UI (and the head
unit's "resume media" behaviour) keeps treating the last Android Auto media app as the current
source. When YouTube takes focus while projecting, Android Auto's focus policy sees it, and Android
Auto ends up resuming its own source. YouTube then gets a focus loss and pauses.

What this project does about it:

1. **AppOps pass-through (one ADB command).** YouTube's focus requests never reach Android Auto's
   policy, so they can't trigger the swap.
2. **Undo auto-switch guard (companion app, notification access).** Needed if Android Auto (or YouTube itself) pauses YouTube through
   some other path. The rule: while projecting, if a pass-through app is stopped within N seconds
   (default 15) of starting and another app starts within 4 s of that, pause the other app and
   resume the pass-through app. It gives up after 2 tries per minute, so it can't loop against
   Android Auto.
3. **Settings worth changing by hand.**
   * Android Auto: turn off automatic media start/resume on connect.
   * YouTube Music: turn off letting external devices start playback.

Still unknown, and the in-app log will show it on your first drive:

* Whether Gearhead installs a full focus policy or only uses `AUDIOFOCUS_FLAG_LOCK`.
* Whether YouTube pauses itself when it detects car mode or projection.
* Whether audio from an app that never took focus is streamed to the head unit when the car's
  current source isn't Android Auto.

## 5. Per-app volume and mute without root or Shizuku

**[source]** AOSP has no per-app volume. The `…StreamVolumeForUid` methods in AudioService set a
*stream's* volume on behalf of an app (used for remote MediaSession volume), not a separate level
per app. Samsung's Sound Assistant implements per-app volume inside its own audio framework.

**Mute (ADB only).** `OP_PLAY_AUDIO` (`cmd appops set <pkg> PLAY_AUDIO ignore`) silences an app's
players at the system level. PlaybackActivityMonitor reports these players as muted with
`MUTED_BY_OP_PLAY_AUDIO` ("opPlayAudio" in `dumpsys audio`). The mode is persisted by AppOps, and
the app keeps playing silently.

**Volume levels (companion app).** This uses the equalizer-app technique:

* Any app with `MODIFY_AUDIO_SETTINGS` (a normal permission) can attach an audio effect to another
  app's audio session.
* The app attaches `android.media.audiofx.DynamicsProcessing` (public since API 28) and sets its
  input gain: negative to turn an app down, up to +6 dB with a limiter to boost it.
* Finding each app's session ids takes a system dump that needs `android.permission.DUMP`. That
  is a development permission a user can grant once with `pm grant`. The app reads the dump
  in-process through the public `Debug.dumpService(…)`, no shell involved:
  * Up to Android 16, `dumpsys audio`. PlaybackActivityMonitor lists every player as
    `AudioPlaybackConfiguration piid:… u/pid:UID/PID state:… attr:… usage=… sessionId:N …`.
  * Android 17 locks AudioService's dump for apps: `Debug.dumpService("audio", …)` throws
    `SecurityException: Access denied, requires: anyOf={MODIFY_AUDIO_ROUTING, QUERY_AUDIO_STATE,
    MODIFY_AUDIO_SETTINGS_PRIVILEGED}`, and none of those can be granted over ADB (no
    `development` flag). `dumpsys media.audio_flinger` still only needs `DUMP`, and each
    playback thread lists its tracks as `Id Active pid/uid Session PortId State Flags Format
    ChannelMask SampleRate StreamType Usage …`, with usage as the numeric `audio_usage_t`. The
    app falls back to that. Verified on the Android 17 emulator image (CE2A.260420.019).
  * AudioService's focus details (the multi-focus flag, an external focus policy) have no
    AudioFlinger equivalent, so on Android 17 the app can't confirm them.
* Limitations:
  * Effects live in the app's process, so a foreground service keeps them alive and it restarts at
    boot. If the process dies (an update, a crash), AudioFlinger keeps the effect pinned to the
    other app's session, still at its last gain, until that app releases its audio. The restarted
    helper takes the pinned effect over. Right after a restart that can briefly fail
    (`INVALID_OPERATION` from `createEffect`), so failed sessions are retried with backoff.
  * Some low-latency (AAudio MMAP) game audio can't take effects.
  * Another equalizer app on the same session competes for control of the effect. The app requests
    top priority and logs if it loses control.

## 6. Set and forget: what persists

| One-time step | Persists across reboots? | Stored by |
|---|---|---|
| `settings put system multi_audio_focus_enabled 1` (+ one reboot) | Yes | SettingsProvider; AudioService reads it at every boot |
| `cmd appops set <pkg> TAKE_AUDIO_FOCUS ignore` | Yes | AppOpsService |
| `cmd appops set <pkg> PLAY_AUDIO ignore` | Yes | AppOpsService |
| `pm grant <app> android.permission.DUMP` | Yes (until the app is uninstalled) | PackageManager permission state |
| `cmd notification allow_listener <component>` | Yes | NotificationManagerService |
| `device_config override media_audio …audio_focus_desktop true` (if accepted) | Yes (local overrides are sticky) | DeviceConfig / aconfig storage |

The companion app's service restarts itself on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. It is a
`specialUse` foreground service, a type Android 15+ still allows to start from boot. Nothing ever
has to be re-run over ADB.

## 7. What each approach can reach

| Capability | Needs | ADB-only edition |
|---|---|---|
| Multi-app audio mode | shell (write hidden `Settings.System` key) | ADB command |
| Pass-through (never take focus) | shell (`MANAGE_APP_OPS_MODES`) | ADB command |
| Mute an app | shell (`MANAGE_APP_OPS_MODES`) | ADB command |
| Per-app volume levels | `DUMP` (grantable) + `MODIFY_AUDIO_SETTINGS` (normal) | App |
| Pause/resume around calls, auto-switch guard | notification access (grantable) | App |
| Android Auto detection | none (Android Auto's public car-connection provider, car mode) | App |
| Diagnostics (`dumpsys audio`) | `DUMP` | App |
| Clear stuck ducks after navigation prompts | `QUERY_AUDIO_STATE` (shell-only) | Not possible |
| Focus isolation (Android 17) | `MODIFY_AUDIO_SETTINGS_PRIVILEGED` + a live process | Not possible |
| Toggle multi focus without reboot | `MODIFY_AUDIO_ROUTING` + a live process | Not possible (reboot instead) |
