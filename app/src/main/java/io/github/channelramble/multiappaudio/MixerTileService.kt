package io.github.channelramble.multiappaudio

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.channelramble.multiappaudio.core.AppLabels
import io.github.channelramble.multiappaudio.core.AppVolumes

/** Quick Settings tile that opens the App volume panel; lit while any app isn't at 100%. */
class MixerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val changed = Config.of(this).volumes.filterValues { it != 0f }
        tile.state = if (changed.isEmpty()) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.subtitle = when (changed.size) {
            0 -> "All at 100%"
            1 -> changed.entries.first().let { (pkg, db) ->
                "${AppLabels(packageManager).label(pkg)} ${MixerNotification.percentText(AppVolumes.dbToPercent(db))}"
            }
            else -> "${changed.size} apps changed"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openPanel() } else openPanel()
    }

    private fun openPanel() {
        val intent = Intent(this, MixerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
