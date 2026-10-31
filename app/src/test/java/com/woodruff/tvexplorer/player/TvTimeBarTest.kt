package com.woodruff.tvexplorer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TvTimeBarTest {
    @Test fun clockUnderAnHour() = assertEquals("4:05", TvTimeBar.clock(245_000))
    @Test fun clockOverAnHour() = assertEquals("1:02:09", TvTimeBar.clock(3_729_000))
    @Test fun clockNeverNegative() = assertEquals("0:00", TvTimeBar.clock(-5_000))

    @Test fun scrubSpeedsUpWhileHeld() {
        assertEquals(10_000L, TvTimeBar.stepFor(0))
        assertEquals(30_000L, TvTimeBar.stepFor(10))
        assertEquals(60_000L, TvTimeBar.stepFor(40))
    }
}
