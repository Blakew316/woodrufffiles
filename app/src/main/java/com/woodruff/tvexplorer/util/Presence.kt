package com.woodruff.tvexplorer.util

import android.os.SystemClock
import com.woodruff.tvexplorer.notify.AppVisibility
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Whether someone is using Woodruff right now, kept in one place for everything that must never
 * interrupt them: an update restarting Woodruff, and the in-app slideshow starting.
 *
 * **Use.** Every key press in every Woodruff window counts, wherever it lands: its activities
 * through their window ([com.woodruff.tvexplorer.update.OnScreen] wraps each one's window
 * callback, so the player, the photo viewer, the slideshow and the doorbell count as much as the
 * main window), its Compose dialog windows (title pages, menus, filters, search: every Dialog root
 * has `Modifier.dpadGate()`, which reports here), and the phone's remote keys and typing. So does
 * Woodruff coming on screen or getting the focus back, so a Woodruff just opened is never "idle".
 *
 * **Busy.** Long jobs that a restart would cut short or leave half-done (a copy to a USB drive, a
 * file coming from the phone, a Google Photos pick that waits for the phone) hold updates back
 * while they run, key presses or not.
 */
object Presence {
    /** A job still running after this long is taken to be stuck: it can't hold updates back for good. */
    internal const val STUCK_MS = 6 * 3_600_000L

    /** Time that only moves forward (not the wall clock, which jumps when the TV sets its time). Tests replace it. */
    @Volatile internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** When someone last pressed a key in Woodruff or it came on screen, on [clock]. */
    @Volatile private var lastUse: Long = clock()

    /** A key, a click or the like, in any Woodruff window: also what [AppVisibility.lastInput] says. */
    fun pressed() {
        lastUse = clock()
        AppVisibility.lastInput = System.currentTimeMillis()
    }

    /**
     * Use that isn't a key press: Woodruff came on screen or got the focus back (the TV's screensaver
     * went away, a pop-up of Android's closed), so whoever brought it there is about to use it; or a
     * pointer moved over it (a remote with an air mouse). It leaves [AppVisibility.lastInput] (what
     * the camera tiles and the backdrop wait on) alone: a pointer gliding about mustn't hold them.
     */
    fun used() {
        lastUse = clock()
    }

    /** How long since someone last used Woodruff (ms). */
    fun sinceUseMs(): Long = (clock() - lastUse).coerceAtLeast(0)

    /**
     * How long until the in-app slideshow is due, after [idleMinutes] without use; 0 when it's due
     * now, null when it's off. Whoever arms its timer asks again when the time comes: a key pressed
     * meanwhile, in a dialog or anywhere else, pushes it back; so does a job still running (a Google
     * Photos pick on the phone, a copy's progress on screen), which it would only cover.
     */
    fun slideshowInMs(idleMinutes: Int): Long? = slideshowDelay(sinceUseMs(), idleMinutes, isBusy)

    /** [slideshowInMs] for a given time since use, with a job running ([busy]) or not. Visible for tests. */
    internal fun slideshowDelay(sinceUseMs: Long, idleMinutes: Int, busy: Boolean = false): Long? {
        if (idleMinutes <= 0) return null
        val wait = (idleMinutes * 60_000L - sinceUseMs).coerceAtLeast(0)
        return if (wait == 0L && busy) BUSY_LOOK_AGAIN_MS else wait
    }

    /** A slideshow held back by a job looks again this often. */
    internal const val BUSY_LOOK_AGAIN_MS = 30_000L

    // ---- Busy ------------------------------------------------------------------------------------

    private val tokens = AtomicLong()
    /** Running jobs ([begin]) and when each started. */
    private val jobs = ConcurrentHashMap<Long, Long>()
    private val checks = CopyOnWriteArrayList<Check>()

    /** Marks the start of a job that mustn't be cut short; pass what it returns to [end]. Any thread. */
    fun begin(): Long = tokens.incrementAndGet().also { jobs[it] = clock() }

    /** The job [begin] started has ended (however it ended). */
    fun end(token: Long) { jobs.remove(token) }

    /** Runs [block] (suspending, if it's called from a coroutine) as a job that mustn't be cut short. */
    inline fun <T> busy(block: () -> T): T {
        val token = begin()
        try {
            return block()
        } finally {
            end(token)
        }
    }

    /**
     * Counts as busy whenever [check] says so (a screen's own "a copy is running" state, say), until
     * the returned handle is [Check.stop]ped. [check] is asked from any thread, so it should only
     * read something thread-safe (a StateFlow's value).
     */
    fun whileBusy(check: () -> Boolean): Check = Check(check).also { checks += it }

    /** A [whileBusy] condition. */
    class Check internal constructor(internal val test: () -> Boolean) {
        /** Since when [test] has said yes, on [clock] (0: it doesn't). */
        @Volatile internal var since = 0L

        fun stop() { checks -= this }
    }

    /** Whether a job that mustn't be cut short is running (one going for longer than [STUCK_MS] doesn't count). */
    val isBusy: Boolean get() {
        val now = clock()
        if (jobs.values.any { now - it < STUCK_MS }) return true
        var busy = false
        for (c in checks) {
            val yes = runCatching { c.test() }.getOrDefault(false)
            if (!yes) { c.since = 0; continue }
            if (c.since == 0L) c.since = now
            if (now - c.since < STUCK_MS) busy = true
        }
        return busy
    }

    /** Back to a fresh run, for tests. */
    internal fun resetForTest() {
        jobs.clear(); checks.clear(); lastUse = clock()
    }
}
