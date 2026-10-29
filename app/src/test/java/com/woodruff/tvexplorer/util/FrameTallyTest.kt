package com.woodruff.tvexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The frame counting behind Settings → Smoothness and the phone's Smoothness Report. */
class FrameTallyTest {
    private val ms = 1_000_000L
    private val deadline = 16_666_667L

    /** Phases (ns) by [Phase], GPU unknown unless given. */
    private fun phases(wait: Long = 0, anim: Long = 0, draw: Long = 0, sync: Long = 0, gpu: Long = -1) =
        LongArray(Phase.COUNT).also {
            it[Phase.WAITING] = wait
            it[Phase.ANIMATION] = anim
            it[Phase.DRAW] = draw
            it[Phase.SYNC] = sync
            it[Phase.GPU] = gpu
        }

    @Test fun percentilesComeFromTheMillisecondHistogram() {
        val d = Durations()
        for (i in 1..100) d.add(i * ms + ms / 2)
        assertEquals(50, d.percentileMs(0.5))
        assertEquals(90, d.percentileMs(0.9))
        assertEquals(99, d.percentileMs(0.99))
        assertEquals(100, d.percentileMs(1.0))
        assertEquals(100, (d.maxNs / ms).toInt())
    }

    @Test fun aSingleValueIsEveryPercentile() {
        val d = Durations().apply { add(7 * ms) }
        assertEquals(7, d.percentileMs(0.5))
        assertEquals(7, d.percentileMs(0.99))
    }

    @Test fun noValuesGiveZero() = assertEquals(0, Durations().percentileMs(0.9))

    @Test fun veryLongFramesShareTheLastBucket() {
        val d = Durations().apply { add(400 * ms); add(-5) }
        assertEquals(HISTOGRAM_MS, msBucket(400 * ms))
        assertEquals(0, msBucket(-5))
        assertEquals(HISTOGRAM_MS, d.percentileMs(0.99))
        assertEquals(400, (d.maxNs / ms).toInt())
    }

    @Test fun overCountsWholeMillisecondsAbove() {
        val d = Durations().apply { add(10 * ms); add(16 * ms + 900_000); add(17 * ms); add(400 * ms) }
        assertEquals(2, d.over(16))
        assertEquals(1, d.over(50))
    }

    @Test fun lateMeansPastTheDeadline() {
        val t = FrameTally()
        t.add(deadline, deadline, phases())
        assertEquals(0, t.late)
        t.add(deadline + 1, deadline, phases())
        assertEquals(1, t.late)
        assertEquals(2, t.frames)
    }

    @Test fun slowAndStutterKeepTheirWholeMillisecondThresholds() {
        val t = FrameTally()
        t.add(26 * ms - 1, deadline, phases())   // 25 ms: not slow
        t.add(26 * ms, deadline, phases())       // 26 ms: slow
        t.add(51 * ms - 1, deadline, phases())   // 50 ms: slow, not a stutter
        t.add(51 * ms, deadline, phases())       // 51 ms: a stutter
        assertEquals(3, t.slow)
        assertEquals(1, t.stutters)
        assertEquals(4, t.late)
    }

    @Test fun phaseAveragesAreOverTheLateFramesOnly() {
        val t = FrameTally()
        t.add(10 * ms, deadline, phases(wait = 9 * ms))                    // on time: not counted
        t.add(30 * ms, deadline, phases(wait = 4 * ms, anim = 20 * ms))
        t.add(40 * ms, deadline, phases(wait = 8 * ms, anim = 30 * ms, gpu = 6 * ms))
        assertEquals(6.0, t.lateAverageMs(Phase.WAITING), 1e-9)
        assertEquals(25.0, t.lateAverageMs(Phase.ANIMATION), 1e-9)
        assertEquals(0.0, t.lateAverageMs(Phase.DRAW), 1e-9)
        // The GPU's time: only frames that reported it (Android 12 and up).
        assertEquals(6.0, t.lateAverageMs(Phase.GPU), 1e-9)
        assertTrue(FrameTally().lateAverageMs(Phase.WAITING).isNaN())
    }

