package com.woodruff.tvexplorer.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Continue watching keeps 30 Drive videos and, apart, 30 of this TV's own (USB drives, Family Memories): the Drive
 * videos the family watches, here or on its other devices, never push a half-watched home video out, to start at 0.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RecentStoreOwnVideosTest {
    private val c: Context = ApplicationProvider.getApplicationContext()
    private val usb = "/storage/1A2B-3C4D/Movies/Film.mkv"

    @Test fun aHalfWatchedUsbFilmStaysWhateverIsWatchedAfterIt() {
        val store = RecentStore(c)
        store.save(usb, "Film", 1_200_000, 6_000_000)
        (1..35).forEach { store.save("drive:f$it", "Film $it", 600_000, 6_000_000) }
        store.saveUpNext("drive:e1", "Show S01E01")
        val keys = store.all().map { it.key }
        assertTrue(usb in keys)
        assertEquals(RecentStore.MAX, keys.count { it.startsWith("drive:") })
        assertEquals(1_200_000L, store.resumePosition(usb))
        // The newest first, as ever.
        assertEquals("drive:e1", keys.first())
    }

    @Test fun thisTvsOwnAreCappedToo() {
        val store = RecentStore(c)
        (1..35).forEach { store.save("/storage/usb/v$it.mkv", "Video $it", 600_000, 6_000_000) }
        val keys = store.all().map { it.key }
        assertEquals(RecentStore.MAX, keys.size)
        assertEquals("/storage/usb/v35.mkv", keys.first())
    }
}
