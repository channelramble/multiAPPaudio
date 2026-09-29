package io.github.channelramble.multiappaudio.daemon

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler

/**
 * Diagnostics only: logs when an app's audio players actually start or stop producing sound,
 * independently of what its MediaSession claims. Because the shell holds MODIFY_AUDIO_ROUTING,
 * AudioService returns non-anonymized configurations (with uid and player state), which we read
 * through reflection since those getters are @SystemApi.
 */
class PlaybackWatcher(
    private val context: Context,
    private val handler: Handler,
    private val log: EventLog,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var activeByUid: Map<Int, String> = emptyMap()

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            log.guard("playback config") { update(configs.orEmpty()) }
        }
    }

    fun start() {
        runCatching {
            audioManager.registerAudioPlaybackCallback(callback, handler)
            update(audioManager.activePlaybackConfigurations)
        }.onFailure { log.add("playback", "playback monitor unavailable: $it") }
    }

    fun stop() {
        runCatching { audioManager.unregisterAudioPlaybackCallback(callback) }
    }

    private fun update(configs: List<AudioPlaybackConfiguration>) {
        val now = HashMap<Int, String>()
        for (c in configs) {
            val uid = intOf(c, "getClientUid") ?: continue
            if (intOf(c, "getPlayerState") != PLAYER_STATE_STARTED) continue
            val muted = runCatching { Hidden.call(c, "isMuted") as Boolean }.getOrDefault(false)
            val usage = c.audioAttributes.usage
            val desc = "usage=$usage" + if (muted) " (muted by system)" else ""
            now[uid] = now[uid]?.let { if (it.contains(desc)) it else "$it, $desc" } ?: desc
        }
        for ((uid, desc) in now) {
            if (activeByUid[uid] != desc) log.add("playback", "${name(uid)} producing audio: $desc")
        }
        for (uid in activeByUid.keys - now.keys) {
            log.add("playback", "${name(uid)} stopped producing audio")
        }
        activeByUid = now
    }

    private fun intOf(target: Any, getter: String): Int? =
        runCatching { Hidden.call(target, getter) as Int }.getOrNull()

    private fun name(uid: Int): String =
        context.packageManager.getNameForUid(uid)?.substringBefore(':') ?: "uid $uid"

    companion object {
        private const val PLAYER_STATE_STARTED = 2
    }
}
