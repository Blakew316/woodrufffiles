package com.woodruff.tvexplorer.player

import androidx.media3.exoplayer.analytics.PlayerId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** How much film the player keeps behind the playhead, so one press back needs nothing from Drive. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerBufferTest {
    @Test fun bigHeapsKeepTwelveSecondsBehindTheFilm() {
        for (heapMb in listOf(256, 384, 512)) {
            val control = playerLoadControl(heapMb)
            assertEquals(12_000_000L, control.getBackBufferDurationUs(PlayerId.UNSET))
            // From the keyframe before it, so every second of it can be played without a download.
            assertTrue(control.retainBackBufferFromKeyframe(PlayerId.UNSET))
        }
    }

    @Test fun smallHeapsKeepFiveSeconds() {
        for (heapMb in listOf(96, 128, 192)) {
            assertEquals(5_000_000L, playerLoadControl(heapMb).getBackBufferDurationUs(PlayerId.UNSET))
        }
    }

    @Test fun onePressBackLandsWellInsideWhatsKept() {
        // A press back is aimed from where the film really is (TvTimeBar.landing): it lands exactly
        // one step behind it, and the seek may only move later from there (SeekAim). Media3 lets go
        // of anything older than the back buffer, measured on its own thread a few ms later; keeping
        // a clear second or more beyond the step means the rewind never lands on that edge.
        val margin = backBufferMs(256) - TvTimeBar.stepFor(0)
        assertTrue("margin $margin ms", margin >= 1_500)
        assertEquals(0L, SeekAim.SNAP.toleranceBeforeUs)
    }
}
