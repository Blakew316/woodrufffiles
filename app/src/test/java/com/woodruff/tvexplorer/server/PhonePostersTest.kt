package com.woodruff.tvexplorer.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.FolderCache
import com.woodruff.tvexplorer.cloud.ListingCache
import com.woodruff.tvexplorer.cloud.MediaInfo
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Drive frames the phone's Watch tab shows through the TV (/api/poster?kind=still&in=…): only of videos in a
 * folder the Watch tab lists (a show, a season of one, a collection of films), never of any other folder of the
 * Drive, even one this TV holds a listing of (from its own Streaming screen, say), which isn't even looked into.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhonePostersTest {
    private val c: Context = ApplicationProvider.getApplicationContext()
    private val folder = DriveFile.FOLDER_MIME

    private fun video(id: String, name: String) = DriveFile(id, name, "video/mp4", 100, 7, "https://lh3.googleusercontent.com/$id=s220")

    private val backyard = DriveFile("s1", "Backyard Science", folder, 0, 1, null, null, MediaInfo("Backyard Science", 2023))
    private val lighthouse = DriveFile("s2", "The Lighthouse", folder, 0, 1, null, null, MediaInfo("The Lighthouse", null))
    private val harborFilms = DriveFile("c1", "Midnight Harbor Collection", folder, 0, 1, null, null, MediaInfo("Midnight Harbor Collection", null))
    private val season1 = DriveFile("f1", "Season 1", folder, 0, 1, null)

    @Before fun setUp() {
        ListingCache.write(c, DrivePlace.Movies, listOf(harborFilms))
        ListingCache.write(c, DrivePlace.TvShows, listOf(backyard, lighthouse))
        saved("s1", listOf(season1, video("t1", "Backyard Science Trailer.mkv")))
        saved("f1", listOf(video("e11", "Backyard Science S01E01 Tide Pools.mkv")))
        saved("s2", listOf(video("l1", "The Lighthouse 1x01 Arrival.mkv")))
        saved("c1", listOf(video("p1", "Midnight Harbor Part 1.mkv")))
        // A folder outside Movies and TV Shows that this TV happens to hold a listing of.
        saved("elsewhere", listOf(video("x1", "Somebody's private video.mkv")))
        // Each frame already fetched once: what's shown comes from the TV's own copy (no network in tests).
        listOf("t1", "e11", "l1", "p1", "x1").forEach { kept(it) }
    }

    @After fun tearDown() {
        ListingCache.clear(c)
        FolderCache.clear(c)
        File(c.cacheDir, "phone-posters").deleteRecursively()
    }

    /** A folder's listing as the TV saves it after listing it once. */
    private fun saved(id: String, files: List<DriveFile>) {
        val d = File(c.cacheDir, "folders").apply { mkdirs() }
        File(d, FolderCache.fileName(id)).writeText(FolderCache.toJson(files))
    }

    /** The frame of video [id], as the TV keeps it once fetched. */
    private fun kept(id: String) {
        val d = File(c.cacheDir, "phone-posters").apply { mkdirs() }
        File(d, PhonePosters.name("still", video(id, ""))).writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0))
    }

    private fun still(id: String, inFolder: String) =
        PhonePosters.serve(c, phoneRequest("/api/poster", params = mapOf("id" to id, "kind" to "still", "in" to inFolder))).status

    @Test fun framesOfTheWatchTabsOwnVideosAreShown() {
        assertEquals("a season of a show", Status.OK, still("e11", "f1"))
        assertEquals("episodes kept in the show's own folder", Status.OK, still("l1", "s2"))
        assertEquals("a film of a collection", Status.OK, still("p1", "c1"))
    }

    @Test fun noFrameComesFromAnyOtherFolderOfTheDrive() {
        assertEquals(Status.NOT_FOUND, still("x1", "elsewhere"))
        // Never even looked into.
        assertNull(FolderCache.inMemory("elsewhere"))
        // Nor a video named with a folder it isn't in, nor a file of a show's folder named as a folder.
        assertEquals(Status.NOT_FOUND, still("x1", "f1"))
        assertEquals(Status.NOT_FOUND, still("e11", "t1"))
    }

    @Test fun aFolderIsTheWatchTabsOnlyIfItsALibraryEntryOrASeasonOfAShow() {
        val entries = listOf(harborFilms, backyard, lighthouse)
        val shows = listOf(backyard, lighthouse)
        val listings = mapOf("s1" to listOf(season1, video("t1", "Trailer.mkv")), "elsewhere" to listOf(DriveFile("f9", "Private", folder, 0, 1, null)))
        val held: (String) -> List<DriveFile>? = { listings[it] }
        assertTrue(PhonePosters.watchFolder("s1", entries, shows, held))
        assertTrue(PhonePosters.watchFolder("c1", entries, shows, held))
        assertTrue(PhonePosters.watchFolder("f1", entries, shows, held))
        assertFalse("a file in a show's folder", PhonePosters.watchFolder("t1", entries, shows, held))
        assertFalse("a folder in a folder that's no show", PhonePosters.watchFolder("f9", entries, shows, held))
        assertFalse("a season of a show whose listing isn't held", PhonePosters.watchFolder("f1", entries, shows) { null })
        assertFalse(PhonePosters.watchFolder("elsewhere", entries, shows, held))
    }
}
