package com.woodruff.tvexplorer.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A library's folders matched to one query for every poster picture in the Drive (1,000 a request), rather than one
 * request per 25 folders on every listing; and each poster kept by the file version it shows (org.json needs Robolectric).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DrivePostersTest {
    private fun image(id: String, name: String, vararg parents: String, modified: String = "2026-10-14T00:48:23.123Z") = JSONObject()
        .put("id", id).put("name", name).put("parents", JSONArray(parents.toList()))
        .put("thumbnailLink", "https://lh3.googleusercontent.com/drive-storage/$id-link=s220").put("modifiedTime", modified)

    @Test fun eachFolderGetsItsOwnPosterPreferringPosterThenFolderThenCover() {
        val found = JSONArray()
            .put(image("c1", "cover.jpg", "heat"))
            .put(image("p1", "Poster.PNG", "heat")) // any case: "poster" wins over "cover", whatever order Drive answers in
            .put(image("f1", "folder.jpg", "ronin"))
            .put(image("c2", "cover.jpg", "ronin"))
            .put(image("x1", "posters.jpg", "alien")) // not one of the names
            .put(image("o1", "poster.jpg", "music-album")) // a folder outside the library
            .put(image("s1", "poster.jpg", "dune", "dune-copy"))
        val into = HashMap<String, DriveApi.FolderPoster>()
        DriveApi.postersFrom(found, setOf("heat", "ronin", "alien", "dune"), DriveApi.Rfc3339(), into)
        assertEquals(setOf("heat", "ronin", "dune"), into.keys)
        val heat = into.getValue("heat").url
        assertTrue(heat, heat.startsWith("https://lh3.googleusercontent.com/drive-storage/p1-link=s480#drive:p1:"))
        // Kept by the poster's own version: a new link next listing is the same picture.
        assertEquals("drive:p1:1791938903123=s480", DriveArt.key(heat))
        assertTrue(into.getValue("ronin").url.contains("f1-link"))
        assertNull(into["music-album"])
    }

    @Test fun aLibraryIsMatchedToOneQueryAFewFoldersAreAskedAbout() {
        // A show's seasons: one request about them is cheaper than reading every poster in the Drive.
        assertFalse(DriveApi.postersByOneQuery(5))
        assertFalse(DriveApi.postersByOneQuery(50))
        // A library of 2,000 folders: 80 requests 25 at a time, one to three for the whole Drive's posters.
        assertTrue(DriveApi.postersByOneQuery(51))
        assertTrue(DriveApi.postersByOneQuery(2_000))
    }
}
