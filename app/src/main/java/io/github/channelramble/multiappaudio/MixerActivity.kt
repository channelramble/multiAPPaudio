package io.github.channelramble.multiappaudio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import io.github.channelramble.multiappaudio.core.ActiveApps
import io.github.channelramble.multiappaudio.core.AppLabels
import io.github.channelramble.multiappaudio.core.AppVolumes
import io.github.channelramble.multiappaudio.core.AudioDump
import kotlin.math.abs

/**
 * The App volume panel: a sheet over whatever app is open, with a slider for each app that's
 * playing. Opened from the App volume notification, the Quick Settings tile or the main screen;
 * changes apply while you drag.
 */
class MixerActivity : Activity() {

    private class Row(val state: TextView, val seek: SeekBar, val percent: TextView, val mute: ImageButton)

    private val config by lazy { Config.of(this) }
    private val labels by lazy { AppLabels(packageManager) }
    private val main = Handler(Looper.getMainLooper())
    private val rows = HashMap<String, Row>()
    private val levelBeforeMute = HashMap<String, Int>()
    private var shown: List<String>? = null
    private var dragging = 0
    private var closing = false

    private lateinit var scrim: View
    private lateinit var sheet: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var rowList: LinearLayout
    private lateinit var emptyText: TextView

    private val surface by lazy { blend(themeColor(android.R.attr.colorBackground), themeColor(android.R.attr.colorForeground), 0.05f) }
    private val onSurface by lazy { themeColor(android.R.attr.textColorPrimary) }
    private val onSurfaceVariant by lazy { themeColor(android.R.attr.textColorSecondary) }
    private val accent by lazy { themeColor(android.R.attr.colorAccent) }

