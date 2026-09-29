package io.github.channelramble.multiappaudio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the helper back after a reboot or an app update, so per-app volume and the call /
 * Android Auto helpers keep working with nothing to re-run. (The ADB settings themselves are
 * persisted by Android and need no process at all.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AudioControlService.refresh(context)
    }
}
