package com.woodruff.tvexplorer.player

import com.woodruff.tvexplorer.ui.motion.TvMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One property of a view gliding (the player's cards, a notice): where it is on its way, frame by frame. */
class GlideTrackTest {
    private val straight: (Float) -> Float = { it }

    @Test fun itGetsThereInItsTimeAndStops() {
        val t = GlideTrack()
        t.start(from = 0f, to = 100f, nowMs = 1_000, ms = 300)
        assertTrue(t.moving)
        assertEquals(0f, t.at(1_000, straight), 0f)
        assertEquals(50f, t.at(1_150, straight), 0.001f)
        assertTrue(t.moving)
        assertEquals(100f, t.at(1_300, straight), 0f)
        assertFalse(t.moving)
        // A late frame lands exactly there too.
        assertEquals(100f, t.at(1_900, straight), 0f)
    }

    @Test fun aFrameFromJustBeforeItStartedFindsItWhereItStarts() {
        // A frame's time can be a little older than the moment the glide was asked for.
        val t = GlideTrack()
        t.start(from = 1f, to = 0f, nowMs = 5_000, ms = 180)
        assertEquals(1f, t.at(4_990, straight), 0f)
        assertTrue(t.moving)
    }

    @Test fun itRunsOnTheWindowsCurve() {
        // Off quickly and easing to a stop, as the windows glide (GlideEase is res/interpolator/tv_glide).
        val t = GlideTrack()
        t.start(from = 0f, to = 1f, nowMs = 0, ms = TvMotion.PresentMs)
        val ease: (Float) -> Float = { TvMotion.GlideEase.transform(it) }
        val third = t.at((TvMotion.PresentMs / 3).toLong(), ease)
        assertEquals(TvMotion.GlideEase.transform((TvMotion.PresentMs / 3).toFloat() / TvMotion.PresentMs), third, 0.0001f)
        assertTrue("most of the way in a third of its time: $third", third > 0.7f)
    }

    @Test fun startedAgainItGoesOnFromWhereItIs() {
        // Turned back half-way (a card asked back while gliding out): from there, not from either end.
        val t = GlideTrack()
        t.start(from = 1f, to = 0f, nowMs = 0, ms = 200)
        val there = t.at(100, straight)
        t.start(from = there, to = 1f, nowMs = 100, ms = 300)
        assertEquals(0.5f, t.at(100, straight), 0.0001f)
        assertEquals(0.75f, t.at(250, straight), 0.0001f)
        assertEquals(1f, t.at(400, straight), 0f)
        assertFalse(t.moving)
    }

    @Test fun noTimeAtAllIsThereOnTheNextFrame() {
        // Reduce motion: everything gets where it's going at once.
        val t = GlideTrack()
        t.start(from = 0f, to = 40f, nowMs = 2_000, ms = 0)
        assertEquals(40f, t.at(2_016, straight), 0f)
        assertFalse(t.moving)
    }

    @Test fun stoppedItStaysPut() {
        val t = GlideTrack()
        t.start(from = 0f, to = 1f, nowMs = 0, ms = 300)
        t.stop()
        assertFalse(t.moving)
    }
}
