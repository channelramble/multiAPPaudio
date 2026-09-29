# Research notes: multi-app audio and Android Auto on stock Android

Sources: AOSP `frameworks/base` for Android 16 QPR1 (`aosp-mirror` main), Android 16 QPR2
(LineageOS 23.2), Android 17 (LineageOS 24.0), crDroid 16.0, and the Android 16/17 framework jars
(hidden APIs included). Paths below are relative to `frameworks/base`.

Each claim is tagged:
* **[source]** Read directly in AOSP code.
* **[inferred]** Deduced from code plus observed behaviour, not confirmed on a device.

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
* It can be toggled live with `IAudioService#setMultiAudioFocusEnabled(boolean)`, which needs
  `MODIFY_AUDIO_ROUTING` and persists the setting.
* **[source]** The shell user holds `MODIFY_AUDIO_ROUTING`
  (`packages/Shell/AndroidManifest.xml`). SettingsProvider lets the shell uid write any
  `Settings.System` key
  (`SettingsProvider#enforceRestrictedSystemSettingsMutationForCallingPackage`). So both
  `adb shell settings put system multi_audio_focus_enabled 1` and a Shizuku-backed call work
  without root.
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

The app tries it anyway (experimental button) and reports whether it took effect after reboot.
The helper's own workaround, below, doesn't depend on it.

### 2.2 The helper's workaround

The helper registers a read-only focus follower (`AudioPolicy` with an
`AudioPolicyFocusListener`; needs `MODIFY_AUDIO_ROUTING`). Once the focus stack is empty again and
the audio mode is back to `MODE_NORMAL`, it does two things:

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

### 3.1 Getting past the Android Auto focus policy

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
* The isolation is tied to a binder token the helper holds. If the helper dies, the framework
  exits the isolation automatically.

Side effects of isolation, and how the helper handles them:

* Isolated apps don't get focus losses for calls. The helper pauses them while the audio mode is
  ringtone or in-call, then resumes them.
* They also don't duck for navigation prompts. In Android Auto, guidance goes over a separate
  channel and the head unit mixes it, so this matters little in the car.

### 3.2 The AppOps fallback (Android 16)

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
  `handleAudioFocus=true`) won't play at all.
* AppOps modes are persisted by the system, so this survives reboots with no helper.

## 4. The YouTube → YouTube Music swap in Android Auto

**[inferred]** YouTube isn't an Android Auto media source. Android Auto's media UI (and the head
unit's "resume media" behaviour) keeps treating the last Android Auto media app as the current
source. When YouTube takes focus while projecting, Android Auto's focus policy sees it, and Android
Auto ends up resuming its own source. YouTube then gets a focus loss and pauses.

What the app does about it:

1. **Pass-through isolation (A17)** or **AppOps (A16).** YouTube's focus requests never reach
   Android Auto's policy, so they can't trigger the swap.
2. **Undo auto-switch guard.** Needed if Android Auto (or YouTube itself) pauses YouTube through
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

## 5. Staying on across reboots, non-root

| Piece | Persistence |
|---|---|
| `multi_audio_focus_enabled` | `Settings.System`, re-read by AudioService at boot. Needs nothing running. |
| AppOps `TAKE_AUDIO_FOCUS` | Stored by AppOpsService. Needs nothing running. |
| Helper daemon (repair, AA pass-through, guard, diagnostics) | Needs Shizuku. At startup, Shizuku sends its binder to every app holding its permission (`ShizukuService#sendBinderToClient`). That starts our process, which restarts the daemon. So the helper returns whenever Shizuku does, including via Shizuku's own start-on-boot. |

## 6. Permissions used (all held by `com.android.shell`)

| Permission | Used for |
|---|---|
| `MODIFY_AUDIO_ROUTING` | `setMultiAudioFocusEnabled`, focus follower `AudioPolicy`, `getFocusStack`, non-anonymized playback configs |
| `MODIFY_AUDIO_SETTINGS_PRIVILEGED` | `enterFocusIsolation` / `exitFocusIsolation` |
| `QUERY_AUDIO_STATE` | `getFocusDuckedUidsForTest`, test focus requests |
| `MEDIA_CONTENT_CONTROL` | Reading and controlling every MediaSession without a notification listener |
| `READ_PROJECTION_STATE` | Android Auto projection listener |
| `DUMP` | `dumpsys audio` (multi-focus state on A16, external focus policy presence) |
| `MANAGE_APP_OPS_MODES` | `cmd appops set … TAKE_AUDIO_FOCUS` |
