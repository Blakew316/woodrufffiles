package com.woodruff.tvexplorer.player

import androidx.media3.exoplayer.SeekParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seeks land on the first clean frame just after the spot, never before it, and exactly where
 * exactness matters. Resolved by Media3's own SeekParameters, as the player resolves them against
 * a file's keyframes (the previous one at or before the spot, the next one after it).
 */
class SeekAimTest {
    private fun land(spotS: Double, keyBeforeS: Double, keyAfterS: Double, p: SeekParameters = SeekAim.SNAP): Double =
        p.resolveSeekPositionUs(us(spotS), us(keyBeforeS), us(keyAfterS)) / 1_000_000.0

    private fun us(s: Double) = (s * 1_000_000).toLong()

    @Test fun snapsToTheNextKeyframeWhenItsClose() {
        // A Blu-ray: a keyframe every second or less.
        assertEquals(100.8, land(100.0, 99.6, 100.8), 1e-9)
        assertEquals(101.5, land(100.0, 99.0, 101.5), 1e-9)
    }

    @Test fun exactWhenNoKeyframeIsClose() {
        // A web film whose next keyframe is seconds away: exactly on the spot, as before.
        assertEquals(100.0, land(100.0, 98.0, 104.0), 1e-9)
        assertEquals(100.0, land(100.0, 99.9, 101.6), 1e-9)
    }

    @Test fun aKeyframeOnTheSpotIsTheSpot() {
        assertEquals(100.0, land(100.0, 100.0, 101.0), 1e-9)
    }

    @Test fun neverBeforeTheSpot() {
        // However close the keyframe before is: a step back must stay inside the seconds kept
        // behind the film, or the rewind downloads them again.
        for (before in listOf(99.99, 99.5, 98.6)) for (after in listOf(100.2, 101.4, 103.0)) {
            assertTrue("$before / $after", land(100.0, before, after) >= 100.0)
        }
        assertEquals(0L, SeekAim.SNAP.toleranceBeforeUs)
        assertEquals(SeekAim.SNAP_MS * 1_000, SeekAim.SNAP.toleranceAfterUs)
    }

    @Test fun watchTogetherIsExact() {
        assertSame(SeekParameters.EXACT, SeekAim.parametersFor(targetMs = 600_000, bufferedMs = 900_000, exact = true))
        assertEquals(100.0, land(100.0, 99.6, 100.8, SeekAim.parametersFor(100_000, 900_000, exact = true)), 1e-9)
    }

    @Test fun theViewersSeeksSnap() {
        val playing = 1_200_000L
        val loaded = playing + 40_000
        // A press back, inside what's kept behind the film.
        assertSame(SeekAim.SNAP, SeekAim.parametersFor(playing - 10_000, loaded, exact = false))
        // Well inside what's loaded ahead.
        assertSame(SeekAim.SNAP, SeekAim.parametersFor(playing + 10_000, loaded, exact = false))
        // Past what's loaded: from Drive either way, from the keyframe on.
        assertSame(SeekAim.SNAP, SeekAim.parametersFor(playing + 120_000, loaded, exact = false))
        assertSame(SeekAim.SNAP, SeekAim.parametersFor(playing - 300_000, loaded, exact = false))
    }

    @Test fun justShortOfTheEndOfWhatsLoadedIsExact() {
        val loaded = 1_211_000L
        // The keyframe after 1:20:10 may not have been loaded yet: snapping to it would throw away
        // what's loaded and ask Drive again, where an exact seek plays on from memory.
        assertSame(SeekParameters.EXACT, SeekAim.parametersFor(1_210_000, loaded, exact = false))
        assertSame(SeekParameters.EXACT, SeekAim.parametersFor(loaded, loaded, exact = false))
        assertSame(SeekAim.SNAP, SeekAim.parametersFor(loaded - SeekAim.SNAP_MS, loaded, exact = false))
        assertSame(SeekAim.SNAP, SeekAim.parametersFor(loaded + 1, loaded, exact = false))
    }

    @Test fun theLogSaysWhatTheSeekWas() {
        assertEquals("back 10 s, kept", SeekAim.describe(1_200_900, 1_190_900, kept = true))
        assertEquals("ahead 125 s", SeekAim.describe(1_200_000, 1_325_000, kept = false))
    }

    @Test fun keptMeansInsideWhatThePlayerHolds() {
        val playing = 1_200_000L
        val loaded = playing + 30_000
        assertTrue(SeekAim.isKept(playing - 10_000, playing, loaded, backBufferMs = 12_000))
        assertFalse(SeekAim.isKept(playing - 10_000, playing, loaded, backBufferMs = 5_000))
        assertTrue(SeekAim.isKept(playing + 20_000, playing, loaded, backBufferMs = 12_000))
        assertFalse(SeekAim.isKept(playing + 31_000, playing, loaded, backBufferMs = 12_000))
    }
}
