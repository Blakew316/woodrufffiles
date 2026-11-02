package com.woodruff.tvexplorer.update

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.Window
import com.woodruff.tvexplorer.util.Presence

/**
 * Which of Woodruff's screens is up, for the updater: whether someone is using it. Installed once,
 * as the app starts ([install]); every Woodruff screen is followed without any code of its own:
 * each activity's window reports its key presses to [Presence] (its window callback is wrapped as
 * the activity is created), so the player, the photo viewer, the slideshow and the doorbell count as
 * use just as the main window does; and a screen coming up counts as use too.
 */
object OnScreen : Application.ActivityLifecycleCallbacks {
    /** Follows every Woodruff screen from now on. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activity.window?.let(::countKeys)
    }

    override fun onActivityStarted(activity: Activity) {
        // Whoever brought it up is about to use it: an update doesn't restart it under them.
        Presence.arrived()
    }

    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /** Every key in [window] counts as use (see [Presence]); the activity gets them as before. */
    internal fun countKeys(window: Window) {
        val callback = window.callback ?: return
        if (callback !is KeyCounter) window.callback = KeyCounter(callback)
    }

    /**
     * An activity's window callback that tells [Presence] about each key press (and click or touch)
     * before handing it to the activity, and about the window getting the focus back.
     */
    internal class KeyCounter(private val activity: Window.Callback) : Window.Callback by activity {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean { Presence.pressed(); return activity.dispatchKeyEvent(event) }
        override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean { Presence.pressed(); return activity.dispatchKeyShortcutEvent(event) }
        override fun dispatchTouchEvent(event: MotionEvent): Boolean { Presence.pressed(); return activity.dispatchTouchEvent(event) }
        override fun dispatchTrackballEvent(event: MotionEvent): Boolean { Presence.pressed(); return activity.dispatchTrackballEvent(event) }
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean { Presence.pressed(); return activity.dispatchGenericMotionEvent(event) }

        override fun onWindowFocusChanged(hasFocus: Boolean) {
            if (hasFocus) Presence.arrived()
            activity.onWindowFocusChanged(hasFocus)
        }

        // Interface methods with a body of their own: handed on too, whatever the compiler does with them.
        override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
            activity.onProvideKeyboardShortcuts(data, menu, deviceId)

        override fun onPointerCaptureChanged(hasCapture: Boolean) {
            if (Build.VERSION.SDK_INT >= 26) activity.onPointerCaptureChanged(hasCapture)
        }
    }
}
