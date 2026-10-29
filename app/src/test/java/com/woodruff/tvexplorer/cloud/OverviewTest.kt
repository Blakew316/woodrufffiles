package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.player.RecentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Overview's pieces: what Continue watching shows, its rows, and the featured titles. */
class OverviewTest {
    private fun film(id: String, name: String, rating: Double? = null, backdrop: String? = null) =
        DriveFile(id, name, "video/x-matroska", 0, 0, null, null, MediaInfo(name.substringBeforeLast('.'), null, listOf("Drama"), rating, backdrop = backdrop))

    private fun folder(id: String, name: String) = DriveFile(id, name, DriveFile.FOLDER_MIME, 0, 0, null)

    private fun playing(key: String, title: String, upNext: Boolean = false) = RecentItem(key, title, 600_000, 1_200_000, 0, upNext)

    @Test fun continueWatchingFindsEachTitleOnceNewestFirst() {
        val heat = film("heat", "Heat (1995).mkv")
        val office = folder("office", "The Office (US)")
        val dark = folder("dark", "Dark (2017)")
        val recent = listOf(
            playing("drive:e9", "The Office (US) - S02E05.mkv", upNext = true), // a show, by its name
            playing("drive:heat", "Heat (1995).mkv"), // a film, by its own key
            playing("drive:d1", "S01E01.mkv"), // known to sit under Dark (the name doesn't say)
            playing("drive:e8", "The Office (US) - S02E04.mkv"), // older: The Office already has its tile
            playing("/storage/usb/clip.mp4", "clip.mp4"), // not in this library
        )
        val found = Library.continuing(listOf(heat, office, dark), recent, mapOf("drive:d1" to "dark"))
        assertEquals(listOf("office", "heat", "dark"), found.map { it.first.id })
        // Each tile knows what it stands for: The Office's is the episode up next.
        assertEquals("drive:e9", found.first().second.key)
        assertTrue(found.first().second.upNext)
    }

    @Test fun continueWatchingCanUseRememberedMatchKeys() {
        val office = folder("office", "The Office (US)")
        val asked = ArrayList<String>()
        val key: (String) -> String = { asked += it; Library.showMatchKey(it) }
        // Found by its own key: no names worked out at all.
        Library.continuing(listOf(office), listOf(playing("drive:office", "x")), emptyMap(), key)
        assertTrue(asked.isEmpty())
        Library.continuing(listOf(office), listOf(playing("drive:e1", "The Office (US) - S01E01.mkv")), emptyMap(), key)
        assertEquals(listOf("The Office (US)", "The Office (US) - S01E01.mkv"), asked)
    }

    @Test fun overviewPutsContinueWatchingFirstAndNowhereElse() {
        val films = (0 until 30).map { i ->
            DriveFile("m$i", "Film $i.mp4", "video/mp4", 0, 0, null, null, MediaInfo("Film $i", 2000 + i, listOf("Drama"), 5.0 + i % 10 * 0.4), created = i.toLong())
        }
        val watching = listOf(films[29], films[3])
        val rows = Library.overview(films, watching)
        assertEquals("Continue watching" to watching, rows.first())
        assertTrue(rows.drop(1).flatMap { it.second }.none { it.id == "m29" || it.id == "m3" })
        // Nothing in progress: no row for it.
        assertEquals("Recently added", Library.overview(films, emptyList()).first().first)
    }

    @Test fun whatTheOverviewShowsFirstIsLoadedAhead() {
        val films = (0 until 30).map { i ->
            DriveFile(
                "m$i", "Film $i.mp4", "video/mp4", 0, 0, null, "https://image.tmdb.org/t/p/w342/p$i.jpg",
                MediaInfo("Film $i", 2000 + i, listOf("Drama"), 7.0, backdrop = "https://image.tmdb.org/t/p/w1280/b$i.jpg"), created = i.toLong(),
            )
        }
        val (backdrop, posters) = Library.overviewArt(films, listOf(films[3]), 7L)
        // The banner's first title, as the Overview picks it that day.
        assertEquals(Library.info(Library.featured(films, 7L).first()).backdrop, backdrop)
        // Continue watching's one, then the first six of Recently added (the newest), not the first in A–Z order.
        assertEquals(listOf(3, 29, 28, 27, 26, 25, 24).map { "https://image.tmdb.org/t/p/w342/p$it.jpg" }, posters)
        // Nothing with wide art: no picture to load for the banner.
        assertEquals(null, Library.overviewArt(films.map { it.copy(info = it.info?.copy(backdrop = null)) }, emptyList(), 7L).first)
    }

    @Test fun featuredIsFiveWithWideArtTheSameAllDay() {
        val withArt = (0 until 8).map { film("a$it", "A$it.mkv", rating = 7.0, backdrop = "https://image.tmdb.org/t/p/w1280/a$it.jpg") }
        val noArt = (0 until 4).map { film("n$it", "N$it.mkv", rating = 9.0) }
        val today = Library.featured(withArt + noArt, 2026_302L)
        assertEquals(5, today.size)
        assertTrue(today.all { Library.info(it).backdrop != null })
        // The same five all day, on every TV (and in the warm-up that loads the first one's picture).
        assertEquals(today, Library.featured(withArt + noArt, 2026_302L))
    }

    @Test fun featuredPrefersWellRatedTitles() {
        val good = (0 until 5).map { film("g$it", "G$it.mkv", rating = 7.5, backdrop = "https://image.tmdb.org/t/p/w1280/g$it.jpg") }
        val poor = (0 until 5).map { film("p$it", "P$it.mkv", rating = 5.0, backdrop = "https://image.tmdb.org/t/p/w1280/p$it.jpg") }
        assertEquals(good.map { it.id }.toSet(), Library.featured(good + poor, 1L).map { it.id }.toSet())
        // Too few well rated: any with wide art.
        assertEquals(5, Library.featured(good.take(2) + poor, 1L).size)
    }
}
