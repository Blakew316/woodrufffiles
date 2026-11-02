package com.woodruff.tvexplorer.util

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import coil.ImageLoader
import com.woodruff.tvexplorer.nest.CameraGl
import com.woodruff.tvexplorer.remote.TvScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Gives memory back to the TV's other apps while Woodruff isn't on screen.
 *
 * Woodruff keeps running behind other apps (the phone connection is a foreground service), so Android
 * never treats it as an app in the background and never asks it to let go of anything:
 * TRIM_MEMORY_UI_HIDDEN and BACKGROUND only reach apps without such a service, and Android 14 sends no
 * RUNNING_* levels at all. So Woodruff sees to it itself. Once none of its screens has been up for
 * [AFTER_MS] (another app is in front, or the TV's home, its screensaver or standby), it lets go of:
 *
 *  - the posters, banners and backdrops kept decoded for scrolling back ([images]: Coil's memory cache);
 *  - what the phone's TV Screen copies pictures into ([TvScreen.release]);
 *  - the camera tiles' graphics thread ([CameraGl.trim]): the tiles on the screen that was left let go
 *    of their own graphics as it stops, with their streams, and none holds it then (they're set up
 *    again as the screen comes back).
 *
 * It does at once if Android says memory is short while Woodruff is off screen (Android 9 to 13).
 * Everything comes back by itself when it's needed: a screen's pictures decode again from the disk,
 * and the tiles' thread starts again with the first tile. The screen that was left up keeps the
 * pictures it shows, so coming back to it looks exactly as it was left. While Woodruff is on screen,
 * Coil's own trimming (it halves or clears its cache when Android asks) is left as it is.
 */
object LeanMemory : Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {
    /** How long Woodruff is off screen before it gives its memory back. */
    const val AFTER_MS = 10 * 60_000L

    /** The app's image loader, once it's made (TvExplorerApp.newImageLoader): its memory cache is let go. */
    @Volatile var images: ImageLoader? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How many of Woodruff's screens are started. Written on the main thread. */
    @Volatile private var started = 0

    private val trimLater = Runnable { trimNow() }

    /** Follows Woodruff's screens and Android's memory warnings from the app's start (once per run of the app). */
    fun install(app: Application) {
        // From nothing: a test's fresh app mustn't keep the last one's screens.
        started = 0
        main.removeCallbacks(trimLater)
        app.registerActivityLifecycleCallbacks(this)
        app.registerComponentCallbacks(this)
    }

    /** Whether one of Woodruff's screens is up now. */
    val onScreen: Boolean get() = started > 0

    override fun onActivityStarted(activity: Activity) {
        started++
        main.removeCallbacks(trimLater)
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) {
            main.removeCallbacks(trimLater)
            main.postDelayed(trimLater, AFTER_MS)
        }
    }

    override fun onTrimMemory(level: Int) {
        if (started == 0 && trimsAtOnce(level)) trimNow()
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        if (started == 0) trimNow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    /** When the last trim asked for a collection (uptime ms); written on [scope]'s threads. */
    @Volatile private var collectedAt = -COLLECT_GAP_MS

    /** Lets go of it all now, in the background. */
    internal fun trimNow() {
        main.removeCallbacks(trimLater)
        val loader = images
        scope.launch {
            // Back on screen by now (it was asked for just as someone came back): its posters stay.
            if (onScreen) return@launch
            runCatching { loader?.memoryCache?.clear() }
            TvScreen.release()
            CameraGl.trim()
            // What was let go is only given back once the objects holding it are collected, and the
            // Java heap of a Woodruff idling behind another app may not be for hours. Once a minute at
            // most: Android can warn of short memory again and again, and each collection costs the
            // TV's CPU just when the app in front needs it.
            val now = SystemClock.uptimeMillis()
            if (now - collectedAt >= COLLECT_GAP_MS) {
                collectedAt = now
                Runtime.getRuntime().gc()
            }
        }
    }

    /** The least time between two collections a trim asks for. */
    private const val COLLECT_GAP_MS = 60_000L

    /**
     * Whether Android's memory warning [level], arriving while Woodruff is off screen, means memory is
     * short now (give it back at once) rather than that its screens have just gone (the timer sees to
     * that). Visible for tests.
     */
    @Suppress("DEPRECATION")
    internal fun trimsAtOnce(level: Int): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
}
