package io.github.channelramble.multiappaudio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.channelramble.multiappaudio.core.AppVolumes
import io.github.channelramble.multiappaudio.core.AudioDump
import java.util.concurrent.Executors

/**
 * ADB-only front end. Anything that needs shell privileges is shown as a one-time ADB command
 * (with its current state); per-app volume and the Android Auto / call helpers run in
 * [AudioControlService] with permissions granted once over ADB.
 */
class MainActivity : Activity() {

    private val config by lazy { Config.of(this) }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var dump: AudioDump? = null
    private var rendering = false

    private lateinit var setupText: TextView
    private lateinit var multiText: TextView
    private lateinit var resumeSwitch: Switch
    private lateinit var volumeRows: LinearLayout
    private lateinit var volumeText: TextView
    private lateinit var aaText: TextView
    private lateinit var passThroughCommands: TextView
    private lateinit var guardSwitch: Switch
    private lateinit var callSwitch: Switch
    private lateinit var muteCommands: TextView
    private lateinit var diagText: TextView
    private lateinit var logText: TextView

    private val poll = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        AudioControlService.refresh(this)
        refreshDump()
        rebuildVolumeRows()
        main.post(poll)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(poll)
    }

    // ------------------------------------------------------------------------------ layout

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
                "Samsung-style multi-app audio, per-app volume and Android Auto fixes for stock " +
                    "Android. No root, no Shizuku: a few one-time ADB commands that Android remembers " +
                    "forever, plus this app for the parts that need a running helper.", 14f, secondary = true
            )
        )

        column.addView(card("One-time ADB setup") {
            setupText = text("", 14f)
            addView(setupText)
            addView(commandBlock(listOf(Adb.grantDump(this@MainActivity), Adb.allowListener(this@MainActivity))))
            addView(
                text(
                    "DUMP lets the app see which app owns each audio stream (per-app volume, " +
                        "diagnostics). Notification access lets it pause/resume media for the call and " +
                        "Android Auto helpers; notifications themselves are never read. Both survive " +
                        "reboots and app updates. You can also grant notification access in Settings " +
                        "(sideloaded apps may need \"Allow restricted settings\" in App info first).",
                    13f, secondary = true
                )
            )
            addView(button("Open notification access settings") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
        })

        column.addView(card("Multi-app audio") {
            multiText = text("", 14f)
            addView(multiText)
            addView(text("Turn on (then reboot once):", 13f, secondary = true))
            addView(commandBlock(listOf(Adb.MULTI_FOCUS_ON, Adb.REBOOT)))
            addView(text("Turn off (then reboot once):", 13f, secondary = true))
            addView(commandBlock(listOf(Adb.MULTI_FOCUS_OFF, Adb.REBOOT)))
            addView(
                text(
                    "This is Android's built-in multi audio focus mode. The setting is stored by " +
                        "Android and re-applied at every boot: set it once and forget it. Calls, alarms " +
                        "and navigation prompts still interrupt.", 13f, secondary = true
                )
            )
            resumeSwitch = switch("Resume media after calls", config.resumeAfterCall) { on ->
                config.resumeAfterCall = on
                AudioControlService.refresh(this@MainActivity)
            }
            addView(resumeSwitch)
            addView(
                text(
                    "Android 16/17 bug in this mode: apps never get audio focus back after an " +
                        "interruption. This switch covers calls (needs notification access). After a " +
                        "navigation prompt, music can stay quieter until you pause and play it. Google's " +
                        "own fix is behind a flag you can try to enable (newer builds may refuse it " +
                        "without root; harmless if so):", 13f, secondary = true
                )
            )
            addView(commandBlock(listOf(Adb.FIX_FLAG_ON, Adb.REBOOT)))
        })

        column.addView(card("Per-app volume") {
            volumeText = text("", 13f, secondary = true)
            addView(volumeText)
            volumeRows = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(volumeRows)
            addView(button("Add apps") {
                pickApps("Per-app volume", config.volumes.keys) { chosen ->
                    val current = config.volumes
                    config.volumes = chosen.associateWith { current[it] ?: 0f }
                    rebuildVolumeRows()
                    AudioControlService.refresh(this@MainActivity)
                }
            })
            addView(
                text(
                    "Works like an equalizer app: a volume effect is attached to each selected app's " +
                        "audio streams. 100% = unchanged, 0% = silent, up to 200% boost (with a limiter). " +
                        "Needs the DUMP grant above and the helper notification running. Some low-latency " +
                        "game audio can't take effects.", 13f, secondary = true
                )
            )
        })

        column.addView(card("Android Auto") {
            aaText = text("", 14f)
            addView(aaText)
            addView(
                text(
                    "While projecting, Android Auto takes over audio-focus decisions: multi-app audio is " +
                        "bypassed and it can pause YouTube and start YouTube Music instead. Pass-through " +
                        "apps are denied audio focus, so Android Auto never sees them start; they play " +
                        "alongside everything else. Run once per app (persists across reboots):",
                    13f, secondary = true
                )
            )
            passThroughCommands = text("", 12f, mono = true).apply { setTextIsSelectable(true) }
            addView(passThroughCommands)
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Choose apps") {
                pickApps("Pass-through apps", config.passThroughPackages) { chosen ->
                    config.passThroughPackages = chosen
                    render()
                    AudioControlService.refresh(this@MainActivity)
                }
            })
            row.addView(button("Copy") { copy(passThroughCommands.text.toString()) })
            addView(row)
            guardSwitch = switch("Undo automatic source switches", config.aaGuard) { on ->
                config.aaGuard = on
                AudioControlService.refresh(this@MainActivity)
            }
            addView(guardSwitch)
            callSwitch = switch("Pause pass-through apps during calls", config.pauseOnCall) { on ->
                config.pauseOnCall = on
                AudioControlService.refresh(this@MainActivity)
            }
            addView(callSwitch)
            addView(
                text(
                    "The guard (needs notification access): if a pass-through app is replaced by another " +
                        "app within ${config.guardWindowSeconds}s of starting while Android Auto is " +
                        "connected, the other app is paused and the pass-through app resumed, at most twice " +
                        "a minute. Some players refuse to start without audio focus; if a pass-through app " +
                        "won't play, run its undo command.\n\nAlso worth doing once: in Android Auto's " +
                        "settings, turn off starting media automatically; in YouTube Music, turn off letting " +
                        "external devices start playback.", 13f, secondary = true
                )
            )
        })

        column.addView(card("Mute apps completely") {
            addView(
                text(
                    "Silences an app's audio at the system level (AppOps PLAY_AUDIO). Persists across " +
                        "reboots; no helper needed.", 13f, secondary = true
                )
            )
            muteCommands = text("", 12f, mono = true).apply { setTextIsSelectable(true) }
            addView(muteCommands)
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Choose apps") {
                pickApps("Mute apps", config.mutedPackages) { chosen ->
                    config.mutedPackages = chosen
                    render()
                }
            })
            row.addView(button("Copy") { copy(muteCommands.text.toString()) })
            addView(row)
        })

        column.addView(card("Diagnostics") {
            diagText = text("", 12f, mono = true)
            addView(diagText)
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Refresh") { refreshDump() })
            row.addView(button("Copy report") { copyReport() })
            row.addView(button("Clear log") {
                AudioControlService.log.clear()
                render()
            })
            addView(row)
            logText = text("", 11f, mono = true).apply { setTextIsSelectable(true) }
            addView(logText)
        })

        column.addView(text("github.com/channelramble/multiAPPaudio", 13f, secondary = true).apply {
            setPadding(0, dp(16), 0, 0)
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/channelramble/multiAPPaudio")))
            }
        })
        return scroll
    }

    // ------------------------------------------------------------------------------ state

    private fun refreshDump() {
        io.execute {
            val d = AudioDump.capture(this)
            main.post {
                dump = d
                render()
            }
        }
    }

    private fun render() {
        rendering = true
        try {
            val hasDump = AudioDump.hasPermission(this)
            val hasAccess = Config.hasNotificationAccess(this)
            val service = AudioControlService.instance
            val snap = service?.snapshot()

            setupText.text = "DUMP: ${yes(hasDump)} · notification access: ${yes(hasAccess)}"

            val stored = storedMultiFocus()
            val live = dump?.multiAudioFocusEnabled ?: snap?.multiFocusLive
            multiText.text = buildString {
                append("Setting: ${if (stored == 1) "on" else "off"}")
                append(" · active now: ${live?.let { if (it) "yes" else "no" } ?: "unknown (needs DUMP)"}")
                if (live != null && live != (stored == 1)) append("\nReboot once to apply the setting.")
            }
            resumeSwitch.isChecked = config.resumeAfterCall

            val volumes = snap?.volumes.orEmpty()
            volumeText.text = when {
                !hasDump -> "Grant DUMP (setup above) to use per-app volume."
                config.volumes.values.none { it != 0f } -> "Add an app and move its slider."
                service == null -> "Helper starting…"
                volumes.isEmpty() -> "Waiting for the selected apps to play."
                else -> "Applied to ${volumes.size} active stream(s)."
            }

            val ext = dump?.externalFocusPolicy ?: snap?.externalFocusPolicy
            aaText.text = buildString {
                append("Android Auto: ${snap?.car ?: "helper not running"}")
                append("\nExternal focus policy installed: ${ext ?: "unknown (needs DUMP)"}")
            }
            passThroughCommands.text = commands(config.passThroughPackages) { Adb.passThrough(it, true) } +
                "\n\n# undo:\n" + commands(config.passThroughPackages) { Adb.passThrough(it, false) }
            guardSwitch.isChecked = config.aaGuard
            callSwitch.isChecked = config.pauseOnCall

            muteCommands.text = if (config.mutedPackages.isEmpty()) {
                "# choose apps to get their commands"
            } else {
                commands(config.mutedPackages) { Adb.mute(it, true) } +
                    "\n\n# undo:\n" + commands(config.mutedPackages) { Adb.mute(it, false) }
            }

            diagText.text = buildString {
                append("helper: ${if (service != null) "running" else "not running"}\n")
                append("media sessions: ${if (snap?.sessionsWatched == true) "watched" else "not watched"}\n")
                append("guard fixes: ${snap?.guardFixes ?: 0}\n")
                snap?.sessions?.forEach { append("  $it\n") }
                volumes.forEach { append("  $it\n") }
                dump?.players?.filter { it.state == "started" }?.forEach { p ->
                    append("  playing: ${label(nameForUid(p.uid))} ${p.usage} session ${p.sessionId}")
                    if (p.muted.isNotBlank() && p.muted != "none") append(" muted:${p.muted}")
                    append("\n")
                }
            }.trimEnd()
            logText.text = AudioControlService.log.dump().lines().takeLast(LOG_LINES).joinToString("\n")
        } finally {
            rendering = false
        }
    }

    private fun rebuildVolumeRows() {
        volumeRows.removeAllViews()
        for ((pkg, db) in config.volumes.toSortedMap()) {
            val valueText = text("", 13f, secondary = true)
            fun show(percent: Int) {
                valueText.text = "${label(pkg)}: $percent%" +
                    if (percent == 100) "" else " (${"%+.1f".format(AppVolumes.percentToDb(percent))} dB)"
            }
            val start = AppVolumes.dbToPercent(db).coerceIn(0, MAX_PERCENT)
            show(start)
            val bar = SeekBar(this).apply {
                max = MAX_PERCENT
                progress = start
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) = show(value)
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {
                        config.volumes = config.volumes + (pkg to AppVolumes.percentToDb(s.progress))
                        AudioControlService.refresh(this@MainActivity)
                    }
                })
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(valueText)
            row.addView(bar)
            row.addView(button("Reset / remove") {
                config.volumes = config.volumes - pkg
                rebuildVolumeRows()
                AudioControlService.refresh(this@MainActivity)
            })
            volumeRows.addView(row)
        }
    }

    // ------------------------------------------------------------------------------ actions

    private fun copyReport() {
        io.execute {
            val d = AudioDump.capture(this)
            val snap = AudioControlService.instance?.snapshot()
            val report = buildString {
                appendLine("Multi-App Audio report")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}")
                appendLine("setting multi_audio_focus_enabled=${storedMultiFocus()}")
                appendLine("DUMP=${AudioDump.hasPermission(this@MainActivity)} notificationAccess=${Config.hasNotificationAccess(this@MainActivity)}")
                appendLine("volumes=${config.volumes} passThrough=${config.passThroughPackages} muted=${config.mutedPackages}")
                appendLine("guard=${config.aaGuard} pauseOnCall=${config.pauseOnCall} resumeAfterCall=${config.resumeAfterCall}")
                appendLine("helper: $snap")
                appendLine()
                appendLine("---- focus (dumpsys audio) ----")
                appendLine(d?.focusSection() ?: "(grant DUMP to include this)")
                appendLine()
                appendLine("---- players ----")
                d?.players?.forEach { appendLine("${nameForUid(it.uid)} $it") } ?: appendLine("(grant DUMP to include this)")
                appendLine()
                appendLine("---- helper log ----")
                appendLine(AudioControlService.log.dump())
            }
            main.post { copy(report, "Report copied to the clipboard.") }
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
                    .setMultiChoiceItems(apps.map { "${it.second}\n${it.first}" }.toTypedArray(), checked) { _, which, on ->
                        checked[which] = on
                    }
                    .setPositiveButton("OK") { _, _ ->
                        onDone(apps.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet())
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun copy(textToCopy: String, message: String = "Copied. Paste into a terminal with adb.") {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Multi-App Audio", textToCopy))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------------------ helpers

    private fun commands(pkgs: Set<String>, line: (String) -> String): String =
        if (pkgs.isEmpty()) "# no apps selected" else pkgs.sorted().joinToString("\n", transform = line)

    private fun storedMultiFocus(): Int = runCatching {
        Settings.System.getInt(contentResolver, AudioControlService.MULTI_FOCUS_SETTING, 0)
    }.getOrDefault(-1)

    private fun nameForUid(uid: Int): String = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid $uid"

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun yes(b: Boolean) = if (b) "granted" else "not granted"

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

    /** Monospace, selectable command lines with a Copy button. */
    private fun commandBlock(lines: List<String>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val body = lines.joinToString("\n")
        addView(text(body, 12f, mono = true).apply { setTextIsSelectable(true) })
        addView(button("Copy") { copy(body) })
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
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(blend(bg, fg, 0.06f))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            addView(text(title, 18f, bold = true))
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
        private const val MAX_PERCENT = 200
    }
}
