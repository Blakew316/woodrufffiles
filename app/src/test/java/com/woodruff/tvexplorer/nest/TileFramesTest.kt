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

    @Test fun aTileWithoutTheFocusTakesTheFirstFrameOfEachBeat() {
        val gate = BeatGate()
        assertTrue(gate.pass(1_000))
        assertFalse(gate.pass(1_000 + TILE_BEAT_MS - 1 - 1_000 % TILE_BEAT_MS))
        assertTrue(gate.pass(1_000 - 1_000 % TILE_BEAT_MS + TILE_BEAT_MS))
    }

    @Test fun tilesWithoutTheFocusDrawOnTheSameBeatsFiveTimesASecond() {
        // Three cameras at 15 frames a second, each with frames arriving at its own moments.
        val phases = listOf(3L, 29L, 61L)
        val drawn = phases.map { phase ->
            val gate = BeatGate()
            (0 until 30).map { 10_000 + phase + it * 1000L / 15 }.filter { gate.pass(it) }
        }
        val beats = drawn.map { times -> times.map { it / TILE_BEAT_MS } }
        // Each draws once per beat, on the same beats as the others: their redraws come together.
        beats.forEach { assertEquals(it.distinct(), it) }
        assertEquals(1, beats.distinct().size)
        assertEquals(10, beats.first().size)
        // Each within one frame of the beat's start, so the three land within ~67 ms of each other.
        drawn.flatten().forEach { assertTrue(it % TILE_BEAT_MS < 1000 / 15 + 1) }
    }

    @Test fun aSlowCameraLosesNoFrames() {
        val gate = BeatGate()
        val frames = (0 until 8).map { 50_000 + it * 250L }
        assertEquals(frames, frames.filter { gate.pass(it) })
    }
}
