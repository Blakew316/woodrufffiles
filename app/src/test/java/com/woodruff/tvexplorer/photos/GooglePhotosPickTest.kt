package com.woodruff.tvexplorer.photos

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A pick replaces the earlier ones only when it fully arrived, one stopped part-way never leaves the window stuck, and
 * photos are stored at the size the TV draws.
 */
class GooglePhotosPickTest {
    @Test fun replacingRemovesTheEarlierPicksNotInTheNewOne() {
        val earlier = setOf("a", "b", "c", "earlier:p:IMG_1.jpg")
        assertEquals(setOf("a", "earlier:p:IMG_1.jpg"), leftOver(earlier, listOf("b", "c", "d"), replacing = true, failed = 0))
    }

    @Test fun addingRemovesNothing() {
        assertEquals(emptySet<String>(), leftOver(setOf("a", "b"), listOf("c"), replacing = false, failed = 0))
    }

    @Test fun aPickWithFailedDownloadsLeavesTheEarlierOnes() {
        assertEquals(emptySet<String>(), leftOver(setOf("a", "b"), listOf("c"), replacing = true, failed = 1))
    }

    @Test fun anEmptyPickRemovesNothing() {
        assertEquals(emptySet<String>(), leftOver(setOf("a", "b"), emptyList(), replacing = true, failed = 0))
    }

    @Test fun aPickStoppedPartWayThroughItsDownloadsEndsSoTheWindowCanPickAgain() {
        val downloading = GooglePhotos.State(GooglePhotos.Step.DOWNLOADING, done = 3, total = 10, added = 3)
        val failed = pickStopped(downloading, "Couldn’t save IMG_1.jpg")
        assertEquals(GooglePhotos.Step.DONE, failed.step)
        assertEquals(3, failed.added)
        assertEquals("Couldn’t save IMG_1.jpg", failed.error)
        // Closed as the downloads started: done with what arrived, no error.
        assertEquals(downloading.copy(step = GooglePhotos.Step.DONE), pickStopped(downloading, null))
        // Before any download (waiting for the phone), the step stays, with the error.
        val picking = GooglePhotos.State(GooglePhotos.Step.PICKING, qrUrl = "https://photospicker.google.com/abc/autoclose")
        assertEquals(picking.copy(error = "The picker timed out. Try again."), pickStopped(picking, "The picker timed out. Try again."))
        assertEquals(picking, pickStopped(picking, null))
    }

    @Test fun photosAre1080pUnlessTheTvDrawsIn4k() {
        assertEquals("=w1920-h1080", photoSize(1920, 1080))
        assertEquals("=w1920-h1080", photoSize(1280, 720))
        assertEquals("=w3840-h2160", photoSize(3840, 2160))
        assertEquals("=w3840-h2160", photoSize(2160, 3840))
    }
}
