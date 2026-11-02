package com.woodruff.tvexplorer.update

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.woodruff.tvexplorer.BuildConfig
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.util.CrashLog
import com.woodruff.tvexplorer.util.EarlyCrashes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * What the main screen shows instead of Woodruff after this version closed twice in a row while
 * starting ([EarlyCrashes]): one calm screen in Woodruff's colours that says what happened in plain
 * words, is already looking for a fixed version ([Updater.lookForFix], as it opens and every few
 * minutes while it's up) and offers the one press that installs it. It can also try opening Woodruff
 * as usual again, and show what went wrong for whoever looks after the TVs.
 *
 * It has to work even when what broke is Woodruff's normal interface, so it's built from framework
 * Views in code: no Compose, no theme (always dark), none of the app's own screens or state. Every
 * optional piece (the logo, the fonts, the crash text) may fail without taking it down, and if building
 * it fails at all, [show] puts up the plainest screen instead ([plain]). Nothing touches the disk on the
 * main thread except the fonts, read once as it opens.
 *
 * Remote: the three pills in a row, the first focused (Install the fix when one is ready, otherwise
 * Check for a fix); Left/Right move between them, Up/Down do nothing, OK presses, Back leaves Woodruff.
 */
class RecoveryView internal constructor(private val activity: Activity) : FrameLayout(activity) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    private val display = typeface(R.font.interdisplay_semibold, Typeface.DEFAULT_BOLD)
    private val semibold = typeface(R.font.inter_semibold, Typeface.DEFAULT_BOLD)
    private val regular = typeface(R.font.inter_regular, Typeface.DEFAULT)

    /** Its 56 dp are kept while it loads (in the background), so nothing moves when it arrives. */
    internal val logoView = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    // Tracking as Woodruff's type scale has it: tighter as the size grows.
    internal val titleView = text(TITLE, 26f, TEXT, display, tracking = -0.014f)
    internal val bodyView = text(BODY, 16f, MUTED, regular, tracking = -0.006f).apply { setLineSpacing(dp(4).toFloat(), 1f) }
    internal val statusView = text("", 15f, ACCENT, semibold, tracking = -0.006f).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    internal val first = pill(CHECKING)
    internal val again = pill(TRY_AGAIN)
    internal val details = pill(WHAT_HAPPENED)
    internal val box = TextView(activity).apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(CODE)
        maxLines = BOX_LINES
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(BOX) }
    }
    internal val detailsGroup = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = GONE
        addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(text(FULL_REPORT, 13f, MUTED, regular, tracking = -0.002f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    /** The centred column (title to details), for tests to measure. */
    internal val column: LinearLayout
    private var detailsOpen = false
    private var logoFailed = false
    /** The build this TV's profile refused to install (the status line says so until something changes). */
    private var refusedFor: Int? = null
    private var scope: CoroutineScope? = null

    init {
        // A soft lift at the top fading into the background by 60 % of the height (the same colours at
        // even steps, so it's a straight fade on every Android version).
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(TOP, mix(1f / 3), mix(2f / 3), BASE, BASE, BASE))
        clipChildren = false
        val pills = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(first, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
            addView(again, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(12) })
            addView(details, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(12) })
        }
        column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(logoView, LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(24) })
            addView(titleView, wide())
            addView(bodyView, wide().apply { topMargin = dp(10) })
            addView(statusView, wide().apply { topMargin = dp(20) })
            addView(pills, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) })
            addView(detailsGroup, wide().apply { topMargin = dp(24) })
        }
        addView(column, LayoutParams(dp(600), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(text("Woodruff Family ${BuildConfig.VERSION_NAME}", 13f, MUTED, regular, tracking = -0.002f), LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(64)
            bottomMargin = dp(27)
        })

        // Focus can't wander off or get lost: three pills, each move spelled out.
        first.nextFocusLeftId = first.id
        first.nextFocusRightId = again.id
        again.nextFocusLeftId = first.id
        again.nextFocusRightId = details.id
        details.nextFocusLeftId = again.id
        details.nextFocusRightId = details.id
        for (p in listOf(first, again, details)) { p.nextFocusUpId = p.id; p.nextFocusDownId = p.id }

        first.setOnClickListener { onFirst() }
        again.setOnClickListener { tryAgain(activity) }
        details.setOnClickListener { toggleDetails() }

        render(Updater.state.value)
        first.requestFocus()
        loadInBackground()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch { Updater.state.collect { render(it) } }
        if (findFocus() == null) first.requestFocus()
        // The look for a fix starts as the screen opens.
        startLooking()
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        stopLooking()
        super.onDetachedFromWindow()
    }

    /** Every few minutes while the screen is up (the Updater spaces the checks); again as it comes back. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startLooking() else stopLooking()
    }

    private var looking = false

    private fun startLooking() {
        if (looking) return
        looking = true
        lookAgain.run()
    }

    private fun stopLooking() {
        looking = false
        removeCallbacks(lookAgain)
    }

    private val lookAgain = object : Runnable {
        override fun run() {
            runCatching { lookForFix(context, false) }
            postDelayed(this, LOOK_AGAIN_MS)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) leave(activity)
                return true
            }
            // Nothing to search here, and nothing above or below the pills.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SEARCH -> return true
            // Moved here rather than left to Android: the phone's remote hands keys to the screen
            // directly, past the part of Android that moves the focus.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) move(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                return true
            }
        }
        if (findFocus() == null && confirms(event.keyCode)) {
            // It shouldn't happen, but a press then brings the focus back rather than going nowhere.
            if (event.action == KeyEvent.ACTION_DOWN) first.requestFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** The focus one pill along ([step]: -1 left, 1 right), staying put at either end. */
    private fun move(step: Int) {
        val pills = listOf(first, again, details)
        val at = pills.indexOf(findFocus())
        pills[if (at < 0) 0 else (at + step).coerceIn(0, pills.size - 1)].requestFocus()
    }

    private fun confirms(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** Install the fix when it's ready, Check for a fix otherwise; nothing while it's checking or installing. */
    private fun onFirst() {
        val s = Updater.state.value
        if (look(s, refused(s)).busy) return
        if (s.step != Updater.Step.READY) {
            lookForFix(context, true)
            return
        }
        if (blockedByProfile(context)) {
            refusedFor = s.latest
            render(s)
            return
        }
        refusedFor = null
        install(context)
    }

    private fun refused(s: Updater.State) = s.step == Updater.Step.READY && refusedFor != null && s.latest == refusedFor

    private fun render(s: Updater.State) {
        if (!refused(s)) refusedFor = null
        val l = look(s, refused(s))
        statusView.text = l.status
        statusView.setTextColor(if (l.accent) ACCENT else MUTED)
        first.text = l.pill
    }

    /** What happened: the logo and the explanation make way for the crash summary; the focus stays put. */
    private fun toggleDetails() {
        detailsOpen = !detailsOpen
        logoView.visibility = if (detailsOpen || logoFailed) GONE else VISIBLE
        bodyView.visibility = if (detailsOpen) GONE else VISIBLE
        detailsGroup.visibility = if (detailsOpen) VISIBLE else GONE
        details.text = if (detailsOpen) HIDE_DETAILS else WHAT_HAPPENED
    }

    /** The crash summary and the logo, read off the main thread and shown when ready. */
    private fun loadInBackground() {
        val app = context.applicationContext
        runCatching {
            load(Runnable {
                // For the phone app's Last Crash on This TV, without a notice: this screen says what happened.
                runCatching { CrashLog.keep(app) }
                val summary = summary(runCatching { EarlyCrashes.report(app) }.getOrNull())
                val picture = runCatching { logo(app) }.getOrNull()
                post {
                    box.text = summary
                    if (picture != null) logoView.setImageBitmap(picture)
                    else {
                        logoFailed = true
                        logoView.visibility = GONE
                    }
                }
            })
        }.onFailure { box.text = NO_DETAILS }
    }

    private fun typeface(id: Int, fallback: Typeface): Typeface = runCatching { font(context, id) }.getOrNull() ?: fallback

    private fun text(words: String, sp: Float, color: Int, face: Typeface, tracking: Float = 0f) = TextView(activity).apply {
        text = words
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = face
        letterSpacing = tracking
        gravity = Gravity.CENTER_HORIZONTAL
        includeFontPadding = false
        // Centred lines of even length, rather than a long one and a word left over.
        breakStrategy = android.text.Layout.BREAK_STRATEGY_BALANCED
    }

    /**
     * A pill: white 20 % glass with a hairline rim at rest; solid white with ink words, a little larger,
     * when focused (as Woodruff's pills are). With the TV's animations off it simply snaps.
     */
    private fun pill(label: String) = TextView(activity).apply {
        id = View.generateViewId()
        // Nothing of this screen is carried into the app when it opens again.
        isSaveEnabled = false
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = semibold
        letterSpacing = -0.006f
        includeFontPadding = false
        maxLines = 1
        gravity = Gravity.CENTER
        setPadding(dp(24), 0, dp(24), 0)
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(INK, TEXT)))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), capsule(WHITE, 0))
            addState(intArrayOf(), capsule(PILL, PILL_RIM))
        }
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_focused), grow(FOCUSED_SCALE, 150))
            addState(intArrayOf(), grow(1f, 120))
        }
    }

    private fun capsule(fill: Int, rim: Int) = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(fill)
        if (rim != 0) setStroke(dp(1).coerceAtLeast(1), rim)
    }

    private fun grow(to: Float, ms: Long) = AnimatorSet().apply {
        playTogether(ObjectAnimator.ofFloat(null, View.SCALE_X, to), ObjectAnimator.ofFloat(null, View.SCALE_Y, to))
        duration = ms
        interpolator = DecelerateInterpolator()
    }

    private fun wide() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** What the status line and the first pill say for an [Updater.State]. */
    internal data class Look(val status: String, val accent: Boolean, val pill: String, val busy: Boolean)

    companion object {
        private const val TAG = "RecoveryView"

        const val TITLE = "Woodruff couldn’t open"
        const val BODY = "It closed twice while starting, so this simple screen is showing instead. A fixed version may already be waiting."
        const val INSTALL = "Install the fix"
        const val CHECK = "Check for a fix"
        const val CHECKING = "Checking…"
        const val INSTALLING = "Installing…"
        const val TRY_AGAIN = "Try opening again"
        const val WHAT_HAPPENED = "What happened"
        const val HIDE_DETAILS = "Hide details"
        const val NO_DETAILS = "Woodruff closed while starting, twice. No details were saved."
        const val FULL_REPORT = "The phone app shows the full report: Settings → Last Crash on This TV."

        /** The details box's most lines, and the characters that fit on one of them (12 sp monospace in 568 dp). */
        internal const val BOX_LINES = 8
        internal const val LINE_CHARS = 76
        /** Frames shown under the exception. */
        private const val FRAMES = 6
        private const val FOCUSED_SCALE = 1.06f
        /** How often the screen asks for a fix while it's up (each ask checks if one is due). */
        private const val LOOK_AGAIN_MS = 5 * 60_000L

        // The dark palette, fixed: this screen never follows the theme (one less thing to go wrong).
        private const val TOP = 0xFF10213A.toInt()
        private const val BASE = 0xFF060A12.toInt()
        private const val TEXT = 0xFFF2F6FA.toInt()
        private const val MUTED = 0xFF93A6BC.toInt()
        private const val ACCENT = 0xFF6FB3E6.toInt()
        private const val INK = 0xFF0B1220.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val PILL = 0x33FFFFFF
        private const val PILL_RIM = 0x26FFFFFF
        private const val BOX = 0x14FFFFFF
        private const val CODE = 0xFFB8C2CF.toInt()

        // What it does, through these so tests and the gallery can stand in their own ([resetForTest]).
        internal var font: (Context, Int) -> Typeface? = { c, id -> ResourcesCompat.getFont(c, id) }
        internal var logo: (Context) -> Bitmap? = { c ->
            BitmapFactory.decodeResource(c.resources, R.drawable.brand_logo, BitmapFactory.Options().apply { inSampleSize = 2 })
        }
        /** Runs the background read of the crash summary and the logo. */
        internal var load: (Runnable) -> Unit = { Thread(it, "recovery").start() }
        internal var lookForFix: (Context, Boolean) -> Unit = { c, manual -> Updater.lookForFix(c, manual) }
        internal var install: (Context) -> Unit = { c -> Updater.install(c, Updater.How.CHOSEN) }
        internal var blockedByProfile: (Context) -> Boolean = { c -> Updater.installsBlockedByProfile(c) }
        /** Back: Woodruff goes behind whatever was there before; opening it again shows this screen while the count stands. */
        internal var leave: (Activity) -> Unit = { it.moveTaskToBack(true) }
        /**
         * Try opening again: one more early close brings this screen back at once; then the main screen
         * starts over as usual. (Made again in place: it's a single-task screen, so starting it anew would
         * only hand this one the request.)
         */
        internal var tryAgain: (Activity) -> Unit = { a -> EarlyCrashes.tryAgain(a); a.recreate() }

        private val defaults = listOf(font, logo, load, lookForFix, install, blockedByProfile, leave, tryAgain)

        @Suppress("UNCHECKED_CAST")
        internal fun resetForTest() {
            font = defaults[0] as (Context, Int) -> Typeface?
            logo = defaults[1] as (Context) -> Bitmap?
            load = defaults[2] as (Runnable) -> Unit
            lookForFix = defaults[3] as (Context, Boolean) -> Unit
            install = defaults[4] as (Context) -> Unit
            blockedByProfile = defaults[5] as (Context) -> Boolean
            leave = defaults[6] as (Activity) -> Unit
            tryAgain = defaults[7] as (Activity) -> Unit
        }

        /** Puts the recovery screen up in [activity] (the main screen, instead of the app). */
        fun show(activity: Activity) = show(activity) { RecoveryView(it) }

        /** [show], with what builds the screen ([build]); if that fails, the plainest screen instead. */
        internal fun show(activity: Activity, build: (Activity) -> View) {
            val view = runCatching { build(activity) }.getOrElse { e ->
                Log.w(TAG, "the recovery screen fell back to its plainest form", e)
                runCatching { plain(activity) }.getOrElse { TextView(activity).apply { text = "$TITLE\n\n$BODY" } }
            }
            activity.setContentView(view)
        }

        /**
         * The plainest screen, when building the real one failed: the title and the explanation, and one
         * focusable Try opening again. The fix is still looked for (as at every start).
         */
        internal fun plain(activity: Activity): View {
            runCatching { lookForFix(activity, false) }
            val again = TextView(activity).apply {
                text = TRY_AGAIN
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(INK, TEXT)))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(WHITE))
                    addState(intArrayOf(), ColorDrawable(PILL))
                }
                gravity = Gravity.CENTER
                val d = activity.resources.displayMetrics.density
                setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (12 * d).toInt())
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setOnClickListener { tryAgain(activity) }
            }
            return LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(BASE)
                addView(TextView(activity).apply {
                    text = "$TITLE\n\n$BODY"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(TEXT)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, (28 * activity.resources.displayMetrics.density).toInt())
                })
                addView(again, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                again.requestFocus()
            }
        }

        /** What the status line and the first pill say for [s] (the spec's words), [refusedByProfile] after a refused Install. */
        internal fun look(s: Updater.State, refusedByProfile: Boolean = false): Look = when (s.step) {
            Updater.Step.IDLE, Updater.Step.CHECKING -> Look("Looking for a fixed version…", true, CHECKING, busy = true)
            Updater.Step.DOWNLOADING -> Look("Downloading the fix… ${(s.progress * 100).toInt().coerceIn(0, 100)}%", true, CHECKING, busy = true)
            Updater.Step.READY -> when {
                refusedByProfile -> Look("This TV’s profile doesn’t allow installing apps. Switch to the main profile, then try again.", false, INSTALL, busy = false)
                s.message != null -> Look("The fix didn’t install: ${curly(s.message).trimEnd('.')}.", false, INSTALL, busy = false)
                else -> Look((s.latestName?.let { "A fixed version ($it) is ready." } ?: "A fixed version is ready."), true, INSTALL, busy = false)
            }
            Updater.Step.INSTALLING -> Look("Installing… Woodruff opens again by itself.", true, INSTALLING, busy = true)
            Updater.Step.UP_TO_DATE -> Look("No fix yet. Woodruff checks again by itself every few minutes.", false, CHECK, busy = false)
            Updater.Step.ERROR ->
                if (s.offline) Look("Can’t check right now: this TV is offline.", false, CHECK, busy = false)
                else Look("Couldn’t check for a fix. Try again in a moment.", false, CHECK, busy = false)
        }

        /** Updater's words with typographic apostrophes, as everything on this screen has. */
        private fun curly(words: String) = words.replace('\'', '’')

        /**
         * The details box's text from a crash [report] ([CrashLog]'s: "Version … · time · thread …", then
         * the trace): the first line saying it happened while starting, the exception's first line, then
         * up to six lines of the trace — where it failed, then Woodruff's own frames and the causes, then
         * the frames nearest the top — in their order, each cut to one line. No report: [NO_DETAILS].
         */
        internal fun summary(report: String?): String {
            val lines = report?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (lines.size < 2) return NO_DETAILS
            val head = lines[0].let { h -> h.indexOf(" · thread ").let { at -> if (at >= 0) h.substring(0, at) else h } } + " · while starting"
            val trace = lines.drop(2).filter { !it.startsWith("...") }
            val picked = LinkedHashSet<Int>()
            if (trace.isNotEmpty()) picked += 0
            for (i in trace.indices) if (picked.size < FRAMES && (trace[i].contains("com.woodruff") || trace[i].startsWith("Caused by"))) picked += i
            for (i in trace.indices) if (picked.size < FRAMES) picked += i
            return (listOf(head, lines[1]) + picked.sorted().map { trace[it] }).joinToString("\n") { fit(it) }
        }

        private fun fit(line: String) = if (line.length <= LINE_CHARS) line else line.take(LINE_CHARS - 1) + "…"

        /** The top colour faded [f] of the way to the base. */
        private fun mix(f: Float): Int {
            fun ch(shift: Int) = (((TOP shr shift) and 0xFF) + (((BASE shr shift) and 0xFF) - ((TOP shr shift) and 0xFF)) * f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
