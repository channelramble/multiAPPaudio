package io.github.channelramble.multiappaudio.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Debug
import java.io.File
import java.io.FileOutputStream

/**
 * `dumpsys audio`, read in-process through the public [Debug.dumpService] API.
 *
 * AudioService only requires android.permission.DUMP for this. That's a "development" permission
 * a user can grant once over ADB (`pm grant <app> android.permission.DUMP`); it survives reboots.
 * No shell process, root or Shizuku is involved.
 */
class AudioDump private constructor(val raw: String) {

    /** One line of the PlaybackActivityMonitor "players" list. */
    data class Player(
        val piid: Int,
        val uid: Int,
        val pid: Int,
        val state: String,
        val usage: String,
        val sessionId: Int,
        val muted: String,
    )

    /** "Multi Audio Focus enabled :true" (MediaFocusControl#dumpMultiAudioFocus). */
    val multiAudioFocusEnabled: Boolean? =
        Regex("Multi Audio Focus enabled :(true|false)").find(raw)?.groupValues?.get(1)?.toBoolean()

    /**
     * An external focus policy (Android Auto while projecting, for instance) takes every focus
     * decision itself and bypasses the multi audio focus logic entirely.
     */
    val externalFocusPolicy: Boolean? = when {
        raw.contains("External focus policy:") -> true
        raw.contains("No external focus policy") -> false
        else -> null
    }

    /**
     * Players, parsed from AudioPlaybackConfiguration#toString (same format on Android 16 and 17):
     * `AudioPlaybackConfiguration piid:.. deviceIds:[..] type:.. u/pid:UID/PID state:started
     * attr:AudioAttributes: usage=USAGE_MEDIA .. sessionId:N mutedState:none ..`
     */
    val players: List<Player> = raw.lineSequence()
        .filter { it.contains("AudioPlaybackConfiguration piid:") }
        .mapNotNull { line ->
            val ids = UID_PID.find(line) ?: return@mapNotNull null
            Player(
                piid = PIID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1,
                uid = ids.groupValues[1].toInt(),
                pid = ids.groupValues[2].toIntOrNull() ?: -1,
                state = STATE.find(line)?.groupValues?.get(1) ?: "unknown",
                usage = USAGE.find(line)?.groupValues?.get(1) ?: "?",
                sessionId = SESSION.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                muted = MUTED.find(line)?.groupValues?.get(1)?.trim() ?: "",
            )
        }
        .toList()

    /** Focus stack + multi-focus section, for the diagnostics report. */
    fun focusSection(): String {
        val start = raw.indexOf("Audio Focus stack entries")
        if (start < 0) return "(focus section not found)"
        val end = listOf("Notify on duck", "In ring or call").map { raw.indexOf(it, start) }
            .filter { it > 0 }.minOrNull() ?: (start + 4000).coerceAtMost(raw.length)
        val multi = raw.indexOf("Multi Audio Focus enabled")
        val multiPart = if (multi >= 0) {
            val stop = raw.indexOf("\n\n", multi).takeIf { it > 0 } ?: (multi + 2000).coerceAtMost(raw.length)
            "\n" + raw.substring(multi, stop)
        } else ""
        return (raw.substring(start, end) + multiPart).lines().filter { it.isNotBlank() }.joinToString("\n")
    }

    companion object {
        private val PIID = Regex("piid:(\\d+)")
        private val UID_PID = Regex("u/pid:(\\d+)/(-?\\d+)")
        private val STATE = Regex(" state:(\\S+)")
        private val USAGE = Regex("usage=(\\w+)")
        private val SESSION = Regex("sessionId:(-?\\d+)")
        private val MUTED = Regex("mutedState:([a-zA-Z ]*)")

        fun hasPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

        /** Blocking; call off the main thread. Null when DUMP isn't granted or the dump failed. */
        fun capture(context: Context): AudioDump? {
            if (!hasPermission(context)) return null
            // Write to a file rather than a pipe: the dump is large and the call is synchronous.
            val file = File.createTempFile("audio", ".txt", context.cacheDir)
            return try {
                val ok = FileOutputStream(file).use { Debug.dumpService("audio", it.fd, emptyArray()) }
                val text = if (ok) file.readText() else ""
                if (text.contains("Permission Denial")) null else AudioDump(text).takeIf { ok }
            } catch (t: Throwable) {
                null
            } finally {
                file.delete()
            }
        }
    }
}
