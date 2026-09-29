package io.github.channelramble.multiappaudio.daemon

import android.content.Context
import android.media.AudioManager
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import io.github.channelramble.multiappaudio.Protocol
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The helper daemon. Shizuku starts it in its own `app_process` running as the shell uid (or root
 * with Sui), passing us an Application context for this package. It keeps running after the app
 * is closed ("daemon" user service) and exits when Shizuku stops.
 *
 * All state lives on the main looper; binder calls hop onto it.
 */
@Suppress("unused") // instantiated reflectively by Shizuku's ServiceStarter
class HelperDaemon(appContext: Context) : Binder() {

    private val handler = Handler(Looper.getMainLooper())
    private val log = EventLog()
    private val ownerUid = appContext.applicationInfo.uid

    /** A context whose package matches our uid (com.android.shell), for system service calls. */
    private val ctx: Context = runCatching {
        appContext.createPackageContext(AudioServiceBridge.SHELL_PACKAGE, 0)
    }.getOrDefault(appContext)

    private val audio = AudioServiceBridge()
    private val audioManager = ctx.getSystemService(AudioManager::class.java)
    private val upstreamFix: Boolean? = UpstreamFixFlag.isActive()

    private val sessionListener = object : SessionWatcher.Listener {
        override fun onStateChanged(pkg: String, old: Int, new: Int) = onSessionStateChanged(pkg, old, new)
    }
    private val sessions = SessionWatcher(ctx, handler, log, sessionListener)
    private val passThrough = PassThrough(audio, ctx.packageManager, log)
    private val guard = AutoSwitchGuard(sessions, handler, log)
    private val restore = RestoreHelper(
        audio, sessions, passThrough, handler, log,
        audioMode = { audioManager.mode },
        upstreamFixActive = upstreamFix == true,
    )
    private val focusMonitor = FocusMonitor(ctx, handler, restore)
    private val playback = PlaybackWatcher(ctx, handler, log)
    private val projection = ProjectionWatcher(ctx, handler, log) {
        log.guard("projection change") { onProjectionChanged() }
    }

    // ---- config pushed by the app
    private var cfgRestore = true
    private var cfgPassThrough: Set<String> = emptySet()
    private var cfgAaIsolation = true
    private var cfgIsolationAlways = false
    private var cfgGuard = true
    private var cfgGuardWindowS = 15
    private var cfgPauseOnCall = true

    private val pausedForCall = LinkedHashSet<String>()
    private var lastMode = AudioManager.MODE_NORMAL

