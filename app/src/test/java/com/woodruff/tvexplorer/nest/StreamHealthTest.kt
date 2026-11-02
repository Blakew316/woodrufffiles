package com.woodruff.tvexplorer.nest

import com.woodruff.tvexplorer.nest.CameraStream.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * A camera is live only while its frames come: a connection that's up but sends nothing isn't, a
 * picture that stops says so after 3 s and is made again after 15 s, and a camera that keeps failing
 * waits longer each time until its frames have flowed for a minute (finding 37, 39).
 */
class StreamHealthTest {
    private val answered = 100_000L

    @Test fun aConnectionThatsUpIsntLiveUntilItsFirstFrame() {
        val h = StreamHealth()
        assertEquals(Status.Connecting, h.connecting())
        // Connected (ICE up), no frame yet: still connecting, as long as 20 s.
        assertEquals(Status.Connecting, h.judge(answered + 5_000, answered, firstFrame = 0, lastFrame = 0))
        assertEquals(Status.Connecting, h.judge(answered + FIRST_FRAME_LIMIT_MS - 1, answered, 0, 0))
        // The first frame: live.
        val first = answered + 1_200
        assertEquals(Status.Live, h.judge(first + 10, answered, first, first))
    }

    @Test fun noFirstFrameIn20SecondsDropsTheConnection() {
        val h = StreamHealth()
        assertNull(h.judge(answered + FIRST_FRAME_LIMIT_MS, answered, 0, 0))
    }

    @Test fun aPictureThatStopsHoldsAndSaysSoAfterThreeSeconds() {
        val h = StreamHealth()
        val last = answered + 2_000
        assertEquals(Status.Live, h.judge(last + STALL_AFTER_MS - 1, answered, answered + 500, last))
        // No frame for 3 s: the picture holds, and the trouble dates from its last frame.
        assertEquals(Status.Stalled(last), h.judge(last + STALL_AFTER_MS, answered, answered + 500, last))
        assertEquals(Status.Stalled(last), h.judge(last + DROP_AFTER_MS - 1, answered, answered + 500, last))
    }

    @Test fun andIsDroppedAfterFifteenSeconds() {
        val h = StreamHealth()
        val last = answered + 2_000
        h.judge(last + STALL_AFTER_MS, answered, answered + 500, last)
        assertNull(h.judge(last + DROP_AFTER_MS, answered, answered + 500, last))
    }

    @Test fun framesComingBackMakeItLiveAgainAndEndTheTrouble() {
        val h = StreamHealth()
        val last = answered + 2_000
        assertEquals(Status.Stalled(last), h.judge(last + 5_000, answered, answered + 500, last))
        val again = last + 6_000
        assertEquals(Status.Live, h.judge(again + 100, answered, answered + 500, again))
        // A later stall is a new trouble, from its own last frame.
        assertEquals(Status.Stalled(again), h.judge(again + 4_000, answered, answered + 500, again))
    }

    @Test fun aDropKeepsTheTroubleItCameFrom() {
        val h = StreamHealth()
        val last = answered + 2_000
        h.judge(last + 5_000, answered, answered + 500, last)
        // Dropped after the stall: reconnecting, still since the picture froze.
        assertEquals(Status.Reconnecting(last), h.failed(last + DROP_AFTER_MS))
        assertEquals(Status.Reconnecting(last), h.connecting())
        // The new connection's first frame ends it.
        val first = last + 30_000
        assertEquals(Status.Live, h.judge(first + 50, first - 1_000, first, first))
        assertEquals(Status.Connecting, h.connecting())
    }

    @Test fun aFailureBeforeAnyPictureDatesFromTheFailure() {
        val h = StreamHealth()
        assertEquals(Status.Reconnecting(5_000), h.failed(5_000))
        assertEquals(Status.Reconnecting(5_000), h.failed(20_000))
    }

    @Test fun eachFailureInARowWaitsLongerUpToTwoMinutes() {
        val h = StreamHealth()
        assertEquals(RETRY_STEP_MS, h.pauseMs())
        val pauses = (1..30).map { h.failed(it * 1_000L); h.pauseMs() }
        assertEquals(listOf(5_000L, 10_000L, 15_000L, 20_000L), pauses.take(4))
        assertEquals(RETRY_MAX_MS, pauses.last())
        assertTrue(pauses.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun aCameraThatConnectsButNeverSendsAPictureBacksOff() {
        // Each try: Google answers, ICE connects, no frame for 20 s, dropped. Before, the wait reset as
        // soon as Google answered, so such a camera asked for a new stream every ~15 s forever.
        val h = StreamHealth()
        var now = 0L
        val waits = mutableListOf<Long>()
        repeat(6) {
            val answeredAt = now + 1_500
            assertEquals(null, h.judge(answeredAt + FIRST_FRAME_LIMIT_MS, answeredAt, 0, 0))
            h.failed(answeredAt + FIRST_FRAME_LIMIT_MS)
            waits += h.pauseMs()
            now = answeredAt + FIRST_FRAME_LIMIT_MS + h.pauseMs()
        }
        assertEquals(listOf(5_000L, 10_000L, 15_000L, 20_000L, 25_000L, 30_000L), waits)
    }

    @Test fun theWaitShortensOnlyAfterAMinuteOfFrames() {
        val h = StreamHealth()
        repeat(3) { h.failed(1_000L + it) }
        assertEquals(15_000L, h.pauseMs())
        val first = 1_000_000L
        // Frames flowing, judged twice a second for 59 s: not yet.
        var t = first
        while (t < first + HEALTHY_AFTER_MS - 1_000) {
            assertEquals(Status.Live, h.judge(t, first - 1_000, first, t))
            t += 500
        }
        assertEquals(3, h.failures)
        // A minute without a stall: well again.
        while (t <= first + HEALTHY_AFTER_MS) {
            h.judge(t, first - 1_000, first, t)
            t += 500
        }
        assertEquals(0, h.failures)
        assertEquals(RETRY_STEP_MS, h.pauseMs())
    }

    @Test fun aStallStartsTheMinuteAgain() {
        val h = StreamHealth()
        h.failed(500)
        val first = 1_000_000L
        h.judge(first, first - 1_000, first, first)
        // 40 s of frames, then a 4 s stall, then 40 s more: never a minute in one go.
        h.judge(first + 40_000, first - 1_000, first, first + 40_000)
        assertEquals(Status.Stalled(first + 40_000), h.judge(first + 44_000, first - 1_000, first, first + 40_000))
        h.judge(first + 44_500, first - 1_000, first, first + 44_400)
        h.judge(first + 84_400, first - 1_000, first, first + 84_400)
        assertEquals(1, h.failures)
    }

    @Test fun theTroubleAViewShows() {
        assertEquals(0L, troubleSince(Status.Connecting))
        assertEquals(0L, troubleSince(Status.Live))
        assertEquals(7L, troubleSince(Status.Stalled(7)))
        assertEquals(9L, troubleSince(Status.Reconnecting(9)))
    }

    @Test fun onlyARenewalGoogleTurnedDownStopsTheOldSession() {
        assertTrue(stopsOldSession(IOException("Camera error 500")))
        assertTrue(stopsOldSession(IllegalStateException("Camera answer rejected")))
        // "Too many requests": another command would add to it.
        assertFalse(stopsOldSession(RateLimited()))
        // The network: it can't reach Google either, and would only hold up the next try.
        assertFalse(stopsOldSession(SocketTimeoutException()))
        assertFalse(stopsOldSession(UnknownHostException("smartdevicemanagement.googleapis.com")))
        assertFalse(stopsOldSession(IOException("wrapped", java.net.ConnectException())))
    }
}
