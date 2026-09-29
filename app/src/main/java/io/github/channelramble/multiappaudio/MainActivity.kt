package io.github.channelramble.multiappaudio

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import io.github.channelramble.multiappaudio.core.AppVolumes
import io.github.channelramble.multiappaudio.core.AudioDump
import java.util.concurrent.Executors

/**
 * ADB-only front end: one card per feature with a status line, its controls, and the details
 * folded under "Read more". Anything that needs shell privileges is shown as a one-time ADB
 * command; per-app volume and the Android Auto / call helpers run in [AudioControlService].
 */
class MainActivity : Activity() {

    private enum class Level { OK, WARN, OFF }

    /** Selectable ADB commands with a Copy button; hidden while it has no lines. */
    private inner class CommandBlock(caption: String) {
        private val body = text("", 12f, mono = true).apply { setTextIsSelectable(true) }

        val view: LinearLayout = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(text(caption, 13f, secondary = true))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(codeBackground)
                setPadding(dp(12), dp(8), dp(4), 0)
                addView(body)
                addView(flatButton("Copy") { copy(body.text.toString()) }, wrap().apply { gravity = Gravity.END })
            })
            visibility = View.GONE
        }

        var lines: List<String> = emptyList()
            set(value) {
                field = value
                body.text = value.joinToString("\n")
                view.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
            }

        fun with(lines: List<String>) = apply { this.lines = lines }
    }

    private val config by lazy { Config.of(this) }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var dump: AudioDump? = null
    private var rendering = false

    private val night by lazy {
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    private val accent by lazy { themeColor(android.R.attr.colorAccent) }
    private val primaryText by lazy { themeColor(android.R.attr.textColorPrimary) }
    private val secondaryText by lazy { themeColor(android.R.attr.textColorSecondary) }
    private val cardBackground by lazy { blend(themeColor(android.R.attr.colorBackground), themeColor(android.R.attr.colorForeground), 0.06f) }
    private val codeBackground by lazy { blend(themeColor(android.R.attr.colorBackground), themeColor(android.R.attr.colorForeground), 0.11f) }
    private val statusDots by lazy {
        mapOf(
            Level.OK to dot(if (night) 0xFF81C995.toInt() else 0xFF1E8E3E.toInt()),
            Level.WARN to dot(if (night) 0xFFFDD663.toInt() else 0xFFB06000.toInt()),
            Level.OFF to dot(secondaryText),
        )
    }

    private lateinit var column: LinearLayout
    private lateinit var setupCard: View
    private lateinit var diagnosticsCard: View

    private lateinit var setupStatus: TextView
    private lateinit var setupMissing: CommandBlock
    private lateinit var setupDetail: TextView
    private lateinit var volumeStatus: TextView
    private lateinit var volumeDetail: TextView
    private lateinit var notificationSwitch: Switch
    private lateinit var multiStatus: TextView
    private lateinit var multiTurnOn: CommandBlock
    private lateinit var resumeSwitch: Switch
    private lateinit var aaStatus: TextView
    private lateinit var passThroughApps: TextView
    private lateinit var passThroughOn: CommandBlock
    private lateinit var passThroughOff: CommandBlock
    private lateinit var aaDetail: TextView
    private lateinit var guardSwitch: Switch
    private lateinit var callSwitch: Switch
    private lateinit var muteStatus: TextView
    private lateinit var muteOn: CommandBlock
    private lateinit var muteOff: CommandBlock
    private lateinit var diagStatus: TextView
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
        main.post(poll)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(poll)
    }

    // ------------------------------------------------------------------------------ layout

    private fun buildUi(): View {
        column = LinearLayout(this).apply {
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
                "Play several apps at once, set each app's volume, and stop Android Auto from " +
                    "switching apps. No root needed.", 14f, secondary = true
            )
        )

        setupCard = card("Setup") {
            setupStatus = statusLine().also { addView(it) }
            addView(summary("Two one-time permissions, granted over ADB. They survive reboots and updates."))
            setupMissing = CommandBlock("Run once from a computer:").also { addView(it.view) }
            readMore {
                setupDetail = detail("").also { addView(it) }
                addView(
                    detail(
                        "App detection (DUMP) lets the app see which app is playing each sound, for " +
                            "per-app volume and diagnostics. Media control (notification access) lets it " +
                            "pause and resume players for the call and Android Auto helpers. It never " +
                            "reads your notifications."
                    )
                )
                addView(CommandBlock("Both commands:").with(listOf(Adb.grantDump(this@MainActivity), Adb.allowListener(this@MainActivity))).view)
                addView(
                    detail(
                        "Media control can also be turned on in Settings. Sideloaded apps may first need " +
                            "\"Allow restricted settings\" from the app's info page."
                    )
                )
                addView(flatButton("Open notification access settings") {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                })
            }
        }
        column.addView(setupCard)

        column.addView(card("App volume") {
            volumeStatus = statusLine().also { addView(it) }
            addView(summary("Turn single apps up or down, right from the notification shade."))
            addView(button("Open App volume") { startActivity(Intent(this@MainActivity, MixerActivity::class.java)) })
            if (Build.VERSION.SDK_INT >= 33) addView(flatButton("Add Quick Settings tile") { requestTile() })
            notificationSwitch = switch("Keep the App volume notification", config.volumeNotification) { on ->
                config.volumeNotification = on
                AudioControlService.refresh(this@MainActivity)
            }
            addView(notificationSwitch)
            readMore {
                volumeDetail = detail("").also { addView(it) }
                addView(
                    detail(
                        "Expand the App volume notification for - and + buttons, or tap it for sliders. " +
                            "The Quick Settings tile opens the same sliders."
                    )
                )
                addView(
                    detail(
                        "It works like an equalizer app: a volume effect on each app's audio. 100% is " +
                            "unchanged, 0% is silent, and up to 200% boosts, with a limiter so it can't " +
                            "clip. Some low-latency game audio can't be adjusted."
                    )
                )
                addView(
                    detail(
                        "With the notification switch off, the notification only shows while per-app " +
                            "volume or the Android Auto and call helpers are in use."
                    )
                )
            }
        })

        column.addView(card("Multi-app audio") {
            multiStatus = statusLine().also { addView(it) }
            addView(
                summary(
                    "Media apps play together instead of pausing each other. Calls, alarms and " +
                        "navigation still interrupt."
                )
            )
            multiTurnOn = CommandBlock("Turn on, then reboot once:").also { addView(it.view) }
            resumeSwitch = switch("Resume media after calls", config.resumeAfterCall) { on ->
                config.resumeAfterCall = on
                AudioControlService.refresh(this@MainActivity)
            }
            addView(resumeSwitch)
            readMore {
                addView(
                    detail(
                        "This is Android's own multi audio focus mode. Android stores the setting and " +
                            "re-applies it at every boot."
                    )
                )
                addView(CommandBlock("Turn off, then reboot once:").with(listOf(Adb.MULTI_FOCUS_OFF, Adb.REBOOT)).view)
                addView(
                    detail(
                        "Android 16 and 17 have a bug in this mode: apps don't get audio focus back after " +
                            "an interruption. \"Resume media after calls\" covers calls (needs media " +
                            "control). After a navigation prompt, music can stay quieter until you pause " +
                            "and play it."
                    )
                )
                addView(
                    CommandBlock(
                        "Google's own fix is behind a flag you can try. Newer builds may refuse it " +
                            "without root, which is harmless:"
                    ).with(listOf(Adb.FIX_FLAG_ON, Adb.REBOOT)).view
                )
            }
        })

        column.addView(card("Android Auto") {
            aaStatus = statusLine().also { addView(it) }
            addView(
                summary(
                    "Pass-through apps keep playing in the car alongside other audio, and Android Auto " +
                        "can't swap them out."
                )
            )
            passThroughApps = text("", 14f).also { addView(it) }
            addView(flatButton("Choose apps") {
                pickApps("Pass-through apps", config.passThroughPackages) { chosen ->
                    config.passThroughPackages = chosen
                    render()
                    AudioControlService.refresh(this@MainActivity)
                }
            })
            passThroughOn = CommandBlock("Run once per app:").also { addView(it.view) }
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
            readMore {
                aaDetail = detail("").also { addView(it) }
                addView(
                    detail(
                        "While projecting, Android Auto makes every audio focus decision itself: " +
                            "multi-app audio is bypassed, and it can pause YouTube to start YouTube Music. " +
                            "Pass-through apps never ask for audio focus, so Android Auto doesn't see them start."
                    )
                )
                addView(
                    detail(
                        "Undo automatic source switches (needs media control): if another app replaces a " +
                            "pass-through app within ${config.guardWindowSeconds}s of it starting while " +
                            "Android Auto is connected, that app is paused and the pass-through app " +
                            "resumed, at most twice a minute."
                    )
                )
                passThroughOff = CommandBlock(
                    "Some players won't start without audio focus. If a pass-through app won't play, undo it:"
                ).also { addView(it.view) }
                addView(
                    detail(
                        "Also worth doing once: in Android Auto's settings, turn off starting media " +
                            "automatically. In YouTube Music, turn off letting external devices start playback."
                    )
                )
            }
        })

        column.addView(card("Mute apps") {
            muteStatus = statusLine().also { addView(it) }
            addView(summary("Silence an app completely. Android remembers it, so no helper is needed."))
            addView(flatButton("Choose apps") {
                pickApps("Mute apps", config.mutedPackages) { chosen ->
                    config.mutedPackages = chosen
                    render()
                }
            })
            muteOn = CommandBlock("Run once per app:").also { addView(it.view) }
            readMore {
                addView(detail("Uses Android's PLAY_AUDIO app-op, which persists across reboots."))
                muteOff = CommandBlock("To undo:").also { addView(it.view) }
            }
        })

        diagnosticsCard = card("Diagnostics") {
            diagStatus = statusLine().also { addView(it) }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(flatButton("Copy report") { copyReport() })
                addView(flatButton("Refresh") { refreshDump() }, wrap())
            })
            readMore("Show details") {
                diagText = text("", 12f, mono = true).also { addView(it) }
                addView(flatButton("Clear log") {
                    AudioControlService.log.clear()
                    render()
                })
                logText = text("", 11f, mono = true).apply { setTextIsSelectable(true) }.also { addView(it) }
            }
        }
        column.addView(diagnosticsCard)

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

            // Setup: at the top while something is missing, out of the way once done.
            val missing = listOfNotNull(
                Adb.grantDump(this).takeUnless { hasDump },
                Adb.allowListener(this).takeUnless { hasAccess },
            )
            when (missing.size) {
                0 -> setStatus(setupStatus, Level.OK, "All set")
                1 -> setStatus(setupStatus, Level.WARN, "One command left to run")
                else -> setStatus(setupStatus, Level.WARN, "Two commands to run")
            }
            setupMissing.lines = missing
            setupDetail.text = "App detection (DUMP): ${yes(hasDump)}\nMedia control (notification access): ${yes(hasAccess)}"
            placeSetupCard(atTop = missing.isNotEmpty())

            val changed = config.volumes.filterValues { it != 0f }
            val applied = snap?.volumes.orEmpty()
            when {
                !hasDump -> setStatus(volumeStatus, Level.WARN, "Needs setup: app detection")
                changed.isEmpty() -> setStatus(volumeStatus, Level.OFF, "All apps at 100%")
                else -> setStatus(
                    volumeStatus, Level.OK,
                    changed.entries.sortedBy { label(it.key).lowercase() }.joinToString(" · ") {
                        "${label(it.key)} ${MixerNotification.percentText(AppVolumes.dbToPercent(it.value))}"
                    },
                )
            }
            volumeDetail.text = when {
                !hasDump -> "Run the Setup command for app detection first."
                changed.isEmpty() -> "No app is turned up or down right now."
                service == null -> "Helper starting…"
                service?.canReadPlayers == false -> "Can't read which apps are playing: ${AudioDump.lastFailure ?: "unknown"}"
                applied.isEmpty() -> "Waiting for those apps to play."
                else -> "Applied to ${applied.size} active stream(s)."
            }
            notificationSwitch.isChecked = config.volumeNotification

            val stored = storedMultiFocus()
            val live = dump?.multiAudioFocusEnabled ?: snap?.multiFocusLive
            when {
                stored == 1 && live == false -> setStatus(multiStatus, Level.WARN, "Reboot once to turn it on")
                stored != 1 && live == true -> setStatus(multiStatus, Level.WARN, "Reboot once to turn it off")
                // live is unknown without DUMP, and on Android 17 (AudioService's dump is locked).
                stored == 1 -> setStatus(multiStatus, Level.OK, "On")
                else -> setStatus(multiStatus, Level.OFF, "Off")
            }
            multiTurnOn.lines = if (stored == 1) emptyList() else listOf(Adb.MULTI_FOCUS_ON, Adb.REBOOT)
            resumeSwitch.isChecked = config.resumeAfterCall

            val car = snap?.car
            setStatus(
                aaStatus,
                if (car == "Android Auto connected" || car == "car mode") Level.OK else Level.OFF,
                when (car) {
                    null -> "Helper not running"
                    "not watched" -> "Connection not watched (source-switch undo is off)"
                    else -> car.replaceFirstChar { it.uppercase() }
                },
            )
            val pass = config.passThroughPackages.sorted()
            passThroughApps.text = "Pass-through: " + (pass.map(::label).sorted().joinToString(", ").ifEmpty { "none" })
            passThroughOn.lines = pass.map { Adb.passThrough(it, true) }
            passThroughOff.lines = pass.map { Adb.passThrough(it, false) }
            val ext = dump?.externalFocusPolicy ?: snap?.externalFocusPolicy
            aaDetail.text = "Android Auto deciding audio focus right now: " + when {
                ext != null -> if (ext) "yes" else "no"
                !hasDump -> "unknown (needs app detection)"
                else -> "unknown (Android 17 doesn't let apps see this)"
            }
            guardSwitch.isChecked = config.aaGuard
            callSwitch.isChecked = config.pauseOnCall

            val muted = config.mutedPackages.sorted()
            if (muted.isEmpty()) {
                setStatus(muteStatus, Level.OFF, "No apps chosen")
            } else {
                setStatus(muteStatus, Level.OFF, "Commands ready for " + muted.map(::label).sorted().joinToString(", "))
            }
            muteOn.lines = muted.map { Adb.mute(it, true) }
            muteOff.lines = muted.map { Adb.mute(it, false) }

            setStatus(
                diagStatus, if (service != null) Level.OK else Level.OFF,
                buildString {
                    append(if (service != null) "Helper running" else "Helper not running")
                    if (snap?.sessionsWatched == true) append(" · ${snap.sessions.size} media session(s)")
                },
            )
            diagText.text = buildString {
                append("helper: ${if (service != null) "running" else "not running"}\n")
                append("audio sessions: ${dump?.let { "readable (${it.source})" } ?: AudioDump.lastFailure ?: "not read yet"}\n")
                append("media sessions: ${if (snap?.sessionsWatched == true) "watched" else "not watched"}\n")
                append("guard fixes: ${snap?.guardFixes ?: 0}\n")
                snap?.sessions?.forEach { append("  $it\n") }
                snap?.volumes?.forEach { append("  $it\n") }
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

    private fun placeSetupCard(atTop: Boolean) {
        val index = column.indexOfChild(setupCard)
        val wanted = if (atTop) FIRST_CARD_INDEX else column.indexOfChild(diagnosticsCard) - 1
        if (index == wanted) return
        column.removeView(setupCard)
        column.addView(setupCard, if (atTop) FIRST_CARD_INDEX else column.indexOfChild(diagnosticsCard))
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
                appendLine("audio sessions: ${d?.let { "readable (${it.source})" } ?: AudioDump.lastFailure}")
                appendLine("volumes=${config.volumes} passThrough=${config.passThroughPackages} muted=${config.mutedPackages}")
                appendLine("guard=${config.aaGuard} pauseOnCall=${config.pauseOnCall} resumeAfterCall=${config.resumeAfterCall} volumeNotification=${config.volumeNotification}")
                appendLine("helper: $snap")
                appendLine()
                appendLine("---- focus (dumpsys audio) ----")
                appendLine(d?.focusSection() ?: "(unavailable: ${AudioDump.lastFailure})")
                appendLine()
                appendLine("---- players ----")
                d?.players?.forEach { appendLine("${nameForUid(it.uid)} $it") } ?: appendLine("(unavailable: ${AudioDump.lastFailure})")
                appendLine()
                appendLine("---- helper log ----")
                appendLine(AudioControlService.log.dump())
            }
            main.post { copy(report, "Report copied to the clipboard.") }
        }
    }

    private fun pickApps(title: String, current: Set<String>, onDone: (Set<String>) -> Unit) =
        AppPicker.show(this, io, title, current, onDone)

    /** Android 13+: the system's own "Add tile?" prompt, instead of editing Quick Settings by hand. */
    private fun requestTile() {
        if (Build.VERSION.SDK_INT < 33) return
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, MixerTileService::class.java),
            getString(R.string.mixer_title),
            Icon.createWithResource(this, R.drawable.ic_mixer),
            mainExecutor,
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added to Quick Settings."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "The tile is already in Quick Settings."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Tile not added."
                else -> "Couldn't ask here. Edit Quick Settings and drag in App volume."
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copy(textToCopy: String, message: String = "Copied. Paste into a terminal with adb.") {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Multi-App Audio", textToCopy))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------------------ helpers

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

    private fun themeDrawable(attr: Int): Drawable? {
        val a = obtainStyledAttributes(intArrayOf(attr))
        return try {
            a.getDrawable(0)
        } finally {
            a.recycle()
        }
    }

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun text(s: String, size: Float, bold: Boolean = false, secondary: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (mono) typeface = Typeface.MONOSPACE
            // A bare TextView defaults to the secondary color; be explicit either way.
            setTextColor(if (secondary) secondaryText else primaryText)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun summary(s: String) = text(s, 14f, secondary = true)

    private fun detail(s: String) = text(s, 13f, secondary = true).apply { setPadding(0, dp(6), 0, dp(2)) }

    private fun statusLine() = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(primaryText)
        gravity = Gravity.CENTER_VERTICAL
        compoundDrawablePadding = dp(8)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun setStatus(view: TextView, level: Level, message: String) {
        view.text = message
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(statusDots.getValue(level), null, null, null)
    }

    private fun dot(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(dp(10), dp(10))
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(color)
    }

    /** The one filled button per card, for its main action. */
    private fun button(label: String, onClick: () -> Unit) =
        Button(this, null, 0, android.R.style.Widget_DeviceDefault_Button_Colored).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = wrap().apply { topMargin = dp(6) }
        }

    /** Text-style button; pulled left so its label lines up with the text above. */
    private fun flatButton(label: String, onClick: () -> Unit) =
        Button(this, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless_Colored).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = wrap().apply { marginStart = -dp(12) }
        }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isChecked = checked
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, on -> if (!rendering) onChange(on) }
    }

    /** A "Read more" toggle with [content] folded underneath it. */
    private fun LinearLayout.readMore(label: String = "Read more", content: LinearLayout.() -> Unit) {
        val details = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            content()
        }
        val toggle = TextView(this@MainActivity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(accent)
            setPadding(0, dp(10), 0, dp(6))
            background = themeDrawable(android.R.attr.selectableItemBackground)
        }
        fun show(open: Boolean) {
            details.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "Show less  ▴" else "$label  ▾"
            toggle.stateDescription = if (open) "Expanded" else "Collapsed"
        }
        toggle.setOnClickListener { show(details.visibility != View.VISIBLE) }
        show(false)
        addView(toggle, wrap())
        addView(details)
    }

    private fun card(title: String, content: LinearLayout.() -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(cardBackground)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
        addView(text(title, 18f, bold = true))
        content()
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    companion object {
        private const val POLL_MS = 2_500L
        private const val LOG_LINES = 80
        /** After the title and the one-line intro. */
        private const val FIRST_CARD_INDEX = 2
    }
}
