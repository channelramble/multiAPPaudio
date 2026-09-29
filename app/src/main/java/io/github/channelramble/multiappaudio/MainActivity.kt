package io.github.channelramble.multiappaudio

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val config by lazy { Config.of(this) }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var status: Bundle? = null
    private var appOpsModes: Map<String, String> = emptyMap()
    private var fixMessage: String? = null

    private lateinit var shizukuText: TextView
    private lateinit var shizukuButton: Button
    private lateinit var helperText: TextView
    private lateinit var helperSwitch: Switch
    private lateinit var multiSwitch: Switch
    private lateinit var multiText: TextView
    private lateinit var restoreSwitch: Switch
    private lateinit var fixText: TextView
    private lateinit var aaText: TextView
    private lateinit var isolationSwitch: Switch
    private lateinit var passThroughText: TextView
    private lateinit var alwaysSwitch: Switch
    private lateinit var callSwitch: Switch
    private lateinit var guardSwitch: Switch
    private lateinit var appOpsText: TextView
    private lateinit var diagText: TextView
    private lateinit var simulateSwitch: Switch
    private lateinit var logText: TextView

    /** Guards against switch listeners firing while we render state into them. */
    private var rendering = false

    private val helperListener: () -> Unit = { refresh() }
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Helper.init(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        Helper.addListener(helperListener)
        Helper.ensureRunning()
        main.post(poll)
    }

    override fun onPause() {
        super.onPause()
        Helper.removeListener(helperListener)
        main.removeCallbacks(poll)
    }

    // ------------------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(column)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        column.addView(text("Multi-App Audio", 26f, bold = true).apply { setPadding(0, dp(12), 0, dp(2)) })
        column.addView(
            text(
                "Samsung-style \"play audio from several apps at once\" for stock Android, " +
                    "plus Android Auto fixes. Uses Shizuku (no root).", 14f, secondary = true
            )
        )

        // ---- setup
        column.addView(card("Setup") {
            shizukuText = text("", 15f)
            addView(shizukuText)
            shizukuButton = button("") { onShizukuButton() }
            addView(shizukuButton)
            helperText = text("", 14f, secondary = true)
            addView(helperText)
            helperSwitch = switch("Run background helper", config.helperEnabled) { on ->
                config.helperEnabled = on
                if (on) Helper.ensureRunning() else Helper.stop()
            }
            addView(helperSwitch)
            addView(
                text(
                    "The helper is only needed for the repair, Android Auto and diagnostics features. " +
                        "It starts automatically whenever Shizuku starts (including Shizuku's own " +
                        "start-on-boot) and keeps running after you close this app.", 13f, secondary = true
                )
            )
        })

        // ---- multi-app audio
        column.addView(card("Multi-app audio") {
            multiSwitch = switch("Let media apps play at the same time", false) { on -> setMultiFocus(on) }
            addView(multiSwitch)
            multiText = text("", 13f, secondary = true)
            addView(multiText)
            addView(
                text(
                    "This turns on Android's built-in multi audio focus mode " +
                        "(Settings.System multi_audio_focus_enabled). Android stores it and re-applies it " +
                        "at every boot, so it keeps working without Shizuku or this app running. Calls, " +
                        "alarms and navigation prompts still interrupt as usual. Enabling it stops whatever " +
                        "is playing once.", 13f, secondary = true
                )
            )
            restoreSwitch = switch("Repair focus after interruptions", config.restoreHelper) { on ->
                config.restoreHelper = on
                Helper.pushConfig()
            }
            addView(restoreSwitch)
            addView(
                text(
                    "Android 16/17 bug in this mode: after a navigation prompt, assistant or call, apps " +
                        "never get audio focus back, so music stays ducked (quiet) or paused. While the " +
                        "helper runs, it clears the stuck duck and resumes what was playing.", 13f, secondary = true
                )
            )
            fixText = text("", 13f, secondary = true)
            addView(fixText)
            addView(button("Try Google's built-in fix flag (experimental)") { setFixFlag() })
        })

        // ---- android auto
        column.addView(card("Android Auto") {
            aaText = text("", 14f)
            addView(aaText)
            isolationSwitch = switch("Pass-through apps ignore Android Auto's focus rules", config.aaIsolation) { on ->
                config.aaIsolation = on
                Helper.pushConfig()
            }
            addView(isolationSwitch)
            addView(
                text(
                    "While projecting, Android Auto takes over audio focus decisions, which bypasses " +
                        "multi-app audio and lets it pause an app it doesn't consider a media source " +
                        "(like YouTube) and start its own (YouTube Music). Pass-through apps are isolated " +
                        "from audio focus (Android 17 API), so Android Auto never sees them take focus and " +
                        "they play alongside everything else.", 13f, secondary = true
                )
            )
            passThroughText = text("", 14f)
            addView(passThroughText)
            addView(button("Choose pass-through apps") {
                pickApps("Pass-through apps", config.passThroughPackages) { chosen ->
                    config.passThroughPackages = chosen
                    Helper.pushConfig()
                    refresh()
                }
            })
            alwaysSwitch = switch("Also isolate them outside Android Auto", config.isolationAlways) { on ->
                config.isolationAlways = on
                Helper.pushConfig()
            }
            addView(alwaysSwitch)
            callSwitch = switch("Pause pass-through apps during calls", config.pauseOnCall) { on ->
                config.pauseOnCall = on
                Helper.pushConfig()
            }
            addView(callSwitch)
            guardSwitch = switch("Undo automatic source switches", config.aaGuard) { on ->
                config.aaGuard = on
                Helper.pushConfig()
            }
            addView(guardSwitch)
            addView(
                text(
                    "If a pass-through app is replaced by another app within ${config.guardWindowSeconds}s " +
                        "of starting (while projecting), the other app is paused and the pass-through app " +
                        "resumed. At most twice a minute, so it can never fight Android Auto in a loop.\n\n" +
                        "Also recommended: in Android Auto's settings, turn off the option that starts " +
                        "media automatically, and in YouTube Music turn off letting external devices start " +
                        "playback.", 13f, secondary = true
                )
            )
        })

        // ---- appops
        column.addView(card("Never take audio focus (AppOps)") {
            addView(
                text(
                    "Fallback for Android 16, or for when the helper isn't running: selected apps are " +
                        "denied audio focus (appops TAKE_AUDIO_FOCUS=ignore). They then never pause other " +
                        "apps and are never paused by them, even under Android Auto. Stored by Android, " +
                        "survives reboots. Caveats: on Android 16 it only affects apps targeting Android 15+, " +
                        "some players refuse to start without focus, and these apps won't duck or " +
                        "pause for calls.",
                    13f, secondary = true
                )
            )
            appOpsText = text("", 14f)
            addView(appOpsText)
            addView(button("Choose apps") {
                pickApps("Never take audio focus", config.appOpsPackages) { chosen -> applyAppOps(chosen) }
            })
        })

        // ---- diagnostics
        column.addView(card("Diagnostics") {
            diagText = text("", 13f, mono = true)
            addView(diagText)
            simulateSwitch = switch("Simulate an Android Auto session", false) { on -> simulate(on) }
            addView(simulateSwitch)
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Copy report") { copyReport() })
            row.addView(button("Clear log") { clearLog() })
            addView(row)
            logText = text("", 11f, mono = true).apply { setTextIsSelectable(true) }
            addView(logText)
        })

        // ---- adb
        column.addView(card("No app? ADB only") {
            addView(
                text(
                    "Multi-app audio (then reboot):\n" +
                        "adb shell settings put system multi_audio_focus_enabled 1\n\n" +
                        "Undo (then reboot):\n" +
                        "adb shell settings put system multi_audio_focus_enabled 0\n\n" +
                        "Let one app never take audio focus:\n" +
                        "adb shell cmd appops set <package> TAKE_AUDIO_FOCUS ignore\n" +
                        "Undo:\n" +
                        "adb shell cmd appops set <package> TAKE_AUDIO_FOCUS default", 12f, mono = true
                ).apply { setTextIsSelectable(true) }
            )
            addView(text("Source and write-up: github.com/channelramble/multiAPPaudio", 13f, secondary = true).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/channelramble/multiAPPaudio")))
                }
            })
        })
        return scroll
    }

    // ------------------------------------------------------------------------------ state

    private fun refresh() {
        renderLocal()
        val client = Helper.client
        if (client == null) {
            status = null
            renderRemote(null, null)
            return
        }
        io.execute {
            val s = runCatching { client.status() }.getOrNull()
            val log = runCatching { client.log() }.getOrNull()
            val ops = config.appOpsPackages
            val modes = if (s != null && ops.isNotEmpty()) {
                runCatching {
                    val r = client.action(Protocol.A_APPOPS_GET, Bundle().apply {
                        putStringArray(Protocol.K_PACKAGES, ops.toTypedArray())
                    })
                    val p = r.getStringArray(Protocol.K_PACKAGES).orEmpty()
                    val m = r.getStringArray(Protocol.K_MODES).orEmpty()
                    p.zip(m).toMap()
                }.getOrDefault(emptyMap())
            } else emptyMap()
            main.post {
                status = s
                appOpsModes = modes
                renderRemote(s, log)
            }
        }
    }

    private fun renderLocal() {
        val state = Helper.shizukuState()
        shizukuText.text = when (state) {
            Helper.ShizukuState.NOT_INSTALLED -> "Shizuku is not installed."
            Helper.ShizukuState.NOT_RUNNING -> "Shizuku is installed but not running. Start it in the Shizuku app."
            Helper.ShizukuState.OUTDATED -> "Shizuku is too old. Please update it."
            Helper.ShizukuState.NEEDS_PERMISSION -> "Shizuku is running. Allow this app to use it."
            Helper.ShizukuState.READY -> "Shizuku ready (uid ${Helper.shizukuUid()}${if (Helper.shizukuUid() == 0) ", root" else ", adb"})."
        }
        shizukuButton.text = when (state) {
            Helper.ShizukuState.NOT_INSTALLED -> "Get Shizuku"
            Helper.ShizukuState.NEEDS_PERMISSION -> "Allow access"
            else -> "Open Shizuku"
        }
        shizukuButton.visibility = if (state == Helper.ShizukuState.READY) View.GONE else View.VISIBLE
    }

    private fun renderRemote(s: Bundle?, log: String?) {
        rendering = true
        try {
            val connected = s != null
            helperSwitch.isChecked = config.helperEnabled
            helperText.text = when {
                connected -> "Helper running as uid ${s!!.getInt(Protocol.S_UID)} (Android API ${s.getInt(Protocol.S_SDK)})."
                !config.helperEnabled -> "Helper is off."
                else -> "Helper not running (needs Shizuku)."
            }

            // Multi focus: the live state from the daemon wins; otherwise the stored setting.
            val stored = storedMultiFocus()
            val live = s?.getString(Protocol.S_MULTI_FOCUS)
            multiSwitch.isChecked = if (live == "true" || live == "false") live == "true" else stored == 1
            multiText.text = buildString {
                append("System setting: ${if (stored == 1) "on" else "off"}")
                if (live != null) append(" · live: $live")
                if (live != null && live != "unknown" && (live == "true") != (stored == 1)) {
                    append(" · reboot to apply the setting")
                }
            }

            restoreSwitch.isChecked = config.restoreHelper
            fixText.text = buildString {
                append("Google's fix flag (audio_focus_desktop): ${s?.getString(Protocol.S_FIX_FLAG) ?: "unknown"}")
                s?.getString(Protocol.S_FIX_FLAG_STAGED)?.let { append(" · staged: $it") }
                if (s != null) append(" · repairs done: ${s.getInt(Protocol.S_RESTORES)}")
                fixMessage?.let { append("\n$it") }
            }

            val isolationSupported = s?.getBoolean(Protocol.S_ISOLATION_SUPPORTED) ?: (Build.VERSION.SDK_INT >= 37)
            aaText.text = if (s == null) {
                "Status unavailable (helper not running)."
            } else buildString {
                val projecting = s.getBoolean(Protocol.S_PROJECTING)
                append("Android Auto: ${if (projecting) "active (${s.getString(Protocol.S_PROJECTION_SOURCE)})" else "not active"}")
                if (s.getBoolean(Protocol.S_SIMULATED)) append(" [simulated]")
                append("\nExternal focus policy installed: ${s.getString(Protocol.S_EXT_FOCUS_POLICY)}")
                val isolated = s.getStringArray(Protocol.S_ISOLATED).orEmpty()
                append("\nIsolated now: ${if (isolated.isEmpty()) "none" else isolated.joinToString { label(it) }}")
                if (!isolationSupported) append("\nFocus isolation needs Android 17; use the AppOps fallback below.")
            }
            isolationSwitch.isChecked = config.aaIsolation
            isolationSwitch.isEnabled = isolationSupported
            passThroughText.text = "Pass-through apps: " +
                config.passThroughPackages.joinToString { label(it) }.ifEmpty { "none" }
            alwaysSwitch.isChecked = config.isolationAlways
            callSwitch.isChecked = config.pauseOnCall
            guardSwitch.isChecked = config.aaGuard

            appOpsText.text = if (config.appOpsPackages.isEmpty()) {
                "No apps selected."
            } else {
                config.appOpsPackages.joinToString("\n") { "${label(it)}: ${appOpsModes[it] ?: "?"}" }
            }

            simulateSwitch.isEnabled = connected
            simulateSwitch.isChecked = s?.getBoolean(Protocol.S_SIMULATED) == true
            diagText.text = if (s == null) "" else buildString {
                append("audio mode: ${s.getString(Protocol.S_AUDIO_MODE)}\n")
                append("focus monitor: ${s.getBoolean(Protocol.S_FOCUS_MONITOR)}\n")
                append("guard fixes: ${s.getInt(Protocol.S_GUARD_FIXES)}\n")
                append("sessions:\n")
                s.getStringArray(Protocol.S_SESSIONS).orEmpty().forEach { append("  $it\n") }
            }.trimEnd()
            logText.text = log?.lines()?.takeLast(LOG_LINES)?.joinToString("\n").orEmpty()
        } finally {
            rendering = false
        }
    }

    private fun storedMultiFocus(): Int = runCatching {
        Settings.System.getInt(contentResolver, "multi_audio_focus_enabled", 0)
    }.getOrDefault(-1)

    // ------------------------------------------------------------------------------ actions

    private fun onShizukuButton() {
        when (Helper.shizukuState()) {
            Helper.ShizukuState.NEEDS_PERMISSION -> Helper.requestPermission()
            Helper.ShizukuState.NOT_INSTALLED -> open("https://shizuku.rikka.app/download/")
            else -> {
                val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (launch != null) startActivity(launch) else open("https://shizuku.rikka.app/guide/setup/")
            }
        }
    }

    private fun setMultiFocus(on: Boolean) {
        if (rendering) return
        val client = Helper.client
        if (client == null) {
            toast("Needs the helper (Shizuku). Without it, use the ADB command below and reboot.")
            refresh()
            return
        }
        io.execute {
            val r = runCatching {
                client.action(Protocol.A_SET_MULTI_FOCUS, Bundle().apply { putBoolean(Protocol.K_ENABLED, on) })
            }
            main.post {
                toast(r.getOrNull()?.getString(Protocol.K_MESSAGE) ?: "Failed: ${r.exceptionOrNull()}")
                refresh()
            }
        }
    }

    private fun setFixFlag() {
        val client = Helper.client ?: return toast("Needs the helper (Shizuku).")
        AlertDialog.Builder(this)
            .setTitle("Google's built-in fix")
            .setMessage(
                "Android 16 QPR2+ contains Google's own fix for the focus-restore bug, gated by the " +
                    "flag android.media.audio.audio_focus_desktop (meant for desktop devices). This tries " +
                    "to enable it through DeviceConfig. Newer builds may refuse this without root. " +
                    "If it is accepted, reboot and check the status line. You can undo it here."
            )
            .setPositiveButton("Enable") { _, _ -> runFixFlag(client, true) }
            .setNeutralButton("Undo") { _, _ -> runFixFlag(client, false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runFixFlag(client: DaemonClient, enable: Boolean) {
        io.execute {
            val r = runCatching {
                client.action(Protocol.A_SET_FIX_FLAG, Bundle().apply { putBoolean(Protocol.K_ENABLED, enable) })
            }
            main.post {
                fixMessage = r.getOrNull()?.getString(Protocol.K_MESSAGE) ?: "Failed: ${r.exceptionOrNull()}"
                refresh()
            }
        }
    }

    private fun applyAppOps(chosen: Set<String>) {
        val client = Helper.client ?: return toast("Needs the helper (Shizuku), or use the ADB commands.")
        val before = config.appOpsPackages
        io.execute {
            val messages = mutableListOf<String>()
            for (pkg in chosen - before) messages += appOp(client, pkg, "ignore")
            for (pkg in before - chosen) messages += appOp(client, pkg, "default")
            main.post {
                config.appOpsPackages = chosen
                if (messages.isNotEmpty()) toast(messages.joinToString("\n"))
                refresh()
            }
        }
    }

    private fun appOp(client: DaemonClient, pkg: String, mode: String): String = runCatching {
        client.action(Protocol.A_APPOPS_SET, Bundle().apply {
            putString(Protocol.K_PACKAGE, pkg)
            putString(Protocol.K_MODE, mode)
        }).getString(Protocol.K_MESSAGE).orEmpty()
    }.getOrElse { "$pkg: $it" }

    private fun simulate(on: Boolean) {
        if (rendering) return
        val client = Helper.client ?: return
        io.execute {
            runCatching {
                client.action(Protocol.A_SIMULATE_PROJECTION, Bundle().apply {
                    if (on) putBoolean(Protocol.K_ENABLED, true)
                })
            }
            main.post { refresh() }
        }
    }

    private fun clearLog() {
        val client = Helper.client ?: return
        io.execute {
            runCatching { client.action(Protocol.A_CLEAR_LOG) }
            main.post { refresh() }
        }
    }

    private fun copyReport() {
        val client = Helper.client
        io.execute {
            val snapshot = client?.let {
                runCatching { it.action(Protocol.A_SNAPSHOT).getString(Protocol.K_TEXT) }.getOrNull()
            }
            val log = client?.let { runCatching { it.log() }.getOrNull() }
            val s = status
            val report = buildString {
                appendLine("MultiAppAudio report")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}")
                appendLine("shizuku: ${Helper.shizukuState()} uid=${Helper.shizukuUid()}")
                appendLine("setting multi_audio_focus_enabled=${storedMultiFocus()}")
                appendLine("config: ${config.toBundle()}")
                if (s != null) {
                    appendLine("status:")
                    for (key in s.keySet().sorted()) {
                        @Suppress("DEPRECATION")
                        val v = s.get(key)
                        appendLine("  $key = ${if (v is Array<*>) v.joinToString() else v}")
                    }
                }
                appendLine()
                appendLine("---- focus snapshot (dumpsys audio) ----")
                appendLine(snapshot ?: "(helper not running)")
                appendLine()
                appendLine("---- event log ----")
                appendLine(log ?: "(helper not running)")
            }
            main.post {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("MultiAppAudio report", report))
                toast("Report copied to the clipboard.")
            }
        }
    }

    private fun pickApps(title: String, current: Set<String>, onDone: (Set<String>) -> Unit) {
        io.execute {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != packageName }
                .distinctBy { it.first }
                .toMutableList()
            for (pkg in current) if (apps.none { it.first == pkg }) apps += pkg to pkg
            apps.sortWith(compareByDescending<Pair<String, String>> { it.first in current }.thenBy { it.second.lowercase() })
            main.post {
                val checked = BooleanArray(apps.size) { apps[it].first in current }
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMultiChoiceItems(
                        apps.map { "${it.second}\n${it.first}" }.toTypedArray(), checked
                    ) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton("OK") { _, _ ->
                        onDone(apps.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet())
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------------------ helpers

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun open(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
    }

    private fun text(s: String, size: Float, bold: Boolean = false, secondary: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (mono) typeface = Typeface.MONOSPACE
            if (secondary) setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isChecked = checked
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, on -> if (!rendering) onChange(on) }
    }

    private fun card(title: String, content: LinearLayout.() -> Unit): View {
        val fg = themeColor(android.R.attr.colorForeground)
        val bg = themeColor(android.R.attr.colorBackground)
        val tint = blend(bg, fg, 0.06f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(tint)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            addView(text(title, 18f, bold = true).apply { gravity = Gravity.START })
            content()
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    companion object {
        private const val POLL_MS = 2_500L
        private const val LOG_LINES = 80
    }
}
