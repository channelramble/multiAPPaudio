package io.github.channelramble.multiappaudio

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle

/**
 * User settings, persisted in the app's SharedPreferences and pushed to the helper daemon
 * every time it (re)connects. The daemon itself keeps no state across restarts.
 */
class Config(private val prefs: SharedPreferences) {

    var helperEnabled: Boolean
        get() = prefs.getBoolean(KEY_HELPER, true)
        set(v) = prefs.edit().putBoolean(KEY_HELPER, v).apply()

    var restoreHelper: Boolean
        get() = prefs.getBoolean(Protocol.C_RESTORE_HELPER, true)
        set(v) = prefs.edit().putBoolean(Protocol.C_RESTORE_HELPER, v).apply()

    var passThroughPackages: Set<String>
        get() = prefs.getStringSet(Protocol.C_PASS_THROUGH_PKGS, null)?.toSet() ?: DEFAULT_PASS_THROUGH
        set(v) = prefs.edit().putStringSet(Protocol.C_PASS_THROUGH_PKGS, v.toSet()).apply()

    var aaIsolation: Boolean
        get() = prefs.getBoolean(Protocol.C_AA_ISOLATION, true)
        set(v) = prefs.edit().putBoolean(Protocol.C_AA_ISOLATION, v).apply()

    var isolationAlways: Boolean
        get() = prefs.getBoolean(Protocol.C_ISOLATION_ALWAYS, false)
        set(v) = prefs.edit().putBoolean(Protocol.C_ISOLATION_ALWAYS, v).apply()

    var aaGuard: Boolean
        get() = prefs.getBoolean(Protocol.C_AA_GUARD, true)
        set(v) = prefs.edit().putBoolean(Protocol.C_AA_GUARD, v).apply()

    var guardWindowSeconds: Int
        get() = prefs.getInt(Protocol.C_GUARD_WINDOW_S, 15)
        set(v) = prefs.edit().putInt(Protocol.C_GUARD_WINDOW_S, v).apply()

    var pauseOnCall: Boolean
        get() = prefs.getBoolean(Protocol.C_PAUSE_ON_CALL, true)
        set(v) = prefs.edit().putBoolean(Protocol.C_PAUSE_ON_CALL, v).apply()

    /** Packages the user asked us to put in the "never take audio focus" AppOps state. */
    var appOpsPackages: Set<String>
        get() = prefs.getStringSet(KEY_APPOPS, null)?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_APPOPS, v.toSet()).apply()

    fun toBundle(): Bundle = Bundle().apply {
        putBoolean(Protocol.C_RESTORE_HELPER, restoreHelper)
        putStringArray(Protocol.C_PASS_THROUGH_PKGS, passThroughPackages.sorted().toTypedArray())
        putBoolean(Protocol.C_AA_ISOLATION, aaIsolation)
        putBoolean(Protocol.C_ISOLATION_ALWAYS, isolationAlways)
        putBoolean(Protocol.C_AA_GUARD, aaGuard)
        putInt(Protocol.C_GUARD_WINDOW_S, guardWindowSeconds)
        putBoolean(Protocol.C_PAUSE_ON_CALL, pauseOnCall)
    }

    companion object {
        private const val KEY_HELPER = "helper_enabled"
        private const val KEY_APPOPS = "appops_pkgs"

        const val YOUTUBE = "com.google.android.youtube"
        const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
        const val ANDROID_AUTO = "com.google.android.projection.gearhead"

        val DEFAULT_PASS_THROUGH = setOf(YOUTUBE)

        fun of(context: Context) =
            Config(context.getSharedPreferences("config", Context.MODE_PRIVATE))
    }
}
