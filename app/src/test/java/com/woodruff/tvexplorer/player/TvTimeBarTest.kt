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

    private val hour = 3_600_000L

    @Test fun onePressBackWhilePlayingLandsTenSecondsBehindTheFilm() {
        // Pressed at 20:00.000; played 1.2 s later, when the film has reached 20:01.200.
        val landed = TvTimeBar.landing(shown = 1_190_000, startLive = 1_200_000, nowLive = 1_201_200, duration = hour)
        assertEquals(1_201_200L - 10_000, landed)
    }

    @Test fun pausedTheSpotIsPlayedAsShown() {
        assertEquals(1_190_000L, TvTimeBar.landing(1_190_000, 1_200_000, 1_200_000, hour))
    }

    @Test fun aLongScrubKeepsItsDistanceFromTheFilm() {
        // Held Right for 6 s while the film played: 4 minutes ahead of where the film is by then.
        assertEquals(1_446_000L, TvTimeBar.landing(1_440_000, 1_200_000, 1_206_000, hour))
    }

    @Test fun scrubbedToTheStartStaysAtTheStart() {
        assertEquals(0L, TvTimeBar.landing(0, 5_000, 6_200, hour))
    }

    @Test fun neverPastTheEnd() {
        assertEquals(hour, TvTimeBar.landing(hour, hour - 20_000, hour - 18_000, hour))
        assertEquals(hour, TvTimeBar.landing(hour - 500, hour - 20_000, hour - 18_000, hour))
    }

    @Test fun unknownPositionsLandAsShown() {
        assertEquals(1_190_000L, TvTimeBar.landing(1_190_000, null, 1_201_200, hour))
        assertEquals(1_190_000L, TvTimeBar.landing(1_190_000, 1_200_000, null, hour))
    }

    @Test fun theFilmJumpingBackMovesTheSpotWithIt() {
        // Watch together put this TV 30 s back while a spot was being chosen: the scrub keeps its
        // distance from the film, not from where it used to be.
        assertEquals(1_160_000L, TvTimeBar.landing(1_190_000, 1_200_000, 1_170_000, hour))
    }
}
