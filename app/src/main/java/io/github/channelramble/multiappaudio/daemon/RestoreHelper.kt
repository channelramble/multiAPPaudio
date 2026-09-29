package io.github.channelramble.multiappaudio.daemon

import android.media.AudioFocusInfo
import android.media.AudioManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock

/**
 * Works around the AOSP multi-audio-focus bug on phones (see [AudioServiceBridge.unduckUid]):
 * once a navigation prompt, assistant or call interrupts apps that are in the multi-focus list,
 * they never receive AUDIOFOCUS_GAIN back, so ducked music stays quiet and paused apps stay paused.
 *
 * When the last transient focus owner goes away (and no call is active) we:
 *  1. clear any framework duck that is still applied (the stock framework would have done this on
 *     focus gain), and
 *  2. resume apps that paused themselves because of the interruption (what they would have done
 *     on AUDIOFOCUS_GAIN).
 *
 * It stands down automatically when multi focus is off, when Google's own fix flag is active, or
 * when an external focus policy (e.g. Android Auto) owns focus decisions.
 */
class RestoreHelper(
    private val audio: AudioServiceBridge,
    private val sessions: SessionWatcher,
    private val passThrough: PassThrough,
    private val handler: Handler,
    private val log: EventLog,
    private val audioMode: () -> Int,
    private val upstreamFixActive: Boolean,
) : FocusMonitor.Sink {

    var enabled = true
    var restores = 0
        private set

    private val transientOwners = HashMap<String, String>() // clientId -> package
    private val pausedByInterruption = HashMap<String, Long>() // package -> when

    private val restoreRunnable = Runnable { log.guard("restore") { restore() } }

    override fun onFocusGrant(afi: AudioFocusInfo) {
        if (afi.clientId.startsWith(OWN_PREFIX)) return
        log.add("focus", "grant   ${FocusMonitor.describe(afi)}")
        if (afi.gainRequest != AudioManager.AUDIOFOCUS_GAIN) {
            transientOwners[afi.clientId] = afi.packageName
        } else {
            pausedByInterruption.remove(afi.packageName) // it asked for focus again by itself
        }
    }

    override fun onFocusLoss(afi: AudioFocusInfo, wasNotified: Boolean) {
        if (afi.clientId.startsWith(OWN_PREFIX)) return
        when (afi.lossReceived) {
            0 -> { // followers are told about an abandon as a "loss" with no loss code
                log.add("focus", "abandon ${FocusMonitor.describe(afi)}")
                if (transientOwners.remove(afi.clientId) != null) schedule()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                log.add("focus", "loss    ${FocusMonitor.describe(afi)} notified=$wasNotified")
                transientOwners.remove(afi.clientId)
                pausedByInterruption.remove(afi.packageName)
            }
            else -> {
                log.add("focus", "loss    ${FocusMonitor.describe(afi)} notified=$wasNotified")
                val pkg = afi.packageName
                val wasPlaying = sessions.isPlaying(pkg) ||
                    SystemClock.uptimeMillis() - sessions.lastPlayingAt(pkg) < RECENT_MS
                if (afi.gainRequest == AudioManager.AUDIOFOCUS_GAIN && wasNotified && wasPlaying) {
                    pausedByInterruption[pkg] = SystemClock.uptimeMillis()
                }
            }
        }
    }

    fun onAudioModeChanged(mode: Int) {
        if (mode == AudioManager.MODE_NORMAL) schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(restoreRunnable)
        handler.postDelayed(restoreRunnable, SETTLE_MS)
    }

    /** Low-frequency safety net in case an abandon was missed (e.g. the interrupting app died). */
    fun tick() {
        if (!enabled || upstreamFixActive) return
        if (pausedByInterruption.isNotEmpty() || audio.focusDuckedUids().orEmpty().isNotEmpty()) {
            schedule()
        }
    }

    private fun stillInterrupted(): Boolean {
        val stack = audio.focusStack() ?: return transientOwners.isNotEmpty()
        val live = stack.map { it.clientId }.filterNot { it.startsWith(OWN_PREFIX) }.toSet()
        transientOwners.keys.retainAll(live) // forget owners that died without abandoning
        return live.isNotEmpty()
    }

    private fun restore() {
        if (!enabled) return
        if (audioMode() != AudioManager.MODE_NORMAL || stillInterrupted()) return
        if (upstreamFixActive) {
            pausedByInterruption.clear()
            return
        }
        val dump = AudioDump.capture()
        if (dump.externalFocusPolicy != false) return // unknown or external policy: hands off
        if (audio.isMultiAudioFocusActive(dump) != true) {
            pausedByInterruption.clear() // stock focus handling already returns focus correctly
            return
        }
        for (uid in audio.focusDuckedUids().orEmpty().distinct()) {
            if (passThrough.isIsolatedUid(uid)) continue
            val ok = runCatching { audio.unduckUid(uid) }
                .onFailure { log.add("restore", "un-duck uid $uid failed: $it") }
                .getOrDefault(false)
            if (ok) {
                restores++
                log.add("restore", "un-ducked uid $uid after the interruption ended")
            }
        }
        val now = SystemClock.uptimeMillis()
        for ((pkg, at) in pausedByInterruption) {
            if (now - at > MAX_INTERRUPTION_MS) continue
            if (sessions.stateOf(pkg) == PlaybackState.STATE_PAUSED &&
                sessions.play(pkg, "interruption ended")
            ) {
                restores++
            }
        }
        pausedByInterruption.clear()
    }

    companion object {
        /** Client ids of our own test focus requests, which must not feed back into this logic. */
        const val OWN_PREFIX = "mma-"
        private const val SETTLE_MS = 600L
        private const val RECENT_MS = 2_000L
        private const val MAX_INTERRUPTION_MS = 30 * 60_000L
    }
}
