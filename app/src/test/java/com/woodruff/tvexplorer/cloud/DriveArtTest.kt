package com.woodruff.tvexplorer.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drive's thumbnails asked for at the size they're shown, kept by the file version they show, and finding a poster for what's being watched. */
class DriveArtTest {
    @Test fun thumbnailsAreAskedForAtPosterSize() {
        assertEquals("https://lh3.googleusercontent.com/abc=s720", DriveApi.big("https://lh3.googleusercontent.com/abc=s220"))
        assertEquals("https://lh3.googleusercontent.com/abc=s720", DriveApi.big("https://lh3.googleusercontent.com/abc=s220-c"))
        assertEquals("https://lh3.googleusercontent.com/abc=s720", DriveApi.big("https://lh3.googleusercontent.com/abc"))
    }

    // Drive hands out a new short-lived link for the same picture every few hours.
    private val monday = "https://lh3.googleusercontent.com/drive-storage/AAA111=s220"
    private val tuesday = "https://lh3.googleusercontent.com/drive-storage/BBB222=s220"

    @Test fun aPictureIsKeptByTheFileVersionItShowsNotByItsLink() {
        val a = DriveArt.tag(monday, "file1", 1_700_000_000_000)
        val b = DriveArt.tag(tuesday, "file1", 1_700_000_000_000)
        assertEquals("$monday#drive:file1:1700000000000", a)
        assertEquals("drive:file1:1700000000000=s220", DriveArt.key(a))
        // A new link for the same picture: the same key, so it's found in the caches.
        assertEquals(DriveArt.key(a), DriveArt.key(b))
        // The file changed in Drive (a new poster put in its place): a new key, so its new picture is fetched.
        assertNotEquals(DriveArt.key(a), DriveArt.key(DriveArt.tag(tuesday, "file1", 1_700_000_999_000)))
        // Another file, or the same at another size: kept apart.
        assertNotEquals(DriveArt.key(a), DriveArt.key(DriveArt.tag(monday, "file2", 1_700_000_000_000)))
        assertNotEquals(DriveArt.key(a), DriveArt.key(DriveApi.big(a)))
        // The link itself is what Google is asked for; a tag again replaces the one it had.
        assertEquals(monday, DriveArt.link(a))
        assertEquals("$tuesday#drive:file1:5", DriveArt.tag(b, "file1", 5))
        // TMDB's addresses never change, and a link saved before tags has none: their own keys, as before.
        assertNull(DriveArt.key("https://image.tmdb.org/t/p/w342/abc.jpg"))
        assertNull(DriveArt.key(monday))
    }

    @Test fun sizingALinkKeepsItsTag() {
        val tagged = DriveArt.tag(monday, "f", 7)
        assertEquals("https://lh3.googleusercontent.com/drive-storage/AAA111=s720#drive:f:7", DriveApi.big(tagged))
        assertEquals("drive:f:7=s720", DriveArt.key(DriveApi.big(tagged)))
        // A poster image (tall) at a tile's size, an episode's frame at its card's, a wide picture at 720.
        assertEquals("https://lh3.googleusercontent.com/drive-storage/AAA111=s480#drive:f:7", DriveApi.poster(tagged))
        assertEquals("https://lh3.googleusercontent.com/drive-storage/AAA111=s400#drive:f:7", DriveApi.card(tagged))
        assertEquals(DriveApi.big(tagged), DriveApi.wide(DriveApi.poster(tagged)))
        // TMDB's art as it is.
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", DriveApi.wide("https://image.tmdb.org/t/p/w342/abc.jpg"))
    }

    private fun film(link: String?, modified: Long = 10, poster: String? = null, name: String = "Heat (1995).mkv") =
        DriveFile("heat", name, "video/x-matroska", 1, modified, link?.let { DriveArt.tag(it, "heat", modified) }, poster)

    @Test fun listingsAreComparedWithoutTheirLinks() {
        val posterMonday = DriveApi.poster(DriveArt.tag(monday, "poster1", 3))
        val posterTuesday = DriveApi.poster(DriveArt.tag(tuesday, "poster1", 3))
        val before = listOf(film(monday, poster = posterMonday), film(null, name = "Ronin (1998).mkv"))
        val after = listOf(film(tuesday, poster = posterTuesday), film(null, name = "Ronin (1998).mkv"))
        assertNotEquals(before, after)
        assertTrue(DriveArt.sameIgnoringLinks(before, after))
        // Anything else that changed is a change: the file's version, its name, a new poster, TMDB's art, a title more.
        assertFalse(DriveArt.sameIgnoringLinks(listOf(film(monday)), listOf(film(tuesday, modified = 11))))
        assertFalse(DriveArt.sameIgnoringLinks(listOf(film(monday)), listOf(film(tuesday, name = "Heat.mkv"))))
        assertFalse(DriveArt.sameIgnoringLinks(listOf(film(monday, poster = posterMonday)), listOf(film(tuesday, poster = DriveApi.poster(DriveArt.tag(tuesday, "poster2", 3))))))
        assertFalse(DriveArt.sameIgnoringLinks(listOf(film(monday, poster = "https://image.tmdb.org/t/p/w342/a.jpg")), listOf(film(monday, poster = "https://image.tmdb.org/t/p/w342/b.jpg"))))
        assertFalse(DriveArt.sameIgnoringLinks(listOf(film(monday)), listOf(film(monday), film(null, name = "Ronin (1998).mkv"))))
        // A thumbnail Drive has made by now is a change too.
        assertFalse(DriveArt.sameIgnoringLinks(listOf(film(null)), listOf(film(monday))))
        assertTrue(DriveArt.sameIgnoringLinks(null, null))
        assertFalse(DriveArt.sameIgnoringLinks(null, emptyList()))
    }

    @Test fun aSavedListingsLinksAreRenewedOnceTheyreAnHourOld() {
        val saved = 1_800_000_000_000L
        assertTrue(DriveArt.savedLinksLast(saved, saved + DriveArt.SAVED_LINKS_MS - 1))
        assertFalse(DriveArt.savedLinksLast(saved, saved + DriveArt.SAVED_LINKS_MS))
        // Nothing saved, or the TV's clock set back: saved now.
        assertFalse(DriveArt.savedLinksLast(0, saved))
        assertFalse(DriveArt.savedLinksLast(saved, saved - 60_000))
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
