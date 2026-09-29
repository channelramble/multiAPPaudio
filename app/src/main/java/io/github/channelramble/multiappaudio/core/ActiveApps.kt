package io.github.channelramble.multiappaudio.core

import android.content.pm.PackageManager

/**
 * The apps the quick volume controls list: apps with an audio player right now (playing or
 * paused), apps that played in the last half hour, and apps with a saved volume. Fed from the
 * same `dumpsys audio` player list that per-app volume uses.
 */
class ActiveApps(private val packageManager: PackageManager, private val ownPackage: String) {

    /** In list order. */
    enum class State { PLAYING, PAUSED, RECENT, SAVED }

    /** [blocked]: its audio currently refuses the volume effect (see AppVolumes). */
    data class App(val pkg: String, val state: State, val blocked: Boolean = false)

    private val lastPlayed = HashMap<String, Long>() // package -> elapsedRealtime last seen playing
    private var live: Map<String, State> = emptyMap()

    fun update(players: List<AudioDump.Player>, now: Long) {
        val states = HashMap<String, State>()
        for (p in players) {
            if (p.uid < FIRST_APP_UID || p.usage !in APP_USAGES) continue
            val state = when (p.state) {
                "started" -> State.PLAYING
                "paused" -> State.PAUSED
                else -> continue
            }
            val pkg = packageManager.packagesForUid(p.uid).firstOrNull() ?: continue
            if (pkg == ownPackage) continue
            if (state == State.PLAYING) lastPlayed[pkg] = now
            if (states[pkg] != State.PLAYING) states[pkg] = state
        }
        live = states
        lastPlayed.values.removeAll { now - it > RECENT_MS }
    }

    /** Playing apps first, then paused, recent and saved ones; alphabetical within each group. */
    fun list(saved: Collection<String>, now: Long, label: (String) -> String): List<App> {
        val state = HashMap<String, State>()
        for (pkg in saved) state[pkg] = State.SAVED
        for ((pkg, at) in lastPlayed) if (now - at <= RECENT_MS) state[pkg] = State.RECENT
        state.putAll(live)
        return state.map { App(it.key, it.value) }
            .sortedWith(compareBy<App> { it.state.ordinal }.thenBy { label(it.pkg).lowercase() })
    }

    companion object {
        private const val FIRST_APP_UID = 10_000
        private const val RECENT_MS = 30 * 60_000L

        /** App audio worth a volume control; UI sounds, notifications, alarms and calls are left out. */
        private val APP_USAGES = setOf(
            "USAGE_MEDIA", "USAGE_GAME", "USAGE_UNKNOWN",
            "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE", "USAGE_ASSISTANT",
        )
    }
}
