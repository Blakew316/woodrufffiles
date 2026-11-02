package com.woodruff.tvexplorer.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.EpisodeMeta
import com.woodruff.tvexplorer.cloud.FreshEpisode
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.tvs.SignInState
import com.woodruff.tvexplorer.ui.screens.MainPlay
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
        val backyard = show("bs", "Backyard Science (2023)", "Backyard Science")
        val first = DriveFile("e1", "Backyard Science S01E01 Tide Pools.mkv", "video/mp4", 1, 1, null)
        assertEquals("Play S1 E1", WatchJson.firstEpisodePlay(backyard, first, emptyList()).getString("label"))
        assertEquals("Play First Episode", WatchJson.firstEpisodePlay(backyard, first.copy(name = "Pilot.mkv"), emptyList()).getString("label"))
        assertEquals("Resume", WatchJson.episodePlay(inProgress.copy(title = "Pilot.mkv")).getString("label"))
        assertEquals("Play Next Episode", WatchJson.episodePlay(next.copy(title = "Pilot.mkv")).getString("label"))
        // From the start beside a button that goes on with the show.
        val again = WatchJson.restartPlay(backyard, first, emptyList())
        assertEquals("Play from the Start", again.getString("label"))
        assertEquals(0, again.getLong("position"))
        assertEquals("drive:e1", again.getString("key"))
    }

    @Test fun thePlayerIsGivenEachEpisodesRealNameAsTheTvsTitlePageGivesIt() {
        val backyard = show("bs", "Backyard Science (2023)", "Backyard Science")
        val volcano = DriveFile("e5", "Backyard.Science.2023.S02E05.1080p.mkv", "video/mp4", 1, 1, null)
        // TMDB doesn't know it: the file's name, as before.
        assertEquals(volcano.name, WatchJson.episodeTitle(backyard, volcano))
        // TMDB knows it: "{Show} S02E05 {name}", which the player splits into "Backyard Science · S2, E5" over "The Volcano".
        val known = volcano.copy(episode = EpisodeMeta(2, 5, "The Volcano", "A trip to the island.", 24))
        assertEquals("Backyard Science S02E05 The Volcano", WatchJson.episodeTitle(backyard, known))
        assertEquals("Backyard Science S02E05 The Volcano", WatchJson.firstEpisodePlay(backyard, known, emptyList()).getString("title"))
        assertEquals("Play S2 E5", WatchJson.firstEpisodePlay(backyard, known, emptyList()).getString("label"))
        // Going on with an episode played before: the title it was played with, unless its real name is known now.
        val inProgress = RecentItem("drive:e5", volcano.name, 731_000, 1_440_000, 1)
        val goOn = MainPlay.GoOn(inProgress)
        assertEquals(volcano.name, WatchJson.mainPlay(backyard, goOn, emptyList())!!.getString("title"))
        val named = WatchJson.mainPlay(backyard, goOn, emptyList(), known.episode)!!
        assertEquals("Backyard Science S02E05 The Volcano", named.getString("title"))
        assertEquals("Resume S2 E5", named.getString("label"))
        assertEquals(731_000, named.getLong("position"))
        // The line under the buttons names it only when TMDB knows its name.
        assertEquals("S2 E5 · The Volcano", WatchJson.playName(known.episode))
        assertNull(WatchJson.playName(EpisodeMeta(2, 5, null, null, 24)))
        assertNull(WatchJson.playName(null))
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

    @Test fun episodesSayWhatTheTvsEpisodeCardsSay() {
        val now = 1_800_000_000_000
        val day = 24 * 60 * 60_000L
        val backyard = show("bs", "Backyard Science (2023)", "Backyard Science")
        fun ep(id: String, name: String, created: Long = now - 400 * day, meta: EpisodeMeta? = null, still: String? = null) =
            DriveFile(id, name, "video/mp4", 100, 7, "https://lh3.googleusercontent.com/$id=s220", still, created = created, episode = meta)
        val recent = listOf(RecentItem("drive:e3", "Backyard Science S02E03.mkv", 20_000, 1_440_000, 1)) // under 2 %
        val f = WatchFacts(emptyList(), listOf(backyard), recent, recent, emptyMap(), { it == "drive:e1" }, { null }, now = now)

        // TMDB knows it: its number, name, synopsis and length, its picture, and the player's title with its name.
        val known = WatchJson.episode(
            ep("e4", "Backyard.Science.S02E04.1080p.mkv", meta = EpisodeMeta(2, 4, "The Volcano", "A trip to the island.", 24), still = "https://image.tmdb.org/t/p/original/v.jpg"),
            backyard, f, "s2",
        )
        assertEquals("The Volcano", known.getString("name"))
        assertEquals("A trip to the island.", known.getString("overview"))
        assertEquals(4, known.getInt("number"))
        assertEquals("S2 E4", known.getString("code"))
        assertEquals(24, known.getInt("runtime"))
        assertFalse(known.getBoolean("special"))
        assertEquals("https://image.tmdb.org/t/p/original/v.jpg", known.getString("still"))
        assertEquals("Backyard Science S02E04 The Volcano", known.getString("playTitle"))
        assertFalse(known.getBoolean("new")) // put in Drive long ago

        // TMDB doesn't: what its file's name says, no synopsis, Drive's own frame through the TV.
        val plain = WatchJson.episode(ep("e5", "Show.S02E05.The.Title.1080p.mkv"), backyard, f, "s2")
        assertEquals("The Title", plain.getString("name"))
        assertTrue(plain.isNull("overview"))
        assertEquals(5, plain.getInt("number"))
        assertTrue(plain.isNull("runtime"))
        assertEquals("/api/poster?id=e5&kind=still&in=s2&v=7", plain.getString("still"))
        assertEquals("Show.S02E05.The.Title.1080p.mkv", plain.getString("playTitle"))
        assertEquals("Episode 4", WatchJson.episode(ep("e6", "Episode 4.mkv"), backyard, f, "s2").getString("name"))
        assertEquals(4, WatchJson.episode(ep("e6", "Episode 4.mkv"), backyard, f, "s2").getInt("number"))
        assertTrue(WatchJson.episode(ep("e7", "Show S00E01.mkv"), backyard, f, "s2").getBoolean("special"))
        assertTrue(WatchJson.episode(ep("e8", "x.mkv", meta = EpisodeMeta(0, 2, "Behind the Scenes")), backyard, f, "s2").getBoolean("special"))

        // New: put in Drive in the last 30 days, with no ✓ and not started anywhere (the card's NEW badge).
        assertTrue(WatchJson.episode(ep("e2", "Show S02E02.mkv", created = now - 2 * day), backyard, f, "s2").getBoolean("new"))
        assertFalse("watched", WatchJson.episode(ep("e1", "Show S02E01.mkv", created = now - 2 * day), backyard, f, "s2").getBoolean("new"))
        assertTrue("barely started", WatchJson.episode(ep("e3", "Show S02E03.mkv", created = now - 2 * day), backyard, f, "s2").getBoolean("new"))
        val started = listOf(RecentItem("drive:e3", "Show S02E03.mkv", 400_000, 1_440_000, 1))
        val f2 = WatchFacts(emptyList(), listOf(backyard), started, started, emptyMap(), { false }, { null }, now = now)
        assertFalse("started", WatchJson.episode(ep("e3", "Show S02E03.mkv", created = now - 2 * day), backyard, f2, "s2").getBoolean("new"))

        // A collection's film: its title as the library writes it, played by its file's name, never "new".
        val film = WatchJson.episode(DriveFile("c1", "Orbit (2021).mkv", "video/mp4", 1, 1, null, created = now - day), backyard, f, "col", collection = true)
        assertEquals("Orbit (2021)", film.getString("name"))
        assertEquals("Orbit (2021).mkv", film.getString("playTitle"))
        assertTrue(film.isNull("number"))
        assertFalse(film.getBoolean("new"))
    }

    @Test fun continueWatchingNamesTheEpisodeAsItsTitleDoes() {
        val f = facts()
        // Started from the TV's title page with its real name, or from a file named after it.
        assertEquals("The Volcano", WatchJson.continueItem(RecentItem("drive:a", "Backyard Science S02E04 The Volcano", 1, 2, 1), f).getString("episode"))
        assertEquals("The Title", WatchJson.continueItem(RecentItem("drive:b", "Show.S01E03.The.Title.1080p.mkv", 1, 2, 1), f).getString("episode"))
        assertEquals("Episode 4", WatchJson.continueItem(RecentItem("drive:c", "Show S02E04.mkv", 1, 2, 1), f).getString("episode"))
        assertEquals("Backyard Science", WatchJson.showName("Backyard.Science.S02E04.mkv"))
        assertEquals("S01E01 - Pilot", WatchJson.showName("S01E01 - Pilot.mkv"))
    }

    @Test fun newEpisodesAreSaidOnTheirShowsAsOnTheTv() {
        val now = 1_800_000_000_000
        val day = 24 * 60 * 60_000L
        val old = DriveFile("bs", "Backyard Science", FOLDER, 0, now - 400 * day, null, null, MediaInfo("Backyard Science", null))
        val brandNew = DriveFile("kr", "Kitchen Rules", FOLDER, 0, now - 3 * day, null, null, MediaInfo("Kitchen Rules", null))
        fun fresh(id: String, name: String, season: Int, number: Int, created: Long) = FreshEpisode(id, name, "season", "Season $season", created, season, number)
        val found = mapOf(
            "bs" to listOf(fresh("n2", "Backyard Science S03E02.mkv", 3, 2, now - 2 * day)),
            "kr" to listOf(fresh("k1", "Kitchen Rules S01E01.mkv", 1, 1, now - 3 * day)),
            "gone" to listOf(fresh("g1", "Gone S01E05.mkv", 1, 5, now - day)), // a show TV Shows doesn't list (any more)
        )
        fun factsWith(watched: Set<String> = emptySet(), recent: List<RecentItem> = emptyList(), folders: Map<String, List<DriveFile>> = emptyMap()): WatchFacts {
            val news = WatchJson.newShows(found, listOf(old, brandNew), now, { it in watched }, recent)
            return WatchFacts(emptyList(), listOf(old, brandNew), recent, recent, emptyMap(), { it in watched }, { folders[it] }, news, now)
        }
        val f = factsWith()
        assertEquals(setOf("bs", "kr"), f.news.keys)
        // A show the family already had: its poster says what's new, it counts as added when that came, and its main
        // play is the first new episode, from its start.
        val t = WatchJson.title(old, f)
        assertEquals("New · S3 E2", t.getString("caption"))
        assertEquals(now - 2 * day, t.getLong("added"))
        val p = t.getJSONObject("play")
        assertEquals("Play S3 E2", p.getString("label"))
        assertEquals("drive:n2", p.getString("key"))
        assertEquals(0, p.getLong("position"))
        assertEquals("Backyard Science S03E02.mkv", p.getString("title"))
        // A show added whole lately is simply new: no caption (its first episode plays as any show's would).
        val k = WatchJson.title(brandNew, f)
        assertTrue(k.isNull("caption"))
        assertEquals("Play S1 E1", k.getJSONObject("play").getString("label"))
        // Watched on any TV: nothing new any more.
        val seen = WatchJson.title(old, factsWith(watched = setOf("drive:n2")))
        assertTrue(seen.isNull("caption"))
        assertTrue(seen.isNull("play"))
        assertEquals(old.added, seen.getLong("added"))
        // Started: it's the episode to go on with, no longer new.
        val going = WatchJson.title(old, factsWith(recent = listOf(RecentItem("drive:n2", "Backyard Science S03E02.mkv", 400_000, 1_440_000, 1))))
        assertTrue(going.isNull("caption"))
        assertEquals("Resume S3 E2", going.getJSONObject("play").getString("label"))
        // A new episode that's the show's first anyway plays as its first (known from its listing): "Play S1 E1".
        val s1 = DriveFile("ks1", "Season 1", FOLDER, 0, 1, null)
        val k1 = DriveFile("k1", "Kitchen Rules S01E01.mkv", "video/mp4", 1, 1, null)
        val listed = factsWith(folders = mapOf("kr" to listOf(s1), "ks1" to listOf(k1)))
        assertEquals("drive:k1", WatchJson.title(brandNew, listed).getJSONObject("play").getString("key"))
        assertEquals("Play S1 E1", WatchJson.title(brandNew, listed).getJSONObject("play").getString("label"))
        // Several new episodes: "New season" from its episode 1, else how many.
        val more = mapOf("bs" to listOf(fresh("a", "x S04E01.mkv", 4, 1, now - day), fresh("b", "x S04E02.mkv", 4, 2, now - day)))
        assertEquals("New season", WatchJson.caption(old, WatchFacts(emptyList(), listOf(old), emptyList(), emptyList(), emptyMap(), { false }, { null }, WatchJson.newShows(more, listOf(old), now, { false }, emptyList()), now)))
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
