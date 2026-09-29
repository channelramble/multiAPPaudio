package io.github.channelramble.multiappaudio.core

import android.content.pm.PackageManager
import android.media.audiofx.DynamicsProcessing
import android.os.SystemClock
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Per-app volume without root or Shizuku.
 *
 * Android has no per-app volume API, but any app with MODIFY_AUDIO_SETTINGS (a normal permission)
 * may attach an audio effect to another app's audio session, which is how equalizer apps work.
 * We attach a [DynamicsProcessing] effect to every session owned by an app the user adjusted and
 * set its input gain: below 0 dB turns that app down, above 0 dB boosts it (with a limiter so it
 * can't clip). Session ids and owners come from `dumpsys audio` (see [AudioDump]).
 *
 * The effects belong to this process, but Android keeps an effect pinned to another app's session
 * (at its last gain) if this process dies, until that app releases its audio. The restarted helper
 * takes the pinned effect over; right after a restart that can fail for a moment, so failed
 * sessions are retried with backoff, and ones that keep failing are reported by [blockedPackages].
 */
class AppVolumes(
    private val packageManager: PackageManager,
    private val log: EventLog,
) {
    private class Attached(val pkg: String, val effect: DynamicsProcessing, var gainDb: Float)

    private class Failure(val pkg: String) {
        var attempts = 0
        var retryAt = 0L
    }

    private val attached = HashMap<Int, Attached>() // session id -> effect
    private val failures = HashMap<Int, Failure>()

    /** package -> gain in dB; packages at 0 dB are simply left alone. */
    var targets: Map<String, Float> = emptyMap()

    fun sync(players: List<AudioDump.Player>) {
        val wanted = HashMap<Int, Pair<String, Float>>()
        for (p in players) {
            if (p.sessionId <= 0 || p.state == "released") continue
            val pkg = packageManager.packagesForUid(p.uid).firstOrNull { it in targets } ?: continue
            val gain = targets.getValue(pkg)
            if (gain != 0f) wanted[p.sessionId] = pkg to gain
        }
        for (session in attached.keys - wanted.keys) release(session)
        failures.keys.retainAll(wanted.keys)
        val now = SystemClock.elapsedRealtime()
        for ((session, target) in wanted) {
            val (pkg, gain) = target
            val existing = attached[session]
            when {
                existing == null -> if (now >= (failures[session]?.retryAt ?: 0L)) attach(session, pkg, gain, now)
                existing.gainDb != gain -> apply(existing, gain)
            }
        }
    }

    /** Apps with a session that refused the volume effect more than once. */
    fun blockedPackages(): Set<String> = failures.values.filter { it.attempts >= 2 }.mapTo(HashSet()) { it.pkg }

    /** When the next failed session is due for a retry (elapsedRealtime), if any. */
    fun nextRetryAt(): Long? = failures.values.minOfOrNull { it.retryAt }

    fun releaseAll() = attached.keys.toList().forEach { release(it) }

    fun summary(): List<String> =
        attached.entries.map { (session, a) -> "${a.pkg} session $session: ${"%+.1f".format(a.gainDb)} dB" }

    private fun attach(session: Int, pkg: String, gain: Float, now: Long) {
        try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                /* channelCount */ 2,
                /* preEqInUse */ false, 0,
                /* mbcInUse */ false, 0,
                /* postEqInUse */ false, 0,
                /* limiterInUse */ true,
            ).build()
            val effect = DynamicsProcessing(PRIORITY, session, config)
            effect.setControlStatusListener { _, controlGranted ->
                if (!controlGranted) log.add("volume", "$pkg: another app took control of the volume effect")
            }
            val a = Attached(pkg, effect, gain)
            apply(a, gain)
            effect.enabled = true
            attached[session] = a
            failures.remove(session)
            log.add("volume", "$pkg: attached to session $session at ${"%+.1f".format(gain)} dB")
        } catch (t: Throwable) {
            val f = failures.getOrPut(session) { Failure(pkg) }
            f.attempts++
            f.retryAt = now + (RETRY_MS shl (f.attempts - 1).coerceAtMost(RETRY_MAX_SHIFT))
            if (f.attempts == 1) {
                log.add("volume", "$pkg: can't attach to session $session (${t.javaClass.simpleName}: ${t.message})")
            } else if (f.attempts % 10 == 0) {
                log.add("volume", "$pkg: session $session still refuses the effect after ${f.attempts} tries")
            }
        }
    }

    private fun apply(a: Attached, gain: Float) {
        a.effect.setInputGainAllChannelsTo(gain)
        a.effect.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                /* inUse */ true, /* enabled */ gain > 0f, /* linkGroup */ 0,
                /* attackTime ms */ 1f, /* releaseTime ms */ 60f, /* ratio */ 10f,
                /* threshold dB */ -1f, /* postGain dB */ 0f,
            )
        )
        a.gainDb = gain
    }

    private fun release(session: Int) {
        val a = attached.remove(session) ?: return
        runCatching { a.effect.enabled = false }
        runCatching { a.effect.release() }
        log.add("volume", "${a.pkg}: released session $session")
    }

    companion object {
        /** High priority so we keep control of the effect if an equalizer app attaches too. */
        private const val PRIORITY = Int.MAX_VALUE
        private const val RETRY_MS = 2_000L
        private const val RETRY_MAX_SHIFT = 5 // backoff tops out at a minute
        const val MIN_DB = -60f
        const val MAX_DB = 6f
        const val MAX_PERCENT = 200

        /** Levels the notification's - / + buttons step through. */
        private val STEPS = intArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 125, 150, 175, 200)

        /** Slider percent (0..200) to gain; 100% is unchanged, 0% is effectively silent. */
        fun percentToDb(percent: Int): Float =
            if (percent <= 0) MIN_DB else (20 * log10(percent / 100.0)).toFloat().coerceIn(MIN_DB, MAX_DB)

        // Rounded, not truncated: percentToDb(70) comes back as 69.99...
        fun dbToPercent(db: Float): Int =
            if (db <= MIN_DB) 0 else (100 * 10.0.pow(db / 20.0)).roundToInt().coerceIn(0, MAX_PERCENT)

        fun stepPercent(current: Int, up: Boolean): Int =
            if (up) STEPS.firstOrNull { it > current } ?: MAX_PERCENT else STEPS.lastOrNull { it < current } ?: 0
    }
}
