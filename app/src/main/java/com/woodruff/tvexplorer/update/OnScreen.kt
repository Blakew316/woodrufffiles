package com.woodruff.tvexplorer.update

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.Window
import androidx.core.content.ContextCompat
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.util.Presence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which of Woodruff's screens is up, for the updater: whether someone is using it, and what to put
 * back after an update ([Restore]). Installed once, as the app starts ([install]); every Woodruff
 * screen is followed without any code of its own:
 *
 *  - Each activity's window reports its key presses to [Presence] (its window callback is wrapped as
 *    the activity is created), so the player, the photo viewer, the slideshow and the doorbell count
 *    as use just as the main window does.
 *  - The screen on top, and the ones under it in Woodruff's task, are followed as they start and stop.
 *  - When the TV's screensaver starts or the screen goes off with a Woodruff screen up, that screen
 *    still counts as on screen ([Showing]): a paused film under the screensaver comes back after an
 *    update, while a Woodruff left for another app doesn't open over it.
 */
object OnScreen : Application.ActivityLifecycleCallbacks {
    /** Time that only moves forward. */
    private val showing = Showing { SystemClock.elapsedRealtime() }
    private val main = Handler(Looper.getMainLooper())

    /** Follows every Woodruff screen from now on, and the screen and the TV's screensaver coming and going. */
    fun install(app: Application) {
        // Once per run of the app; from nothing (a test's fresh app must not keep the last one's screens).
        showing.reset()
        app.registerActivityLifecycleCallbacks(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        }
        // System broadcasts only (they reach a receiver that isn't exported).
        runCatching { ContextCompat.registerReceiver(app, system, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    private val system = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_DREAMING_STARTED -> showing.coveredBySystem()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_DREAMING_STOPPED -> showing.uncovered()
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activity.window?.let(::countKeys)
        showing.created(activity, activity.taskId)
    }

    override fun onActivityStarted(activity: Activity) {
        // Whoever brought it up is about to use it: an update doesn't restart it under them.
        Presence.used()
        showing.started(activity)
    }

    /**
     * What's about to start is being put back after an update ([Restore.reopen]): until it's been
     * seen, it stays on screen even if Android stops it at once (started behind the TV's screensaver
     * or with the screen off), so an update coming before anyone's back puts it back again. Any thread:
     * posted ahead of the activities' own start.
     */
    fun restoring() { main.post { showing.restoring() } }

    override fun onActivityStopped(activity: Activity) = showing.stopped(activity, activity.isFinishing)
    override fun onActivityDestroyed(activity: Activity) = showing.destroyed(activity)
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /** Woodruff's player, if it's what's up: playing (never interrupted) or paused (it may be, and comes back). */
    enum class Film { NONE, PLAYING, PAUSED }

    /**
     * What's on screen now, for an update deciding whether to go ahead: what to put back afterwards
     * (null: Woodruff isn't on screen, so it doesn't open again), whether that's Woodruff's player
     * and playing, and whether the TV's screen is on at all.
     */
    data class Moment(val route: Restore.Route?, val film: Film, val screenOn: Boolean)

    /** [Moment] now. Main thread (the screens are asked what they show). */
    fun moment(c: Context): Moment {
        val screenOn = runCatching { c.getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull() ?: true
        val top = showing.top() ?: return Moment(null, Film.NONE, screenOn)
        // The top screen that can say what it is (the doorbell's camera can't: what's under it comes
        // back), and the main window under it (the screen that says it's one of the main window's),
        // which Back from it returns to.
        val stack = showing.stackFrom(top).mapNotNull { it.key as? Activity }.filter { !it.isFinishing }
        val shown = stack.firstOrNull { it is Restore.Restorable }
        val route = shown?.let(::routeOf)
        val under = if (route is Restore.App) null
        else stack.drop(stack.indexOf(shown) + 1).firstNotNullOfOrNull { routeOf(it) as? Restore.App }
        val full = when (route) {
            is Restore.Film -> route.copy(under = under)
            is Restore.Photo -> route.copy(under = under)
            is Restore.Slideshow -> route.copy(under = under)
            is Restore.App -> route
            // A screen that couldn't say what it shows: the main window, as it was if it's there. Nothing
            // that can say (only the doorbell's camera, opened from the TV's screensaver): nothing.
            null -> under ?: if (shown != null) Restore.App(Screen.HOME) else null
        }
        val film = when {
            route !is Restore.Film -> Film.NONE
            route.playing && showing.isStarted(shown) -> Film.PLAYING
            else -> Film.PAUSED
        }
        return Moment(full, film, screenOn)
    }

    private fun routeOf(a: Activity): Restore.Route? = runCatching { (a as? Restore.Restorable)?.restoreRoute() }.getOrNull()

    /** [moment] from any thread; null when the main thread didn't answer in time (then nothing installs). */
    suspend fun momentNow(c: Context): Moment? =
        if (Looper.myLooper() == Looper.getMainLooper()) moment(c)
        else withTimeoutOrNull(2_000) { withContext(Dispatchers.Main) { moment(c) } }

    /** What to put back after an update started now (by someone pressing Install, say); main thread. */
    fun routeNow(c: Context): Restore.Route? = runCatching { moment(c).route }.getOrNull()

    /**
     * Whether [main] starting now is someone coming back to it from another of Woodruff's own screens
     * (the player at the end of a film, the photo viewer, the doorbell's camera): the middle of using
     * Woodruff, not opening it, so no update is offered over it. (That screen hasn't stopped yet as
     * [main] starts.) Woodruff's slideshow doesn't count: it only ran because nobody was using it.
     * Main thread, from [main]'s start.
     */
    fun backFromInside(main: Activity): Boolean = showing.startedBesides(main) { it is com.woodruff.tvexplorer.screensaver.SlideshowActivity }

    /** Every key in [window] counts as use (see [Presence]); the activity gets them as before. */
    internal fun countKeys(window: Window) {
        val callback = window.callback ?: return
        if (callback !is KeyCounter) window.callback = KeyCounter(callback)
    }

    /**
     * An activity's window callback that tells [Presence] about each key press (and tap) before
     * handing it to the activity, about other input (a pointer moving), and about the window getting
     * the focus back.
     */
    internal class KeyCounter(private val activity: Window.Callback) : Window.Callback by activity {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean { Presence.pressed(); return activity.dispatchKeyEvent(event) }
        override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean { Presence.pressed(); return activity.dispatchKeyShortcutEvent(event) }
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) Presence.pressed() else Presence.used()
            return activity.dispatchTouchEvent(event)
        }
        override fun dispatchTrackballEvent(event: MotionEvent): Boolean { Presence.used(); return activity.dispatchTrackballEvent(event) }
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean { Presence.used(); return activity.dispatchGenericMotionEvent(event) }

        override fun onWindowFocusChanged(hasFocus: Boolean) {
            // On screen for someone to see (not behind the TV's screensaver, the screen not off).
            if (hasFocus) { Presence.used(); showing.seen() }
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

/**
 * Which of Woodruff's screens counts as on screen, from their starts and stops and the TV's: the one
 * started last; or, once none is, the one the TV's screensaver or its screen going off covered (it
 * comes back as soon as the screensaver goes, unless the TV wakes into something else). A Woodruff
 * left for another app or the TV's Home isn't. Main thread; [clock] is time that only moves forward.
 * Visible for tests.
 */
internal class Showing(private val clock: () -> Long) {
    /** A live screen ([key]: its activity) and the task it's in. */
    class Entry(val key: Any, val task: Int)

    /** Live screens, oldest first: in one task, also bottom to top. */
    private val live = ArrayList<Entry>()
    /** Started ones, in the order they started. */
    private val started = LinkedHashSet<Any>()
    /** The last one to stop with nothing of Woodruff's still up (not one closed with Back), and when. */
    private var left: Entry? = null
    private var leftAt = 0L
    /** Covered by the screensaver or the screen going off, and when that stops counting (after the TV woke). */
    private var covered: Entry? = null
    private var coverEndsAt = Long.MAX_VALUE
    /** Put back after an update and not seen yet ([restoring]). */
    private var restoring = false

    fun created(key: Any, task: Int) { if (entry(key) == null) live += Entry(key, task) }

    fun started(key: Any) {
        started.remove(key)
        started += key
        covered = null
        coverEndsAt = Long.MAX_VALUE
    }

    fun stopped(key: Any, finishing: Boolean) {
        if (!started.remove(key) || started.isNotEmpty()) return
        left = if (finishing) null else entry(key)
        leftAt = clock()
        // Put back unseen and stopped at once: behind the TV's screensaver, or with the screen off.
        if (restoring && covered == null) left?.let { covered = it; coverEndsAt = Long.MAX_VALUE }
    }

    /**
     * What starts next is being put back after an update. Android may start it behind the TV's
     * screensaver or with the screen off, where it stops at once: until it's [seen], or the TV wakes
     * into something else, it stays on screen as the screen it replaced was.
     */
    fun restoring() { restoring = true }

    /** A screen's window got the focus: someone can see it, so from now on leaving it is leaving. */
    fun seen() { restoring = false }

    fun destroyed(key: Any) {
        val e = entry(key) ?: return
        val at = live.indexOf(e)
        live.removeAt(at)
        started.remove(key)
        // What was under it in its task takes its place (the doorbell's camera closing over the slideshow).
        val below = live.subList(0, at).lastOrNull { it.task == e.task }
        if (covered === e) covered = below
        if (left === e) left = below
    }

    /** The screen went off, or the TV's screensaver started: what's up (or just was) stays on screen meanwhile. */
    fun coveredBySystem() {
        val now = clock()
        expire(now)
        if (covered != null) { coverEndsAt = Long.MAX_VALUE; return }
        covered = started.lastOrNull()?.let(::entry) ?: left?.takeIf { now - leftAt <= LEFT_GRACE_MS }
    }

    /** The screen came on, or the screensaver stopped: the covered screen counts until the TV has woken into something else. */
    fun uncovered() {
        restoring = false
        if (covered != null && coverEndsAt == Long.MAX_VALUE) coverEndsAt = clock() + WAKE_GRACE_MS
    }

    /** The screen that counts as on screen; null when Woodruff isn't. */
    fun top(): Entry? {
        expire(clock())
        return started.lastOrNull()?.let(::entry) ?: covered
    }

    /** [top] and the live screens under it in its task, top first. */
    fun stackFrom(top: Entry): List<Entry> {
        val at = live.indexOf(top)
        if (at < 0) return emptyList()
        return live.subList(0, at + 1).filter { it.task == top.task }.asReversed()
    }

    fun isStarted(key: Any?): Boolean = key != null && key in started

    /** Whether a screen other than [key] (and not one [except] lets pass) is started too. */
    fun startedBesides(key: Any, except: (Any) -> Boolean = { false }): Boolean = started.any { it !== key && !except(it) }

    fun reset() {
        live.clear(); started.clear(); left = null; covered = null; coverEndsAt = Long.MAX_VALUE; restoring = false
    }

    private fun entry(key: Any): Entry? = live.firstOrNull { it.key === key }

    private fun expire(now: Long) {
        if (now >= coverEndsAt) { covered = null; coverEndsAt = Long.MAX_VALUE }
    }

    companion object {
        /** A screen that stopped this shortly before the screen went off (or the other way round) was what the TV showed. */
        const val LEFT_GRACE_MS = 2_000L
        /** After the TV wakes, the covered screen comes back within this, or the TV woke into something else. */
        const val WAKE_GRACE_MS = 10_000L
    }
}
