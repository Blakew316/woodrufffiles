package com.woodruff.tvexplorer.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocation
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * How much film the player keeps loaded: behind the playhead, so one press back needs nothing from
 * Drive, and ahead of it, kept topped up so a Wi-Fi drop is ridden out with all it can hold. The
 * decisions are Media3's own (DefaultLoadControl), asked the way the player asks them as it plays.
 */
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

    @Test fun theBufferIsToppedUpAsTheFilmPlaysNotLeftToSinkToFifteenSeconds() {
        for (heapMb in listOf(128, 512)) {
            val control = prepared(playerLoadControl(heapMb))
            assertTrue(control.shouldContinueLoading(playing(bufferedMs = 0)))
            // Full: 50 s ahead.
            assertFalse(control.shouldContinueLoading(playing(bufferedMs = 50_000)))
            // A second played: loading again at once, all the way down. (It used to wait until only
            // 15 s were left before loading again, so a Wi-Fi drop could catch it with little loaded.)
            for (ms in listOf(49_000L, 40_000L, 30_000L, 20_000L, 16_000L)) {
                assertTrue("$heapMb MB heap, $ms ms ahead", control.shouldContinueLoading(playing(bufferedMs = ms)))
            }
        }
    }

    @Test fun atTheMemoryCapItLoadsAgainAsSoonAsThereIsRoom() {
        // A Blu-ray remux reaches the byte cap well before 50 s: loading stops there (the memory limit
        // holds), and starts again as soon as playing frees some room, not once it's down to 15 s.
        val heapMb = 64
        val cap = bufferCapBytes(heapMb)!!
        val control = prepared(playerLoadControl(heapMb))
        val allocator = control.allocator
        val held = ArrayList<Allocation>()
        while (allocator.totalBytesAllocated < cap) held += allocator.allocate()
        assertFalse(control.shouldContinueLoading(playing(bufferedMs = 20_000)))
        assertFalse(control.shouldContinueLoading(playing(bufferedMs = 19_000)))
        // The film plays on and the oldest second is let go of.
        allocator.release(held.removeAt(0))
        assertTrue(control.shouldContinueLoading(playing(bufferedMs = 18_500)))
        held.forEach(allocator::release)
    }

    @Test fun playStartsAsSoonAsASecondIsLoaded() {
        val control = prepared(playerLoadControl(512))
        assertFalse(control.shouldStartPlayback(playing(bufferedMs = 900)))
        assertTrue(control.shouldStartPlayback(playing(bufferedMs = 1_000)))
        // After a stall, two seconds, so it doesn't stop again straight away.
        assertFalse(control.shouldStartPlayback(playing(bufferedMs = 1_500, rebuffering = true)))
        assertTrue(control.shouldStartPlayback(playing(bufferedMs = 2_000, rebuffering = true)))
    }

    private fun prepared(control: DefaultLoadControl) = control.apply { onPrepared(PlayerId.UNSET) }

    /** What the player tells its load control while playing a film with [bufferedMs] loaded ahead. */
    private fun playing(bufferedMs: Long, rebuffering: Boolean = false) = LoadControl.Parameters(
        PlayerId.UNSET, Timeline.EMPTY, MediaSource.MediaPeriodId(Any()), /* playbackPositionUs = */ 0,
        bufferedMs * 1_000, /* playbackSpeed = */ 1f, /* playWhenReady = */ true, rebuffering, C.TIME_UNSET,
    )
}
