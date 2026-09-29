package io.github.channelramble.multiappaudio

import android.app.NotificationManager
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
import android.os.SystemClock
import android.provider.Settings
import io.github.channelramble.multiappaudio.core.ActiveApps
import io.github.channelramble.multiappaudio.core.AppLabels
import io.github.channelramble.multiappaudio.core.AppVolumes
import io.github.channelramble.multiappaudio.core.AudioDump
import io.github.channelramble.multiappaudio.core.AutoSwitchGuard
import io.github.channelramble.multiappaudio.core.CarWatcher
import io.github.channelramble.multiappaudio.core.EventLog
import io.github.channelramble.multiappaudio.core.SessionWatcher

/**
 * Background service for the features that need a live process: per-app volume effects, the
 * App volume notification, the Android Auto auto-switch guard, and pause/resume around calls. It
 * starts at boot by itself and stops itself when none of those features is in use. Everything else
 * (multi-app audio, pass-through, mute) is a persisted system setting applied once over ADB and
 * needs no process at all.
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
    private lateinit var labels: AppLabels
    private lateinit var activeApps: ActiveApps
    private lateinit var notification: MixerNotification

    private var playbackCallbackRegistered = false
    private var modeListenerRegistered = false
    private var lastMode = AudioManager.MODE_NORMAL
    private val playingAtCallStart = HashSet<String>()
    private val pausedByUs = HashSet<String>()
    private var lastDump: AudioDump? = null
    private var warnedNoDump = false
    private var lastNotificationKey: String? = null

    /** What the quick controls list, in display order; published from the service thread. */
    @Volatile
    var mixerApps: List<ActiveApps.App> = emptyList()
        private set

    /** False while `dumpsys audio` can't be read, so nothing can be detected or adjusted. */
    @Volatile
    var canReadPlayers = true
        private set

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

    private val applyVolumes = Runnable { log.guard("apply volumes") { applyVolumesFromConfig() } }

    private val retryRescan = Runnable { scheduleRescan(0) }

    private val notificationUpdate = Runnable { log.guard("notification") { postNotification() } }

    private val periodic = object : Runnable {
        override fun run() {
            if (volumes.targets.values.any { it != 0f }) scheduleRescan(0) else publishApps()
            handler.postDelayed(this, PERIODIC_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        labels = AppLabels(packageManager)
        notification = MixerNotification(this, labels)
        startInForeground()
        thread = HandlerThread("audio-control").also { it.start() }
        handler = Handler(thread.looper)
        audioManager = getSystemService(AudioManager::class.java)
        volumes = AppVolumes(packageManager, log)
        activeApps = ActiveApps(packageManager, packageName)
        sessions = SessionWatcher(this, Config.listenerComponent(this), handler, log,
            object : SessionWatcher.Listener {
                // guard is assigned right below; callbacks only arrive later on the handler thread.
                override fun onStateChanged(pkg: String, old: Int, new: Int) = guard.onStateChanged(pkg, old, new)
            })
        guard = AutoSwitchGuard(sessions, handler, log)
        car = CarWatcher(this, handler, log) { connected -> guard.active = connected }
        log.add("service", "started (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        handler.postDelayed(periodic, PERIODIC_MS)
        instance = this // last: callers use the handler and helpers set up above
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
        if (!cfg.needsService(this) && !panelOpen) {
            log.add("service", "nothing to do, stopping")
            stopSelf()
            return
        }

        volumes.targets = cfg.volumes
        updatePlaybackCallback(cfg)

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
        publishApps()
    }

    /** Playback changes drive per-app volume and keep the App volume list current. */
    private fun updatePlaybackCallback(cfg: Config) {
        val want = cfg.volumes.values.any { it != 0f } || cfg.volumeNotification || panelOpen
        if (want && !playbackCallbackRegistered) {
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
            playbackCallbackRegistered = true
        } else if (!want && playbackCallbackRegistered) {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
            playbackCallbackRegistered = false
        }
    }

    private fun scheduleRescan(delayMs: Long) {
        handler.removeCallbacks(rescan)
        handler.postDelayed(rescan, delayMs)
    }

    /** Refreshes the player list now, e.g. when the panel opens. Safe from any thread. */
    fun requestRescan() = scheduleRescan(0)

    /**
     * Applies the saved volumes right away (slider drags, notification buttons) against the last
     * known players instead of re-reading `dumpsys audio`; new players arrive through the playback
     * callback. Bursts collapse into one pass. Safe from any thread.
     */
    fun applyVolumesNow() {
        handler.removeCallbacks(applyVolumes)
        handler.post(applyVolumes)
    }

    private fun applyVolumesFromConfig() {
        val cfg = Config.of(this)
        volumes.targets = cfg.volumes
        updatePlaybackCallback(cfg)
        val dump = lastDump
        if (dump != null) syncVolumes(dump) else scheduleRescan(0)
        publishApps()
    }

    private fun syncVolumes(dump: AudioDump) {
        volumes.sync(dump.players)
        handler.removeCallbacks(retryRescan)
        volumes.nextRetryAt()?.let { at ->
            handler.postDelayed(retryRescan, (at - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
    }

    private fun publishApps() {
        val blocked = volumes.blockedPackages()
        mixerApps = activeApps.list(Config.of(this).volumes.keys, SystemClock.elapsedRealtime(), labels::label)
            .map { if (it.pkg in blocked) it.copy(blocked = true) else it }
        handler.removeCallbacks(notificationUpdate)
        handler.postDelayed(notificationUpdate, NOTIFICATION_DEBOUNCE_MS)
    }

    private fun postNotification() {
        val volumesNow = Config.of(this).volumes
        val hasDump = AudioDump.hasPermission(this)
        val key = "$hasDump|$canReadPlayers|" + mixerApps.joinToString {
            "${it.pkg}:${it.state}:${it.blocked}:${MixerNotification.percent(volumesNow, it.pkg)}"
        }
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification.build(mixerApps, volumesNow, hasDump, canReadPlayers))
    }

    private fun rescanNow() {
        val dump = AudioDump.capture(this)
        canReadPlayers = dump != null
        lastDump = dump ?: lastDump
        if (dump == null) {
            if (!warnedNoDump) {
                warnedNoDump = true
                if (AudioDump.hasPermission(this)) {
                    log.add("volume", "can't read audio sessions (${AudioDump.lastFailure})")
                } else {
                    log.add("volume", "can't read audio sessions: grant DUMP once with: ${Adb.grantDump(this)}")
                }
            }
            publishApps()
            return
        }
        warnedNoDump = false
        syncVolumes(dump)
        activeApps.update(dump.players, SystemClock.elapsedRealtime())
        publishApps()
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
        dumpAvailable = canReadPlayers,
        multiFocusLive = lastDump?.multiAudioFocusEnabled,
        externalFocusPolicy = lastDump?.externalFocusPolicy,
        car = if (Config.of(this).aaGuard) car.description else "not watched",
        sessionsWatched = sessions.isRunning,
        sessions = sessions.summary().toList(),
        volumes = volumes.summary(),
        guardFixes = guard.totalFixes,
    )

    private fun startInForeground() {
        notification.createChannel()
        val first = notification.build(emptyList(), Config.of(this).volumes, AudioDump.hasPermission(this), readable = true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, first)
        }
    }

    companion object {
        const val MULTI_FOCUS_SETTING = "multi_audio_focus_enabled"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_DEBOUNCE_MS = 150L
        private const val RESCAN_DEBOUNCE_MS = 300L
        private const val PERIODIC_MS = 30_000L
        private const val RESUME_AFTER_CALL_MS = 1_500L

        val log = EventLog()

        @Volatile
        var instance: AudioControlService? = null
            private set

        /** While the App volume panel is open the service runs regardless of settings. */
        @Volatile
        var panelOpen = false

        /** Starts the service if some feature needs it, stops it otherwise. Safe to call anytime. */
        fun refresh(context: Context) {
            val intent = Intent(context, AudioControlService::class.java)
            try {
                if (Config.of(context).needsService(context) || panelOpen) {
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

        /** Call after changing a saved volume: applies it immediately. */
        fun volumesChanged(context: Context) {
            instance?.applyVolumesNow() ?: refresh(context)
        }
    }
}
