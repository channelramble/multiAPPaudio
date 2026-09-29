package io.github.channelramble.multiappaudio.daemon

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Tracks whether Android Auto is projecting.
 *
 * Primary signal: UiModeManager automotive projection state (Android Auto calls
 * `requestProjection(PROJECTION_TYPE_AUTOMOTIVE)`), observed through the @SystemApi
 * `OnProjectionStateChangedListener` (the shell holds READ_PROJECTION_STATE). The listener is an
 * interface, so a java.lang.reflect.Proxy implements it without compile-time stubs.
 * Backstop: a slow poll that also treats car mode as projection.
 */
class ProjectionWatcher(
    private val context: Context,
    private val handler: Handler,
    private val log: EventLog,
    private val onChange: (projecting: Boolean) -> Unit,
) {
    private val uiModeManager = context.getSystemService(UiModeManager::class.java)
    private var listenerProxy: Any? = null
    private var lastProjection = false
    private var lastCarMode = false
    private var simulated: Boolean? = null

    var source: String = "none"
        private set

    val isProjecting: Boolean
        get() = simulated ?: (lastProjection || lastCarMode)

    val isSimulated: Boolean get() = simulated != null

    private val poll = object : Runnable {
        override fun run() {
            log.guard("projection poll") { refresh("poll") }
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        registerListener()
        refresh("start")
        handler.postDelayed(poll, POLL_MS)
    }

    fun stop() {
        handler.removeCallbacks(poll)
        listenerProxy?.let { proxy ->
            runCatching { Hidden.call(uiModeManager, "removeOnProjectionStateChangedListener", proxy) }
        }
        listenerProxy = null
    }

    /** Lets the user try the Android Auto features at home. null = follow the real state. */
    fun simulate(value: Boolean?) {
        val before = isProjecting
        simulated = value
        log.add("aa", if (value == null) "simulation off" else "simulated projection = $value")
        if (before != isProjecting) onChange(isProjecting)
    }

    private fun registerListener() {
        try {
            val listenerClass =
                Class.forName("android.app.UiModeManager\$OnProjectionStateChangedListener")
            val proxy = Proxy.newProxyInstance(
                listenerClass.classLoader, arrayOf(listenerClass)
            ) { self, method, args ->
                when (method.name) {
                    "onProjectionStateChanged" -> {
                        val types = args?.getOrNull(0) as? Int ?: 0
                        val packages = (args?.getOrNull(1) as? Set<*>)?.joinToString() ?: ""
                        handler.post {
                            log.guard("projection") {
                                applyProjection(types and PROJECTION_TYPE_AUTOMOTIVE != 0, "listener [$packages]")
                            }
                        }
                        null
                    }
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.getOrNull(0)
                    "toString" -> "MultiAppAudioProjectionListener"
                    else -> null
                }
            }
            val executor = Executor { r -> handler.post(r) }
            Hidden.call(
                uiModeManager, "addOnProjectionStateChangedListener",
                PROJECTION_TYPE_AUTOMOTIVE, executor, proxy
            )
            listenerProxy = proxy
            log.add("aa", "projection listener registered")
        } catch (t: Throwable) {
            log.add("aa", "projection listener unavailable, polling only: $t")
        }
    }

    private fun refresh(reason: String) {
        val types = runCatching { Hidden.call(uiModeManager, "getActiveProjectionTypes") as Int }.getOrDefault(0)
        applyProjection(types and PROJECTION_TYPE_AUTOMOTIVE != 0, reason)
        val car = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_CAR
        if (car != lastCarMode) {
            val before = isProjecting
            lastCarMode = car
            log.add("aa", "car mode ${if (car) "entered" else "exited"} ($reason)")
            if (car) source = "car mode"
            if (before != isProjecting) onChange(isProjecting)
        }
    }

    private fun applyProjection(active: Boolean, reason: String) {
        if (active == lastProjection) return
        val before = isProjecting
        lastProjection = active
        if (active) source = "automotive projection"
        log.add("aa", "automotive projection ${if (active) "STARTED" else "ended"} ($reason)")
        if (before != isProjecting) onChange(isProjecting)
    }

    companion object {
        private const val PROJECTION_TYPE_AUTOMOTIVE = 0x0001
        private const val POLL_MS = 10_000L
    }
}
