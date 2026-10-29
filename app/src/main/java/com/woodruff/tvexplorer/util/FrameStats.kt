package com.woodruff.tvexplorer.util

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window

/**
 * Counts the frames each screen draws on the real TV, and how many were late, slow or stutters, so "it
 * feels laggy" can be pinned to a screen and to a phase of the frame. Frames drawn within a second of
 * a remote key count as navigating, the rest as idle (camera video, animations running by
 * themselves), so a screen that plays video neither hides its navigation hitches nor gets blamed for
 * the video's own frames. Kept in memory since the app started (or since "OK starts counting again"),
 * shown in Settings → Advanced → Smoothness, and in full in the phone app's Smoothness Report
 * ([report]), with a 3-minute recording of where the main thread spends long stretches
 * ([MainThreadWatch]).
 */
object FrameStats {
    /** Which screen is on show; set by the UI. */
    @Volatile var screen: String = "Home"

    private val lock = Any()
    private val screens = LinkedHashMap<String, ScreenFrames>()
    /** How long each remote press (and held repeat) kept the main thread busy. */
    private var keys = Durations()
    private var countingSince = SystemClock.elapsedRealtime()
    /** The frames counted during the last finished recording, for its report. */
    private var recorded: CountedFrames? = null

    /**
     * The last remote keys' times (uptime ms, as KeyEvent has them), newest at [keyNext] - 1: written
     * on the main thread, read on the frame-stats one, both under [lock]. Sixteen, so a slow frame
     * reported after several more key repeats still finds the key it followed.
     */
    private val keyTimes = LongArray(16)
    private var keyNext = 0

    /** The display's refresh interval: a frame's deadline before Android 12 (which gives one per frame). */
    @Volatile var frameIntervalNs = 16_666_667L
        private set
    private var displayWatched = false

    private val thread by lazy { HandlerThread("frame-stats").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    /** The frame's phases, refilled for each frame on the frame-stats thread (so no allocation). */
    private val phases = LongArray(Phase.COUNT)

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> onFrame(metrics) }

