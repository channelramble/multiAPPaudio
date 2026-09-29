package io.github.channelramble.multiappaudio.core

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler

/**
 * Tracks whether Android Auto is connected, without any special permission.
 *
 * Primary signal: Android Auto's public car-connection provider, the same one androidx.car.app's
 * `CarConnection` reads (`content://androidx.car.app.connection`, column `CarConnectionState`,
 * 2 = projection), refreshed on its `CAR_CONNECTION_UPDATED` broadcast. Backup signal: car mode.
 */
class CarWatcher(
    private val context: Context,
    private val handler: Handler,
    private val log: EventLog,
    private val onChange: (connected: Boolean) -> Unit,
) {
    private val uiModeManager = context.getSystemService(UiModeManager::class.java)
    private var projection = false
    private var carMode = false
    private var started = false

    val isConnected: Boolean get() = projection || carMode

    val description: String
        get() = when {
            projection -> "Android Auto connected"
            carMode -> "car mode"
            else -> "not connected"
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            handler.post { log.guard("car broadcast") { refresh(intent.action ?: "broadcast") } }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            log.guard("car poll") { refresh("poll") }
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(ACTION_CAR_CONNECTION_UPDATED)
            addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
        }
        context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
        refresh("start")
        handler.postDelayed(poll, POLL_MS)
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(poll)
        runCatching { context.unregisterReceiver(receiver) }
        projection = false
        carMode = false
    }

    private fun refresh(reason: String) {
        val before = isConnected
        val newProjection = queryConnectionType() == CONNECTION_TYPE_PROJECTION
        val newCarMode = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_CAR
        if (newProjection != projection || newCarMode != carMode) {
            projection = newProjection
            carMode = newCarMode
            log.add("car", "$description ($reason)")
        }
        if (before != isConnected) onChange(isConnected)
    }

    private fun queryConnectionType(): Int = try {
        context.contentResolver.query(PROVIDER, arrayOf(COLUMN_STATE), null, null, null)?.use { c ->
            val column = c.getColumnIndex(COLUMN_STATE)
            if (column >= 0 && c.moveToNext()) c.getInt(column) else 0
        } ?: 0
    } catch (t: Throwable) {
        0
    }

    companion object {
        const val ACTION_CAR_CONNECTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
        private val PROVIDER: Uri = Uri.parse("content://androidx.car.app.connection")
        private const val COLUMN_STATE = "CarConnectionState"
        private const val CONNECTION_TYPE_PROJECTION = 2
        private const val POLL_MS = 30_000L
    }
}
