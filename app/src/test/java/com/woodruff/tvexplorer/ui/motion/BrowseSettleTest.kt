package com.woodruff.tvexplorer.ui.motion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browsing-motion timing rules on a made-up clock: the backdrop waits until no key has been
 * pressed for 750 ms; the banner moves on 12 s after the latest of its last move, the last key and
 * coming fully into view, never while it isn't wholly on screen, and fetches its next picture 2 s
 * before; a TV changing its wall clock can neither freeze nor rush either.
 */
class BrowseSettleTest {
    /** A clock for both wall and forward-only time, with key presses at given times. */
    private class Clock(presses: List<Long> = emptyList()) {
        var t = 0L
        var wallShift = 0L
        val presses = presses.toMutableList()
        var sleeps = 0
        /** The last press at or before now, in wall-clock time (as AppVisibility.lastInput holds it). */
        fun lastInput(): Long = (presses.filter { it <= t }.maxOrNull() ?: -100_000L) + wallShift
        fun keys() = KeyClock(lastInput = ::lastInput, wall = { t + wallShift }, now = { t })
        suspend fun sleep(ms: Long) { sleeps++; t += ms }
    }

    @Test
    fun settleWaitCountsFromTheLastKey() {
        assertEquals(750, BrowseSettle.settleWaitMs(now = 1_000, lastKey = 1_000))
        assertEquals(250, BrowseSettle.settleWaitMs(now = 1_500, lastKey = 1_000))
        assertEquals(0, BrowseSettle.settleWaitMs(now = 1_750, lastKey = 1_000))
        assertEquals(0, BrowseSettle.settleWaitMs(now = 60_000, lastKey = 1_000))
        // Never longer than one settle, whatever the numbers say.
        assertEquals(750, BrowseSettle.settleWaitMs(now = 1_000, lastKey = 9_000))
    }

    @Test
    fun keysEveryHalfSecondKeepItWaitingUntil750msAfterTheLast() = runBlocking {
        // Right pressed every 500 ms across 20 posters, from t = 0.
        val clock = Clock(presses = (0 until 20).map { it * 500L })
        BrowseSettle.awaitSettled(clock.keys(), clock::sleep)
        assertEquals(19 * 500L + 750, clock.t)
        // A delay per press at most, never a check every frame.
        assertTrue("slept ${clock.sleeps} times", clock.sleeps <= 21)
    }

    @Test
    fun alreadySettledReturnsAtOnce() = runBlocking {
        val clock = Clock(presses = listOf(0L)).apply { t = 5_000 }
        BrowseSettle.awaitSettled(clock.keys(), clock::sleep)
        assertEquals(5_000L, clock.t)
        assertEquals(0, clock.sleeps)
    }

    @Test
    fun aWallClockSetBackwardsDoesNotFreezeTheWait() = runBlocking {
        // A key at t = 0, then the TV sets its clock back an hour: the key now looks an hour ahead.
        val clock = Clock(presses = listOf(0L)).apply { t = 100; wallShift = 0 }
        val keys = KeyClock(lastInput = { 0L }, wall = { clock.t - 3_600_000L }, now = { clock.t })
        BrowseSettle.awaitSettled(keys, clock::sleep)
        // Counted as pressed when first seen, so one settle and done (not an hour).
        assertEquals(100L + 750, clock.t)
    }

    @Test
    fun aKeyKeepsItsPlaceWhenTheWallClockJumpsLater() {
        var wall = 10_000L
        var now = 500L
        val keys = KeyClock(lastInput = { 9_900L }, wall = { wall }, now = { now })
        assertEquals(400L, keys.lastKey()) // 100 ms ago
        wall += 3_600_000L; now += 50
        assertEquals("the same key, the same place", 400L, keys.lastKey())
    }

