package com.woodruff.tvexplorer.ui.motion

import android.os.SystemClock
import com.woodruff.tvexplorer.notify.AppVisibility
import kotlinx.coroutines.delay

/**
 * Changes that follow the focus but repaint much of the screen (Streaming's full-screen backdrop,
 * the featured banner) wait until browsing settles: no key pressed for [SETTLE_MS]. Moving across
 * posters at a normal pace (a press every half second or so) then never starts a big fade under the
 * focus's own motion, on the screens the slowest TVs' graphics chips struggle with most; stopping
 * to look changes it once.
 *
 * The last key is [AppVisibility.lastInput], the wall-clock time every key reaching Woodruff's
 * windows sets. Waits run on a clock that only moves forward ([uptime]): each key is placed on it
 * once, when it's first seen ([KeyClock]), so a TV setting its clock (as it does after waking) can
 * neither freeze a wait nor end one early.
 */
object BrowseSettle {
    /** How long without a key before browsing counts as settled. */
    const val SETTLE_MS = 750L

    /** The featured banner moves on this long after the latest of its last move, the last key, and coming fully into view. */
    const val BANNER_MS = 12_000L

    /** The banner's next picture is fetched this long before it's due, so it fades in whole. */
    const val BANNER_PREFETCH_MS = 2_000L

    /** The clock [AppVisibility.lastInput] is on. Tests replace it. */
    @Volatile internal var wall: () -> Long = { System.currentTimeMillis() }

    /** A clock that only moves forward (the one [delay] waits on). Tests replace it. */
    @Volatile internal var uptime: () -> Long = { SystemClock.uptimeMillis() }

    /**
     * Returns once no key has been pressed for [SETTLE_MS]: it waits until then, and again if a key
     * came meanwhile. A delay per wait, never a check on every frame.
     */
    suspend fun awaitSettled() = awaitSettled(KeyClock()) { delay(it) }

    /** [awaitSettled] on [keys], waiting with [sleep]. Visible for tests. */
    internal suspend fun awaitSettled(keys: KeyClock, sleep: suspend (Long) -> Unit) {
        while (true) {
            val wait = settleWaitMs(keys.now(), keys.lastKey())
            if (wait <= 0L) return
            sleep(wait)
        }
    }

    /** How long after [now] browsing settles, the last key having been at [lastKey] (0: it has). */
    internal fun settleWaitMs(now: Long, lastKey: Long): Long = (SETTLE_MS - (now - lastKey)).coerceIn(0, SETTLE_MS)

    /**
     * When the banner moves on to its next title: [BANNER_MS] after the latest of its last move
     * ([moved]), the last key ([lastKey]) and the moment it came fully into view ([inView]). Any key
     * starts the 12 s again, so the title never changes under someone choosing; and a title that has
     * just scrolled back into view stays for a whole turn.
     */
    internal fun bannerDueAt(moved: Long, lastKey: Long, inView: Long): Long = maxOf(moved, lastKey, inView) + BANNER_MS

    /** When the picture of the title due at [due] is fetched ahead. */
    internal fun bannerPrefetchAt(due: Long): Long = due - BANNER_PREFETCH_MS

    /**
     * What the banner does next, at [now]: its last move was at [moved], the last key at [lastKey];
     * [inViewSince] is when it came wholly into view (null while it isn't: it never moves on then);
     * [fetched] says the next title's picture has been asked for. All on the forward-only clock.
     */
    internal fun bannerStep(now: Long, moved: Long, lastKey: Long, inViewSince: Long?, fetched: Boolean): BannerStep {
        val since = inViewSince ?: return BannerStep.Hidden
        val due = bannerDueAt(moved, lastKey, since)
        val fetchAt = bannerPrefetchAt(due)
        return when {
            now < fetchAt -> BannerStep.Wait(fetchAt - now)
            !fetched -> BannerStep.Fetch
            now < due -> BannerStep.Wait(due - now)
            else -> BannerStep.Advance
        }
    }
}

/** The featured banner's next step (see [BrowseSettle.bannerStep]). */
internal sealed interface BannerStep {
    /** It isn't wholly on screen: nothing until it is. */
    data object Hidden : BannerStep

    /** Nothing for [ms]; then look again (a key pressed meanwhile pushes the next title back). */
    data class Wait(val ms: Long) : BannerStep

    /** Fetch the next title's picture now, so it fades in whole when it's due. */
    data object Fetch : BannerStep

    /** Move on to the next title now. */
    data object Advance : BannerStep
}

/**
 * When the last key was pressed, on [now]'s forward-only clock, from the wall-clock time [lastInput]
 * reports. A key is placed once, when it's first seen, and keeps that place: one seen "in the future"
 * (the wall clock went back after it) counts as pressed just then, and one from long ago as long ago.
 * Not thread-safe: each waiter keeps its own (the main thread's coroutines).
 */
internal class KeyClock(
    private val lastInput: () -> Long = { AppVisibility.lastInput },
    private val wall: () -> Long = BrowseSettle.wall,
    val now: () -> Long = BrowseSettle.uptime,
) {
    private var seen = Long.MIN_VALUE
    private var at = 0L

    /** When the last key was pressed, on [now]'s clock. */
    fun lastKey(): Long {
        val input = lastInput()
        if (input != seen) {
            seen = input
            at = now() - (wall() - input).coerceAtLeast(0)
        }
        return at
    }
}