    private val poll = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        // Edge-to-edge everywhere (Android 15+ enforces it anyway): the sheet runs under the
        // navigation bar and pads itself from the insets.
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        }
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close() }
        }
        animateIn()
    }

    override fun onResume() {
        super.onResume()
        // The helper keeps the app list current; make sure it runs while the panel is open.
        AudioControlService.panelOpen = true
        AudioControlService.refresh(this)
        AudioControlService.instance?.requestRescan()
        main.post(poll)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(poll)
        AudioControlService.panelOpen = false
        AudioControlService.refresh(this)
    }

    override fun onStop() {
        super.onStop()
        // A panel left behind (Home, screen off) would come back stale; start fresh next time.
        if (!isChangingConfigurations && !isFinishing) finish()
    }

    @Deprecated("Back before Android 13, and on 13-15 without predictive back")
    override fun onBackPressed() = close()

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    // ------------------------------------------------------------------------------ layout

    private fun buildUi(): View {
        val root = FrameLayout(this)
        scrim = View(this).apply {
            setBackgroundColor(SCRIM)
            alpha = 0f
            setOnClickListener { close() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // taps on the sheet must not reach the scrim
            val r = dp(28).toFloat()
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(surface)
            }
            setPadding(dp(SHEET_PADDING_DP), dp(10), dp(SHEET_PADDING_DP), dp(12))
        }
        val wide = resources.configuration.screenWidthDp >= WIDE_SCREEN_DP
        root.addView(
            sheet,
            FrameLayout.LayoutParams(
                if (wide) dp(SHEET_MAX_WIDTH_DP) else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            )
        )
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            sheet.setPadding(dp(SHEET_PADDING_DP) + bars.left, dp(10), dp(SHEET_PADDING_DP) + bars.right, dp(12) + bars.bottom)
            insets
        }

        sheet.addView(View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(withAlpha(onSurfaceVariant, 0.4f))
            }
        }, LinearLayout.LayoutParams(dp(32), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })
        sheet.addView(text(getString(R.string.mixer_title), 22f, onSurface).apply { setTypeface(typeface, Typeface.BOLD) })
        statusText = text("", 13f, onSurfaceVariant).apply { setPadding(0, dp(2), 0, dp(6)) }
        sheet.addView(statusText)

        rowList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val maxListHeight = (resources.displayMetrics.heightPixels * LIST_MAX_SCREEN_FRACTION).toInt()
        sheet.addView(MaxHeightScrollView(this, maxListHeight).apply {
            isVerticalScrollBarEnabled = false
            addView(rowList)
        })
        emptyText = text(
            "Nothing is playing right now. Start music or a video and it shows up here.",
            14f, onSurfaceVariant,
        ).apply {
            setPadding(0, dp(12), 0, dp(12))
            visibility = View.GONE
        }
        sheet.addView(emptyText)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        footer.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        footer.addView(flatButton("Settings") { openSettings() })
        sheet.addView(footer)
        return root
    }

    private fun buildRow(app: ActiveApps.App): View {
        val pkg = app.pkg
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(ImageView(this).apply {
            setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        top.addView(text(labels.label(pkg), 16f, onSurface).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create(typeface, 500, false)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val state = text("", 13f, onSurfaceVariant)
        top.addView(state)
        container.addView(top)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mute = ImageButton(this).apply {
            background = themeDrawable(android.R.attr.selectableItemBackgroundBorderless)
            imageTintList = ColorStateList.valueOf(onSurfaceVariant)
            setOnClickListener { toggleMute(pkg) }
        }
        // Pulled left so the speaker lines up under the app icon.
        bottom.addView(mute, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = -dp(8) })
        val seek = SeekBar(this).apply {
            max = config.maxPercent
            styleSlider(this, notchAt100 = max > 100)
            contentDescription = "${labels.label(pkg)} volume"
        }
        bottom.addView(seek, LinearLayout.LayoutParams(0, dp(44), 1f))
        val percent = text("", 14f, onSurface).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            fontFeatureSettings = "tnum"
            background = themeDrawable(android.R.attr.selectableItemBackground)
            contentDescription = "Reset ${labels.label(pkg)} to 100%"
            setOnClickListener { setLevel(pkg, 100) }
        }
        bottom.addView(percent, LinearLayout.LayoutParams(dp(60), dp(44)))
        container.addView(bottom)

        val row = Row(state, seek, percent, mute)
        showState(row, app)
        val start = level(pkg)
        seek.progress = start
        showLevel(row, start)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var atDetent = start == 100

            override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                // With boost on, 100% (unchanged) sits mid-track: a soft detent there, with a tick.
                val boost = s.max > 100
                val v = if (boost && value != 100 && abs(value - 100) <= DETENT) 100 else value
                if (v != value) s.progress = v
                if (boost && v == 100 && !atDetent) s.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                atDetent = v == 100
                setLevel(pkg, v, fromSlider = true)
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                dragging++
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                dragging--
            }
        })
        rows[pkg] = row
        return container
    }

    // ------------------------------------------------------------------------------ state

    private fun render() {
        val service = AudioControlService.instance
        statusText.text = when {
            !AudioDump.hasPermission(this) ->
                "Grant DUMP once over ADB so playing apps show up here:\n${Adb.grantDump(this)}"
            service == null -> "Starting…"
            !service.canReadPlayers ->
                "Can't see which apps are playing, so changes won't apply yet (${AudioDump.lastFailure ?: "unknown reason"})."
            else -> "Changes apply as you drag. Tap a percentage to reset it."
        }
        val apps = service?.mixerApps ?: return
        if (apps.any { it.blocked }) {
            statusText.text = "An app's audio is refusing the volume control. Closing and reopening " +
                "that app usually fixes it."
        }
        val keys = apps.map { it.pkg }
        if (keys != shown && dragging == 0) {
            shown = keys
            rows.clear()
            rowList.removeAllViews()
            apps.forEach { rowList.addView(buildRow(it)) }
        } else {
            apps.forEach { app -> rows[app.pkg]?.let { showState(it, app) } }
        }
        emptyText.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun level(pkg: String) = AppVolumes.dbToPercent(config.volumes[pkg] ?: 0f)

    private fun setLevel(pkg: String, percent: Int, fromSlider: Boolean = false) {
        config.setVolume(pkg, AppVolumes.percentToDb(percent))
        AudioControlService.volumesChanged(this)
        val row = rows[pkg] ?: return
        if (!fromSlider) row.seek.progress = percent
        showLevel(row, percent)
    }

    private fun toggleMute(pkg: String) {
        val current = level(pkg)
        if (current > 0) {
            levelBeforeMute[pkg] = current
            setLevel(pkg, 0)
        } else {
            setLevel(pkg, levelBeforeMute.remove(pkg) ?: 100)
        }
    }

    private fun showLevel(row: Row, percent: Int) {
        row.percent.text = MixerNotification.percentText(percent)
        row.mute.setImageResource(if (percent == 0) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        row.mute.contentDescription = if (percent == 0) "Unmute" else "Mute"
    }

    private fun showState(row: Row, app: ActiveApps.App) {
        row.state.text = when {
            app.blocked -> "Can't adjust"
            app.state == ActiveApps.State.PLAYING -> "Playing"
            app.state == ActiveApps.State.PAUSED -> "Paused"
            else -> "Stopped"
        }
        row.state.setTextColor(if (app.state == ActiveApps.State.PLAYING && !app.blocked) accent else onSurfaceVariant)
    }

    // ------------------------------------------------------------------------------ actions

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun animateIn() {
        sheet.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sheet.viewTreeObserver.removeOnPreDrawListener(this)
                sheet.translationY = sheet.height.toFloat()
                sheet.animate().translationY(0f).setDuration(ANIM_IN_MS).setInterpolator(DecelerateInterpolator(2f)).start()
                scrim.animate().alpha(1f).setDuration(ANIM_IN_MS).start()
                return true
            }
        })
    }

    private fun close() {
        if (closing) return
        closing = true
        scrim.animate().alpha(0f).setDuration(ANIM_OUT_MS).start()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(ANIM_OUT_MS)
            .setInterpolator(AccelerateInterpolator()).withEndAction { finish() }.start()
    }

    // ------------------------------------------------------------------------------ helpers

    /** Thicker track than the platform default; with boost on, a notch marks 100% mid-track. */
    private fun styleSlider(seek: SeekBar, notchAt100: Boolean) {
        val h = dp(6)
        val track = GradientDrawable().apply {
            cornerRadius = h / 2f
            setColor(blend(surface, onSurface, 0.16f))
        }
        val fill = ClipDrawable(GradientDrawable().apply {
            cornerRadius = h / 2f
            setColor(accent)
        }, Gravity.START, ClipDrawable.HORIZONTAL)
        val notch = GradientDrawable().apply { setColor(surface) }
        val layers = if (notchAt100) arrayOf<Drawable>(track, fill, notch) else arrayOf<Drawable>(track, fill)
        seek.progressDrawable = LayerDrawable(layers).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            if (notchAt100) {
                setLayerGravity(2, Gravity.CENTER)
                setLayerSize(2, dp(2), h)
            }
        }
        seek.minHeight = h
        seek.maxHeight = h
        seek.thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accent)
            setSize(dp(20), dp(20))
        }
        seek.splitTrack = false
    }

    private fun text(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
    }

    private fun flatButton(label: String, onClick: () -> Unit) =
        Button(this, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless_Colored).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

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

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    private fun withAlpha(color: Int, alpha: Float) =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /** Wraps its content up to [maxHeight], then scrolls. */
    private class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }

    companion object {
        private const val POLL_MS = 1_000L
        private const val ANIM_IN_MS = 260L
        private const val ANIM_OUT_MS = 180L
        private const val DETENT = 4
        private const val SCRIM = 0x66000000
        private const val WIDE_SCREEN_DP = 600
        private const val SHEET_MAX_WIDTH_DP = 560
        private const val SHEET_PADDING_DP = 20
        private const val LIST_MAX_SCREEN_FRACTION = 0.6f
    }
}
