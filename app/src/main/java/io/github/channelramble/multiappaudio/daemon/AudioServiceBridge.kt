package io.github.channelramble.multiappaudio.daemon

import android.media.AudioAttributes
import android.media.AudioFocusInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock

/**
 * Thin, version-tolerant wrapper around the hidden `IAudioService` binder interface.
 *
 * Everything here needs the permissions of the shell uid (MODIFY_AUDIO_ROUTING,
 * MODIFY_AUDIO_SETTINGS_PRIVILEGED, QUERY_AUDIO_STATE, DUMP), which is why it lives in the daemon.
 * Method shapes were checked against AOSP android16-qpr1 and android17 (LineageOS 24.0) sources.
 */
class AudioServiceBridge {

    private val svc: Any = Hidden.aidl("audio", "android.media.IAudioService")
        ?: throw IllegalStateException("audio service not found")

    // ---------------------------------------------------------------- multi audio focus

    /**
     * AOSP's built-in "multi audio focus" mode. Also persists
     * `Settings.System.multi_audio_focus_enabled`, which AudioService reads at every boot, so the
     * mode survives reboots without this daemon running.
     */
    fun setMultiAudioFocus(enabled: Boolean) {
        Hidden.call(svc, "setMultiAudioFocusEnabled", enabled)
    }

    /** Live in-memory state. Uses the Android 17 getter, falls back to parsing dumpsys. */
    fun isMultiAudioFocusActive(dump: AudioDump? = null): Boolean? {
        if (Hidden.method(svc, "isMultiAudioFocusEnabled", 0) != null) {
            return runCatching { Hidden.call(svc, "isMultiAudioFocusEnabled") as Boolean }.getOrNull()
        }
        return (dump ?: AudioDump.capture()).multiAudioFocusEnabled
    }

    // ---------------------------------------------------------------- focus isolation (A17+)

    /** Android 17 added per-uid "focus isolation"; the server side is not flag-gated. */
    val isolationSupported: Boolean
        get() = Hidden.method(svc, "enterFocusIsolation", 2) != null

    fun enterIsolation(uid: Int, token: IBinder): Boolean =
        Hidden.call(svc, "enterFocusIsolation", uid, token) as Boolean

    fun exitIsolation(token: IBinder, retainFocus: Boolean): Boolean =
        Hidden.call(
            svc, "exitFocusIsolation", token,
            if (retainFocus) FOCUS_ISOLATION_EXIT_RETAIN_FOCUS else FOCUS_ISOLATION_EXIT_LOSE_FOCUS
        ) as Boolean

    // ---------------------------------------------------------------- focus stack

    /**
     * The regular focus stack (MODIFY_AUDIO_ROUTING). With multi focus on, plain AUDIOFOCUS_GAIN
     * holders live in a separate list, so anything on this stack is an interruption
     * (navigation prompt, assistant, call) or a delayed request.
     */
    fun focusStack(): List<AudioFocusInfo>? = runCatching {
        @Suppress("UNCHECKED_CAST")
        (Hidden.call(svc, "getFocusStack") as List<AudioFocusInfo>).toList()
    }.getOrNull()

    // ---------------------------------------------------------------- ducking repair

    /** uids whose players are currently ducked by the framework (QUERY_AUDIO_STATE). */
    fun focusDuckedUids(): List<Int>? = runCatching {
        @Suppress("UNCHECKED_CAST")
        (Hidden.call(svc, "getFocusDuckedUidsForTest") as List<Int>).toList()
    }.getOrNull()

