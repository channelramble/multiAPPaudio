package io.github.channelramble.multiappaudio.core

import android.content.pm.PackageManager
import android.os.Process

private const val PER_USER_RANGE = 100_000

/**
 * Packages behind an audio player's uid. A player in another profile (work profile, private space)
 * may not resolve from here, so it falls back to the same app in this profile: a volume set for an
 * app then covers its copies in every profile.
 */
fun PackageManager.packagesForUid(uid: Int): List<String> {
    runCatching { getPackagesForUid(uid) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it.toList() }
    val sameAppHere = Process.myUid() / PER_USER_RANGE * PER_USER_RANGE + uid % PER_USER_RANGE
    if (sameAppHere == uid) return emptyList()
    return runCatching { getPackagesForUid(sameAppHere) }.getOrNull()?.toList().orEmpty()
}

/** App names, cached: the quick controls refresh often and PackageManager lookups aren't free. */
class AppLabels(private val packageManager: PackageManager) {
    private val cache = HashMap<String, String>()

    @Synchronized
    fun label(pkg: String): String = cache.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}
