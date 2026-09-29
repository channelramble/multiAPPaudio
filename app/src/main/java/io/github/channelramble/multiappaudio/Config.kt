package io.github.channelramble.multiappaudio

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences

/** User settings, persisted in SharedPreferences. */
class Config(private val prefs: SharedPreferences) {

    /** package -> gain in dB (0 = unchanged). Kept even at 0 dB so the slider stays listed. */
    var volumes: Map<String, Float>
        get() = prefs.getStringSet(KEY_VOLUMES, emptySet()).orEmpty().mapNotNull { entry ->
            val pkg = entry.substringBefore('=')
            entry.substringAfter('=', "").toFloatOrNull()?.let { pkg to it }
        }.toMap()
        set(v) = prefs.edit().putStringSet(KEY_VOLUMES, v.map { "${it.key}=${it.value}" }.toSet()).apply()

    /** Apps set to "never take audio focus" (the ADB AppOps command); also protected by the guard. */
    var passThroughPackages: Set<String>
        get() = prefs.getStringSet(KEY_PASS_THROUGH, null)?.toSet() ?: DEFAULT_PASS_THROUGH
        set(v) = prefs.edit().putStringSet(KEY_PASS_THROUGH, v.toSet()).apply()

    /** Apps the user wants muted completely (the ADB PLAY_AUDIO command). */
    var mutedPackages: Set<String>
        get() = prefs.getStringSet(KEY_MUTED, emptySet()).orEmpty().toSet()
        set(v) = prefs.edit().putStringSet(KEY_MUTED, v.toSet()).apply()

    var aaGuard: Boolean
        get() = prefs.getBoolean(KEY_GUARD, true)
        set(v) = prefs.edit().putBoolean(KEY_GUARD, v).apply()

    var pauseOnCall: Boolean
        get() = prefs.getBoolean(KEY_PAUSE_ON_CALL, true)
        set(v) = prefs.edit().putBoolean(KEY_PAUSE_ON_CALL, v).apply()

    var resumeAfterCall: Boolean
        get() = prefs.getBoolean(KEY_RESUME_AFTER_CALL, true)
        set(v) = prefs.edit().putBoolean(KEY_RESUME_AFTER_CALL, v).apply()

    val guardWindowSeconds: Int get() = 15

    /** Whether the background service has anything to do. */
    fun needsService(context: Context): Boolean {
        if (volumes.values.any { it != 0f }) return true
        val sessionFeatures = aaGuard || pauseOnCall || resumeAfterCall
        return sessionFeatures && hasNotificationAccess(context)
    }

    companion object {
        private const val KEY_VOLUMES = "volumes"
        private const val KEY_PASS_THROUGH = "pass_through_pkgs"
        private const val KEY_MUTED = "muted_pkgs"
        private const val KEY_GUARD = "aa_guard"
        private const val KEY_PAUSE_ON_CALL = "pause_on_call"
        private const val KEY_RESUME_AFTER_CALL = "resume_after_call"

        const val YOUTUBE = "com.google.android.youtube"
        val DEFAULT_PASS_THROUGH = setOf(YOUTUBE)

        fun of(context: Context) =
            Config(context.getSharedPreferences("config", Context.MODE_PRIVATE))

        fun listenerComponent(context: Context) = ComponentName(context, MediaListener::class.java)

        fun hasNotificationAccess(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(listenerComponent(context))
    }
}
