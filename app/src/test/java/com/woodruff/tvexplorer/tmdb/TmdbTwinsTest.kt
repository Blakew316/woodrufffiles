package com.woodruff.tvexplorer.tmdb

import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.MediaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which title a copy that found nothing on TMDB borrows its poster and details from. */
class TmdbTwinsTest {
    private fun video(name: String, title: String, year: Int?, poster: String? = null, tmdbId: Int? = null) =
        DriveFile(name, name, "video/x-matroska", 0, 0, null, poster, MediaInfo(title, year, tmdbId = tmdbId))

    @Test fun aCopyBorrowsItsTwinsPosterAndDetails() {
        val film = video("Inception (2010).mkv", "Inception", 2010, poster = "inception.jpg", tmdbId = 27205)
        val copy = Tmdb.shareWithTwins(listOf(film, video("Inception (2010) - Copy.mkv", "Inception", 2010)))[1]
        assertEquals("inception.jpg", copy.posterUrl)
        assertEquals(27205, copy.info?.tmdbId)
    }

    @Test fun aBluRayCopyKeepsItsLabelAndFindsTheFilmsPoster() {
        val film = video("Avengers - Age of Ultron (2015).mp4", "Avengers - Age of Ultron", 2015, poster = "ultron.jpg", tmdbId = 99861)
        val bluRay = video("Avengers - Age of Ultron Blu-Ray (2015).mp4", "Avengers - Age of Ultron Blu-Ray", 2015)
        val out = Tmdb.shareWithTwins(listOf(film, bluRay))[1]
        assertEquals("ultron.jpg", out.posterUrl)
        assertEquals("Avengers - Age of Ultron Blu-Ray", out.info?.title)
    }

    @Test fun anotherYearsFilmOfTheSameNameIsNotATwin() {
        val old = video("Little Women (1994).mkv", "Little Women", 1994)
        val new = video("Little Women (2019).mkv", "Little Women", 2019, poster = "2019.jpg", tmdbId = 331482)
        assertEquals(old, Tmdb.shareWithTwins(listOf(old, new))[0])
    }

    @Test fun aTwinWithoutAYearStillMatches() {
        val own = video("Lake House.mkv", "Lake House", null, poster = "own.jpg")
        assertEquals("own.jpg", Tmdb.shareWithTwins(listOf(own, video("Lake House (2006) - Copy.mkv", "Lake House", 2006)))[1].posterUrl)
    }

    @Test fun noYearOnlyWhenTheTwinsAgreeOnOne() {
        val copy = video("Little Women - Copy.mkv", "Little Women", null)
        val a = video("Little Women (1994).mkv", "Little Women", 1994, poster = "1994.jpg")
        val b = video("Little Women (2019).mkv", "Little Women", 2019, poster = "2019.jpg")
        assertNull(Tmdb.shareWithTwins(listOf(copy, a, b))[0].posterUrl)
        assertEquals("1994.jpg", Tmdb.shareWithTwins(listOf(copy, a))[0].posterUrl)
    }
}
