package com.woodruff.tvexplorer

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * How Woodruff's main window is opened, so that coming back to Woodruff returns to where it was
 * (browsing-motion §3.4).
 *
 * MainActivity is `singleTop`, not `singleTask`: opening Woodruff from the TV's app row brings its
 * task back as it was — the paused film over the screen it was started from, the photo viewer, any
 * screen with its focus and scroll — instead of closing everything above the main window. Android
 * only does that when the task still answers to the app row's intent (`MAIN` + the launcher
 * category: `Task.isSameIntentFilter`). Two things keep it so:
 *  - every intent Woodruff opens its main window with carries that same identity ([open], [resume]),
 *    so the task it starts, or re-roots with CLEAR_TOP, still matches the app row;
 *  - should a task not match anyway (opened from a phone launcher's category, or by an older
 *    intent), Android starts a second main window on top for the app row, and MainActivity closes
 *    it at once ([isRepeatLaunch]), so the task shows what it had.
 */
object MainIntents {
    /**
     * The main window at [screen] ([MainActivity.EXTRA_OPEN]: "notifications", "home", a route put
     * back after an update): a notification's tap, the phone's Home key. Whatever is over the main
     * window (the player, the photo viewer) closes, and the main window gets the route in
     * onNewIntent; a new task starts only when Woodruff has none.
     */
    fun open(c: Context, screen: String? = null): Intent =
        launcherLike(c)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (screen != null) putExtra(MainActivity.EXTRA_OPEN, screen) }

    /**
     * Woodruff as it was, as the TV's app row opens it: its task to the front unchanged (the phone
     * connection's notification, reopening after an update with nothing to put back).
     */
    fun resume(c: Context): Intent = launcherLike(c)

    /**
     * Whether a main window started with [intent] is a second one the app row started on top of
     * Woodruff's task ([isTaskRoot] false: there's something under it), to be closed at once so the
     * task shows what it had. Never one opened for a screen ([MainActivity.EXTRA_OPEN]), which is
     * there on purpose (a notification tapped while a film opened from the phone is up, say).
     */
    fun isRepeatLaunch(isTaskRoot: Boolean, intent: Intent?): Boolean {
        if (isTaskRoot || intent == null || intent.action != Intent.ACTION_MAIN) return false
        if (intent.hasExtra(MainActivity.EXTRA_OPEN)) return false
        return intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) || intent.hasCategory(Intent.CATEGORY_LAUNCHER)
    }

    /**
     * Whether Back at the main window's last level (Home's side menu) should put Woodruff in the
     * background itself: before Android 12 ([sdk] < 31) Android finishes the main window instead,
     * throwing away every screen and both libraries, so the next opening rebuilt it all and listed
     * the libraries again. Android 12 and later move the task to the back by themselves.
     */
    fun backKeepsTask(sdk: Int): Boolean = sdk < 31

    /**
     * The main window's last-in-line Back where [backKeepsTask]: Woodruff to the background, its
     * task and everything in it kept. A main window opened over something else of Woodruff's (a film
     * the phone started) isn't the task's root: Back there closes it as before, back to the film.
     */
    fun keepTaskOnBack(activity: ComponentActivity): OnBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (activity.isTaskRoot) {
                activity.moveTaskToBack(true)
                return
            }
            isEnabled = false
            activity.onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
    }

    /** The app row's identity (MAIN, the TV launcher's category) for the main window, as a new task. */
    private fun launcherLike(c: Context): Intent =
        Intent(c, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
