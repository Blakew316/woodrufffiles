package com.woodruff.tvexplorer.cloud

import android.util.JsonReader
import android.util.JsonWriter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.StringReader
import java.io.StringWriter

/** A library's saved copy: read and written as a stream, and it remembers when it was listed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ListingCacheTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val files = listOf(
        DriveFile(
            "1aB-c_D", "Midnight Harbor (2024).mkv", "video/x-matroska", 4_294_967_296, 1_700_000_000_001,
            "https://lh3.googleusercontent.com/abc=s220", "https://image.tmdb.org/t/p/w342/poster.jpg",
            MediaInfo(
                "Midnight Harbor", 2024, listOf("Thriller", "Drama"), 7.4,
                "A harbour town, a storm and a \"quiet\" night.\nSecond line / with a slash.",
                "https://image.tmdb.org/t/p/w1280/back.jpg", 12345, tv = false,
            ),
            created = 1_600_000_000_000,
        ),
        DriveFile("2xY", "Backyard Science", DriveFile.FOLDER_MIME, 0, 1_700_000_000_002, null, null, MediaInfo("Backyard Science", null), 0),
        DriveFile("3zz", "notes.txt", "text/plain", 12, 3, null),
    )

    @After fun clean() = ListingCache.clear(context)

    private fun roundTrip(list: List<DriveFile>, cutAt: Int = 0): Pair<List<DriveFile>, Int> {
        val out = StringWriter()
        JsonWriter(out).use { ListingCache.writeListing(it, list, cutAt) }
        return JsonReader(StringReader(out.toString())).use(ListingCache::readListing)
    }

    @Test fun aListingReadsBackAsItWasSaved() {
        assertEquals(files to 0, roundTrip(files))
        assertEquals(emptyList<DriveFile>() to 0, roundTrip(emptyList()))
    }

    @Test fun aListingCutByTheLimitRemembersWhere() {
        assertEquals(files to 20_000, roundTrip(files, cutAt = 20_000))
        ListingCache.write(context, DrivePlace.Movies, files, cutAt = 20_000)
        assertEquals(20_000, ListingCache.read(context, DrivePlace.Movies)!!.cutAt)
        // Listed again, all of it this time.
        ListingCache.write(context, DrivePlace.Movies, files)
        assertEquals(0, ListingCache.read(context, DrivePlace.Movies)!!.cutAt)
    }

    @Test fun aWholeListingIsSavedAsTheVersionBeforeSavedIt() {
        // A plain array, so going back to the version before still reads it.
        val out = StringWriter()
        JsonWriter(out).use { ListingCache.writeListing(it, files, 0) }
        assertEquals(files.size, JSONArray(out.toString()).length())
    }

    @Test fun aCopySavedByTheVersionBeforeStillReads() {
        // What earlier versions wrote, with org.json (which writes 0.0 as 0 and escapes slashes).
        val old = JSONArray().put(
            JSONObject().put("id", "1aB-c_D").put("n", "Midnight Harbor (2024).mkv").put("m", "video/x-matroska").put("s", 4_294_967_296)
                .put("t", 1_700_000_000_001).put("c", 1_600_000_000_000).put("th", "https://lh3.googleusercontent.com/abc=s220").put("p", "")
                .put("i", JSONObject().put("ti", "Midnight Harbor").put("y", 2024).put("g", JSONArray(listOf("Thriller")))
                    .put("r", 0.0).put("o", "").put("b", "").put("id", 0).put("tv", true)),
        ).toString()
        val (list, cut) = JsonReader(StringReader(old)).use(ListingCache::readListing)
        assertEquals(0, cut)
        val read = list.single()
        assertEquals("https://lh3.googleusercontent.com/abc=s220", read.thumbnailLink)
        assertNull(read.posterUrl)
        assertEquals(MediaInfo("Midnight Harbor", 2024, listOf("Thriller"), null, null, null, null, tv = true), read.info)
        assertEquals(4_294_967_296, read.size)
    }

    @Test fun theSavedCopyKnowsWhenItWasListed() {
        val before = System.currentTimeMillis()
        ListingCache.write(context, DrivePlace.Movies, files)
        val saved = ListingCache.read(context, DrivePlace.Movies)!!
        assertEquals(files, saved.files)
        // File times can be coarse (whole seconds on some file systems).
        assertTrue(saved.listedAt >= before - 2_000)
    }

    @Test fun touchingAnUnchangedCopyMarksItListedNow() {
        ListingCache.write(context, DrivePlace.TvShows, files)
        val dir = File(context.cacheDir, "listings")
        dir.listFiles()!!.forEach { it.setLastModified(1_000_000_000_000) }
        assertEquals(1_000_000_000_000, ListingCache.read(context, DrivePlace.TvShows)!!.listedAt)
        val before = System.currentTimeMillis()
        ListingCache.touch(context, DrivePlace.TvShows)
        val saved = ListingCache.read(context, DrivePlace.TvShows)!!
        assertTrue(saved.listedAt >= before - 2_000)
        assertEquals(files, saved.files)
        // Nothing saved: nothing to touch, and nothing made.
        ListingCache.touch(context, DrivePlace.Movies)
        assertNull(ListingCache.read(context, DrivePlace.Movies))
    }

    @Test fun aListingBegunBeforeASignOutIsNotSavedAfterIt() {
        val begun = ListingCache.generation()
        ListingCache.clear(context)
        ListingCache.write(context, DrivePlace.Movies, files, gen = begun)
        ListingCache.touch(context, DrivePlace.Movies, begun)
        assertNull(ListingCache.read(context, DrivePlace.Movies))
        // The next account's listing is saved as usual.
        ListingCache.write(context, DrivePlace.Movies, files, gen = ListingCache.generation())
        assertEquals(files, ListingCache.read(context, DrivePlace.Movies)!!.files)
    }

    @Test fun aDamagedCopyIsNoCopy() {
        ListingCache.write(context, DrivePlace.Movies, files)
        File(context.cacheDir, "listings").listFiles()!!.single { it.name.endsWith(".json") }.writeText("[{\"id\": \"1\", \"n\": ")
        assertNull(ListingCache.read(context, DrivePlace.Movies))
    }

    @Test fun onlyLibraryPlacesAreSaved() {
        ListingCache.write(context, DrivePlace.SharedWithMe, files)
        assertNull(ListingCache.read(context, DrivePlace.SharedWithMe))
        ListingCache.write(context, DrivePlace.Folder("abc", "Season 1"), files)
        assertEquals(files, ListingCache.read(context, DrivePlace.Folder("abc", "Season 1"))!!.files)
    }
}
