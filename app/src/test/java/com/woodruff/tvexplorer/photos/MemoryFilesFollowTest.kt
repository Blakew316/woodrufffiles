package com.woodruff.tvexplorer.photos

import android.app.Application
import android.os.Environment
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.screensaver.Appearance
import com.woodruff.tvexplorer.ui.MainViewModel
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
 * The TV's own file browser deleting, renaming or moving something in Family Memories: as on the LG (memoryGone), a
 * Google Photos pick deleted there is let go of, so a different file later given its name is never removed by a pick
 * that replaces it or by Remove all; renamed in its folder it stays the pick; moved out of it, it's no pick any more.
 * When it was taken and the TV's background go with it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryFilesFollowTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val list get() = File(app.filesDir, "google_photos_picked.json")
    private val elsewhere get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Kept")
    private val store = ViewModelStore()
    private lateinit var vm: MainViewModel

    @Before fun fresh() {
        clean()
        vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MainViewModel::class.java]
    }

    @After fun putBack() {
        // The view model's work stops with it, and what it left on the main thread runs now, not in a later test.
        store.clear()
        clean()
    }

    private fun clean() {
        GooglePhotos.folder.deleteRecursively()
        GooglePhotos.videoFolder.deleteRecursively()
        elsewhere.deleteRecursively()
        list.delete()
        Appearance.setBackground(app, null)
        GooglePhotos.resetForTest()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun file(dir: File, name: String, bytes: Int) = File(dir.apply { mkdirs() }, name).apply { writeBytes(ByteArray(bytes)) }

    /** Picks downloaded Beach.jpg (100 bytes) and Clip.mp4 (300); a phone added IMG_0001.jpg. */
    private fun picks(): File {
        val beach = file(GooglePhotos.folder, "Beach.jpg", 100)
        file(GooglePhotos.videoFolder, "Clip.mp4", 300)
        file(GooglePhotos.folder, "IMG_0001.jpg", 50)
        list.writeText("""{"a":{"f":"Beach.jpg","v":false},"b":{"f":"Clip.mp4","v":true}}""")
        GooglePhotos.ready()
        return beach
    }

    /** Waits for the browser's operation to end saying [done]: its main-thread steps on the test's looper. */
    private fun awaitSaid(done: String) {
        val until = System.currentTimeMillis() + 10_000
        while (vm.message.value != done && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(done, vm.message.value)
    }

    private fun finish(job: Job) {
        val until = System.currentTimeMillis() + 10_000
        while (!job.isCompleted && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Remove all finished", job.isCompleted)
    }

    /** The count Google Photos' window shows, read again. */
    private fun onTv(): GooglePhotos.OnTv {
        GooglePhotos.forget(File(GooglePhotos.folder, "Nothing.jpg"))
        return GooglePhotos.onTv.value
    }

    @Test fun aPickDeletedInTheBrowserIsLetGoOf() {
        val beach = picks()
        Appearance.setBackground(app, beach.absolutePath)
        vm.delete(listOf(beach))
        awaitSaid("Deleted 1 item(s)")
        assertEquals(GooglePhotos.OnTv(photos = 0, videos = 1, bytes = 300), onTv())
        assertNull("the background went with it", Appearance.background)
        // A different photo given the name later (from another app, say) isn't the pick's.
        val other = file(GooglePhotos.folder, "Beach.jpg", 40)
        finish(GooglePhotos.removeAll())
        assertTrue("the new Beach.jpg stays", other.isFile)
        assertFalse("the video pick went", File(GooglePhotos.videoFolder, "Clip.mp4").exists())
        assertTrue("the phone's photo stays", File(GooglePhotos.folder, "IMG_0001.jpg").isFile)
    }

    @Test fun aPickRenamedInItsFolderStaysThePickWithItsDateAndTheBackground() {
        val beach = picks()
        val taken = 1_400_000_000_000L
        MediaDates.record(app, beach, taken)
        MediaDates.save(app)
        // Only the index knows when it was taken (a Google Photos copy has no EXIF).
        beach.setLastModified(1_700_000_000_000L)
        Appearance.setBackground(app, beach.absolutePath)
        vm.rename(beach, "Sea.jpg")
        awaitSaid("Renamed to Sea.jpg")
        val sea = File(GooglePhotos.folder, "Sea.jpg")
        assertTrue(sea.isFile)
        assertEquals(GooglePhotos.OnTv(photos = 1, videos = 1, bytes = 400), onTv())
        assertEquals(taken, MediaDates.quick(app, listOf(sea)).first[sea.absolutePath])
        assertEquals(sea.absolutePath, Appearance.background)
        // Remove all takes the renamed pick, and nothing that took its old name.
        val other = file(GooglePhotos.folder, "Beach.jpg", 40)
        finish(GooglePhotos.removeAll())
        assertFalse("the renamed pick went", sea.exists())
        assertTrue("the new Beach.jpg stays", other.isFile)
        assertTrue("the phone's photo stays", File(GooglePhotos.folder, "IMG_0001.jpg").isFile)
    }

    @Test fun aPickMovedOutOfFamilyMemoriesIsNoPickAnyMore() {
        val beach = picks()
        elsewhere.mkdirs()
        vm.copyToClipboard(listOf(beach), move = true)
        vm.openFolder(elsewhere)
        shadowOf(Looper.getMainLooper()).idle()
        vm.paste()
        awaitSaid("Pasted 1 item(s)")
        val moved = File(elsewhere, "Beach.jpg")
        assertTrue(moved.isFile)
        assertEquals(GooglePhotos.OnTv(photos = 0, videos = 1, bytes = 300), onTv())
        val other = file(GooglePhotos.folder, "Beach.jpg", 40)
        finish(GooglePhotos.removeAll())
        assertTrue("the new Beach.jpg stays", other.isFile)
        assertTrue("the moved photo stays where it went", moved.isFile)
    }

    @Test fun deletingTheWholeFolderLetsGoOfEveryPickInIt() {
        picks()
        vm.delete(listOf(GooglePhotos.folder))
        awaitSaid("Deleted 1 item(s)")
        assertEquals(GooglePhotos.OnTv(photos = 0, videos = 1, bytes = 300), onTv())
        val other = file(GooglePhotos.folder, "Beach.jpg", 40)
        finish(GooglePhotos.removeAll())
        assertTrue("the new Beach.jpg stays", other.isFile)
    }

    @Test fun aPhotoCopiedInTakesNoOldClaimOnItsName() {
        // The pick list still names Beach.jpg, though the file went another way (another app deleted it).
        picks().delete()
        val outside = file(elsewhere, "Beach.jpg", 60)
        vm.copyToClipboard(listOf(outside), move = false)
        vm.openFolder(GooglePhotos.folder)
        shadowOf(Looper.getMainLooper()).idle()
        vm.paste()
        awaitSaid("Pasted 1 item(s)")
        val copied = File(GooglePhotos.folder, "Beach.jpg")
        assertTrue(copied.isFile)
        finish(GooglePhotos.removeAll())
        assertTrue("the copy stays", copied.isFile)
    }
}
