package io.github.channelramble.multiappaudio

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.channelramble.multiappaudio.daemon.HelperDaemon
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * App-side glue: tracks Shizuku, starts the helper daemon as a Shizuku "user service" and keeps a
 * [DaemonClient] to it.
 *
 * Auto-start: when the Shizuku server starts (at boot with root/Sui, or when the user or Shizuku's
 * own start-on-boot brings it up over wireless debugging) it delivers its binder to every app that
 * holds its permission, launching our process if needed. The sticky binder listener below then
 * starts the daemon, which keeps running on its own (`daemon(true)`) after our process goes away.
 */
object Helper {
    enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, OUTDATED, NEEDS_PERMISSION, READY }

    private const val TAG = "MultiAppAudio"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 42

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private lateinit var app: Context
    private var initialized = false
    private var binding = false

    @Volatile
    var client: DaemonClient? = null
        private set

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky({ ensureRunning() }, main)
        Shizuku.addBinderDeadListener({
            client = null
            binding = false
            notifyChanged()
        }, main)
        Shizuku.addRequestPermissionResultListener({ _, _ ->
            ensureRunning()
            notifyChanged()
        }, main)
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    private fun notifyChanged() = main.post { listeners.forEach { it() } }

    fun shizukuState(): ShizukuState {
        if (!Shizuku.pingBinder()) {
            val installed = runCatching {
                app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true
            }.getOrDefault(false)
            return if (installed) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
        }
        if (Shizuku.isPreV11()) return ShizukuState.OUTDATED
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuState.READY
        } else {
            ShizukuState.NEEDS_PERMISSION
        }
    }

    fun shizukuUid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    fun requestPermission() {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
    }

    /** Starts (or re-attaches to) the daemon if the user wants it and Shizuku allows it. */
    fun ensureRunning() {
        if (!initialized || !Config.of(app).helperEnabled) return
        if (shizukuState() != ShizukuState.READY) return
        if (client?.isAlive == true || binding) return
        binding = true
        try {
            Shizuku.bindUserService(args(), connection)
        } catch (t: Throwable) {
            binding = false
            Log.w(TAG, "bindUserService failed", t)
        }
    }

    fun stop() {
        if (Shizuku.pingBinder()) {
            runCatching { Shizuku.unbindUserService(args(), connection, true) }
        }
        client = null
        binding = false
        notifyChanged()
    }

    /** Sends the current settings to the daemon (off the main thread). */
    fun pushConfig() {
        val c = client ?: return
        val bundle = Config.of(app).toBundle()
        io.execute { runCatching { c.applyConfig(bundle) }.onFailure { Log.w(TAG, "applyConfig", it) } }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            if (service == null || !service.pingBinder()) return
            client = DaemonClient(service)
            pushConfig()
            notifyChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            client = null
            binding = false
            notifyChanged()
        }
    }

    private fun args(): Shizuku.UserServiceArgs {
        val version = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode.toInt()
        }.getOrDefault(1) * 100 + Protocol.DAEMON_VERSION
        return Shizuku.UserServiceArgs(ComponentName(app.packageName, HelperDaemon::class.java.name))
            .daemon(true)
            .processNameSuffix("helper")
            .tag("helper")
            .debuggable(false)
            .version(version)
    }
}