    private fun onFrame(m: FrameMetrics) {
        // The first frame after a window appears includes inflating it: not a navigation hitch.
        if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return
        val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
        if (total <= 0) return
        val p = phases
        p[Phase.WAITING] = nonNegative(m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION))
        p[Phase.INPUT] = nonNegative(m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION))
        p[Phase.ANIMATION] = nonNegative(m.getMetric(FrameMetrics.ANIMATION_DURATION))
        p[Phase.LAYOUT] = nonNegative(m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
        p[Phase.DRAW] = nonNegative(m.getMetric(FrameMetrics.DRAW_DURATION))
        p[Phase.SYNC] = nonNegative(m.getMetric(FrameMetrics.SYNC_DURATION))
        p[Phase.GRAPHICS] = nonNegative(m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)) +
            nonNegative(m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION))
        // FrameMetrics' phases leave a gap: the frame waiting for the render thread to take it.
        var known = 0L
        for (i in 0 until Phase.RENDER_QUEUE) known += p[i]
        p[Phase.RENDER_QUEUE] = nonNegative(total - known)
        var deadline = frameIntervalNs
        if (Build.VERSION.SDK_INT >= 31) {
            p[Phase.GPU] = nonNegative(m.getMetric(FrameMetrics.GPU_DURATION))
            val own = m.getMetric(FrameMetrics.DEADLINE)
            if (own > 0) deadline = own
        } else {
            p[Phase.GPU] = -1
        }
        // When the frame's own work began (the vsync it was meant for, plus the wait for the main thread):
        // it can only show a key that came before then.
        val startMs = if (Build.VERSION.SDK_INT >= 26) {
            (m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) + p[Phase.WAITING]) / 1_000_000
        } else {
            SystemClock.uptimeMillis() - (total - p[Phase.WAITING]) / 1_000_000 // reported just after it ended
        }
        val name = screen
        synchronized(lock) {
            val s = screens.getOrPut(name) { ScreenFrames(name) }
            (if (isNavigating(startMs, keyTimes)) s.navigating else s.idle).add(total, deadline, p)
        }
    }

    private fun nonNegative(ns: Long): Long = if (ns > 0) ns else 0

    fun attach(activity: Activity) {
        if (Build.VERSION.SDK_INT < 24) return
        runCatching { activity.window.addOnFrameMetricsAvailableListener(listener, handler) }
        runCatching { watchDisplay(activity.applicationContext) }
    }

    fun detach(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 24) runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
    }

    /**
     * Keeps [frameIntervalNs] right when the display changes mode (frame-rate matching switches it to
     * 24 or 50 Hz for a film). Once per app run.
     */
    private fun watchDisplay(context: Context) {
        if (displayWatched) return
        val dm = context.getSystemService(DisplayManager::class.java) ?: return
        displayWatched = true
        val read = {
            val hz = dm.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f
            if (hz >= 10f) frameIntervalNs = (1e9 / hz).toLong()
        }
        read()
        dm.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) read()
            }
        }, handler)
    }

    /**
     * A remote key reaching the app (MainActivity.dispatchKeyEvent): the frames after it count as
     * navigating, and while it's handled the main thread counts as busy for the recording (Android
     * hands keys over outside any message, where the recording's Printer can't see them). Returns
     * when handling began, for [keyFinished]. Main thread.
     */
    fun keyStarted(event: KeyEvent): Long {
        synchronized(lock) { keyTimes[keyNext++ and (keyTimes.size - 1)] = event.eventTime }
        val now = System.nanoTime()
        MainThreadWatch.keyStarted(now)
        return now
    }

    /** The key [keyStarted] returned [started] for has been handled. Main thread. */
    fun keyFinished(event: KeyEvent, started: Long) {
        val ns = System.nanoTime() - started
        MainThreadWatch.keyFinished(started, ns)
        if (event.action == KeyEvent.ACTION_DOWN) synchronized(lock) { keys.add(ns) }
    }

    /**
     * Settings' Smoothness row, from the navigating frames (see [navigatingSummary]): "While navigating:
     * Movies 12% slow (9 stutters, mostly loading pictures) · Home 4% slow". During a recording it says
     * how long is left; after one, that the full report is on the phone.
     */
    fun summary(): String {
        val text = synchronized(lock) { navigatingSummary(screens.values.toList()) }
        val r = MainThreadWatch.state()
        return when {
            r.running -> "Recording… ${clock(r.leftMs)} left · $text"
            r.finished -> "$text · Full report on your phone"
            else -> text
        }
    }

    /**
     * Starts counting again, and records the next 3 minutes in detail for the phone's Smoothness Report
     * (Settings' "OK starts counting again", and Record in the phone app).
     */
    fun reset() {
        synchronized(lock) {
            screens.clear()
            keys = Durations()
            countingSince = SystemClock.elapsedRealtime()
            recorded = null
        }
        MainThreadWatch.start()
    }

    /** The report for the phone (plain text). Off the main thread. */
    fun report(context: Context): String = SmoothnessReport.build(context.applicationContext)

    /** The frames for the report: the last finished recording's, or else those counted so far. */
    internal fun counted(): CountedFrames {
        val finished = MainThreadWatch.state().finished
        return synchronized(lock) { recorded?.takeIf { finished } ?: snapshot(fromRecording = false) }
    }

    /** The recording's 3 minutes are up: its frames are kept as they are now for its report. */
    internal fun recordingFinished() = synchronized(lock) { recorded = snapshot(fromRecording = true) }

    private fun snapshot(fromRecording: Boolean) = CountedFrames(
        screens.values.map { it.copy() }, keys.copy(), SystemClock.elapsedRealtime() - countingSince, fromRecording,
    )
}
