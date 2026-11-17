package com.woodruff.tvexplorer.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbNamesTest {
    private fun movie(name: String, thisYear: Int = 2026) = Tmdb.parse(name, stripExtension = true, thisYear = thisYear)
    private fun folder(name: String) = Tmdb.parse(name, stripExtension = false)

    @Test fun plainTitleWithYear() = assertEquals("Inception" to "2010", movie("Inception (2010).mkv"))
    @Test fun sceneStyleName() = assertEquals("The Matrix" to "1999", movie("The.Matrix.1999.1080p.BluRay.x264.mkv"))
    @Test fun releaseGroupTags() = assertEquals("Dune Part Two" to "2024", movie("[YTS] Dune Part Two (2024) [2160p].mp4"))
    @Test fun noYear() = assertEquals("Finding Nemo" to null, movie("Finding_Nemo.mp4"))
    @Test fun yearInTitleIsKept() = assertEquals("2001 A Space Odyssey" to "1968", movie("2001 A Space Odyssey (1968).mkv"))
    @Test fun showFolder() = assertEquals("The Office US" to null, folder("The.Office.US.S01.1080p"))
    @Test fun showFolderWithYear() = assertEquals("Doctor Who" to "2005", folder("Doctor Who (2005)"))

    // A year in the title itself was taken for the release year, and found another film's poster and details.
    @Test fun aYearInTheTitleIsntTheReleaseYear() {
        assertEquals("Blade Runner 2049" to "2017", movie("Blade Runner 2049 (2017).mkv"))
        assertEquals("Wonder Woman 1984" to "2020", movie("Wonder Woman 1984 (2020).mkv"))
        assertEquals("Death Race 2000" to "1975", movie("Death Race 2000 (1975).mkv"))
        // Their folders, and scene-style names: the last year before the quality tags.
        assertEquals("Blade Runner 2049" to "2017", folder("Blade Runner 2049 (2017)"))
        assertEquals("Wonder Woman 1984" to "2020", movie("Wonder.Woman.1984.2020.1080p.WEB-DL.mkv"))
        assertEquals("Death Race 2000" to "1975", movie("Death.Race.2000.1975.REMASTERED.720p.BluRay.x264.mkv"))
        assertEquals("Blade Runner 2049" to "2017", movie("Blade.Runner.2049.2017.2160p.UHD.BluRay.x265-GROUP2019.mkv"))
        // A year still to come is part of a title, never its release year.
        assertEquals("Blade Runner 2049" to null, movie("Blade Runner 2049.mkv", thisYear = 2026))
        assertEquals("Movie" to "2027", movie("Movie (2027).mkv", thisYear = 2026))
        // A title that is a year, alone or with its release year.
        assertEquals("1917" to "2019", movie("1917 (2019).mkv"))
        assertEquals("1917" to "2019", movie("1917.2019.1080p.mkv"))
        assertEquals("1984" to null, movie("1984.mkv"))
        // The show a title's episodes belong to matches its own folder.
        assertEquals(
            com.woodruff.tvexplorer.cloud.Library.showMatchKey("Wonder Woman 1984 (2020)"),
            com.woodruff.tvexplorer.cloud.Library.showMatchKey("Wonder.Woman.1984.2020.1080p.mkv"),
        )
        assertEquals("wonder woman 1984", com.woodruff.tvexplorer.cloud.Library.showMatchKey("Wonder Woman 1984 (2020)"))
    }

    @Test fun bluRayCopyKeepsItsLabelButSearchesAsTheFilm() {
        val (title, year) = movie("Avengers - Age of Ultron Blu-Ray (2015).mp4")
        assertEquals("Avengers - Age of Ultron Blu-Ray" to "2015", title to year)
        assertEquals("Avengers - Age of Ultron", Tmdb.searchTitle(title))
    }

    @Test fun seasons() {
        assertEquals(2, Tmdb.seasonNumber("Season 2"))
        assertEquals(3, Tmdb.seasonNumber("S03"))
        assertEquals(1, Tmdb.seasonNumber("Series 1"))
        assertEquals(0, Tmdb.seasonNumber("Specials"))
        assertNull(Tmdb.seasonNumber("Behind the scenes"))
    }
}
