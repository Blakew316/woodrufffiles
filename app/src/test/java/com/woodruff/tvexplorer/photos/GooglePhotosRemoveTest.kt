package com.woodruff.tvexplorer.photos

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.screensaver.Appearance
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Google Photos' "Remove all" and the window's count of what's on the TV: only the files picks downloaded. The two
 * folders are Family Memories, and the photos and videos the phones added there stay.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GooglePhotosRemoveTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val list get() = File(app.filesDir, "google_photos_picked.json")

    @Before fun fresh() {
        clean()
        GooglePhotos.resetForTest()
    }

    @After fun clean() {
        GooglePhotos.folder.deleteRecursively()
        GooglePhotos.videoFolder.deleteRecursively()
        list.delete()
        Appearance.setSlideshowFolder(app, null)
        Appearance.setBackground(app, null)
        GooglePhotos.resetForTest()
        // Compose hears of those writes now: left for after the test, the next tests' screens stopped updating.
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun file(dir: File, name: String, bytes: Int) = File(dir.apply { mkdirs() }, name).apply { writeBytes(ByteArray(bytes)) }

    /** Picks downloaded Beach.jpg (100 bytes) and Clip.mp4 (300); a phone added IMG_0001.jpg and Party.mov. */
    private fun picksAndPhones(): List<File> {
        val picks = listOf(file(GooglePhotos.folder, "Beach.jpg", 100), file(GooglePhotos.videoFolder, "Clip.mp4", 300))
        file(GooglePhotos.folder, "IMG_0001.jpg", 50)
        file(GooglePhotos.videoFolder, "Party.mov", 70)
        list.writeText("""{"a":{"f":"Beach.jpg","v":false},"b":{"f":"Clip.mp4","v":true}}""")
        return picks
    }

    /** Runs [job] to its end: its main-thread steps on the test's looper, the rest on the IO threads. */
    private fun finish(job: Job) {
        val until = System.currentTimeMillis() + 10_000
        while (!job.isCompleted && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Remove all finished", job.isCompleted)
    }

    @Test fun theCountIsWhatPicksDownloadedNotWhatThePhonesAdded() {
        picksAndPhones()
        // (Anything the pick list hears about counts the folders again.)
        GooglePhotos.forget(File(GooglePhotos.folder, "Gone.jpg"))
        assertEquals(GooglePhotos.OnTv(photos = 1, videos = 1, bytes = 400), GooglePhotos.onTv.value)
    }

    @Test fun removeAllDeletesOnlyThePicks() {
        val picks = picksAndPhones()
        finish(GooglePhotos.removeAll())
        picks.forEach { assertFalse("${it.name} removed", it.exists()) }
        assertTrue("the phone's photo stays", File(GooglePhotos.folder, "IMG_0001.jpg").isFile)
        assertTrue("the phone's video stays", File(GooglePhotos.videoFolder, "Party.mov").isFile)
        assertEquals(GooglePhotos.OnTv(), GooglePhotos.onTv.value)
    }

    @Test fun theSlideshowStaysOnFamilyMemoriesWhileThePhonesPhotosAreThere() {
        picksAndPhones()
        Appearance.setSlideshowFolder(app, GooglePhotos.folder.path)
        finish(GooglePhotos.removeAll())
        assertEquals(GooglePhotos.folder.path, Appearance.slideshowFolder)
    }

    @Test fun withNothingLeftTheSlideshowGoesBackToTheUsualFolders() {
        file(GooglePhotos.folder, "Beach.jpg", 100)
        list.writeText("""{"a":{"f":"Beach.jpg","v":false}}""")
        Appearance.setSlideshowFolder(app, GooglePhotos.folder.path)
        finish(GooglePhotos.removeAll())
        assertNull(Appearance.slideshowFolder)
    }

    @Test fun aBackgroundFromAPickGoesWithIt() {
        val (beach) = picksAndPhones()
        Appearance.setBackground(app, beach.path)
        finish(GooglePhotos.removeAll())
        assertNull(Appearance.background)
    }

    @Test fun aDownloadNeverWritesOverAFileThatsThere() {
        val mine = file(GooglePhotos.folder, "IMG_0001.jpg", 50)
        val taken = GooglePhotos.reserve(GooglePhotos.folder, "IMG_0001.jpg")
        assertEquals("IMG_0001 (1).jpg", taken.name)
        assertEquals(0L, taken.length())
        assertEquals(50L, mine.length())
    }

    @Test fun aBackgroundFromAPhoneStays() {
        picksAndPhones()
        val mine = File(GooglePhotos.folder, "IMG_0001.jpg").path
        Appearance.setBackground(app, mine)
        finish(GooglePhotos.removeAll())
        assertEquals(mine, Appearance.background)
    }
}
