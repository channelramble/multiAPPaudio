package io.github.channelramble.multiappaudio.daemon

/**
 * Google's own fix for the multi-audio-focus "never gets focus back" bug ships in Android 16 QPR2+
 * behind the read-write aconfig flag `android.media.audio.audio_focus_desktop` (namespace
 * `media_audio`), which is meant for desktop devices and is off on phones.
 *
 * Flipping it from the shell is best-effort: Android 17's `device_config` CLI refuses aconfig
 * writes unless root, and newer builds restrict which namespaces the shell may write. We try the
 * DeviceConfig API directly and report the outcome honestly. When it does not stick, the daemon's
 * [RestoreHelper] covers the same bug while it is running.
 */
object UpstreamFixFlag {
    private const val NAMESPACE = "media_audio"
    private const val FLAG = "android.media.audio.audio_focus_desktop"

    /** The value the running system_server was booted with, or null if it can't be read. */
    fun isActive(): Boolean? = runCatching {
        Hidden.staticCall("android.media.audio.Flags", "audioFocusDesktop") as Boolean
    }.getOrNull()

    /** A value staged in DeviceConfig (applies after reboot), if any. */
    fun staged(): String? = runCatching {
        Hidden.staticCall("android.provider.DeviceConfig", "getProperty", NAMESPACE, FLAG) as String?
    }.getOrNull()

    fun set(enabled: Boolean): String {
        val attempts = mutableListOf<String>()
        try {
            val ok = if (enabled) {
                Hidden.staticCall(
                    "android.provider.DeviceConfig", "setLocalOverride", NAMESPACE, FLAG, "true"
                ) as Boolean
            } else {
                Hidden.staticCall("android.provider.DeviceConfig", "clearLocalOverride", NAMESPACE, FLAG)
                true
            }
            attempts += "DeviceConfig.${if (enabled) "setLocalOverride" else "clearLocalOverride"}: " +
                if (ok) "accepted" else "rejected"
            if (ok) return attempts.joinToString("\n") + "\nReboot to apply, then check the status line."
        } catch (t: Throwable) {
            attempts += "DeviceConfig API: ${t.javaClass.simpleName}: ${t.message}"
        }
        val cli = if (enabled) {
            Shell.run("device_config", "override", NAMESPACE, FLAG, "true")
        } else {
            Shell.run("device_config", "clear_override", NAMESPACE, FLAG)
        }
        attempts += "device_config CLI (exit ${cli.code}): ${cli.text().ifBlank { "no output" }}"
        return attempts.joinToString("\n")
    }
}