    @Test
    fun bannerMovesOn12sAfterItsLastMoveWithNoKeys() {
        assertEquals(BannerStep.Wait(10_000), BrowseSettle.bannerStep(now = 0, moved = 0, lastKey = -50_000, inViewSince = 0, fetched = false))
        assertEquals(BannerStep.Fetch, BrowseSettle.bannerStep(now = 10_000, moved = 0, lastKey = -50_000, inViewSince = 0, fetched = false))
        assertEquals(BannerStep.Wait(2_000), BrowseSettle.bannerStep(now = 10_000, moved = 0, lastKey = -50_000, inViewSince = 0, fetched = true))
        assertEquals(BannerStep.Advance, BrowseSettle.bannerStep(now = 12_000, moved = 0, lastKey = -50_000, inViewSince = 0, fetched = true))
    }

    @Test
    fun bannerWaitsWhileNotWhollyOnScreen() {
        assertEquals(BannerStep.Hidden, BrowseSettle.bannerStep(now = 60_000, moved = 0, lastKey = 0, inViewSince = null, fetched = true))
        // Back in view at 30 s: a whole turn from then.
        assertEquals(BannerStep.Wait(10_000), BrowseSettle.bannerStep(now = 30_000, moved = 0, lastKey = 0, inViewSince = 30_000, fetched = false))
    }

    @Test
    fun aKeyPushesTheNextTitleBack12s() {
        assertEquals(BrowseSettle.bannerDueAt(moved = 0, lastKey = 11_000, inView = 0), 23_000L)
        assertEquals(BannerStep.Wait(10_000), BrowseSettle.bannerStep(now = 11_000, moved = 0, lastKey = 11_000, inViewSince = 0, fetched = true))
    }

    /** Runs the banner's steps as HeroCarousel does, on [clock], until [until]; returns when it moved on. */
    private fun rotate(clock: Clock, until: Long, inView: (Long) -> Boolean = { true }): List<Long> = runBlocking {
        val keys = clock.keys()
        val moves = mutableListOf<Long>()
        var moved = 0L
        var inViewSince: Long? = 0L
        var fetchedFor = -1
        var index = 0
        while (clock.t < until) {
            // HeroCarousel follows the list's scroll; here, what the list does at each moment.
            val whole = inView(clock.t)
            if (!whole) inViewSince = null else if (inViewSince == null) inViewSince = clock.t
            val next = index + 1
            when (val step = BrowseSettle.bannerStep(clock.t, moved, keys.lastKey(), inViewSince, fetched = fetchedFor == next)) {
                BannerStep.Hidden -> clock.sleep(100) // the real one waits for the scroll instead
                is BannerStep.Wait -> clock.sleep(minOf(step.ms, 100)) // short steps, so key presses and scrolls are seen
                BannerStep.Fetch -> fetchedFor = next
                BannerStep.Advance -> { index = next; moved = clock.t; moves += clock.t }
            }
        }
        moves
    }

    @Test
    fun theBannerNeverMovesWithin12sOfAKeyNorWhileScrolledAway() {
        // Keys every 3 s from 5 s to 35 s; the banner scrolled away from 60 s to 90 s.
        val presses = (5_000L..35_000L step 3_000L).toList()
        val clock = Clock(presses)
        val hidden = 60_000L until 90_000L
        val moves = rotate(clock, until = 130_000) { it !in hidden }
        assertTrue("it moved on: $moves", moves.isNotEmpty())
        for (m in moves) {
            assertTrue("moved at $m, within 12 s of a key", presses.none { it <= m && m - it < BrowseSettle.BANNER_MS })
            assertTrue("moved at $m while scrolled away", m !in hidden)
        }
        // Keys from 5 s on keep pushing it back: the first move is 12 s after the last key (35 s),
        // the next a whole turn later, just before it scrolls away at 60 s...
        assertEquals(listOf(47_000L, 59_000L), moves.take(2))
        // ...and once back in view at 90 s, a whole turn from then, and on.
        assertEquals(listOf(102_000L, 114_000L, 126_000L), moves.drop(2))
    }
}
