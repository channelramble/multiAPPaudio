package io.github.channelramble.multiappaudio.core

import android.content.pm.PackageManager
import android.media.audiofx.DynamicsProcessing
import kotlin.math.log10
import kotlin.math.pow

/**
 * Per-app volume without root or Shizuku.
 *
 * Android has no per-app volume API, but any app with MODIFY_AUDIO_SETTINGS (a normal permission)
 * may attach an audio effect to another app's audio session, which is how equalizer apps work.
 * We attach a [DynamicsProcessing] effect to every session owned by an app the user adjusted and
 * set its input gain: below 0 dB turns that app down, above 0 dB boosts it (with a limiter so it
 * can't clip). Session ids and owners come from `dumpsys audio` (see [AudioDump]).
 *
 * The effects belong to this process: if it dies, apps return to normal volume until it restarts.
 */
class AppVolumes(
    private val packageManager: PackageManager,
    private val log: EventLog,
) {
    private class Attached(val pkg: String, val effect: DynamicsProcessing, var gainDb: Float)

    private val attached = HashMap<Int, Attached>() // session id -> effect
    private val failedSessions = HashSet<Int>()

    /** package -> gain in dB; packages at 0 dB are simply left alone. */
    var targets: Map<String, Float> = emptyMap()

    fun sync(players: List<AudioDump.Player>) {
        val wanted = HashMap<Int, Pair<String, Float>>()
        for (p in players) {
            if (p.sessionId <= 0 || p.state == "released") continue
            val pkg = packageManager.getPackagesForUid(p.uid)?.firstOrNull { it in targets } ?: continue
            val gain = targets.getValue(pkg)
            if (gain != 0f) wanted[p.sessionId] = pkg to gain
        }
        for (session in attached.keys - wanted.keys) release(session)
        failedSessions.retainAll(wanted.keys)
        for ((session, target) in wanted) {
            val (pkg, gain) = target
            val existing = attached[session]
            when {
                existing == null -> if (session !in failedSessions) attach(session, pkg, gain)
                existing.gainDb != gain -> apply(existing, gain)
            }
        }
    }

    fun releaseAll() = attached.keys.toList().forEach { release(it) }

    fun summary(): List<String> =
        attached.entries.map { (session, a) -> "${a.pkg} session $session: ${"%+.1f".format(a.gainDb)} dB" }

    private fun attach(session: Int, pkg: String, gain: Float) {
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
            log.add("volume", "$pkg: attached to session $session at ${"%+.1f".format(gain)} dB")
        } catch (t: Throwable) {
            failedSessions += session
            log.add("volume", "$pkg: can't attach to session $session (${t.javaClass.simpleName}: ${t.message})")
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
        const val MIN_DB = -60f
        const val MAX_DB = 6f

        /** Slider percent (0..200) to gain; 100% is unchanged, 0% is effectively silent. */
        fun percentToDb(percent: Int): Float =
            if (percent <= 0) MIN_DB else (20 * log10(percent / 100.0)).toFloat().coerceIn(MIN_DB, MAX_DB)

        fun dbToPercent(db: Float): Int =
            if (db <= MIN_DB) 0 else (100 * 10.0.pow(db / 20.0)).toInt()
    }
}
