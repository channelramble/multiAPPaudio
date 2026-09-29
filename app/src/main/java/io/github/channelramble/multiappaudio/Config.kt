package io.github.channelramble.multiappaudio

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import io.github.channelramble.multiappaudio.core.AppVolumes

/** User settings, persisted in SharedPreferences. */
class Config(private val prefs: SharedPreferences) {

    /** package -> gain in dB as saved (0 = unchanged). */
    private var savedVolumes: Map<String, Float>
        get() = prefs.getStringSet(KEY_VOLUMES, emptySet()).orEmpty().mapNotNull { entry ->
            val pkg = entry.substringBefore('=')
            entry.substringAfter('=', "").toFloatOrNull()?.let { pkg to it }
        }.toMap()
        set(v) = prefs.edit().putStringSet(KEY_VOLUMES, v.map { "${it.key}=${it.value}" }.toSet()).apply()

    /**
     * The levels in effect: the saved ones, capped at 100% (0 dB) while boost is off. Saved boosts
     * are kept, so they come back when boost is turned on again.
     */
    val volumes: Map<String, Float>
        get() = if (allowBoost) savedVolumes else savedVolumes.mapValues { it.value.coerceAtMost(0f) }

    fun setVolume(pkg: String, db: Float) {
        savedVolumes = savedVolumes + (pkg to db)
    }

    /** Every app back to 100%, boosts included. */
    fun resetVolumes() {
        savedVolumes = emptyMap()
    }

    /** Let App volume go above 100%, up to 200% (a boost, with a limiter). */
    var allowBoost: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_BOOST, false)
        set(v) = prefs.edit().putBoolean(KEY_ALLOW_BOOST, v).apply()

    /** Top of the App volume controls: 100%, or 200% with boost on. */
    val maxPercent: Int get() = if (allowBoost) AppVolumes.MAX_PERCENT else 100

    /** How much each tap on the notification's - / + buttons changes an app's level, in percent. */
    var volumeStep: Int
        get() = prefs.getInt(KEY_VOLUME_STEP, DEFAULT_VOLUME_STEP).takeIf { it in VOLUME_STEPS } ?: DEFAULT_VOLUME_STEP
        set(v) = prefs.edit().putInt(KEY_VOLUME_STEP, v).apply()

    /** Keep the helper, and with it the App volume notification, running even when idle. */
    var volumeNotification: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_NOTIFICATION, true)
        set(v) = prefs.edit().putBoolean(KEY_VOLUME_NOTIFICATION, v).apply()

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
        if (volumeNotification || volumes.values.any { it != 0f }) return true
        val sessionFeatures = aaGuard || pauseOnCall || resumeAfterCall
        return sessionFeatures && hasNotificationAccess(context)
    }

    companion object {
        private const val KEY_VOLUMES = "volumes"
        private const val KEY_VOLUME_NOTIFICATION = "volume_notification"
        private const val KEY_ALLOW_BOOST = "allow_boost"
        private const val KEY_VOLUME_STEP = "volume_step"
        const val DEFAULT_VOLUME_STEP = 10
        val VOLUME_STEPS = listOf(5, 10, 20, 25)
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
