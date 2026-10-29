package com.woodruff.tvexplorer.photos

import org.junit.Assert.assertEquals
import org.junit.Test

/** A pick replaces the earlier ones only when it fully arrived, and photos are stored at the size the TV draws. */
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

    @Test fun photosAre1080pUnlessTheTvDrawsIn4k() {
        assertEquals("=w1920-h1080", photoSize(1920, 1080))
        assertEquals("=w1920-h1080", photoSize(1280, 720))
        assertEquals("=w3840-h2160", photoSize(3840, 2160))
        assertEquals("=w3840-h2160", photoSize(2160, 3840))
    }
}
