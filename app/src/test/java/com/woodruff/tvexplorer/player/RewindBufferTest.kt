package com.woodruff.tvexplorer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * One press back lands inside what the player keeps, checked against Media3's own buffer: the
 * SampleQueues that hold a film's sound and picture, trimmed the way the player trims them as it
 * plays (everything before [the position minus the back buffer], from the keyframe before), then
 * asked to seek the way a progressive film seeks: from memory only if every sound and picture queue
 * still holds the spot, otherwise from Drive, throwing away what was loaded ahead as well.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RewindBufferTest {
    /** A film loaded from 0 to 60 s: sound in 21 ms frames, a picture keyframe every [gopUs]. */
    private class Loaded(private val gopUs: Long) {
        private val sound = queue(MimeTypes.AUDIO_AAC)
        private val picture = queue(MimeTypes.VIDEO_H264)

        init {
            var t = 0L
            while (t < 60_000_000) { write(sound, t, key = true); t += 21_333 }
            var i = 0
            while (i * FRAME_US < 60_000_000) { write(picture, i * FRAME_US, key = (i * FRAME_US) % gopUs < FRAME_US); i++ }
        }

        /** Played up to [liveUs], keeping [keptMs] behind it: the player's discardBuffer while playing. */
        fun playedTo(liveUs: Long, keptMs: Int): Loaded {
            for (q in listOf(sound, picture)) {
                q.skip(q.getSkipCount(liveUs, false))
                q.discardTo(liveUs - keptMs * 1_000L, /* toKeyframe = */ true, /* stopAtReadPosition = */ true)
            }
            return this
        }

        /** Where a seek to [spotUs] lands with [params], resolved against this film's keyframes. */
        fun resolve(spotUs: Long, params: SeekParameters): Long {
            val before = (spotUs / gopUs) * gopUs
            val after = if (before == spotUs) spotUs else before + gopUs
            return params.resolveSeekPositionUs(spotUs, before, after)
        }

        /** Whether a seek to [us] is played from memory (every sound and picture queue holds it). */
        fun keeps(us: Long): Boolean {
            val inSound = sound.seekTo(us, false)
            val inPicture = picture.seekTo(us, false)
            return inSound && inPicture
        }

        private fun queue(mime: String) = SampleQueue.createWithoutDrm(DefaultAllocator(true, 1024)).apply {
            format(Format.Builder().setSampleMimeType(mime).build())
        }

        private fun write(q: SampleQueue, timeUs: Long, key: Boolean) {
            q.sampleData(ParsableByteArray(1), 1)
            q.sampleMetadata(timeUs, if (key) C.BUFFER_FLAG_KEY_FRAME else 0, 1, 0, null)
        }

        companion object {
            /** 23.976 frames a second, as on a Blu-ray. */
            const val FRAME_US = 41_708L
        }
    }

    private val bluRayGop = 1_001_000L
    private val webGop = 4_004_000L
    /** How much later than the app's reading of the position the player trims, on its own thread. */
    private val trimLagUs = 50_000L
    private val step = TvTimeBar.stepFor(0)

    @Test fun onePressBackWhilePlayingIsPlayedFromMemory() {
        // Pressed at 30.0 s (the bar a second old, but the scrub starts from the film itself); played
        // 1.2 s later with the film at 31.2 s.
        val landed = TvTimeBar.landing(30_000L - step, startLive = 30_000, nowLive = 31_200, duration = 3_600_000)
        for (gop in listOf(bluRayGop, webGop)) {
            val film = Loaded(gop).playedTo(31_200_000 + trimLagUs, BACK_BUFFER_MS)
            assertTrue("gop $gop", film.keeps(film.resolve(landed * 1_000, SeekAim.SNAP)))
            assertTrue("gop $gop, exact", Loaded(gop).playedTo(31_200_000 + trimLagUs, BACK_BUFFER_MS).keeps(landed * 1_000))
        }
    }

    @Test fun aPausedPressBackIsPlayedFromMemory() {
        val landed = TvTimeBar.landing(30_000L - step, 30_000, 30_000, 3_600_000)
        for (gop in listOf(bluRayGop, webGop)) {
            val film = Loaded(gop).playedTo(30_000_000, BACK_BUFFER_MS)
            assertTrue("gop $gop", film.keeps(film.resolve(landed * 1_000, SeekAim.SNAP)))
        }
    }

    @Test fun itUsedToLandJustOutsideTheTenSecondsKept() {
        // Before: the spot taken from the bar's last value (0.6 s old), played as shown 1.2 s later,
        // with 10 s kept and exact seeks.
        val shownUs = (30_000L - 600 - step) * 1_000
        assertFalse(Loaded(bluRayGop).playedTo(31_200_000 + trimLagUs, 10_000).keeps(shownUs))
    }

    @Test fun aimingAloneLandsOnTheEdgeOfTenSecondsKept() {
        // Why 12 s: aimed from the film, a press back lands exactly 10 s behind it, and the player
        // trims a few ms later on its own thread, so 10 s kept would let go of that very spot.
        val landed = TvTimeBar.landing(30_000L - step, 30_000, 31_200, 3_600_000)
        assertFalse(Loaded(bluRayGop).playedTo(31_200_000 + trimLagUs, 10_000).keeps(landed * 1_000))
    }

    @Test fun aSnapBackwardWouldThrowTheRewindAway() {
        // A web film with keyframes 4 s apart, paused: the keyframe before the spot is older than
        // the sound kept, so snapping back to it downloads again. Snapping forward stays in memory.
        val spotUs = (30_000L - step) * 1_000
        val forward = Loaded(webGop).playedTo(30_000_000, BACK_BUFFER_MS)
        assertTrue(forward.keeps(forward.resolve(spotUs, SeekAim.SNAP)))
        val backward = Loaded(webGop).playedTo(30_000_000, BACK_BUFFER_MS)
        assertFalse(backward.keeps(backward.resolve(spotUs, SeekParameters.PREVIOUS_SYNC)))
    }

    @Test fun onASmallHeapATenSecondRewindStillDownloads() {
        // The documented trade-off: 5 s kept where memory is tight.
        val landed = TvTimeBar.landing(30_000L - step, 30_000, 31_200, 3_600_000)
        val film = Loaded(bluRayGop).playedTo(31_200_000 + trimLagUs, SMALL_HEAP_BACK_BUFFER_MS)
        assertFalse(film.keeps(film.resolve(landed * 1_000, SeekAim.SNAP)))
    }
}
