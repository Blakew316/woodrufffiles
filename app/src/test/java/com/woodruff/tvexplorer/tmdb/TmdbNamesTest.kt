package com.woodruff.tvexplorer.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbNamesTest {
    private fun movie(name: String) = Tmdb.parse(name, stripExtension = true)
    private fun folder(name: String) = Tmdb.parse(name, stripExtension = false)

    @Test fun plainTitleWithYear() = assertEquals("Inception" to "2010", movie("Inception (2010).mkv"))
    @Test fun sceneStyleName() = assertEquals("The Matrix" to "1999", movie("The.Matrix.1999.1080p.BluRay.x264.mkv"))
    @Test fun releaseGroupTags() = assertEquals("Dune Part Two" to "2024", movie("[YTS] Dune Part Two (2024) [2160p].mp4"))
    @Test fun noYear() = assertEquals("Finding Nemo" to null, movie("Finding_Nemo.mp4"))
    @Test fun yearInTitleIsKept() = assertEquals("2001 A Space Odyssey" to "1968", movie("2001 A Space Odyssey (1968).mkv"))
    @Test fun showFolder() = assertEquals("The Office US" to null, folder("The.Office.US.S01.1080p"))
    @Test fun showFolderWithYear() = assertEquals("Doctor Who" to "2005", folder("Doctor Who (2005)"))

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
