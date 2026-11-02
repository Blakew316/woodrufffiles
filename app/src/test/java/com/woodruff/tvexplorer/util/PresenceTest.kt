package com.woodruff.tvexplorer.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.AppVisibility
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Someone using Woodruff, and jobs that mustn't be cut short ([Presence]), on a clock of the test's. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PresenceTest {
    private var now = 50_000_000L
    private val minute = 60_000L

    @Before fun fakeClock() {
        Presence.clock = { now }
        Presence.resetForTest()
    }

    @After fun realClock() {
        Presence.resetForTest()
        Presence.clock = { android.os.SystemClock.elapsedRealtime() }
    }

    @Test fun aKeyPressIsUseAndTheLastInputEveryoneReads() {
        now += 5 * minute
        assertEquals(5 * minute, Presence.sinceUseMs())
        AppVisibility.lastInput = 0
        Presence.pressed()
        assertEquals(0, Presence.sinceUseMs())
        assertTrue(AppVisibility.lastInput > 0)
    }

    @Test fun comingOnScreenIsUseButNotAKeyPress() {
        now += 5 * minute
        AppVisibility.lastInput = 1
        Presence.arrived()
        assertEquals(0, Presence.sinceUseMs())
        // The camera tiles and the backdrop wait on key presses only.
        assertEquals(1, AppVisibility.lastInput)
    }

    @Test fun theSlideshowWaitsForTheWholeTimeWithoutUse() {
        assertNull(Presence.slideshowDelay(10 * minute, idleMinutes = 0))
        assertEquals(5 * minute, Presence.slideshowDelay(0, idleMinutes = 5))
        assertEquals(2 * minute, Presence.slideshowDelay(3 * minute, idleMinutes = 5))
        assertEquals(0L, Presence.slideshowDelay(9 * minute, idleMinutes = 5))
        // A key in a dialog a minute before it was due: it waits the whole time again.
        Presence.pressed()
        now += minute
        assertEquals(4 * minute, Presence.slideshowInMs(5))
        // Due, but a Google Photos pick (or a copy) is running: it looks again shortly instead.
        assertEquals(Presence.BUSY_LOOK_AGAIN_MS, Presence.slideshowDelay(9 * minute, idleMinutes = 5, busy = true))
        assertEquals(2 * minute, Presence.slideshowDelay(3 * minute, idleMinutes = 5, busy = true))
        val job = Presence.begin()
        now += 10 * minute
        assertEquals(Presence.BUSY_LOOK_AGAIN_MS, Presence.slideshowInMs(5))
        Presence.end(job)
        assertEquals(0L, Presence.slideshowInMs(5))
    }

    @Test fun jobsHoldUpdatesBackWhileTheyRunEvenNested() {
        assertFalse(Presence.isBusy)
        val outer = Presence.begin()
        val inner = Presence.begin()
        Presence.end(inner)
        assertTrue(Presence.isBusy)
        Presence.end(outer)
        assertFalse(Presence.isBusy)
        // However a job ends, it ends.
        runCatching { Presence.busy<Unit> { assertTrue(Presence.isBusy); error("the copy failed") } }
        assertFalse(Presence.isBusy)
    }

    @Test fun aScreensOwnBusyStateCountsUntilItStops() {
        var copying = true
        val check = Presence.whileBusy { copying }
        assertTrue(Presence.isBusy)
        copying = false
        assertFalse(Presence.isBusy)
        copying = true
        assertTrue(Presence.isBusy)
        check.stop()
        assertFalse(Presence.isBusy)
    }

    @Test fun aStuckJobCantHoldUpdatesBackForever() {
        Presence.begin()
        now += Presence.STUCK_MS - 1
        assertTrue(Presence.isBusy)
        now += 1
        assertFalse(Presence.isBusy)

        // The same for a screen's state that never clears: from when it was first seen.
        val check = Presence.whileBusy { true }
        assertTrue(Presence.isBusy)
        now += Presence.STUCK_MS
        assertFalse(Presence.isBusy)
        check.stop()
    }
}
