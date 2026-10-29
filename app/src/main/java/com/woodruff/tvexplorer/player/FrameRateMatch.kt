package com.woodruff.tvexplorer.player

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlin.math.abs
import kotlin.math.round

/**
 * "Match frame rate" (Settings → Appearance): show a 24-frame film at 24 Hz instead of 60. Off by default.
 * A TV box can't change its refresh rate without the picture going black for a second or two, so
 * that happens as a film starts, and again on leaving the player (the window's choice goes with it).
 */
object FrameRateMatch {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("player", Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean("matchFrameRate", false)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("matchFrameRate", on).apply() }

    /** Shorter clips (trailers, home videos) aren't worth the black screen of a switch. */
    const val MIN_LENGTH_MS = 5 * 60_000L

    /** A display mode, as far as choosing one goes. */
    data class Mode(val id: Int, val width: Int, val height: Int, val hz: Float)

    /**
     * The mode for video at [fps]: the current resolution, at the lowest refresh rate that is a whole
     * multiple of it (23.976 → 23.976 or 47.95; 24 → 24 or 48). Only when there's no exact one, a
     * multiple 0.1 % off (24 Hz for 23.976: a frame shown twice every 40 s or so, rather than judder
     * all the time). Null when the current mode already fits, or nothing does.
     */
    fun pick(modes: List<Mode>, current: Mode, fps: Float): Mode? {
        if (!(fps > 0f)) return null
        val sameSize = modes.filter { it.width == current.width && it.height == current.height }
        for (tolerance in floatArrayOf(EXACT, NEAR)) {
            if (offBy(current.hz, fps) <= tolerance) return null
            sameSize.filter { offBy(it.hz, fps) <= tolerance }.minByOrNull { it.hz }?.let { return it }
        }
        return null
    }

    /** How far [hz] is from a whole multiple of [fps], as a fraction (0 = exactly one). */
    private fun offBy(hz: Float, fps: Float): Float {
        val ratio = hz / fps
        val n = round(ratio)
        return if (n >= 1f) abs(ratio - n) / n else Float.MAX_VALUE
    }

    // 23.976 and 24 are 0.1 % apart: exact means well within that, near means about that.
    private const val EXACT = 0.0005f
    private const val NEAR = 0.0015f
    private const val TIMEOUT_MS = 4_000L
    /** After the switch, the TV takes a moment more to show a picture again. */
    private const val SETTLE_MS = 1_000L

    /**
     * Switches [activity]'s screen to the mode for a film and says when it's done. One at a time: a
     * new [start], or [cancel], drops the one before without calling it back.
     */
    class Switch(private val activity: Activity) {
        private val main = Handler(Looper.getMainLooper())
        private var listener: DisplayManager.DisplayListener? = null
        private var then: (() -> Unit)? = null
        private val finish = Runnable {
            stopListening()
            val t = then
            then = null
            t?.invoke()
        }

        /**
         * Asks for the mode that suits [fps]; [then] runs once the display has switched (or after
         * 4 s, if it never says). False, and [then] never runs, when there's nothing to switch.
         */
        fun start(fps: Float, then: () -> Unit): Boolean {
            cancel()
            val display = display() ?: return false
            val target = pick(display.supportedModes.map { mode(it) }, mode(display.mode), fps) ?: return false
            val manager = activity.getSystemService(DisplayManager::class.java) ?: return false
            this.then = then
            listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId != display.displayId) return
                    // That mode, or one just like it.
                    val now = mode(display.mode)
                    val switched = now.id == target.id ||
                        (now.width == target.width && now.height == target.height && abs(now.hz - target.hz) < 0.01f)
                    if (!switched) return
                    stopListening()
                    main.removeCallbacks(finish)
                    main.postDelayed(finish, SETTLE_MS)
                }
            }.also { manager.registerDisplayListener(it, main) }
            activity.window.attributes = activity.window.attributes.also { it.preferredDisplayModeId = target.id }
            main.postDelayed(finish, TIMEOUT_MS)
            return true
        }

        fun cancel() {
            stopListening()
            main.removeCallbacks(finish)
            then = null
        }

        private fun stopListening() {
            listener?.let { activity.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it) }
            listener = null
        }

        private fun display(): Display? =
            if (Build.VERSION.SDK_INT >= 30) activity.display else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay

        private fun mode(m: Display.Mode) = Mode(m.modeId, m.physicalWidth, m.physicalHeight, m.refreshRate)
    }
}
