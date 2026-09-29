package io.github.channelramble.multiappaudio.core

import android.content.pm.PackageManager

/**
 * The apps the App volume controls list: the ones playing right now. An app that stops stays
 * listed for a few seconds (as paused or stopped), so a buffering pause or a track change doesn't
 * make its row flicker. Fed from the same player list that per-app volume uses.
 */
class ActiveApps(private val packageManager: PackageManager, private val ownPackage: String) {

    enum class State { PLAYING, PAUSED, STOPPED }

    /** [blocked]: its audio currently refuses the volume effect (see AppVolumes). */
    data class App(val pkg: String, val state: State, val blocked: Boolean = false)

    private var playing: Set<String> = emptySet()
    private var paused: Set<String> = emptySet()
    private val stoppedAt = HashMap<String, Long>() // package -> elapsedRealtime it stopped playing

    fun update(players: List<AudioDump.Player>, now: Long) {
        val nowPlaying = HashSet<String>()
        val nowPaused = HashSet<String>()
        for (p in players) {
            if (p.uid < FIRST_APP_UID || p.usage !in APP_USAGES) continue
            val pkg = packageManager.packagesForUid(p.uid).firstOrNull() ?: continue
            if (pkg == ownPackage) continue
            when (p.state) {
                "started" -> nowPlaying += pkg
                "paused" -> nowPaused += pkg
            }
        }
        for (pkg in playing - nowPlaying) stoppedAt[pkg] = now
        stoppedAt.keys.removeAll(nowPlaying)
        playing = nowPlaying
        paused = nowPaused
    }

    /** Playing apps plus ones that stopped moments ago, alphabetical so rows don't jump around. */
    fun list(now: Long, label: (String) -> String): List<App> {
        stoppedAt.values.removeAll { now - it >= LINGER_MS }
        return (playing + stoppedAt.keys).map { pkg ->
            val state = when (pkg) {
                in playing -> State.PLAYING
                in paused -> State.PAUSED
                else -> State.STOPPED
            }
            App(pkg, state)
        }.sortedBy { label(it.pkg).lowercase() }
    }

    /** When the next app that stopped drops off the list (elapsedRealtime), if any. */
    fun nextExpiry(): Long? = stoppedAt.values.minOrNull()?.plus(LINGER_MS)

    companion object {
        private const val FIRST_APP_UID = 10_000
        private const val LINGER_MS = 5_000L

        /** App audio worth a volume control; UI sounds, notifications, alarms and calls are left out. */
        private val APP_USAGES = setOf(
            "USAGE_MEDIA", "USAGE_GAME", "USAGE_UNKNOWN",
            "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE", "USAGE_ASSISTANT",
        )
    }
}
