package com.woodruff.tvexplorer.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * A library listed in full this recently is shown as it is when it's opened: no Drive or TMDB
 * pass, so switching between Movies and TV Shows, or coming back from a title, costs nothing. Older
 * than this, it's listed again in the background while the saved copy stays on screen. Refresh (in
 * Filter) always lists it again.
 */
internal const val FRESH_MS = 5 * 60_000L

/**
 * Whether a library listed in full at [listedAt] (null: not known) is still fresh at [now]. A clock
 * that went back since (a TV setting its time after a power cut) makes it stale, not fresh for ever.
 */
internal fun isFresh(listedAt: Long?, now: Long): Boolean = listedAt != null && now - listedAt in 0 until FRESH_MS

/**
 * Whether any of the libraries, listed in full at [listedAt] (null: not known), needs listing again at
 * [now]: Home coming back after a while lists them behind the screen, so Recently added catches up.
 */
internal fun anyStale(listedAt: Iterable<Long?>, now: Long): Boolean = listedAt.any { !isFresh(it, now) }

/**
 * Whether opening a library lists it from Drive: Refresh asked for it ([force]), it isn't fresh
 * (listed in full at [listedAt]), or the last try didn't get through or had its keys refused
 * ([status]): then it's tried again, so the offline words go as soon as Google answers rather than
 * staying up.
 */
internal fun needsListing(force: Boolean, status: DriveStatus, listedAt: Long?, now: Long): Boolean =
    force || status.down || status is DriveStatus.Refused || !isFresh(listedAt, now)

/**
 * Each library's refresh, one at a time and shared: whoever asks while one is under way (the
 * screen, either tab, Home's warm-up at launch) gets that one instead of starting another, which
 * used to list the whole library twice at once. Each runs in [scope] on its own, so the one who
 * asked going away (switching tabs, opening a show) doesn't cancel it halfway and lose its work.
 *
 * A TMDB-only pass (a new TMDB key) waits for a listing under way and then runs, so it looks up
 * the fresh copy; a listing asked for during a TMDB-only pass runs after it. [K] is the library.
 */
internal class LibraryRefreshes<K>(private val scope: CoroutineScope) {
    private class Running(val tmdbOnly: Boolean, val job: Deferred<Fetch>)

    // Guarded by itself.
    private val running = HashMap<K, Running>()

    /** A listing of [key] by [list]: the one under way, or a new one. */
    fun listing(key: K, list: suspend () -> Fetch): Deferred<Fetch> = start(key, tmdbOnly = false, list)

    /** A TMDB-only pass over [key] by [retag], once whatever is under way for it is done. */
    fun tmdbPass(key: K, retag: suspend () -> Fetch): Deferred<Fetch> = start(key, tmdbOnly = true, retag)

    private fun start(key: K, tmdbOnly: Boolean, block: suspend () -> Fetch): Deferred<Fetch> = synchronized(running) {
        val before = running[key]?.takeIf { it.job.isActive }
        if (before != null && !tmdbOnly && !before.tmdbOnly) return before.job
        val job = scope.async {
            // Cancelled or not, it's over before this starts: the two never write the listing at once.
            before?.job?.join()
            block()
        }
        running[key] = Running(tmdbOnly, job)
        job
    }

    /** Signing out: every refresh stops, and none is handed out again. */
    fun cancelAll() = synchronized(running) {
        running.values.forEach { it.job.cancel() }
        running.clear()
    }
}
