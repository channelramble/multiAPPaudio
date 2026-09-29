package io.github.channelramble.multiappaudio.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Debug
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Who is playing what, read in-process through the public [Debug.dumpService] API: `dumpsys audio`
 * (AudioService) where Android allows it, otherwise `dumpsys media.audio_flinger`.
 *
 * Both need android.permission.DUMP, a "development" permission a user can grant once over ADB
 * (`pm grant <app> android.permission.DUMP`); it survives reboots. No shell process, root or
 * Shizuku is involved. Android 17 also locks AudioService's dump behind signature permissions
 * (MODIFY_AUDIO_ROUTING, QUERY_AUDIO_STATE), so there the players come from AudioFlinger's track
 * list, and the audio focus details below are unknown.
 */
class AudioDump private constructor(private val raw: String, val source: String) {

    /** One player (AudioService) or track (AudioFlinger), in AudioService's terms. */
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

    val players: List<Player> = if (source == AUDIO_SERVICE) audioServicePlayers(raw) else audioFlingerTracks(raw)

    /** Focus stack + multi-focus section, for the diagnostics report. */
    fun focusSection(): String {
        if (source != AUDIO_SERVICE) return "(not readable on this Android version; players come from $source)"
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
        const val AUDIO_SERVICE = "audio"
        const val AUDIO_FLINGER = "media.audio_flinger"

        private val PIID = Regex("piid:(\\d+)")
        private val UID_PID = Regex("u/pid:(\\d+)/(-?\\d+)")
        private val STATE = Regex(" state:(\\S+)")
        private val USAGE = Regex("usage=(\\w+)")
        private val SESSION = Regex("sessionId:(-?\\d+)")
        private val MUTED = Regex("mutedState:([a-zA-Z ]*)")

        /**
         * A playback track line (Track::appendDump): optional fast-track index and type letter, then
         * `Id Active pid/uid Session PortId State Flags Format ChannelMask`, then the rest (sample
         * rate, stream type, usage, ...). Log lines start with a date and record tracks with yes/no,
         * so neither matches.
         */
        private val TRACK = Regex(
            """^\s*(?:F\d+\s+)?(?:[A-Z?]\s+)?(\d+)\s+(yes|no)\s+(\d+)/\s*(\d+)\s+(-?\d+)\s+(-?\d+)\s+(\S{1,2})\s+0x\p{XDigit}+\s+\p{XDigit}+\s+\p{XDigit}+\s+(.*)$"""
        )
        private val WHITESPACE = Regex("\\s+")

        /**
         * Where the usage sits after the sample rate. Builds differ ("SRate ST Usg" on the Android
         * 17 emulator, "SRate x ST Usg" on a Pixel 10), so it's read from the column header.
         */
        private const val DEFAULT_USAGE_OFFSET = 2

        /** audio_usage_t values, named like AudioAttributes.usageToString. */
        private val USAGE_NAMES = mapOf(
            0 to "USAGE_UNKNOWN", 1 to "USAGE_MEDIA", 2 to "USAGE_VOICE_COMMUNICATION",
            3 to "USAGE_VOICE_COMMUNICATION_SIGNALLING", 4 to "USAGE_ALARM", 5 to "USAGE_NOTIFICATION",
            6 to "USAGE_NOTIFICATION_RINGTONE", 10 to "USAGE_NOTIFICATION_EVENT",
            11 to "USAGE_ASSISTANCE_ACCESSIBILITY", 12 to "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE",
            13 to "USAGE_ASSISTANCE_SONIFICATION", 14 to "USAGE_GAME", 16 to "USAGE_ASSISTANT",
        )

        private const val PIPE_READ_TIMEOUT_MS = 5_000L

        /** Why the last [capture] failed (null after a success), for the event log. */
        @Volatile
        var lastFailure: String? = null
            private set

        /** Set once AudioService refuses: it won't change while this process lives. */
        @Volatile
        private var audioServiceRefused = false

        fun hasPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

        /** Blocking; call off the main thread. Null when DUMP isn't granted or the dump failed. */
        fun capture(context: Context): AudioDump? {
            if (!hasPermission(context)) {
                lastFailure = "DUMP not granted"
                return null
            }
            val failures = ArrayList<String>()
            val services = if (audioServiceRefused) listOf(AUDIO_FLINGER) else listOf(AUDIO_SERVICE, AUDIO_FLINGER)
            for (service in services) {
                val text = read(context, service, failures) ?: continue
                lastFailure = null
                return AudioDump(text, service)
            }
            lastFailure = failures.joinToString("; ")
            return null
        }

        /** A file first; if the system can't write into this app's storage, a pipe. */
        private fun read(context: Context, service: String, failures: MutableList<String>): String? {
            val methods = listOf<Pair<String, () -> String?>>(
                "file" to { dumpToFile(context, service) },
                "pipe" to { dumpToPipe(service) },
            )
            for ((how, dump) in methods) {
                val result = runCatching(dump)
                val text = result.getOrNull()
                val error = result.exceptionOrNull()
                val problem = error?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: problem(text)
                if (problem == null) return text
                failures += "$service via $how: $problem"
                // A refusal doesn't depend on how the dump is delivered.
                if (error is SecurityException || problem.startsWith("Permission Denial")) {
                    if (service == AUDIO_SERVICE) audioServiceRefused = true
                    break
                }
            }
            return null
        }

        /** What's wrong with a captured dump, or null if it's usable. */
        private fun problem(text: String?): String? = when {
            text == null -> "dumpService failed"
            text.isBlank() -> "empty dump"
            text.contains("Permission Denial") ->
                text.lineSequence().first { it.contains("Permission Denial") }.trim().take(160)
            else -> null
        }

        /** Null when the service couldn't be reached. */
        private fun dumpToFile(context: Context, service: String): String? {
            val file = File.createTempFile("dump", ".txt", context.cacheDir)
            try {
                val ok = FileOutputStream(file).use { Debug.dumpService(service, it.fd, emptyArray()) }
                return if (ok) file.readText() else null
            } finally {
                file.delete()
            }
        }

        /** The call is synchronous and the dump outgrows a pipe buffer, so read it concurrently. */
        private fun dumpToPipe(service: String): String? {
            val (read, write) = ParcelFileDescriptor.createPipe()
            var text = ""
            val reader = thread(name = "dump-reader") {
                text = ParcelFileDescriptor.AutoCloseInputStream(read).use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val ok = try {
                Debug.dumpService(service, write.fileDescriptor, emptyArray())
            } finally {
                write.close()
            }
            reader.join(PIPE_READ_TIMEOUT_MS)
            return if (ok && !reader.isAlive) text else null
        }

        /**
         * Players, parsed from AudioPlaybackConfiguration#toString (same format on Android 14-16):
         * `AudioPlaybackConfiguration piid:.. deviceIds:[..] type:.. u/pid:UID/PID state:started
         * attr:AudioAttributes: usage=USAGE_MEDIA .. sessionId:N mutedState:none ..`
         */
        private fun audioServicePlayers(raw: String): List<Player> = raw.lineSequence()
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

        /**
         * Playback tracks from AudioFlinger's thread dump, with states mapped to AudioService's
         * names. The usage column is printed in hex (`%3x`).
         */
        private fun audioFlingerTracks(raw: String): List<Player> {
            val tracks = ArrayList<Player>()
            var usageOffset = DEFAULT_USAGE_OFFSET
            for (line in raw.lineSequence()) {
                if (line.contains("Client(pid/uid)") && line.contains("SRate")) {
                    val names = line.trim().split(WHITESPACE)
                    val rate = names.indexOf("SRate")
                    val usg = names.indexOf("Usg")
                    if (rate >= 0 && usg > rate) usageOffset = usg - rate
                    continue
                }
                val g = TRACK.find(line)?.groupValues ?: continue
                val usage = g[8].trim().split(WHITESPACE).getOrNull(usageOffset)?.toIntOrNull(16) ?: -1
                tracks += Player(
                    piid = g[1].toInt(),
                    uid = g[4].toInt(),
                    pid = g[3].toInt(),
                    state = when (g[7].first()) {
                        'A', 'R', '1', '2' -> "started"
                        'P', 'p' -> "paused"
                        'T' -> "released"
                        'I' -> "idle"
                        else -> "stopped"
                    },
                    usage = USAGE_NAMES[usage] ?: "USAGE_$usage",
                    sessionId = g[5].toInt(),
                    muted = "",
                )
            }
            return tracks
        }
    }
}
