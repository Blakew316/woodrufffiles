package com.woodruff.tvexplorer.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the other TVs' progress is fetched: a pull that failed (the TV still waking up) no longer
 * blocks the next one for two minutes, and a resume asks again when the last pull is a few seconds old.
 */
class PullClockTest {
    private val routine = 2 * 60_000L
    private val forResume = 15_000L
    private val t0 = 1_700_000_000_000L

    @Test fun theFirstPullIsDue() {
        assertTrue(PullClock().due(t0, routine))
    }

    @Test fun afterReachingGoogleTheRoutinePullWaitsTwoMinutes() {
        val clock = PullClock().apply { started(t0); reached(t0 + 400) }
        assertFalse(clock.due(t0 + 60_000, routine))
        assertTrue(clock.due(t0 + 400 + routine, routine))
    }

    @Test fun aResumeAsksAgainWhenTheLastPullIsAFewSecondsOld() {
        val clock = PullClock().apply { started(t0); reached(t0 + 400) }
        // A title's page opened a moment ago fetched it: Resume doesn't ask again.
        assertFalse(clock.due(t0 + 5_000, forResume))
        // Walking over from the other TV takes longer than that.
        assertTrue(clock.due(t0 + 20_000, forResume))
    }

    @Test fun aPullThatFailedIsTriedAgainSoonNotInTwoMinutes() {
        // Home's pull right after waking, before the Wi-Fi is back: it never reaches Google.
        val clock = PullClock().apply { started(t0) }
        assertFalse(clock.due(t0 + 3_000, routine))
        assertTrue(clock.due(t0 + PullClock.RETRY_MS, routine))
    }

    @Test fun theNetworkComingBackTriesAtOnce() {
        val clock = PullClock().apply { started(t0) }
        assertTrue(clock.due(t0 + 1_000, routine, afterComeback = true))
        // But not when a pull reached Google a moment ago (a Wi-Fi blip).
        clock.reached(t0 + 500)
        assertFalse(clock.due(t0 + 1_000, routine, afterComeback = true))
    }

    @Test fun signingInAgainMakesItDueAtOnce() {
        val clock = PullClock().apply { started(t0); reached(t0) }
        clock.reset()
        assertTrue(clock.due(t0 + 1, routine))
    }

    @Test fun aClockSetBackDoesntBlockPullsForEver() {
        // The TV set its time after a power cut: "the last pull" is in the future.
        val clock = PullClock().apply { started(t0); reached(t0) }
        assertTrue(clock.due(t0 - 3_600_000, routine))
    }
}
