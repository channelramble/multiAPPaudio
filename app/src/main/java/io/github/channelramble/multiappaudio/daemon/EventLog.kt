package io.github.channelramble.multiappaudio.daemon

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Small thread-safe ring buffer of human-readable events, also mirrored to logcat. */
class EventLog(private val capacity: Int = 600) {
    private val lines = ArrayDeque<String>(capacity)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "${fmt.format(Date())} [$tag] $message"
        Log.i(LOG_TAG, line)
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast(line)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()

    /** Runs a callback from the system, logging instead of crashing the daemon on failure. */
    inline fun guard(where: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            add("error", "$where: ${t.stackTraceToString().lines().take(6).joinToString(" | ")}")
        }
    }

    companion object {
        const val LOG_TAG = "MultiAppAudio"
    }
}
