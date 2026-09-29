package io.github.channelramble.multiappaudio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.channelramble.multiappaudio.core.AppVolumes

/** The App volume notification's - / + buttons. */
class MixerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STEP) return
        val data = intent.data ?: return
        val pkg = data.getQueryParameter(PARAM_PKG) ?: return
        val up = data.getQueryParameter(PARAM_DIR) == "up"
        val config = Config.of(context)
        val current = AppVolumes.dbToPercent(config.volumes[pkg] ?: 0f)
        val next = AppVolumes.stepPercent(current, up, config.maxPercent, config.volumeStep)
        config.setVolume(pkg, AppVolumes.percentToDb(next))
        AudioControlService.volumesChanged(context)
    }

    companion object {
        private const val ACTION_STEP = "io.github.channelramble.multiappaudio.STEP_VOLUME"
        private const val PARAM_PKG = "pkg"
        private const val PARAM_DIR = "dir"

        /** The package and direction go in the data URI so every button gets its own PendingIntent. */
        fun step(context: Context, pkg: String, up: Boolean): PendingIntent {
            val data = Uri.Builder().scheme("multiappaudio").authority("step")
                .appendQueryParameter(PARAM_PKG, pkg)
                .appendQueryParameter(PARAM_DIR, if (up) "up" else "down")
                .build()
            val intent = Intent(ACTION_STEP, data, context, MixerReceiver::class.java)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
