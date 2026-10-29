package com.woodruff.tvexplorer.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Drive's thumbnails asked for at poster size, and finding a poster for what's being watched. */
class DriveArtTest {
    @Test fun thumbnailsAreAskedForAtPosterSize() {
        assertEquals("https://lh3.googleusercontent.com/abc=s720", DriveApi.big("https://lh3.googleusercontent.com/abc=s220"))
        assertEquals("https://lh3.googleusercontent.com/abc=s720", DriveApi.big("https://lh3.googleusercontent.com/abc=s220-c"))
        assertEquals("https://lh3.googleusercontent.com/abc=s720", DriveApi.big("https://lh3.googleusercontent.com/abc"))
    }

    @Test fun noPostersKnownNoneFound() =
        assertNull(artworkFor(emptyMap(), "drive:x", "Heat (1995).mkv", mapOf("drive:x" to "heat")))

    @Test fun aPosterByTheEntryItSitsUnderElseItsOwnId() {
        val heat = DriveFile("heat", "Heat (1995)", DriveFile.FOLDER_MIME, 0, 0, null, "https://img/heat.jpg")
        val dune = DriveFile("dune", "Dune (2021).mkv", "video/x-matroska", 0, 0, null, "https://img/dune.jpg")
        val index = mapOf("id:heat" to heat, "id:dune" to dune)
        assertEquals(heat, artworkFor(index, "drive:x", "Heat (1995).mkv", mapOf("drive:x" to "heat")))
        assertEquals(dune, artworkFor(index, "drive:dune", "Dune (2021).mkv", emptyMap()))
    }
}
