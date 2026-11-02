package com.woodruff.tvexplorer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Which Woodruff screen counts as on screen ([Showing]): the one up; under the TV's screensaver or
 * with the screen off, the one it covered; never one left for another app. Both orders Android sends
 * a screen's stop and the screensaver's start in are covered.
 */
class ShowingTest {
    private var now = 1_000_000L
    private val showing = Showing { now }
    private val main = "MainActivity"
    private val player = "PlayerActivity"
    private val slideshow = "SlideshowActivity"
    private val doorbell = "DoorbellActivity"
    private val task = 7

    private fun open(vararg keys: Any) = keys.forEach { showing.created(it, task); showing.started(it) }

    @Test fun theScreenStartedLastIsOnScreen() {
        open(main)
        assertSame(main, showing.top()?.key)
        // The player opens over Home: it starts before Home stops.
        open(player)
        showing.stopped(main, finishing = false)
        assertSame(player, showing.top()?.key)
        // Back from the player: Home starts again before the player stops (finishing).
        showing.started(main)
        showing.stopped(player, finishing = true)
        showing.destroyed(player)
        assertSame(main, showing.top()?.key)
    }

    @Test fun aWoodruffLeftForAnotherAppIsntOnScreen() {
        open(main)
        showing.stopped(main, finishing = false)
        assertNull(showing.top())
        // Long after, the TV's screensaver starts over that other app: still not Woodruff's.
        now += 20 * 60_000L
        showing.coveredBySystem()
        assertNull(showing.top())
    }

    @Test fun aPausedFilmUnderTheScreensaverStaysOnScreenEitherOrder() {
        // Android 11+: the screensaver is an activity of its own, so the player stops. The broadcast
        // can come first...
        open(main, player)
        showing.stopped(main, false)
        showing.coveredBySystem()
        now += 400
        showing.stopped(player, false)
        assertSame(player, showing.top()?.key)

        // ...or just after the stop.
        showing.reset()
        open(main, player)
        showing.stopped(main, false)
        showing.stopped(player, false)
        now += 800
        showing.coveredBySystem()
        assertSame(player, showing.top()?.key)
        // An hour later it still counts (an update then puts it back).
        now += 3_600_000L
        assertSame(player, showing.top()?.key)
    }

    @Test fun waking() {
        open(main, player)
        showing.stopped(main, false)
        showing.stopped(player, false)
        showing.coveredBySystem()
        // The TV wakes back into the film: it's simply up again.
        showing.uncovered()
        now += 500
        showing.started(player)
        assertSame(player, showing.top()?.key)
        assertEquals(true, showing.isStarted(player))

        // Covered again; this time the TV wakes into its Home (the film doesn't start again).
        showing.stopped(player, false)
        showing.coveredBySystem()
        showing.uncovered()
        now += Showing.WAKE_GRACE_MS - 1
        assertSame(player, showing.top()?.key)
        now += 1
        assertNull(showing.top())
    }

    @Test fun theScreensaverGoingIntoStandbyKeepsItCovered() {
        open(slideshow)
        showing.coveredBySystem() // screen off with the slideshow up (it keeps the screen on otherwise)
        showing.stopped(slideshow, false)
        // A screensaver ending because the TV goes to sleep: "stopped", then the screen goes off.
        showing.uncovered()
        now += 3_000
        showing.coveredBySystem()
        now += Showing.WAKE_GRACE_MS * 10
        assertSame(slideshow, showing.top()?.key)
    }

    @Test fun aScreenThatStoppedJustBeforeTheScreenWentOffCountsButNotLongBefore() {
        open(main)
        showing.stopped(main, false)
        now += Showing.LEFT_GRACE_MS
        showing.coveredBySystem()
        assertSame(main, showing.top()?.key)

        showing.reset()
        open(main)
        showing.stopped(main, false)
        now += Showing.LEFT_GRACE_MS + 1
        showing.coveredBySystem()
        assertNull(showing.top())
    }

    @Test fun closingWithBackIsLeaving() {
        open(main)
        showing.stopped(main, finishing = true)
        showing.coveredBySystem()
        assertNull(showing.top())
    }

    @Test fun whatWasUnderAClosedScreenTakesItsPlace() {
        // The doorbell's camera over the slideshow; the screen goes off, then the camera closes itself.
        open(main, slideshow, doorbell)
        showing.stopped(main, false)
        showing.stopped(slideshow, false)
        showing.coveredBySystem()
        showing.stopped(doorbell, false)
        showing.destroyed(doorbell)
        assertSame(slideshow, showing.top()?.key)
        assertEquals(listOf(slideshow, main), showing.top()?.let { showing.stackFrom(it) }?.map { it.key })
    }

    @Test fun aScreenPutBackUnseenStaysOnScreenForTheNextUpdate() {
        // Put back during the night with the screen off: Android starts it and stops it at once.
        showing.restoring()
        open(main, player)
        showing.stopped(main, false)
        showing.stopped(player, false)
        // Hours later another update comes: the film is still what the TV shows.
        now += 3 * 3_600_000L
        assertSame(player, showing.top()?.key)
        // In the morning the TV wakes into it; it's seen; then left for another app: not any more.
        showing.uncovered()
        showing.started(player)
        showing.seen()
        showing.stopped(player, false)
        now += 60_000
        assertNull(showing.top())
    }

    @Test fun aScreenPutBackWhereSomeoneSeesItIsLeftAsUsual() {
        showing.restoring()
        open(main)
        showing.seen() // its window got the focus
        showing.stopped(main, false) // Home
        assertNull(showing.top())
    }

    @Test fun theTvWakingIntoSomethingElseEndsAScreenPutBackUnseen() {
        showing.restoring()
        open(player)
        showing.stopped(player, false)
        showing.uncovered()
        now += Showing.WAKE_GRACE_MS
        assertNull(showing.top())
        // And a later stop is a plain leave again.
        open(player)
        showing.stopped(player, false)
        now += Showing.LEFT_GRACE_MS + 1
        assertNull(showing.top())
    }

    @Test fun theStackOnlyHoldsTheTopScreensOwnTask() {
        showing.created(main, 1)
        showing.started(main)
        showing.stopped(main, false)
        // The doorbell's camera opened from the TV's screensaver, in a task of its own.
        showing.created(doorbell, 2)
        showing.started(doorbell)
        assertEquals(listOf(doorbell), showing.top()?.let { showing.stackFrom(it) }?.map { it.key })
    }
}
