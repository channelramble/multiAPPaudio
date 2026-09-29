package io.github.channelramble.multiappaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import io.github.channelramble.multiappaudio.core.AppVolumes
import io.github.channelramble.multiappaudio.core.AudioDump
import io.github.channelramble.multiappaudio.core.AutoSwitchGuard
import io.github.channelramble.multiappaudio.core.CarWatcher
import io.github.channelramble.multiappaudio.core.EventLog
import io.github.channelramble.multiappaudio.core.SessionWatcher

/**
 * Background service for the features that need a live process: per-app volume effects, the
 * Android Auto auto-switch guard, and pause/resume around calls. It starts at boot by itself and
 * stops itself when none of those features is in use. Everything else (multi-app audio, pass-through,
 * mute) is a persisted system setting applied once over ADB and needs no process at all.
 */
class AudioControlService : Service() {

    /** What the UI shows; written on the service thread. */
    data class Snapshot(
        val dumpAvailable: Boolean = false,
        val multiFocusLive: Boolean? = null,
        val externalFocusPolicy: Boolean? = null,
        val car: String = "not watched",
        val sessionsWatched: Boolean = false,
        val sessions: List<String> = emptyList(),
        val volumes: List<String> = emptyList(),
        val guardFixes: Int = 0,
    )

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var audioManager: AudioManager
    private lateinit var volumes: AppVolumes
    private lateinit var sessions: SessionWatcher
    private lateinit var guard: AutoSwitchGuard
    private lateinit var car: CarWatcher

