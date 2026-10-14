package com.woodruff.tvexplorer.tmdb

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Reading TMDB search results (org.json needs Robolectric on the JVM). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TmdbHitTest {
    @Test fun movieResult() {
        val o = JSONObject("""{"poster_path":"/abc.jpg","release_date":"1999-03-30","genre_ids":[28,878],"vote_average":8.2,"vote_count":25000}""")
        val h = Tmdb.hit(o, Tmdb.movieGenres, "release_date")
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", h.poster)
        assertEquals(1999, h.year)
        assertEquals(listOf("Action", "Sci-Fi"), h.genres)
        assertEquals(8.2, h.rating!!, 0.001)
    }

    @Test fun showGenresAreSplitAndDeduplicated() {
        val o = JSONObject("""{"first_air_date":"2005-03-26","genre_ids":[10759,10765,12345,28],"vote_average":7.5,"vote_count":3000}""")
        val h = Tmdb.hit(o, Tmdb.tvGenres, "first_air_date")
        assertNull(h.poster)
        assertEquals(2005, h.year)
        assertEquals(listOf("Action", "Adventure", "Sci-Fi", "Fantasy"), h.genres) // unknown ids dropped
    }

    @Test fun fewVotesAndMissingDates() {
        val h = Tmdb.hit(JSONObject("""{"release_date":"","vote_average":9.9,"vote_count":3}"""), Tmdb.movieGenres, "release_date")
        assertNull(h.year)
        assertNull(h.rating)
    }

    @Test fun cacheRoundTrip() {
        val h = Tmdb.Hit("https://image.tmdb.org/t/p/w342/x.jpg", 2010, listOf("Drama"), 7.1)
        assertEquals(h, Tmdb.Hit.fromJson(h.toJson()))
        val empty = Tmdb.Hit(null, null, emptyList(), null)
        assertEquals(empty, Tmdb.Hit.fromJson(empty.toJson()))
    }
}
