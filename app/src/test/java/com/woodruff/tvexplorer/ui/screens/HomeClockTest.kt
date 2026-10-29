package com.woodruff.tvexplorer.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's clock moves once a minute, just after the minute turns. */
class HomeClockTest {
    @Test fun onTheMinuteItWaitsForTheNextOne() = assertEquals(60_020L, untilMinuteTurns(5 * 60_000L))

    @Test fun partWayItWaitsOnlyForTheRest() {
        assertEquals(45_020L, untilMinuteTurns(5 * 60_000L + 15_000L))
        assertEquals(30L, untilMinuteTurns(6 * 60_000L - 10L))
    }
}