    private var playbackCallbackRegistered = false
    private var modeListenerRegistered = false
    private var lastMode = AudioManager.MODE_NORMAL
    private val playingAtCallStart = HashSet<String>()
    private val pausedByUs = HashSet<String>()
    private var dumpAvailable = false
    private var lastDump: AudioDump? = null

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            scheduleRescan(RESCAN_DEBOUNCE_MS)
        }
    }

    // Registered with an executor that already runs on the service thread.
    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        log.guard("audio mode") { onAudioModeChanged(mode) }
    }

    private val rescan = Runnable { log.guard("rescan") { rescanNow() } }

    private val periodic = object : Runnable {
        override fun run() {
            if (volumes.targets.values.any { it != 0f }) scheduleRescan(0)
            handler.postDelayed(this, PERIODIC_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        instance = this
        thread = HandlerThread("audio-control").also { it.start() }
        handler = Handler(thread.looper)
        audioManager = getSystemService(AudioManager::class.java)
        volumes = AppVolumes(packageManager, log)
        sessions = SessionWatcher(this, Config.listenerComponent(this), handler, log,
            object : SessionWatcher.Listener {
                // guard is assigned right below; callbacks only arrive later on the handler thread.
                override fun onStateChanged(pkg: String, old: Int, new: Int) = guard.onStateChanged(pkg, old, new)
            })
        guard = AutoSwitchGuard(sessions, handler, log)
        car = CarWatcher(this, handler, log) { connected -> guard.active = connected }
        log.add("service", "started (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        handler.postDelayed(periodic, PERIODIC_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.post { log.guard("reconcile") { reconcile() } }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.post {
            handler.removeCallbacksAndMessages(null)
            volumes.releaseAll()
            sessions.stop()
            car.stop()
            if (playbackCallbackRegistered) audioManager.unregisterAudioPlaybackCallback(playbackCallback)
            if (modeListenerRegistered) audioManager.removeOnModeChangedListener(modeListener)
            log.add("service", "stopped")
            thread.quitSafely()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --------------------------------------------------------------------------- behaviour

    private fun reconcile() {
        val cfg = Config.of(this)
        if (!cfg.needsService(this)) {
            log.add("service", "nothing to do, stopping")
            stopSelf()
            return
        }

        volumes.targets = cfg.volumes
        val wantVolumes = cfg.volumes.values.any { it != 0f }
        if (wantVolumes && !playbackCallbackRegistered) {
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
            playbackCallbackRegistered = true
        } else if (!wantVolumes && playbackCallbackRegistered) {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
            playbackCallbackRegistered = false
        }

        val access = Config.hasNotificationAccess(this)
        val wantSessions = access && (cfg.aaGuard || cfg.pauseOnCall || cfg.resumeAfterCall)
        if (wantSessions) sessions.start() else sessions.stop()
        if (wantSessions && cfg.aaGuard) car.start() else car.stop()

        if (wantSessions && !modeListenerRegistered) {
            audioManager.addOnModeChangedListener({ r -> handler.post(r) }, modeListener)
            modeListenerRegistered = true
            lastMode = audioManager.mode
        } else if (!wantSessions && modeListenerRegistered) {
            audioManager.removeOnModeChangedListener(modeListener)
            modeListenerRegistered = false
        }

        guard.enabled = cfg.aaGuard
        guard.windowMs = cfg.guardWindowSeconds * 1000L
        guard.protectedPackages = cfg.passThroughPackages
        guard.active = car.isConnected
        scheduleRescan(0)
    }

    private fun scheduleRescan(delayMs: Long) {
        handler.removeCallbacks(rescan)
        handler.postDelayed(rescan, delayMs)
    }

    private fun rescanNow() {
        val dump = AudioDump.capture(this)
        dumpAvailable = dump != null
        lastDump = dump ?: lastDump
        if (dump == null) {
            if (volumes.targets.values.any { it != 0f }) {
                log.add("volume", "can't read audio sessions: grant DUMP once with: ${Adb.grantDump(this)}")
            }
            return
        }
        volumes.sync(dump.players)
    }

    /**
     * Calls. Pass-through apps never receive focus losses, so they'd keep playing through a call:
     * pause them and resume them afterwards. With multi-app audio on, Android also never hands focus
     * back to paused apps after a call (an AOSP bug), so resume what was playing before it started.
     */
    private fun onAudioModeChanged(mode: Int) {
        if (mode == lastMode) return
        val cfg = Config.of(this)
        val callStarting = lastMode == AudioManager.MODE_NORMAL && mode != AudioManager.MODE_NORMAL
        val callEnded = lastMode != AudioManager.MODE_NORMAL && mode == AudioManager.MODE_NORMAL
        lastMode = mode
        if (callStarting) {
            log.add("call", "call/ringing started")
            playingAtCallStart.clear()
            playingAtCallStart += sessions.playingPackages()
            if (cfg.pauseOnCall) {
                for (pkg in cfg.passThroughPackages.intersect(playingAtCallStart)) {
                    if (sessions.pause(pkg, "call started")) pausedByUs += pkg
                }
            }
        } else if (callEnded) {
            log.add("call", "call ended")
            val multiFocusOn = Settings.System.getInt(contentResolver, MULTI_FOCUS_SETTING, 0) == 1
            val toResume = HashSet(pausedByUs)
            if (cfg.resumeAfterCall && multiFocusOn) toResume += playingAtCallStart
            pausedByUs.clear()
            playingAtCallStart.clear()
            handler.postDelayed({
                log.guard("resume after call") {
                    toResume.filter { sessions.stateOf(it) == PlaybackState.STATE_PAUSED }
                        .forEach { sessions.play(it, "call ended") }
                }
            }, RESUME_AFTER_CALL_MS)
        }
    }

    @Volatile
    private var cachedSnapshot = Snapshot()

    /**
     * Called from the UI thread. Never blocks: returns the last snapshot and asks the service thread
     * (the only thread that touches the state) for a fresh one, ready for the next UI poll.
     */
    fun snapshot(): Snapshot {
        handler.post { cachedSnapshot = buildSnapshot() }
        return cachedSnapshot
    }

    private fun buildSnapshot(): Snapshot = Snapshot(
        dumpAvailable = dumpAvailable,
        multiFocusLive = lastDump?.multiAudioFocusEnabled,
        externalFocusPolicy = lastDump?.externalFocusPolicy,
        car = if (Config.of(this).aaGuard) car.description else "not watched",
        sessionsWatched = sessions.isRunning,
        sessions = sessions.summary().toList(),
        volumes = volumes.summary(),
        guardFixes = guard.totalFixes,
    )

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Background helper", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps per-app volume and Android Auto helpers running. You can hide it."
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Multi-App Audio")
            .setContentText("Per-app volume and Android Auto helpers are active")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val MULTI_FOCUS_SETTING = "multi_audio_focus_enabled"
        private const val CHANNEL = "helper"
        private const val NOTIFICATION_ID = 1
        private const val RESCAN_DEBOUNCE_MS = 300L
        private const val PERIODIC_MS = 30_000L
        private const val RESUME_AFTER_CALL_MS = 1_500L

        val log = EventLog()

        @Volatile
        var instance: AudioControlService? = null
            private set

        /** Starts the service if some feature needs it, stops it otherwise. Safe to call anytime. */
        fun refresh(context: Context) {
            val intent = Intent(context, AudioControlService::class.java)
            try {
                if (Config.of(context).needsService(context)) {
                    context.startForegroundService(intent)
                } else {
                    context.stopService(intent)
                }
            } catch (t: Throwable) {
                // e.g. ForegroundServiceStartNotAllowedException when called from the background;
                // boot, app update and opening the app all start it from an allowed state.
                log.add("service", "couldn't start now: ${t.javaClass.simpleName}")
            }
        }
    }
}