    @Test fun slowFramesSayWhatTheyWereMostlySpentOn() {
        val t = FrameTally()
        assertEquals(-1, t.mostlySlow())
        t.add(30 * ms, deadline, phases(wait = 5 * ms, draw = 20 * ms, gpu = 25 * ms))
        assertEquals(Phase.DRAW, t.mostlySlow())   // never the GPU, which overlaps the others
    }

    @Test fun copiesAreIndependent() {
        val t = FrameTally().apply { add(30 * ms, deadline, phases(wait = 30 * ms)) }
        val c = FrameTally().also { t.copyInto(it) }
        t.add(40 * ms, deadline, phases())
        assertEquals(1, c.frames)
        assertEquals(1, c.late)
        assertEquals(30, c.totals.maxNs / ms)
        assertEquals(30.0, c.lateAverageMs(Phase.WAITING), 1e-9)
    }

    @Test fun framesWithinASecondAfterAKeyAreNavigating() {
        val keys = LongArray(16).also { it[3] = 10_000 }
        assertTrue(isNavigating(10_000, keys))
        assertTrue(isNavigating(11_000, keys))
        assertFalse(isNavigating(11_001, keys))
        // A frame whose work began before the key can't have drawn it.
        assertFalse(isNavigating(9_999, keys))
    }

    @Test fun anOlderKeyStillCountsWhenNewerOnesCameAfterTheFrame() {
        // A slow frame reported after more key repeats: the key it followed is still in the ring.
        val keys = longArrayOf(5_000, 5_400, 5_450, 5_500)
        assertTrue(isNavigating(5_300, keys))
    }

    @Test fun emptySlotsAreNoKeys() = assertFalse(isNavigating(500, LongArray(16)))

    private fun screen(name: String, frames: Int, slow: Int, stutters: Int = 0, drawMs: Long = 20): ScreenFrames =
        ScreenFrames(name).apply {
            repeat(frames - slow) { navigating.add(10 * ms, deadline, phases()) }
            repeat(slow - stutters) { navigating.add(30 * ms, deadline, phases(draw = drawMs * ms)) }
            repeat(stutters) { navigating.add(60 * ms, deadline, phases(draw = drawMs * ms)) }
            repeat(500) { idle.add(80 * ms, deadline, phases(sync = 70 * ms)) }   // video: never in the row
        }

    @Test fun theRowIsAboutNavigatingFramesWorstFirst() {
        val row = navigatingSummary(listOf(screen("Home", 100, 4), screen("Movies", 100, 50, stutters = 1), screen("Cameras", 100, 0)))
        assertEquals("While navigating: Movies 50% slow (1 stutter, mostly drawing) · Home 4% slow (mostly drawing) · Cameras 0% slow", row)
    }

    @Test fun stuttersAreCountedInTheRow() {
        assertEquals("While navigating: Home 10% slow (3 stutters, mostly drawing)", navigatingSummary(listOf(screen("Home", 100, 10, stutters = 3))))
    }

    @Test fun screensWithFewNavigatingFramesAreLeftOut() {
        assertEquals("Not enough yet: use the app a little, then look again", navigatingSummary(listOf(screen("Home", MIN_FRAMES - 1, 30))))
        assertEquals("Not enough yet: use the app a little, then look again", navigatingSummary(emptyList()))
    }

    @Test fun theCountdownRoundsUpToTheSecond() {
        assertEquals("0:00", clock(0))
        assertEquals("0:01", clock(1))
        assertEquals("0:01", clock(1_000))
        assertEquals("1:00", clock(59_001))
        assertEquals("2:41", clock(161_000))
        assertEquals("3:00", clock(180_000))
        assertEquals("0:00", clock(-5))
    }
}
