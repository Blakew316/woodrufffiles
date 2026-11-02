package com.woodruff.tvexplorer.ui.motion

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.woodruff.tvexplorer.ui.SearchKey
import com.woodruff.tvexplorer.update.OnScreen
import com.woodruff.tvexplorer.util.FrameStats
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Woodruff's pop-ups: title pages, menus, confirmations, the filters, search. Each is a window of its
 * own over the activity that opened it, so that activity's window never sees its keys or its frames.
 * While one is up it's registered here (by [dpadGate], which every Dialog root has), and then:
 *
 *  - The phone's remote presses keys where the TV's remote would: in the pop-up with the focus (so Back
 *    closes it and OK presses Play on a title page). The phone's TV Screen shows it over the screen it
 *    opened on ([com.woodruff.tvexplorer.remote.TvScreen]).
 *  - Its keys get what the main window's get from MainActivity.dispatchKeyEvent ([Keys]): held arrows
 *    gated ([DpadGate]), each press timed for Settings → Smoothness and counted as use, and the remote's
 *    Search button opening Search ([SearchKey]) instead of falling through to Android.
 *  - Settings → Smoothness counts its frames under its own name ([name]: "Title page", "Filters", "Menu"),
 *    not the screen's under it.
 *
 * Main thread.
 */
object DialogWindows {
    /** What a pop-up nobody named is counted as in Settings → Smoothness. */
    const val POP_UP = "Pop-up"

    private class Entry(window: Window, owner: Activity?, val order: Long) {
        val window = WeakReference(window)
        val owner = owner?.let { WeakReference(it) }
        /** Registrations still standing: a pop-up registered twice stays until both have let go. */
        var holders = 1
        /** Read for each of its frames, on the frame-stats thread. */
        @Volatile var label: String = POP_UP
    }

    /** The pop-ups that are up, in the order they came up (each one over the ones before). */
    private val entries = ArrayList<Entry>()
    /** Names given to windows, kept for a window named before it registers. */
    private val names = WeakHashMap<Window, String>()
    /** When each window first came up, so one registering again (its content rebuilt) keeps its place. */
    private val firstSeen = WeakHashMap<Window, Long>()
    private var counter = 0L

    /** [window], a pop-up's, is up (see the class comment for what that brings). */
    fun register(window: Window) {
        prune()
        entries.firstOrNull { it.window.get() === window }?.let { it.holders++; return }
        val owner = window.context.activity()
        val order = firstSeen.getOrPut(window) { ++counter }
        val entry = Entry(window, owner, order).apply { label = names[window] ?: POP_UP }
        val at = entries.indexOfFirst { it.order > order }
        if (at < 0) entries += entry else entries.add(at, entry)
        val callback = window.callback
        if (callback != null && callback !is OnScreen.KeyCounter) window.callback = Keys(callback, opensSearch = owner is SearchKey.Host)
        FrameStats.attach(window) { entry.label }
    }

    /** [window]'s pop-up is going (or its content let go of it). */
    fun unregister(window: Window) {
        val entry = entries.firstOrNull { it.window.get() === window } ?: return
        if (--entry.holders > 0) return
        entries.remove(entry)
        FrameStats.detach(window)
    }

    /** Names [window]'s frames for Settings → Smoothness ("Title page", "Menu"); before or after it registers. */
    fun name(window: Window, label: String) {
        names[window] = label
        entries.firstOrNull { it.window.get() === window }?.label = label
    }

    /** [activity]'s pop-ups that are up now, the lowest first (the last one is on top). */
    fun of(activity: Activity): List<Window> {
        prune()
        return entries.filter { it.owner?.get() === activity }
            .mapNotNull { it.window.get() }
            .filter { it.peekDecorView()?.isAttachedToWindow == true }
    }

    /** What [window]'s frames are counted as (for tests). */
    internal fun label(window: Window): String? = entries.firstOrNull { it.window.get() === window }?.label

    private fun prune() { entries.removeAll { it.window.get() == null } }

    /** The activity a pop-up's window belongs to: its context is the activity's, themed for dialogs. */
    private fun Context.activity(): Activity? {
        var c: Context? = this
        while (c != null) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }

    /**
     * A pop-up's window callback. Each key counts as use ([OnScreen.KeyCounter]); then, as in
     * MainActivity.dispatchKeyEvent for the main window: the remote's Search button opens Search (from
     * the main window's pop-ups, [opensSearch]), held arrows that come too fast are dropped, and each
     * key that goes through is timed for Settings → Smoothness (the frames after it count as navigating).
     */
    internal class Keys(dialog: Window.Callback, private val opensSearch: Boolean) : OnScreen.KeyCounter(dialog) {
        override fun handOn(event: KeyEvent): Boolean {
            if (opensSearch && SearchKey.take(event)) return true
            if (DpadGate.shouldDrop(event)) return true
            val started = FrameStats.keyStarted(event)
            try {
                return super.handOn(event)
            } finally {
                FrameStats.keyFinished(event, started)
            }
        }
    }
}

/**
 * Names the pop-up it's composed in, for Settings → Smoothness: its frames are counted under [label]
 * ("Menu") rather than as [DialogWindows.POP_UP]. Anywhere in a Dialog's content.
 */
@Composable
fun PopUpName(label: String) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(window, label) {
        window?.let { DialogWindows.name(it, label) }
        onDispose {}
    }
}
