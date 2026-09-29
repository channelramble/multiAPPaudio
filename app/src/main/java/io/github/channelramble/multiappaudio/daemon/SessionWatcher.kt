package io.github.channelramble.multiappaudio.daemon

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock

/**
 * Follows every active MediaSession (the shell holds MEDIA_CONTENT_CONTROL, so no notification
 * listener is needed) and reports playback state transitions per package.
 */
class SessionWatcher(
    context: Context,
    private val handler: Handler,
    private val log: EventLog,
    private val listener: Listener,
) {
    interface Listener {
        fun onStateChanged(pkg: String, old: Int, new: Int)
    }

    private class Tracked(val controller: MediaController, val callback: MediaController.Callback) {
        var state: Int = controller.playbackState?.state ?: PlaybackState.STATE_NONE
        var lastPlayingAt: Long = if (state == PlaybackState.STATE_PLAYING) SystemClock.uptimeMillis() else 0L
    }

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val tracked = HashMap<MediaSession.Token, Tracked>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        log.guard("sessions changed") { sync(list.orEmpty()) }
    }

    fun start() {
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, null, handler)
            sync(manager.getActiveSessions(null))
            log.add("session", "watching ${tracked.size} media session(s)")
        } catch (t: Throwable) {
            log.add("session", "media session access failed: $t")
        }
    }

    fun stop() {
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        tracked.values.forEach { runCatching { it.controller.unregisterCallback(it.callback) } }
        tracked.clear()
    }

    fun stateOf(pkg: String): Int =
        tracked.values.filter { it.controller.packageName == pkg }
            .maxByOrNull { if (it.state == PlaybackState.STATE_PLAYING) 1 else 0 }?.state
            ?: PlaybackState.STATE_NONE

    fun isPlaying(pkg: String) = stateOf(pkg) == PlaybackState.STATE_PLAYING

    fun lastPlayingAt(pkg: String): Long =
        tracked.values.filter { it.controller.packageName == pkg }.maxOfOrNull { it.lastPlayingAt } ?: 0L

    fun play(pkg: String, why: String): Boolean {
        val t = pick(pkg) ?: return false
        log.add("action", "play() -> $pkg ($why)")
        return runCatching { t.controller.transportControls.play(); true }.getOrDefault(false)
    }

    fun pause(pkg: String, why: String): Boolean {
        val t = pick(pkg) ?: return false
        log.add("action", "pause() -> $pkg ($why)")
        return runCatching { t.controller.transportControls.pause(); true }.getOrDefault(false)
    }

    fun summary(): Array<String> =
        tracked.values.map { "${it.controller.packageName}: ${stateName(it.state)}" }
            .distinct().sorted().toTypedArray()

    private fun pick(pkg: String): Tracked? =
        tracked.values.filter { it.controller.packageName == pkg }
            .maxByOrNull { maxOf(it.lastPlayingAt, if (it.state == PlaybackState.STATE_PAUSED) 1L else 0L) }

    private fun sync(controllers: List<MediaController>) {
        val live = controllers.associateBy { it.sessionToken }
        tracked.keys.filter { it !in live }.forEach { token ->
            tracked.remove(token)?.let { gone ->
                runCatching { gone.controller.unregisterCallback(gone.callback) }
                if (gone.state == PlaybackState.STATE_PLAYING) {
                    listener.onStateChanged(gone.controller.packageName, gone.state, PlaybackState.STATE_NONE)
                }
            }
        }
        for ((token, controller) in live) {
            if (token in tracked) continue
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    log.guard("playback state") { update(token, state?.state ?: PlaybackState.STATE_NONE) }
                }

                override fun onSessionDestroyed() {
                    log.guard("session destroyed") { update(token, PlaybackState.STATE_NONE) }
                }
            }
            val t = Tracked(controller, callback)
            tracked[token] = t
            controller.registerCallback(callback, handler)
            log.add("session", "+ ${controller.packageName} (${stateName(t.state)})")
        }
    }

    private fun update(token: MediaSession.Token, newState: Int) {
        val t = tracked[token] ?: return
        // Buffering / connecting / skipping are transient; keep the last stable state so a
        // PLAYING -> BUFFERING -> PLAYING blip is not mistaken for an interruption.
        if (newState !in REPORTED) return
        val old = t.state
        if (old == newState) return
        t.state = newState
        if (newState == PlaybackState.STATE_PLAYING) t.lastPlayingAt = SystemClock.uptimeMillis()
        log.add("session", "${t.controller.packageName}: ${stateName(old)} -> ${stateName(newState)}")
        listener.onStateChanged(t.controller.packageName, old, newState)
    }

    companion object {
        private val REPORTED = setOf(
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, PlaybackState.STATE_ERROR,
        )

        fun stateName(state: Int) = when (state) {
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            PlaybackState.STATE_CONNECTING -> "CONNECTING"
            PlaybackState.STATE_ERROR -> "ERROR"
            PlaybackState.STATE_NONE -> "NONE"
            else -> "STATE_$state"
        }
    }
}
