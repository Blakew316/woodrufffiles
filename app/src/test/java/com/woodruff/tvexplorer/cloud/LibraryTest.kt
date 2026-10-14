package com.woodruff.tvexplorer.cloud

import org.junit.Assert.assertEquals
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

    @Test fun genreAndDecadeChoices() {
        assertEquals("Sci-Fi", Library.genres(all).first()) // most common first
        assertEquals(listOf(2020, 2000, 1990), Library.decades(all))
    }
}
