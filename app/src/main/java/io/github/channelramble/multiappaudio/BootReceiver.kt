package io.github.channelramble.multiappaudio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Nothing here is required for the core feature: multi audio focus is a persisted system setting
 * that Android re-applies on every boot by itself. This only makes sure the optional helper comes
 * back when Shizuku is already up at boot (root / Sui) or after the app is updated. When Shizuku
 * starts later, it wakes this app on its own and [Helper] starts the daemon then.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Helper.init(context)
        Helper.ensureRunning()
    }
}
