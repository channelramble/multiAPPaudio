package io.github.channelramble.multiappaudio.daemon

import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs a command directly (no `sh -c`, so arguments are never re-parsed by a shell).
 * The daemon runs as the shell uid, so this has the same power as `adb shell <cmd>`.
 */
object Shell {
    data class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
        fun text() = (out + if (err.isNotBlank()) "\n$err" else "").trim()
    }

    fun run(vararg cmd: String, timeoutMs: Long = 15_000): Result {
        val process = try {
            ProcessBuilder(*cmd).start()
        } catch (e: Exception) {
            return Result(-1, "", e.toString())
        }
        process.outputStream.close()
        var out = ""
        var err = ""
        val outReader = thread(name = "sh-out") { out = process.inputStream.readAll() }
        val errReader = thread(name = "sh-err") { err = process.errorStream.readAll() }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        outReader.join(1_000)
        errReader.join(1_000)
        return Result(if (finished) process.exitValue() else -2, out, err)
    }

    private fun InputStream.readAll(): String = bufferedReader().use { it.readText() }
}