    @Volatile private var cachedDump: AudioDump? = null
    @Volatile private var cachedDumpAt = 0L

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        log.guard("audio mode") { onAudioModeChanged(mode) }
    }

    private val tick = object : Runnable {
        override fun run() {
            log.guard("tick") { restore.tick() }
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            log.add("daemon", "crashed: ${e.stackTraceToString()}")
            exitProcess(1)
        }
        log.add(
            "daemon",
            "started: uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE}) " +
                "build=${Build.DISPLAY} upstreamFix=${upstreamFix ?: "unknown"} " +
                "isolation=${audio.isolationSupported}"
        )
        handler.post { log.guard("startup") { startComponents() } }
    }

    private fun startComponents() {
        sessions.start()
        focusMonitor.start(log)
        playback.start()
        runCatching { audioManager.addOnModeChangedListener({ r -> handler.post(r) }, modeListener) }
            .onFailure { log.add("daemon", "audio mode listener unavailable: $it") }
        lastMode = audioManager.mode
        projection.start()
        handler.postDelayed(tick, TICK_MS)
        reconcile()
    }

    private fun shutdown() {
        log.add("daemon", "shutting down")
        handler.removeCallbacks(tick)
        passThrough.exitAll()
        projection.stop()
        runCatching { audioManager.removeOnModeChangedListener(modeListener) }
        playback.stop()
        focusMonitor.stop()
        sessions.stop()
    }

    // ------------------------------------------------------------------ behaviour

    private fun reconcile() {
        restore.enabled = cfgRestore
        guard.enabled = cfgGuard
        guard.windowMs = cfgGuardWindowS * 1000L
        guard.protectedPackages = cfgPassThrough
        guard.active = projection.isProjecting
        val isolate = cfgAaIsolation && (projection.isProjecting || cfgIsolationAlways)
        passThrough.apply(if (isolate) cfgPassThrough else emptySet())
    }

    private fun onProjectionChanged() {
        log.add("aa", "Android Auto ${if (projection.isProjecting) "active" else "inactive"} (${projection.source})")
        reconcile()
    }

    private fun onSessionStateChanged(pkg: String, old: Int, new: Int) {
        guard.onStateChanged(pkg, old, new)
    }

    /**
     * Isolated apps never receive focus losses, so they would keep playing through a phone call.
     * Pause them while the phone rings or is in a call and resume them afterwards.
     */
    private fun onAudioModeChanged(mode: Int) {
        if (mode == lastMode) return
        log.add("mode", "audio mode ${modeName(lastMode)} -> ${modeName(mode)}")
        lastMode = mode
        restore.onAudioModeChanged(mode)
        if (mode != AudioManager.MODE_NORMAL) {
            if (!cfgPauseOnCall) return
            for (pkg in passThrough.isolatedPackages) {
                if (sessions.isPlaying(pkg) && sessions.pause(pkg, "call started")) pausedForCall += pkg
            }
        } else if (pausedForCall.isNotEmpty()) {
            val toResume = pausedForCall.toList()
            pausedForCall.clear()
            handler.postDelayed({
                log.guard("resume after call") {
                    toResume.filter { sessions.stateOf(it) == PlaybackState.STATE_PAUSED }
                        .forEach { sessions.play(it, "call ended") }
                }
            }, 1_000)
        }
    }

    // ------------------------------------------------------------------ binder API

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == Protocol.TX_SHIZUKU_DESTROY) {
            handler.post {
                shutdown()
                exitProcess(0)
            }
            return true
        }
        if (code < Protocol.TX_STATUS || code > Protocol.TX_LOG) {
            return super.onTransact(code, data, reply, flags)
        }
        val caller = getCallingUid()
        if (caller != ownerUid && caller != SHELL_UID && caller != ROOT_UID) {
            throw SecurityException("uid $caller may not talk to the MultiAppAudio helper")
        }
        data.enforceInterface(Protocol.DESCRIPTOR)
        val arg = data.readBundle(javaClass.classLoader) ?: Bundle()
        val result: Bundle = try {
            when (code) {
                Protocol.TX_STATUS -> {
                    val dump = dump(maxAgeMs = 5_000)
                    onMain { status(dump) }
                }
                Protocol.TX_APPLY_CONFIG -> onMain { applyConfig(arg); Bundle() }
                Protocol.TX_ACTION -> action(arg)
                else -> Bundle().apply { putString(Protocol.K_TEXT, log.dump()) }
            }
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            // Binder only marshals RuntimeExceptions back to the caller; anything checked
            // (timeouts, ExecutionException) would otherwise take the whole daemon down.
            throw IllegalStateException(e.toString(), e)
        }
        reply?.writeNoException()
        reply?.writeBundle(result)
        return true
    }

    private fun applyConfig(b: Bundle) {
        cfgRestore = b.getBoolean(Protocol.C_RESTORE_HELPER, cfgRestore)
        cfgPassThrough = b.getStringArray(Protocol.C_PASS_THROUGH_PKGS)?.filter(::isPackageName)?.toSet()
            ?: cfgPassThrough
        cfgAaIsolation = b.getBoolean(Protocol.C_AA_ISOLATION, cfgAaIsolation)
        cfgIsolationAlways = b.getBoolean(Protocol.C_ISOLATION_ALWAYS, cfgIsolationAlways)
        cfgGuard = b.getBoolean(Protocol.C_AA_GUARD, cfgGuard)
        cfgGuardWindowS = b.getInt(Protocol.C_GUARD_WINDOW_S, cfgGuardWindowS).coerceIn(3, 120)
        cfgPauseOnCall = b.getBoolean(Protocol.C_PAUSE_ON_CALL, cfgPauseOnCall)
        log.add(
            "config",
            "restore=$cfgRestore passThrough=$cfgPassThrough aaIsolation=$cfgAaIsolation " +
                "always=$cfgIsolationAlways guard=$cfgGuard/${cfgGuardWindowS}s pauseOnCall=$cfgPauseOnCall"
        )
        reconcile()
    }

    private fun status(dump: AudioDump?): Bundle = Bundle().apply {
        putInt(Protocol.S_DAEMON_VERSION, Protocol.DAEMON_VERSION)
        putInt(Protocol.S_UID, Process.myUid())
        putInt(Protocol.S_SDK, Build.VERSION.SDK_INT)
        putString(Protocol.S_MULTI_FOCUS, triState(runCatching { audio.isMultiAudioFocusActive(dump) }.getOrNull()))
        putString(Protocol.S_EXT_FOCUS_POLICY, triState(dump?.externalFocusPolicy))
        putString(Protocol.S_FIX_FLAG, triState(upstreamFix))
        putString(Protocol.S_FIX_FLAG_STAGED, UpstreamFixFlag.staged())
        putBoolean(Protocol.S_ISOLATION_SUPPORTED, audio.isolationSupported)
        putBoolean(Protocol.S_FOCUS_MONITOR, focusMonitor.isRunning)
        putBoolean(Protocol.S_PROJECTING, projection.isProjecting)
        putString(Protocol.S_PROJECTION_SOURCE, projection.source)
        putBoolean(Protocol.S_SIMULATED, projection.isSimulated)
        putStringArray(Protocol.S_ISOLATED, passThrough.isolatedPackages.toTypedArray())
        putStringArray(Protocol.S_SESSIONS, sessions.summary())
        putString(Protocol.S_AUDIO_MODE, modeName(lastMode))
        putInt(Protocol.S_RESTORES, restore.restores)
        putInt(Protocol.S_GUARD_FIXES, guard.totalFixes)
    }

    private fun action(arg: Bundle): Bundle {
        val out = Bundle()
        fun done(ok: Boolean, message: String): Bundle {
            out.putBoolean(Protocol.K_OK, ok)
            out.putString(Protocol.K_MESSAGE, message)
            log.add("action", message.lines().first())
            return out
        }
        return when (val name = arg.getString("__action")) {
            Protocol.A_SET_MULTI_FOCUS -> {
                val enabled = arg.getBoolean(Protocol.K_ENABLED)
                runCatching { audio.setMultiAudioFocus(enabled) }
                    .fold(
                        onSuccess = {
                            invalidateDump()
                            val now = audio.isMultiAudioFocusActive(dump(0))
                            done(now == enabled, "multi audio focus set to $enabled (system reports ${now ?: "unknown"})")
                        },
                        onFailure = { done(false, "setMultiAudioFocusEnabled failed: $it") },
                    )
            }
            Protocol.A_SET_FIX_FLAG -> done(true, UpstreamFixFlag.set(arg.getBoolean(Protocol.K_ENABLED)))
            Protocol.A_APPOPS_SET -> {
                val pkg = arg.getString(Protocol.K_PACKAGE).orEmpty()
                val mode = arg.getString(Protocol.K_MODE).orEmpty()
                if (!isPackageName(pkg) || mode !in setOf("ignore", "allow", "default")) {
                    done(false, "invalid appops request")
                } else {
                    val r = Shell.run("cmd", "appops", "set", pkg, "TAKE_AUDIO_FOCUS", mode)
                    done(r.ok, "appops $pkg TAKE_AUDIO_FOCUS=$mode: ${if (r.ok) "done" else r.text()}")
                }
            }
            Protocol.A_APPOPS_GET -> {
                val pkgs = arg.getStringArray(Protocol.K_PACKAGES).orEmpty().filter(::isPackageName)
                val modes = pkgs.map { pkg ->
                    val r = Shell.run("cmd", "appops", "get", pkg, "TAKE_AUDIO_FOCUS")
                    Regex("TAKE_AUDIO_FOCUS: (\\w+)").find(r.out)?.groupValues?.get(1) ?: "default"
                }
                out.putStringArray(Protocol.K_PACKAGES, pkgs.toTypedArray())
                out.putStringArray(Protocol.K_MODES, modes.toTypedArray())
                out.putBoolean(Protocol.K_OK, true)
                out
            }
            Protocol.A_SNAPSHOT -> {
                invalidateDump()
                out.putString(Protocol.K_TEXT, dump(0)?.focusSection() ?: "dumpsys audio unavailable")
                out.putBoolean(Protocol.K_OK, true)
                out
            }
            Protocol.A_SIMULATE_PROJECTION -> {
                val value = if (arg.containsKey(Protocol.K_ENABLED)) arg.getBoolean(Protocol.K_ENABLED) else null
                onMain { projection.simulate(value) }
                done(true, "Android Auto simulation: ${value ?: "off"}")
            }
            Protocol.A_CLEAR_LOG -> {
                log.clear()
                done(true, "log cleared")
            }
            else -> done(false, "unknown action $name")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun dump(maxAgeMs: Long): AudioDump? {
        val now = SystemClock.uptimeMillis()
        cachedDump?.let { if (now - cachedDumpAt <= maxAgeMs) return it }
        return runCatching { AudioDump.capture() }.getOrNull()?.also {
            cachedDump = it
            cachedDumpAt = now
        }
    }

    private fun invalidateDump() {
        cachedDump = null
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == handler.looper) return block()
        val task = FutureTask(block)
        handler.post(task)
        return task.get(20, TimeUnit.SECONDS)
    }

    companion object {
        private const val TICK_MS = 20_000L
        private const val ROOT_UID = 0
        private const val SHELL_UID = 2000
        private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        fun isPackageName(s: String) = PACKAGE_RE.matches(s)

        private fun triState(b: Boolean?) = b?.toString() ?: "unknown"

        fun modeName(mode: Int) = when (mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
            else -> "MODE_$mode"
        }
    }
}
