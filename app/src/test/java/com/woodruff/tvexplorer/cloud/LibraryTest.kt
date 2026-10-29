package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.player.RecentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private fun movie(name: String, modified: Long = 0, info: MediaInfo? = null) =
        DriveFile(name, name, "video/x-matroska", 0, modified, null, null, info)

    private val matrix = movie("The.Matrix.1999.1080p.BluRay.x264.mkv", 3, MediaInfo("The Matrix", 1999, listOf("Action", "Sci-Fi"), 8.2))
    private val amelie = movie("Amélie (2001).mp4", 1, MediaInfo("Amélie", 2001, listOf("Comedy", "Romance"), 7.9))
    private val dune = movie("[YTS] Dune Part Two (2024) [2160p].mp4", 2, MediaInfo("Dune Part Two", 2024, listOf("Sci-Fi", "Adventure"), 8.4))
    private val nemo = movie("Finding_Nemo.mp4", 4) // no TMDB info and no year
    private val all = listOf(matrix, amelie, dune, nemo)

    private fun titles(files: List<DriveFile>) = files.map { Library.info(it).title }

    @Test fun parsesNamesWithoutTmdb() {
        assertEquals(MediaInfo("Finding Nemo", null), Library.info(nemo))
        assertEquals("Inception (2010)", Library.label(movie("Inception.2010.720p.WEB-DL.mkv")))
        assertEquals("Doctor Who (2005)", Library.label(DriveFile("d", "Doctor Who (2005)", DriveFile.FOLDER_MIME, 0, 0, null)))
    }

    @Test fun showMatchKeyPairsFoldersWithTheirEpisodes() {
        val k = Library.showMatchKey("Breaking Bad (2008)")
        assertEquals("breaking bad", k)
        assertEquals(k, Library.showMatchKey("Breaking Bad (2008) - S01E01 - Pilot.mkv"))
        assertEquals(k, Library.showMatchKey("Breaking.Bad.2008.S01E01.mkv"))
        assertEquals(Library.showMatchKey("The Office (US)"), Library.showMatchKey("The Office (US) - S02E04.mkv"))
    }

    @Test fun titleSortIgnoresArticlesAndAccents() =
        assertEquals(listOf("Amélie", "Dune Part Two", "Finding Nemo", "The Matrix"), titles(Library.apply(all, LibraryFilter())))

    @Test fun newestPutsUnknownYearsLast() =
        assertEquals(listOf("Dune Part Two", "Amélie", "The Matrix", "Finding Nemo"), titles(Library.apply(all, LibraryFilter(sort = LibrarySort.YEAR))))

    @Test fun recentlyAddedUsesDriveTime() =
        assertEquals(listOf("Finding Nemo", "The Matrix", "Dune Part Two", "Amélie"), titles(Library.apply(all, LibraryFilter(sort = LibrarySort.RECENT))))

    @Test fun topRated() =
        assertEquals(listOf("Dune Part Two", "The Matrix", "Amélie", "Finding Nemo"), titles(Library.apply(all, LibraryFilter(sort = LibrarySort.RATING))))

    @Test fun genreFilter() = assertEquals(listOf("Dune Part Two", "The Matrix"), titles(Library.apply(all, LibraryFilter(genre = "Sci-Fi"))))

    @Test fun decadeFilter() {
        assertEquals(listOf("The Matrix"), titles(Library.apply(all, LibraryFilter(decade = 1990))))
        assertEquals(listOf("Amélie"), titles(Library.apply(all, LibraryFilter(decade = 2000))))
    }

    @Test fun searchIgnoresCaseAccentsAndWordOrder() {
        assertEquals(listOf("Amélie"), titles(Library.apply(all, LibraryFilter(query = "AMELIE"))))
        assertEquals(listOf("Dune Part Two"), titles(Library.apply(all, LibraryFilter(query = "two dune"))))
        assertEquals(listOf("The Matrix"), titles(Library.apply(all, LibraryFilter(query = "the matrix"))))
        assertEquals(emptyList<String>(), titles(Library.apply(all, LibraryFilter(query = "titanic"))))
    }

    @Test fun filtersCombine() =
        assertEquals(listOf("Dune Part Two"), titles(Library.apply(all, LibraryFilter(genre = "Sci-Fi", decade = 2020))))

    @Test fun unwatchedLeavesOutWhatsBeenWatched() {
        val seen = setOf(matrix.id, nemo.id)
        assertEquals(listOf("Amélie", "Dune Part Two"), titles(Library.apply(all, LibraryFilter(unwatched = true), watched = { it.id in seen })))
        // Only when it's chosen: otherwise what's been watched stays.
        assertEquals(4, Library.apply(all, LibraryFilter(), watched = { true }).size)
        // Nothing known to be watched: everything's unwatched.
        assertEquals(4, Library.apply(all, LibraryFilter(unwatched = true)).size)
    }

    @Test fun unwatchedCombinesWithGenreAndSort() {
        assertEquals(listOf("Dune Part Two"), titles(Library.apply(all, LibraryFilter(unwatched = true, genre = "Sci-Fi"), watched = { it === matrix })))
        assertEquals(
            listOf("Dune Part Two", "Amélie", "Finding Nemo"),
            titles(Library.apply(all, LibraryFilter(unwatched = true, sort = LibrarySort.RATING), watched = { it === matrix })),
        )
    }

    @Test fun aShowIsStartedByAWatchedEpisodeOrOneInProgress() {
        fun show(id: String, name: String) = DriveFile(id, name, DriveFile.FOLDER_MIME, 0, 0, null)
        fun playing(key: String, title: String, upNext: Boolean = false) = RecentItem(key, title, 600_000, 1_200_000, 0, upNext)
        val shows = listOf(show("office", "The Office (US)"), show("dark", "Dark (2017)"), show("lost", "Lost"), show("bluey", "Bluey"))
        val clip = DriveFile("clip", "Pilot.mkv", "video/x-matroska", 0, 0, null)
        // Where each played episode is known to sit (EpisodeShows).
        val under = mapOf("drive:e1" to "office", "drive:e2" to "dark", "drive:e3" to "bluey")
        val watched = setOf("drive:e1", "drive:clip")
        val recent = listOf(
            playing("drive:e2", "S01E01.mkv"), // Dark: in progress, known to be its episode
            playing("drive:e9", "Lost - S02E03.mkv", upNext = true), // Lost: up next, by its name
        )
        val started = Library.startedShows(shows + clip, recent, under, { it in watched })
        // The Office by its watched episode, a loose video by its own ✓; Bluey's episode was only queued.
        assertEquals(setOf("office", "dark", "lost", "clip"), started)
        assertEquals(listOf("Bluey"), titles(Library.apply(shows, LibraryFilter(unwatched = true), watched = { it.id in started })))
    }

    @Test fun genreAndDecadeChoices() {
        assertEquals("Sci-Fi", Library.genres(all).first()) // most common first
        assertEquals(listOf(2020, 2000, 1990), Library.decades(all))
    }

    @Test fun recentlyAddedIsWhenItWasPutInDriveNotLastRenamed() {
        // Renamed later (modified moved to now), but added earlier.
        val renamed = DriveFile("a", "A (2001).mp4", "video/mp4", 0, modified = 50, thumbnailLink = null, created = 1)
        val newer = DriveFile("b", "B (2002).mp4", "video/mp4", 0, modified = 5, thumbnailLink = null, created = 2)
        assertEquals(listOf("B", "A"), titles(Library.apply(listOf(renamed, newer), LibraryFilter(sort = LibrarySort.RECENT))))
    }

    // Sixty films whose genres overlap (Comedy and Family in two kinds each), rated 5.0–8.6.
    private val library = (0 until 60).map { i ->
        val genres = when (i % 4) {
            0 -> listOf("Action", "Adventure", "Sci-Fi")
            1 -> listOf("Comedy", "Family")
            2 -> listOf("Drama", "Crime")
            else -> listOf("Animation", "Comedy", "Family")
        }
        DriveFile("m$i", "Film $i.mp4", "video/mp4", 0, 0, null, null, MediaInfo("Film $i", 2000 + i % 20, genres, 5.0 + i % 10 * 0.4), created = i.toLong())
    }

    @Test fun overviewRowsNeverRepeatATitle() {
        val rows = Library.overviewRows(library, emptySet())
        val ids = rows.flatMap { (_, titles) -> titles.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(listOf("Recently added", "Top rated"), rows.take(2).map { it.first })
        // Genre rows too, each worth a row.
        assertTrue(rows.map { it.first }.toString(), rows.size > 2)
        rows.drop(2).forEach { (g, titles) -> assertTrue("$g: ${titles.size}", titles.size >= 6) }
    }

    @Test fun overviewRowsLeaveOutWhatsInContinueWatching() {
        val watching = setOf("m59", "m58", "m10")
        val ids = Library.overviewRows(library, watching).flatMap { (_, titles) -> titles.map { it.id } }
        assertTrue(ids.none { it in watching })
    }

    @Test fun aFilmGoesToItsOwnGenreRow() {
        // Eight without genres take Recently added; twelve split six and six between two genres
        // listed in opposite orders. (No ratings: no Top rated row.)
        val fillers = (0 until 8).map { DriveFile("f$it", "F$it.mp4", "video/mp4", 0, 0, null, null, MediaInfo("F$it", null)) }
        val animated = (0 until 6).map { DriveFile("a$it", "A$it.mp4", "video/mp4", 0, 0, null, null, MediaInfo("A$it", null, listOf("Animation", "Comedy"))) }
        val comedies = (0 until 6).map { DriveFile("c$it", "C$it.mp4", "video/mp4", 0, 0, null, null, MediaInfo("C$it", null, listOf("Comedy", "Animation"))) }
        val rows = Library.overviewRows(fillers + animated + comedies, emptySet()).toMap()
        assertEquals(animated.map { it.id }.toSet(), rows.getValue("Animation").map { it.id }.toSet())
        assertEquals(comedies.map { it.id }.toSet(), rows.getValue("Comedy").map { it.id }.toSet())
    }
}
