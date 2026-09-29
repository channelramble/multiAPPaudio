package io.github.channelramble.multiappaudio

import android.content.Context

/**
 * The one-time ADB commands behind every feature. Each is "set and forget": Android persists the
 * result (Settings.System, AppOps, permission grants, notification-listener approvals) across
 * reboots and app updates, so nothing ever has to be run again.
 */
object Adb {
    const val MULTI_FOCUS_ON = "adb shell settings put system multi_audio_focus_enabled 1"
    const val MULTI_FOCUS_OFF = "adb shell settings put system multi_audio_focus_enabled 0"
    const val REBOOT = "adb reboot"

    /** Google's own fix for the multi-focus restore bug; newer builds may refuse it without root. */
    const val FIX_FLAG_ON =
        "adb shell device_config override media_audio android.media.audio.audio_focus_desktop true"
    const val FIX_FLAG_OFF =
        "adb shell device_config clear_override media_audio android.media.audio.audio_focus_desktop"

    fun grantDump(context: Context) =
        "adb shell pm grant ${context.packageName} android.permission.DUMP"

    fun allowListener(context: Context) =
        "adb shell cmd notification allow_listener ${Config.listenerComponent(context).flattenToString()}"

    fun passThrough(pkg: String, on: Boolean) =
        "adb shell cmd appops set $pkg TAKE_AUDIO_FOCUS ${if (on) "ignore" else "default"}"

    fun mute(pkg: String, on: Boolean) =
        "adb shell cmd appops set $pkg PLAY_AUDIO ${if (on) "ignore" else "default"}"
}
