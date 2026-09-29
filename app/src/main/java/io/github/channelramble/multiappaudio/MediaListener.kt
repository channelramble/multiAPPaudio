package io.github.channelramble.multiappaudio

import android.service.notification.NotificationListenerService

/**
 * Exists only so the user can grant notification access, which is what lets a normal app see and
 * control other apps' media sessions (MediaSessionManager#getActiveSessions). Notifications
 * themselves are never read.
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        AudioControlService.refresh(this)
    }
}
