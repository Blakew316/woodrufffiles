package com.woodruff.tvexplorer.nest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How many frames a camera tile shows: each one redraws the window on the main thread. */
class TileFramesTest {
    @Test fun theTileWithTheFocusPlaysSmoothest() {
        assertEquals(15f, tileFps(focused = true), 0f)
        assertEquals(5f, tileFps(focused = false), 0f)
    }

    @Test fun thePictureHoldsJustAfterARemotePress() {
        assertTrue(holdsPicture(now = 10_000, lastInput = 10_000))
        assertTrue(holdsPicture(now = 10_000 + HOLD_AFTER_PRESS_MS - 1, lastInput = 10_000))
    }

    @Test fun thenItPlaysAgain() {
        assertFalse(holdsPicture(now = 10_000 + HOLD_AFTER_PRESS_MS, lastInput = 10_000))
        assertFalse(holdsPicture(now = 70_000, lastInput = 10_000))
    }

    @Test fun aPressStampedAheadOfTheClockDoesNotFreezeIt() = assertFalse(holdsPicture(now = 10_000, lastInput = 99_000))
}
