package com.woodruff.tvexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** The viewer and player get at most 300 paths around the one opened: an intent's extras cross a ~1 MB binder buffer. */
class OpenerWindowTest {
    private val photos = (1..1_000).map { "/p/$it.jpg" }

    @Test fun aLongListIsCutAroundTheOneOpened() {
        val w = Opener.window(photos, "/p/500.jpg")
        assertEquals(300, w.size)
        assertEquals("/p/350.jpg", w.first())
        assertEquals("/p/649.jpg", w.last())
        assertEquals(150, w.indexOf("/p/500.jpg"))
    }

    @Test fun nearTheEndsItStaysFull() {
        assertEquals(photos.take(300), Opener.window(photos, "/p/3.jpg"))
        assertEquals(photos.takeLast(300), Opener.window(photos, "/p/999.jpg"))
    }

    @Test fun aShortListIsWholeAndAMissingOneStartsAtTheTop() {
        val few = photos.take(12)
        assertEquals(few, Opener.window(few, "/p/7.jpg"))
        assertEquals(photos.take(300), Opener.window(photos, "/p/not-there.jpg"))
        assertEquals(listOf("/p/1.jpg", "/p/2.jpg"), Opener.window(photos, "/p/1.jpg", max = 2))
    }
}
