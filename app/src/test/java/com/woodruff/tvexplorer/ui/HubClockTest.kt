package com.woodruff.tvexplorer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Command Center's clock wakes once a minute, just after the minute turns. */
class HubClockTest {
    private val minute = 60_000L
    private val start = 29_000_000L * minute   // some whole minute

    @Test fun itWakesJustAfterTheNextMinuteTurns() {
        for (now in listOf(start, start + 1, start + 30_000, start + 59_999)) {
            val wake = now + untilNextMinute(now)
            assertEquals(now / minute + 1, wake / minute)
            assertEquals(50L, wake % minute)
        }
    }

    @Test fun onTheMinuteItWaitsTheWholeMinute() {
        assertEquals(60_050L, untilNextMinute(start))
    }
}
