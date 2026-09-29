package io.github.channelramble.multiappaudio.daemon

import android.content.pm.PackageManager
import android.os.Binder

/**
 * Per-app audio focus isolation (Android 17+, `AudioManager#enterFocusIsolation`).
 *
 * An isolated uid's focus requests are always granted, never interrupt other apps, are never
 * interrupted by other apps, and - crucially for Android Auto - are handled *before* any external
 * focus policy is consulted (see MediaFocusControl#requestAudioFocus). So Android Auto's focus
 * policy never sees them and cannot pause them or swap in its own media source because of them.
 *
 * Each isolation is tied to a binder token we hold; if this daemon dies, the framework ends the
 * isolation automatically, so nothing is left behind.
 */
class PassThrough(
    private val audio: AudioServiceBridge,
    private val packageManager: PackageManager,
    private val log: EventLog,
) {
    private class Entry(val uid: Int, val token: Binder)

    private val entries = LinkedHashMap<String, Entry>()
    private var warnedUnsupported = false

    val isolatedPackages: Set<String> get() = entries.keys

    fun isIsolatedUid(uid: Int) = entries.values.any { it.uid == uid }

    fun apply(target: Set<String>) {
        if (!audio.isolationSupported) {
            if (target.isNotEmpty() && !warnedUnsupported) {
                warnedUnsupported = true
                log.add("isolate", "focus isolation needs Android 17+; not available on this build")
            }
            return
        }
        (entries.keys - target).toList().forEach { exit(it) }
        (target - entries.keys).forEach { enter(it) }
    }

    fun exitAll() = entries.keys.toList().forEach { exit(it) }

    private fun enter(pkg: String) {
        val uid = try {
            packageManager.getPackageUid(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            log.add("isolate", "$pkg is not installed, skipped")
            return
        }
        val token = Binder()
        val ok = runCatching { audio.enterIsolation(uid, token) }
            .onFailure { log.add("isolate", "enterFocusIsolation($pkg) failed: $it") }
            .getOrDefault(false)
        if (ok) {
            entries[pkg] = Entry(uid, token)
            log.add("isolate", "$pkg (uid $uid) now bypasses audio focus")
        } else {
            log.add("isolate", "enterFocusIsolation($pkg) refused (already isolated by someone else?)")
        }
    }

    private fun exit(pkg: String) {
        val e = entries.remove(pkg) ?: return
        runCatching { audio.exitIsolation(e.token, retainFocus = true) }
            .onFailure { log.add("isolate", "exitFocusIsolation($pkg) failed: $it") }
        log.add("isolate", "$pkg back to normal audio focus")
    }
}
