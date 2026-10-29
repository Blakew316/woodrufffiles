package com.woodruff.tvexplorer.tmdb

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Reading what a title's page shows from TMDB's details, collections and seasons (org.json needs Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TmdbDetailsTest {
    @Test fun movieLengthRatingCollectionAndCast() {
        val o = JSONObject(
            """
            {"id": 122, "title": "The Return of the King", "runtime": 201,
             "belongs_to_collection": {"id": 119, "name": "The Lord of the Rings Collection", "poster_path": "/p.jpg", "backdrop_path": null},
             "release_dates": {"results": [
               {"iso_3166_1": "DE", "release_dates": [{"certification": "12", "descriptors": [], "iso_639_1": "", "note": "", "release_date": "2003-12-17T00:00:00.000Z", "type": 3}]},
               {"iso_3166_1": "US", "release_dates": [
                 {"certification": "", "descriptors": [], "iso_639_1": "", "note": "Premiere", "release_date": "2003-12-01T00:00:00.000Z", "type": 1},
                 {"certification": "R", "descriptors": [], "iso_639_1": "", "note": "Extended", "release_date": "2004-12-14T00:00:00.000Z", "type": 5},
                 {"certification": "PG-13", "descriptors": [], "iso_639_1": "", "note": "", "release_date": "2003-12-17T00:00:00.000Z", "type": 3}
               ]}
             ]},
             "credits": {
               "cast": [
                 {"name": "Elijah Wood", "character": "Frodo", "order": 0}, {"name": "Ian McKellen", "character": "Gandalf", "order": 1},
                 {"name": "Viggo Mortensen", "order": 2}, {"name": "Sean Astin", "order": 3}, {"name": "Orlando Bloom", "order": 4},
                 {"name": "Billy Boyd", "order": 5}, {"name": "Dominic Monaghan", "order": 6}
               ],
               "crew": [{"name": "Peter Jackson", "job": "Director"}]
             }}
            """,
        )
        val d = Tmdb.movieDetails(o)
        assertEquals(201, d.runtime)
        // The cinema release's rating, not the extended disc's.
        assertEquals("PG-13", d.ageRating)
        assertEquals(119, d.collectionId)
        assertEquals("The Lord of the Rings Collection", d.collectionName)
        assertEquals(listOf("Elijah Wood", "Ian McKellen", "Viggo Mortensen", "Sean Astin", "Orlando Bloom", "Billy Boyd"), d.cast)
    }

    @Test fun movieWithNothingKnown() {
        val o = JSONObject(
            """
            {"id": 1, "runtime": null, "belongs_to_collection": null,
             "release_dates": {"results": [{"iso_3166_1": "GB", "release_dates": [{"certification": "15", "type": 3}]},
                                           {"iso_3166_1": "US", "release_dates": [{"certification": "NR", "type": 3}]}]},
             "credits": {"cast": [], "crew": []}}
            """,
        )
        // Another country's rating, or "not rated", says nothing here.
        assertEquals(Tmdb.Details(), Tmdb.movieDetails(o))
    }

    @Test fun showRatingAndCast() {
        val o = JSONObject(
            """
            {"id": 1396, "name": "Breaking Bad", "episode_run_time": [45, 47],
             "content_ratings": {"results": [{"descriptors": [], "iso_3166_1": "DE", "rating": "16"}, {"descriptors": [], "iso_3166_1": "US", "rating": "TV-MA"}]},
             "aggregate_credits": {"cast": [
               {"name": "Bryan Cranston", "roles": [{"credit_id": "x", "character": "Walter White", "episode_count": 62}], "total_episode_count": 62, "order": 0},
               {"name": "Aaron Paul", "roles": [], "total_episode_count": 62, "order": 1}
             ]}}
            """,
        )
        val d = Tmdb.showDetails(o)
        assertEquals("TV-MA", d.ageRating)
        assertNull(d.runtime) // a show's episodes have their own lengths
        assertNull(d.collectionId)
        assertEquals(listOf("Bryan Cranston", "Aaron Paul"), d.cast)
    }

    @Test fun collectionPartsInReleaseOrder() {
        val o = JSONObject(
            """
            {"id": 119, "name": "The Lord of the Rings Collection", "parts": [
              {"id": 122, "title": "The Return of the King", "release_date": "2003-12-17"},
              {"id": 120, "title": "The Fellowship of the Ring", "release_date": "2001-12-18"},
              {"id": 999, "title": "Announced", "release_date": ""},
              {"id": 121, "title": "The Two Towers", "release_date": "2002-12-18"},
              {"id": 998, "title": "Rumoured", "release_date": null}
            ]}
            """,
        )
        assertEquals(listOf(120, 121, 122, 999, 998), Tmdb.collectionParts(o))
        assertEquals(emptyList<Int>(), Tmdb.collectionParts(JSONObject("""{"id": 1}""")))
    }

    @Test fun seasonPicturesAndLengths() {
        val o = JSONObject(
            """
            {"season_number": 1, "episodes": [
              {"episode_number": 1, "still_path": "/a.jpg", "runtime": 58},
              {"episode_number": 2, "still_path": null, "runtime": 47},
              {"episode_number": 3, "still_path": "/c.jpg", "runtime": null},
              {"episode_number": 4, "still_path": null, "runtime": null}
            ]}
            """,
        )
        val eps = Tmdb.seasonEpisodes(o)
        assertEquals(Tmdb.Episode("https://image.tmdb.org/t/p/original/a.jpg", 58), eps[1])
        assertEquals(Tmdb.Episode(null, 47), eps[2]) // no picture: not ".../originalnull"
        assertEquals(Tmdb.Episode("https://image.tmdb.org/t/p/original/c.jpg", null), eps[3])
        assertNull(eps[4])
    }

    @Test fun detailsCacheRoundTrip() {
        val d = Tmdb.Details(152, "PG-13", 263, "The Dark Knight Collection", listOf("Christian Bale", "Heath Ledger"))
        assertEquals(d, Tmdb.Details.fromJson(d.toJson()))
        assertEquals(Tmdb.Details(), Tmdb.Details.fromJson(Tmdb.Details().toJson()))
    }

    @Test fun missingPicturesAreNone() {
        val h = Tmdb.hit(JSONObject("""{"poster_path": null, "backdrop_path": null, "overview": null, "release_date": "1999-03-30"}"""), Tmdb.movieGenres, "release_date")
        assertNull(h.poster)
        assertNull(h.backdrop)
        assertNull(h.overview)
        assertEquals(1999, h.year)
        // As an earlier version saved them.
        assertNull(Tmdb.Hit.fromJson("""{"p": "https://image.tmdb.org/t/p/w342null", "b": "https://image.tmdb.org/t/p/w1280null", "y": 1999}""")?.poster)
    }
}
