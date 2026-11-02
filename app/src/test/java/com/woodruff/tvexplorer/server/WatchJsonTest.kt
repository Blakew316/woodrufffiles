package com.woodruff.tvexplorer.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.tvs.SignInState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The phone's Watch tab says what the TV's own title pages and rows say (org.json needs Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WatchJsonTest {
    private val FOLDER = DriveFile.FOLDER_MIME

    private fun film(id: String, name: String, title: String, year: Int? = null, rating: Double? = null, poster: String? = null, backdrop: String? = null, genres: List<String> = emptyList()) =
        DriveFile(id, name, "video/x-matroska", 1_000, 1_700_000_000_000, null, poster, MediaInfo(title, year, genres, rating, backdrop = backdrop))

    private fun show(id: String, name: String, title: String = name) =
        DriveFile(id, name, FOLDER, 0, 1_700_000_000_000, null, null, MediaInfo(title, null))

    private fun facts(movies: List<DriveFile> = emptyList(), shows: List<DriveFile> = emptyList(), recent: List<RecentItem> = emptyList(),
                      showOf: Map<String, String> = emptyMap(), watched: Set<String> = emptySet(), folders: Map<String, List<DriveFile>> = emptyMap()) =
        WatchFacts(movies, shows, recent, recent, showOf, { it in watched }, { folders[it] })

    @Test fun aFilmResumesPastItsFirstMinuteAsTheTitlePageDoes() {
        val harbor = film("h1", "Midnight Harbor (2024).mkv", "Midnight Harbor", 2024)
        val at45 = RecentItem("drive:h1", harbor.name, 2_710_000, 6_840_000, 1)
        val p = WatchJson.filmPlay(harbor, listOf(at45))
        assertEquals("Resume from 45m", p.getString("label"))
        assertEquals(2_710_000, p.getLong("position"))
        assertEquals("drive:h1", p.getString("key"))
        assertEquals("Midnight Harbor (2024).mkv", p.getString("title"))
        assertEquals(6_840_000, p.getLong("duration"))
        val again = WatchJson.filmRestart(harbor, listOf(at45))!!
        assertEquals("Start Over", again.getString("label"))
        assertEquals(0, again.getLong("position"))
        // In its first minute: "Play", from where it was (the player resumes there too), and no Start Over.
        val early = RecentItem("drive:h1", harbor.name, 30_000, 6_840_000, 1)
        assertEquals("Play", WatchJson.filmPlay(harbor, listOf(early)).getString("label"))
        assertEquals(30_000, WatchJson.filmPlay(harbor, listOf(early)).getLong("position"))
        assertNull(WatchJson.filmRestart(harbor, listOf(early)))
        // Finished (past 95 %): from the start.
        val done = RecentItem("drive:h1", harbor.name, 6_700_000, 6_840_000, 1)
        assertEquals(0, WatchJson.filmPlay(harbor, listOf(done)).getLong("position"))
        assertEquals("Play", WatchJson.filmPlay(harbor, emptyList()).getString("label"))
    }

    @Test fun aShowGoesOnWhereTheFamilyLeftItInTheTvsWords() {
        val inProgress = RecentItem("drive:e4", "Backyard Science S02E04 The Volcano.mkv", 731_000, 1_440_000, 1)
        assertEquals("Resume S2 E4", WatchJson.episodePlay(inProgress).getString("label"))
        assertEquals(731_000, WatchJson.episodePlay(inProgress).getLong("position"))
        val next = RecentItem("drive:e5", "Backyard Science S02E05 Rain.mkv", 0, 0, 2, upNext = true)
        assertEquals("Play S2 E5", WatchJson.episodePlay(next).getString("label"))
        assertEquals(0, WatchJson.episodePlay(next).getLong("position"))
        val first = DriveFile("e1", "Backyard Science S01E01 Tide Pools.mkv", "video/mp4", 1, 1, null)
        assertEquals("Play S1 E1", WatchJson.firstEpisodePlay(first, emptyList()).getString("label"))
        assertEquals("Play First Episode", WatchJson.firstEpisodePlay(first.copy(name = "Pilot.mkv"), emptyList()).getString("label"))
        assertEquals("Resume", WatchJson.episodePlay(inProgress.copy(title = "Pilot.mkv")).getString("label"))
        assertEquals("Play Next Episode", WatchJson.episodePlay(next.copy(title = "Pilot.mkv")).getString("label"))
    }

    @Test fun aShowsEpisodeIsFoundByItsShowWhateverTheFileIsCalled() {
        val backyard = show("bs", "Backyard Science (2023)", "Backyard Science")
        val named = RecentItem("drive:e4", "Backyard.Science.2023.S02E04.mkv", 731_000, 1_440_000, 2)
        val unnamed = RecentItem("drive:x1", "S01E01 - Pilot.mkv", 400_000, 1_440_000, 1)
        assertEquals("drive:e4", WatchJson.showResumeItem(backyard, facts(shows = listOf(backyard), recent = listOf(named)))?.key)
        // Played from its page (EpisodeShows knows it), or listed in its folders.
        assertEquals("drive:x1", WatchJson.showResumeItem(backyard, facts(shows = listOf(backyard), recent = listOf(unnamed), showOf = mapOf("drive:x1" to "bs")))?.key)
        assertEquals("drive:x1", WatchJson.showResumeItem(backyard, facts(shows = listOf(backyard), recent = listOf(unnamed)), listed = setOf("drive:x1"))?.key)
        assertNull(WatchJson.showResumeItem(backyard, facts(shows = listOf(backyard), recent = listOf(unnamed))))
    }

    @Test fun continueWatchingTilesSayWhatToStartAndWhatItIs() {
        val backyard = show("bs", "Backyard Science", "Backyard Science")
        val harbor = film("h1", "Midnight Harbor (2024).mkv", "Midnight Harbor", 2024, poster = "https://image.tmdb.org/t/p/w342/h.jpg")
        val ep = RecentItem("drive:e4", "Backyard Science S02E04 The Volcano.mkv", 731_000, 1_440_000, 2)
        val f = facts(movies = listOf(harbor), shows = listOf(backyard), recent = listOf(ep), showOf = mapOf("drive:e4" to "bs"))
        val tile = WatchJson.continueItem(ep, f)
        assertEquals("show", tile.getString("kind"))
        assertEquals("bs", tile.getString("titleId"))
        assertEquals("Backyard Science", tile.getString("name"))
        assertEquals("S2 E4", tile.getString("code"))
        assertEquals("The Volcano", tile.getString("episode"))
        assertEquals("Resume S2 E4", tile.getJSONObject("play").getString("label"))
        assertTrue(tile.getBoolean("drive"))
        // A film: its name and poster from the library, Resume and Start Over.
        val film = RecentItem("drive:h1", harbor.name, 2_710_000, 6_840_000, 1)
        val t2 = WatchJson.continueItem(film, facts(movies = listOf(harbor), recent = listOf(film)))
        assertEquals("movie", t2.getString("kind"))
        assertEquals("Midnight Harbor", t2.getString("name"))
        assertEquals("https://image.tmdb.org/t/p/w342/h.jpg", t2.getString("poster"))
        assertEquals("Resume from 45m", t2.getJSONObject("play").getString("label"))
        assertEquals("Start Over", t2.getJSONObject("restart").getString("label"))
        assertTrue(t2.isNull("code"))
        // Not in the library (a file on the TV's own storage): named from the file, no poster, not on Drive.
        val local = RecentItem("/storage/emulated/0/Movies/Paper Planes (2019).mp4", "Paper Planes (2019).mp4", 500_000, 5_000_000, 1)
        val t3 = WatchJson.continueItem(local, facts())
        assertEquals("Paper Planes", t3.getString("name"))
        assertFalse(t3.getBoolean("drive"))
        assertTrue(t3.isNull("poster"))
        assertTrue(t3.isNull("titleId"))
        // An episode outside the library: its show's name from the file.
        val loose = WatchJson.continueItem(RecentItem("drive:z", "Kitchen.Rules.S03E01.1080p.mkv", 0, 0, 1, upNext = true), facts())
        assertEquals("Kitchen Rules", loose.getString("name"))
        assertEquals("Play S3 E1", loose.getJSONObject("play").getString("label"))
    }

    @Test fun episodeNamesComeFromTheFileUntilTmdbsAreKept() {
        assertEquals("The Volcano", WatchJson.episodeName("Backyard Science S02E04 The Volcano.mkv", 4))
        assertEquals("The Title", WatchJson.episodeName("Show.S01E03.The.Title.1080p.mkv", 3))
        assertEquals("Episode 4", WatchJson.episodeName("Show S02E04.mkv", 4))
        assertEquals("Episode 4", WatchJson.episodeName("Episode 4.mkv", 4))
        assertEquals("Behind the scenes", WatchJson.episodeName("Behind the scenes.mkv", null))
        assertEquals("S2 E4 · The Volcano", WatchJson.episodeLabel("Backyard Science S02E04 The Volcano.mkv"))
        assertEquals("S2 E4", WatchJson.episodeLabel("Show S02E04.mkv"))
        assertNull(WatchJson.episodeLabel("Midnight Harbor (2024).mkv"))
        assertEquals("Backyard Science", WatchJson.showName("Backyard.Science.S02E04.mkv"))
        assertEquals("S01E01 - Pilot", WatchJson.showName("S01E01 - Pilot.mkv"))
    }

    @Test fun searchRanksLikeTheTv() {
        val harbor = film("1", "a", "Midnight Harbor", 2024, rating = 7.8)
        val office = show("2", "The Office")
        val coast = film("3", "b", "Harbor Lights", 2019, rating = 6.0)
        val dark = film("4", "c", "The Dark Harbor", 2024, rating = 8.5)
        val ranked = WatchJson.search(listOf(harbor, office, coast, dark), "harbor").map { it.first.id }
        // The title starting with it first; then a word starting with it (higher rated first).
        assertEquals(listOf("3", "4", "1"), ranked)
        // A leading "the" is left out on both sides; accents and case are ignored.
        assertEquals(listOf("2"), WatchJson.search(listOf(harbor, office), "the off").map { it.first.id })
        assertEquals(listOf("2"), WatchJson.search(listOf(office), "OFFICE").map { it.first.id })
        // Four digits also match the year.
        assertEquals(setOf("1", "4"), WatchJson.search(listOf(harbor, coast, dark), "2024").map { it.first.id }.toSet())
        assertEquals(listOf("4"), WatchJson.search(listOf(harbor, coast, dark), "dark 2024").map { it.first.id })
        assertTrue(WatchJson.search(listOf(harbor), "  ").isEmpty())
        assertTrue(WatchJson.search(listOf(harbor), "zebra").isEmpty())
    }

    @Test fun artIsTmdbsOrComesThroughTheTv() {
        val tmdb = film("1", "a.mkv", "A", poster = "https://image.tmdb.org/t/p/w342/a.jpg", backdrop = "https://image.tmdb.org/t/p/w1280/b.jpg")
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", WatchJson.posterUrl(tmdb))
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", WatchJson.backdropUrl(tmdb))
        // The owner's own poster on Drive: through the TV, which has the token; the phone never gets it.
        val own = film("x y", "b.mkv", "B", poster = "https://lh3.googleusercontent.com/abc=s720")
        assertEquals("/api/poster?id=x+y&kind=poster&v=1700000000000", WatchJson.posterUrl(own))
        // A video with only Drive's frame: that frame, as the TV's tile shows it, and as its backdrop.
        val frame = DriveFile("v1", "c.mkv", "video/mp4", 1, 5, "https://lh3.googleusercontent.com/t=s220")
        assertEquals("/api/poster?id=v1&kind=poster&v=5", WatchJson.posterUrl(frame))
        assertEquals("/api/poster?id=v1&kind=backdrop&v=5", WatchJson.backdropUrl(frame))
        // Nothing, or somewhere else: the dark title card.
        assertNull(WatchJson.posterUrl(show("s", "Detective Rowe")))
        assertNull(WatchJson.posterUrl(film("e", "e.mkv", "E", poster = "http://example.net/p.jpg")))
        assertEquals("/api/poster?id=e1&kind=still&in=s2&v=9", WatchJson.artRoute("e1", "still", 9, "s2"))
    }

    @Test fun titlesCarryTheirCheckOnlyForFilms() {
        val harbor = film("h1", "Midnight Harbor (2024).mkv", "Midnight Harbor", 2024, rating = 7.8, genres = listOf("Drama", "Thriller"))
        val backyard = show("bs", "Backyard Science")
        val f = facts(movies = listOf(harbor), shows = listOf(backyard), watched = setOf("drive:h1"))
        val t = WatchJson.title(harbor, f)
        assertEquals("movie", t.getString("kind"))
        assertEquals("Midnight Harbor", t.getString("title"))
        assertEquals(2024, t.getInt("year"))
        assertEquals(7.8, t.getDouble("rating"), 0.001)
        assertEquals(2, t.getJSONArray("genres").length())
        assertTrue(t.getBoolean("watched"))
        assertEquals("Play", t.getJSONObject("play").getString("label"))
        val s = WatchJson.title(backyard, f)
        assertEquals("show", s.getString("kind"))
        assertFalse(s.getBoolean("watched"))
        assertTrue(s.isNull("play")) // nothing in progress: the sheet works out its first episode
        // A film kept in its own folder: its ✓ and play once the folder's listing is known.
        val inFolder = show("hf", "Ironwood (2025)", "Ironwood")
        val file = DriveFile("iw", "Ironwood.2025.mkv", "video/mp4", 9_000, 1, null)
        val f2 = facts(movies = listOf(inFolder), watched = setOf("drive:iw"), folders = mapOf("hf" to listOf(file)))
        val t2 = WatchJson.title(inFolder, f2)
        assertTrue(t2.getBoolean("watched"))
        assertEquals("drive:iw", t2.getJSONObject("play").getString("key"))
        assertTrue(WatchJson.title(inFolder, facts(movies = listOf(inFolder))).isNull("play"))
    }

    @Test fun theFirstSeasonIsSeasonOneNotSpecials() {
        assertEquals(1, WatchJson.firstSeason(listOf("Specials", "Season 1", "Season 2")))
        assertEquals(0, WatchJson.firstSeason(listOf("Season 1", "Season 2")))
        assertEquals(1, WatchJson.firstSeason(listOf("Season 2", "Season 1")))
        assertEquals(0, WatchJson.firstSeason(listOf("Episodes")))
        assertEquals(0, WatchJson.firstSeason(emptyList()))
    }

    @Test fun watchSaysWhereItStands() {
        assertEquals("notSetUp", watchState(SignInState.NOT_SET_UP, saved = false, empty = false, listing = false))
        assertEquals("notSignedIn", watchState(SignInState.NOT_SIGNED_IN, false, false, false))
        assertEquals("expired", watchState(SignInState.EXPIRED, saved = true, empty = false, listing = false))
        assertEquals("expiredEmpty", watchState(SignInState.EXPIRED, saved = false, empty = false, listing = false))
        assertEquals("keysRejected", watchState(SignInState.KEYS_REJECTED, true, false, false))
        assertEquals("offline", watchState(SignInState.OFFLINE, true, false, false))
        assertEquals("unreachable", watchState(SignInState.UNREACHABLE, false, false, false))
        assertEquals("ready", watchState(SignInState.SIGNED_IN, saved = true, empty = false, listing = false))
        assertEquals("empty", watchState(SignInState.SIGNED_IN, saved = true, empty = true, listing = false))
        assertEquals("loading", watchState(SignInState.SIGNED_IN, saved = false, empty = false, listing = true))
        // Nothing saved and listing it couldn't get through: Google doesn't answer (it tries again by itself).
        assertEquals("unreachable", watchState(SignInState.SIGNED_IN, saved = false, empty = false, listing = false))
    }

    @Test fun anUnchangedHomeCostsA304() {
        val a = WatchHome.etagOf(JSONObject().put("state", "ready").toString())
        assertEquals(a, WatchHome.etagOf(JSONObject().put("state", "ready").toString()))
        assertNotEquals(a, WatchHome.etagOf(JSONObject().put("state", "offline").toString()))
        assertTrue(a.startsWith("\"") && a.endsWith("\""))
        WatchHome.forget()
        var built = 0
        val first = WatchHome.get { built++; "{\"n\":1}" }
        val again = WatchHome.get { built++; "{\"n\":2}" }
        assertEquals(1, built) // phones asking within 10 s share one
        assertEquals(first.etag, again.etag)
        WatchHome.forget() // something changed from the phone: built afresh
        assertEquals("{\"n\":3}", WatchHome.get { built++; "{\"n\":3}" }.body)
        // A mark arriving while an answer is being built: that answer goes out once but isn't kept.
        WatchHome.forget()
        assertEquals("{\"n\":4}", WatchHome.get { built++; WatchHome.forget(); "{\"n\":4}" }.body)
        assertEquals("{\"n\":5}", WatchHome.get { built++; "{\"n\":5}" }.body)
        WatchHome.forget()
    }

    @Test fun driveArtIsAskedForAtTheSizeThePhoneShows() {
        assertEquals("https://lh3.googleusercontent.com/abc=w342", PhonePosters.sized("https://lh3.googleusercontent.com/abc=s220", "w342"))
        assertEquals("https://lh3.googleusercontent.com/abc=w1280", PhonePosters.sized("https://lh3.googleusercontent.com/abc=s720", "w1280"))
        assertEquals("https://lh3.googleusercontent.com/abc=w300", PhonePosters.sized("https://lh3.googleusercontent.com/abc=s220-c", "w300"))
        assertEquals("https://lh3.googleusercontent.com/abc=w342", PhonePosters.sized("https://lh3.googleusercontent.com/abc", "w342"))
        val f = DriveFile("id1", "n", "video/mp4", 0, 10, null)
        assertEquals(PhonePosters.name("poster", f), PhonePosters.name("poster", f))
        // A new picture (the file changed) or another kind of picture is kept apart.
        assertNotEquals(PhonePosters.name("poster", f), PhonePosters.name("poster", f.copy(modified = 11)))
        assertNotEquals(PhonePosters.name("poster", f), PhonePosters.name("backdrop", f))
    }

    @Test fun aWaitingStartOnlyWaitsTenMinutes() {
        assertTrue(PendingPlay.fresh(1_000, 1_000))
        assertTrue(PendingPlay.fresh(1_000, 1_000 + 10 * 60_000))
        assertFalse(PendingPlay.fresh(1_000, 1_001 + 10 * 60_000))
        assertFalse(PendingPlay.fresh(5_000, 1_000)) // a clock that went back: not trusted
    }
}
