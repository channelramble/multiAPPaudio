package io.github.channelramble.multiappaudio.core

import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock

/**
 * Undoes Android Auto's "you started YouTube, so here is YouTube Music instead" swap.
 *
 * Pattern we act on, only while projecting: a protected (pass-through) app starts playing, and
 * within [windowMs] it is stopped while another app starts playing within [PAIR_MS] of that stop.
 * A person deliberately switching sources rarely does so seconds after starting something, so this
 * pattern is a good signature of an automatic takeover. We pause the intruder and resume the
 * protected app, at most [MAX_FIXES] times per [RESET_MS] so we can never fight in a loop.
 */
class AutoSwitchGuard(
    private val sessions: SessionWatcher,
    private val handler: Handler,
    private val log: EventLog,
) {
    var enabled = false
    var active = false
        set(value) {
            if (field != value) reset()
            field = value
        }
    var windowMs = 15_000L
    var protectedPackages: Set<String> = emptySet()

    var totalFixes = 0
        private set

    private val startedAt = HashMap<String, Long>()
    private val interruptedAt = HashMap<String, Long>()
    private val intruderAt = HashMap<String, Long>()
    private val fixes = HashMap<String, Int>()
    private val lastFixAt = HashMap<String, Long>()

    fun onStateChanged(pkg: String, old: Int, new: Int) {
        if (!enabled || !active) return
        val now = SystemClock.uptimeMillis()
        if (pkg in protectedPackages) {
            if (new == PlaybackState.STATE_PLAYING) {
                startedAt[pkg] = now
                if (now - (lastFixAt[pkg] ?: 0L) > RESET_MS) fixes.remove(pkg)
            } else if (old == PlaybackState.STATE_PLAYING) {
                interruptedAt[pkg] = now
                val intruder = intruderAt.filter { now - it.value <= PAIR_MS }.maxByOrNull { it.value }?.key
                if (intruder != null) check(pkg, intruder, now)
            }
        } else if (new == PlaybackState.STATE_PLAYING) {
            intruderAt[pkg] = now
            for (p in protectedPackages) {
                val stoppedAt = interruptedAt[p] ?: continue
                if (now - stoppedAt <= PAIR_MS) check(p, pkg, now)
            }
        }
    }

    private fun check(protectedPkg: String, intruder: String, now: Long) {
        val started = startedAt[protectedPkg] ?: return
        val stopped = interruptedAt[protectedPkg] ?: return
        val playedFor = stopped - started
        if (playedFor > windowMs) return // it had been playing a while: treat as a deliberate switch
        val count = fixes[protectedPkg] ?: 0
        if (count >= MAX_FIXES) {
            log.add("guard", "$intruder keeps replacing $protectedPkg; giving up to avoid a loop")
            return
        }
        fixes[protectedPkg] = count + 1
        lastFixAt[protectedPkg] = now
        totalFixes++
        interruptedAt.remove(protectedPkg)
        intruderAt.remove(intruder)
        log.add(
            "guard",
            "$intruder replaced $protectedPkg ${playedFor / 1000.0}s after it started: undoing (${count + 1}/$MAX_FIXES)"
        )
        sessions.pause(intruder, "guard")
        handler.postDelayed({ log.guard("guard resume") { sessions.play(protectedPkg, "guard") } }, RESUME_DELAY_MS)
    }

    private fun reset() {
        startedAt.clear()
        interruptedAt.clear()
        intruderAt.clear()
        fixes.clear()
        lastFixAt.clear()
    }

    companion object {
        private const val PAIR_MS = 4_000L
        private const val MAX_FIXES = 2
        private const val RESET_MS = 60_000L
        private const val RESUME_DELAY_MS = 400L
    }
}