    /**
     * Clears a stuck framework duck on [uid].
     *
     * With multi audio focus on, AOSP only returns focus to entries of the multi-focus list when
     * they are "locked" owners (MediaFocusControl#notifyTopOfAudioFocusStack), so a player ducked
     * by a navigation prompt is never un-ducked. Upstream fixed this behind the
     * `android.media.audio.audio_focus_desktop` flag, which is off on phones.
     *
     * The un-duck in the framework happens in FocusRequester#handleFocusGainFromRequest ->
     * PlaybackActivityMonitor#restoreVShapedPlayers(uid). We trigger exactly that by issuing a
     * test-API AUDIOFOCUS_GAIN request attributed to [uid] and abandoning it immediately. In multi
     * focus mode a GAIN request is granted without propagating any loss to other apps.
     *
     * Callers MUST check that multi focus is active and that no external focus policy is
     * installed; otherwise this request would behave like a real focus grab.
     */
    fun unduckUid(uid: Int): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val clientId = "mma-unduck-$uid-${SystemClock.uptimeMillis()}"
        val cb = Binder()
        val request = Hidden.method(svc, "requestAudioFocusForTest", 10)
            ?: Hidden.method(svc, "requestAudioFocusForTest", 9)
            ?: return false
        val abandon = Hidden.method(svc, "abandonAudioFocusForTest", 5)
            ?: Hidden.method(svc, "abandonAudioFocusForTest", 4)
            ?: return false
        val result = Hidden.unwrap {
            if (request.parameterCount == 10) {
                request.invoke(
                    svc, attributes, AudioManager.AUDIOFOCUS_GAIN, cb, null, clientId,
                    SHELL_PACKAGE, AUDIOFOCUS_FLAG_TEST, uid, Build.VERSION.SDK_INT, null
                )
            } else {
                request.invoke(
                    svc, attributes, AudioManager.AUDIOFOCUS_GAIN, cb, null, clientId,
                    SHELL_PACKAGE, AUDIOFOCUS_FLAG_TEST, uid, Build.VERSION.SDK_INT
                )
            }
        } as Int
        Hidden.unwrap {
            if (abandon.parameterCount == 5) {
                abandon.invoke(svc, null, clientId, attributes, SHELL_PACKAGE, null)
            } else {
                abandon.invoke(svc, null, clientId, attributes, SHELL_PACKAGE)
            }
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        const val AUDIOFOCUS_FLAG_TEST = 1 shl 3
        const val FOCUS_ISOLATION_EXIT_RETAIN_FOCUS = 1
        const val FOCUS_ISOLATION_EXIT_LOSE_FOCUS = 2
    }
}

/** Parsed bits of `dumpsys audio` that have no binder getter on older releases. */
class AudioDump private constructor(val raw: String) {

    /** "Multi Audio Focus enabled :true" (MediaFocusControl#dumpMultiAudioFocus). */
    val multiAudioFocusEnabled: Boolean? =
        Regex("Multi Audio Focus enabled :(true|false)").find(raw)?.groupValues?.get(1)?.toBoolean()

    /**
     * "No external focus policy" / "External focus policy: ..." (MediaFocusControl#dumpFocusStack).
     * An external focus policy (Android Auto while projecting, for instance) bypasses the multi
     * audio focus logic entirely.
     */
    val externalFocusPolicy: Boolean? = when {
        raw.contains("External focus policy:") -> true
        raw.contains("No external focus policy") -> false
        else -> null
    }

    /** The focus stack / multi-focus section, for the diagnostics report. */
    fun focusSection(): String {
        val start = raw.indexOf("Audio Focus stack entries")
        if (start < 0) return "(focus section not found)"
        val endMarkers = listOf("Notify on duck", "In ring or call")
        val end = endMarkers.map { raw.indexOf(it, start) }.filter { it > 0 }.minOrNull()
            ?: (start + 4000).coerceAtMost(raw.length)
        val multi = raw.indexOf("Multi Audio Focus enabled")
        val multiPart = if (multi >= 0) {
            val stop = raw.indexOf("\n\n", multi).takeIf { it > 0 } ?: (multi + 2000).coerceAtMost(raw.length)
            "\n" + raw.substring(multi, stop)
        } else ""
        return (raw.substring(start, end) + multiPart).lines()
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    companion object {
        fun capture(): AudioDump = AudioDump(Shell.run("dumpsys", "audio").out)
    }
}
