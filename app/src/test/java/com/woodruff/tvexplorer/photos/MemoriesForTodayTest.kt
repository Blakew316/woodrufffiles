package com.woodruff.tvexplorer.photos

import android.app.Application
import android.os.Environment
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.screensaver.Appearance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Calendar

/**
 * "On this day" is worked out at most once an hour, but never from another folder's photos than the slideshow's, and
 * never with photos deleted since: Home's tile and the slideshow show what's there now.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoriesForTodayTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val pictures get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    private val album get() = File(pictures, "Album")
    private val family get() = File(pictures, "Family")

    @Before fun fresh() = Memories.changed()

    @After fun clean() {
        album.deleteRecursively()
        family.deleteRecursively()
        Appearance.setSlideshowFolder(app, null)
        Memories.changed()
        // Compose hears of those writes now: left for after the test, the next tests' screens stopped updating.
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** A photo in [dir] taken on today's date [yearsAgo] years ago (by its camera-style name). */
    private fun memory(dir: File, yearsAgo: Int): File {
        val c = Calendar.getInstance()
        val name = "IMG_%04d%02d%02d_120000.jpg".format(c.get(Calendar.YEAR) - yearsAgo, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        return File(dir.apply { mkdirs() }, name).apply { writeBytes(ByteArray(8)) }
    }

    private fun today() = Memories.forToday(app) { Appearance.photos(app) }

    @Test fun anotherSlideshowFolderIsWorkedOutAgain() {
        val old = memory(album, 3)
        val mine = memory(family, 5)
        Appearance.setSlideshowFolder(app, album.path)
        assertEquals(listOf(old), today())
        Appearance.setSlideshowFolder(app, family.path)
        assertEquals(listOf(mine), today())
    }

    @Test fun photosDeletedSinceAreLeftOut() {
        val old = memory(album, 3)
        Appearance.setSlideshowFolder(app, album.path)
        assertEquals(listOf(old), today())
        old.delete()
        assertEquals(emptyList<File>(), today())
    }

    @Test fun aChangeToFamilyMemoriesIsWorkedOutAgain() {
        Appearance.setSlideshowFolder(app, album.path.also { album.mkdirs() })
        assertEquals(emptyList<File>(), today())
        val added = memory(album, 2)
        Memories.changed()
        assertEquals(listOf(added), today())
    }

    @Test fun keptForAnHourOfTheSameDayAndFolder() {
        val at = 1_000_000_000L
        val hour = 3_600_000L
        assertTrue(Memories.fresh(2026_300, null, at, 2026_300, null, at + hour - 1))
        assertFalse("an hour on", Memories.fresh(2026_300, null, at, 2026_300, null, at + hour))
        assertFalse("another day", Memories.fresh(2026_300, null, at, 2026_301, null, at + 1))
        assertFalse("another folder", Memories.fresh(2026_300, null, at, 2026_300, "/Pictures/Google Photos", at + 1))
        assertFalse("the clock went back", Memories.fresh(2026_300, null, at, 2026_300, null, at - 1))
    }
}
