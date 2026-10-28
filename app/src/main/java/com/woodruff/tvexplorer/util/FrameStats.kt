package com.woodruff.tvexplorer.util

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/**
 * Counts how many frames each screen draws and how many of them were slow, on the real TV, so
 * "it feels laggy" can be pinned to a screen. A frame over 25 ms missed at least one refresh at
 * 60 Hz (a visible hitch); over 50 ms is a stutter. Kept in memory since the app started; shown in
 * Settings → Overview → Smoothness.
 */
object FrameStats {
    /** Which screen is on show; set by the UI. */
    @Volatile var screen: String = "Home"

    private class Counts {
        var frames = 0; var slow = 0; var stutters = 0
        /**
         * Where the slow frames' time went: the app's own work (input, composition, anything else
         * keeping it busy), layout, drawing, pictures being handed to the graphics chip, the chip itself.
         */
        val parts = LongArray(5)
    }
    private val PART_NAMES = arrayOf("app work", "layout", "drawing", "loading pictures", "graphics")
    private val counts = LinkedHashMap<String, Counts>()
    private val thread by lazy { HandlerThread("frame-stats").apply { start() } }

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        if (Build.VERSION.SDK_INT < 24) return@OnFrameMetricsAvailableListener
        // The first frame after a window appears includes inflating it: not a navigation hitch.
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
        val ms = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000
        synchronized(counts) {
            val c = counts.getOrPut(screen) { Counts() }
            c.frames++
            if (ms > 25) {
                c.slow++
                c.parts[0] += metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) +
                    metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + metrics.getMetric(FrameMetrics.ANIMATION_DURATION)
                c.parts[1] += metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                c.parts[2] += metrics.getMetric(FrameMetrics.DRAW_DURATION)
                c.parts[3] += metrics.getMetric(FrameMetrics.SYNC_DURATION)
                c.parts[4] += metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) + metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
            }
            if (ms > 50) c.stutters++
        }
    }

    fun attach(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 24) runCatching { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
    }

    fun detach(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 24) runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
    }

    /**
     * "Movies 12% slow (9 stutters, mostly loading pictures) · Home 4% slow", worst first: which
     * screen, and what its slow frames were spent on. Screens with too few frames to judge are left out.
     */
    fun summary(): String = synchronized(counts) {
        counts.filter { it.value.frames >= 60 }
            .entries.sortedByDescending { it.value.slow * 1f / it.value.frames }
            .joinToString(" · ") { (name, c) ->
                val cause = c.parts.indices.maxByOrNull { c.parts[it] }?.takeIf { c.slow > 0 }?.let { PART_NAMES[it] }
                val detail = listOfNotNull(c.stutters.takeIf { it > 0 }?.let { "$it stutters" }, cause?.let { "mostly $it" })
                "$name ${c.slow * 100 / c.frames}% slow" + (if (detail.isNotEmpty()) " (${detail.joinToString(", ")})" else "")
            }
            .ifBlank { "Not enough yet: use the app a little, then look again" }
    }

    fun reset() = synchronized(counts) { counts.clear() }
}
