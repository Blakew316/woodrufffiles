package com.woodruff.tvexplorer.player

import android.graphics.Bitmap
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Scrub pictures stay out of the way of the seek the family is waiting for. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScrubPreviewTest {
    private val url = "https://www.googleapis.com/drive/v3/files/abc?alt=media"
    private val key = "drive:abc"

    @After fun tearDown() = ShadowMediaMetadataRetriever.reset()

    @Test fun androidsReaderFetchesOnlyALittleAheadOfAFrame() {
        val headers = ScrubPreview.headersFor(url, mapOf("Authorization" to "Bearer t"))
        assertEquals("Bearer t", headers["Authorization"])
        // NuCachedSource2's own names: low-water 256 KB, high-water 2 MB (not 20), no keep-alive reads.
        assertEquals("256/2048/0", headers["x-cache-config"])
        // And it hangs up once it has that much, rather than leave the connection open.
        assertTrue("x-disconnect-at-highwatermark" in headers)
        // Any address on the network: a link sent from the phone too.
        assertEquals(ScrubPreview.READ_AHEAD_LIMIT, ScrubPreview.headersFor("http://example.test/film.mp4", emptyMap()))
        assertEquals(ScrubPreview.READ_AHEAD_LIMIT, ScrubPreview.headersFor("HTTPS://example.test/film.mp4", emptyMap()))
    }

    @Test fun filesOnTheTvGetNoHeaders() {
        assertEquals(emptyMap<String, String>(), ScrubPreview.headersFor("/storage/1A2B-3C4D/Films/Film.mkv", emptyMap()))
        assertFalse(ScrubPreview.isHttp("content://media/external/video/1"))
        assertFalse(ScrubPreview.isHttp("httpfoo"))
        assertTrue(ScrubPreview.isHttp("https://example.test/a.mkv"))
    }

    /** Frames for the 10 s slots around 0 s, 30 s and 60 s (the reader is asked for each slot's middle). */
    private fun frames() {
        for (s in listOf(5L, 35L, 65L)) {
            ShadowMediaMetadataRetriever.addScaledFrame(
                DataSource.toDataSource(url), s * 1_000_000, ScrubPreview.WIDTH, ScrubPreview.HEIGHT,
                Bitmap.createBitmap(ScrubPreview.WIDTH, ScrubPreview.HEIGHT, Bitmap.Config.ARGB_8888),
            )
        }
    }

    /** A Drive file that takes a while to open: its header and index come first. */
    private class SlowOpen {
        val opening = CountDownLatch(1)
        val open = CountDownLatch(1)
        fun source(key: String): Pair<String, Map<String, String>> {
            opening.countDown()
            open.await(5, TimeUnit.SECONDS)
            return "https://www.googleapis.com/drive/v3/files/abc?alt=media" to emptyMap()
        }
    }

    /**
     * Runs the main thread's queue (where the reader's thread posts its frames) until [done] holds,
     * or 5 s pass. A frame is cached a moment before its post, so the cache alone can be early.
     */
    private fun awaitMain(done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return true
            Thread.sleep(10)
        }
        return false
    }

    @Test fun aScrubThatEndedWhileTheFileOpenedReadsNoNewSpot() {
        frames()
        val slow = SlowOpen()
        val pv = ScrubPreview(slow::source)
        try {
            var shown = 0
            pv.request(key, 0) { shown++ }
            assertTrue(slow.opening.await(5, TimeUnit.SECONDS))
            // The scrub is played (the seek goes out) while the file is still opening.
            pv.cancel()
            slow.open.countDown()
            // The next scrub's spot is read with the file already open; the ended scrub's never was
            // (every frame read is kept in the cache).
            pv.request(key, 60_000) { shown++ }
            assertTrue(awaitMain { shown == 1 })
            assertNotNull(pv.cached(key, 60_000))
            assertNull(pv.cached(key, 0))
        } finally {
            pv.release()
        }
    }

    @Test fun aScrubThatMovedOnWhileTheFileOpenedReadsTheNewSpot() {
        frames()
        val slow = SlowOpen()
        val pv = ScrubPreview(slow::source)
        try {
            val shown = mutableListOf<Bitmap>()
            pv.request(key, 0) { shown += it }
            assertTrue(slow.opening.await(5, TimeUnit.SECONDS))
            // More presses while the file opens: only the latest spot is worth reading.
            pv.request(key, 30_000) { shown += it }
            slow.open.countDown()
            assertTrue(awaitMain { shown.isNotEmpty() })
            assertNotNull(pv.cached(key, 30_000))
            assertNull(pv.cached(key, 0))
            assertEquals(1, shown.size)
        } finally {
            pv.release()
        }
    }

    @Test fun aWantedSpotIsReadOnceTheFileIsOpen() {
        frames()
        val slow = SlowOpen()
        val pv = ScrubPreview(slow::source)
        try {
            var shown: Bitmap? = null
            pv.request(key, 0) { shown = it }
            assertTrue(slow.opening.await(5, TimeUnit.SECONDS))
            slow.open.countDown()
            assertTrue(awaitMain { shown != null })
            assertNotNull(pv.cached(key, 0))
        } finally {
            pv.release()
        }
    }
}
