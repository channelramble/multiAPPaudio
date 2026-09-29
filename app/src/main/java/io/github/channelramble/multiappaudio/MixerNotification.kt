package io.github.channelramble.multiappaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.RemoteViews
import io.github.channelramble.multiappaudio.core.ActiveApps
import io.github.channelramble.multiappaudio.core.AppLabels
import io.github.channelramble.multiappaudio.core.AppVolumes

/**
 * The helper's foreground notification, which doubles as the quick volume control. Collapsed, it
 * lists what's playing with each app's level; expanded, each app gets - / + buttons; tapping it
 * opens the slider panel ([MixerActivity]).
 */
class MixerNotification(private val context: Context, private val labels: AppLabels) {

    private val icons = HashMap<String, Bitmap?>()

    fun createChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.mixer_title), NotificationManager.IMPORTANCE_LOW).apply {
                description = "Per-app volume controls. Also keeps the per-app volume and Android Auto helpers running."
                setShowBadge(false)
            }
        )
        // 0.2.0's minimized "Background helper" channel; this one replaces it.
        nm.deleteNotificationChannel(OLD_CHANNEL)
    }

    /** [readable]: whether the last `dumpsys audio` read worked (see AudioDump.lastFailure). */
    fun build(apps: List<ActiveApps.App>, volumes: Map<String, Float>, hasDump: Boolean, readable: Boolean): Notification {
        val shown = apps.filter { it.state != ActiveApps.State.SAVED }.take(MAX_ROWS)
        val summary = when {
            !hasDump -> "Grant DUMP over ADB to see which apps are playing"
            !readable -> "Can't see which apps are playing. Open the app for details"
            shown.isEmpty() -> "Nothing playing. Tap to set app volumes"
            else -> shown.joinToString(" · ") { "${labels.label(it.pkg)} ${percentText(percent(volumes, it.pkg))}" }
        }
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_mixer)
            .setContentTitle(context.getString(R.string.mixer_title))
            .setContentText(summary)
            .setContentIntent(openPanel(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (shown.isNotEmpty()) {
            // Only the expanded view is custom; collapsed keeps the standard title + summary.
            builder.setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomBigContentView(expanded(shown, volumes))
        }
        return builder.build()
    }

    private fun expanded(apps: List<ActiveApps.App>, volumes: Map<String, Float>): RemoteViews {
        val root = RemoteViews(context.packageName, R.layout.mixer_notification)
        for (app in apps) {
            val name = labels.label(app.pkg)
            val percent = percent(volumes, app.pkg)
            val row = RemoteViews(context.packageName, R.layout.mixer_notification_row)
            icon(app.pkg)?.let { row.setImageViewBitmap(R.id.icon, it) }
            row.setTextViewText(R.id.name, when {
                app.blocked -> "$name · Can't adjust"
                app.state == ActiveApps.State.PAUSED -> "$name · Paused"
                else -> name
            })
            row.setTextViewText(R.id.percent, percentText(percent))
            row.setProgressBar(R.id.level, AppVolumes.MAX_PERCENT, percent, false)
            row.setOnClickPendingIntent(R.id.down, MixerReceiver.step(context, app.pkg, up = false))
            row.setOnClickPendingIntent(R.id.up, MixerReceiver.step(context, app.pkg, up = true))
            row.setContentDescription(R.id.down, "$name quieter")
            row.setContentDescription(R.id.up, "$name louder")
            row.setFloat(R.id.down, "setAlpha", if (percent > 0) 1f else DISABLED_ALPHA)
            row.setFloat(R.id.up, "setAlpha", if (percent < AppVolumes.MAX_PERCENT) 1f else DISABLED_ALPHA)
            root.addView(R.id.rows, row)
        }
        root.setTextViewText(R.id.hint, "Tap for sliders and more apps")
        return root
    }

    private fun icon(pkg: String): Bitmap? = icons.getOrPut(pkg) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            val px = (ICON_DP * context.resources.displayMetrics.density).toInt()
            Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also {
                drawable.setBounds(0, 0, px, px)
                drawable.draw(Canvas(it))
            }
        }.getOrNull()
    }

    companion object {
        const val CHANNEL = "app_volume"
        private const val OLD_CHANNEL = "helper"
        private const val MAX_ROWS = 3
        private const val ICON_DP = 28
        private const val DISABLED_ALPHA = 0.38f

        fun percent(volumes: Map<String, Float>, pkg: String) = AppVolumes.dbToPercent(volumes[pkg] ?: 0f)

        fun percentText(percent: Int) = if (percent == 0) "Muted" else "$percent%"

        fun openPanel(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MixerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
