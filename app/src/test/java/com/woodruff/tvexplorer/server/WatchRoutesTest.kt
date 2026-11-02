package com.woodruff.tvexplorer.server

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.EpisodeMeta
import com.woodruff.tvexplorer.cloud.FolderCache
import com.woodruff.tvexplorer.cloud.FreshEpisode
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.ListingCache
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.cloud.TitleSearch
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.player.Watched
import com.woodruff.tvexplorer.tmdb.Tmdb
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The phone's Watch tab against what a TV keeps: saved listings, folder listings, Continue watching
 * and ✓s, through the routes as the companion server calls them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WatchRoutesTest {
    private val c: Context = ApplicationProvider.getApplicationContext()
    private val started = mutableListOf<Intent>()
    private lateinit var routes: WatchRoutes

    private fun movie(id: String, title: String, year: Int, rating: Double?, genres: List<String>, added: Long, backdrop: Boolean = false) =
        DriveFile(id, "$title ($year).mkv", "video/x-matroska", 1_000, added, null, "https://image.tmdb.org/t/p/w342/$id.jpg",
            MediaInfo(title, year, genres, rating, overview = "About $title.", backdrop = if (backdrop) "https://image.tmdb.org/t/p/w1280/$id.jpg" else null))

    private val movies = listOf(
        movie("m1", "Midnight Harbor", 2024, 7.8, listOf("Thriller"), 50, backdrop = true),
        movie("m2", "Ironwood", 2025, 7.4, listOf("Drama"), 40, backdrop = true),
        movie("m3", "Orbit", 2021, 7.6, listOf("Sci-Fi"), 30),
        movie("m4", "Paper Planes", 2022, null, listOf("Family"), 20),
    )
    private val backyard = DriveFile("s1", "Backyard Science", DriveFile.FOLDER_MIME, 0, 60, null, null, MediaInfo("Backyard Science", 2023, listOf("Family"), 8.1))
    private val lighthouse = DriveFile("s2", "The Lighthouse", DriveFile.FOLDER_MIME, 0, 10, null, null, MediaInfo("The Lighthouse", null))
    private fun ep(id: String, name: String) = DriveFile(id, name, "video/mp4", 100, 1, "https://lh3.googleusercontent.com/$id=s220")
    private val DAY = 24 * 60 * 60_000L

    @Before fun setUp() {
        WatchHome.forget()
        WatchTmdb.reset()
        routes = WatchRoutes(c) { started += it; true }
        ListingCache.write(c, DrivePlace.Movies, movies)
        ListingCache.write(c, DrivePlace.TvShows, listOf(backyard, lighthouse))
        // Backyard Science: two seasons; The Lighthouse keeps its episodes in its own folder.
        folder("s1", listOf(DriveFile("f1", "Season 1", DriveFile.FOLDER_MIME, 0, 1, null), DriveFile("f2", "Season 2", DriveFile.FOLDER_MIME, 0, 1, null)))
        folder("f1", listOf(ep("e11", "Backyard Science S01E01 Tide Pools.mkv"), ep("e12", "Backyard Science S01E02 Ants.mkv")))
        folder("f2", listOf(ep("e21", "Backyard Science S02E01.mkv"), ep("e24", "Backyard Science S02E04 The Volcano.mkv")))
        folder("s2", listOf(ep("l1", "The Lighthouse 1x01 Arrival.mkv"), ep("l2", "The Lighthouse 1x02 Fog.mkv")))
        val store = RecentStore(c)
        store.save("drive:m1", "Midnight Harbor (2024).mkv", 2_710_000, 6_840_000)
        store.save("drive:e24", "Backyard Science S02E04 The Volcano.mkv", 731_000, 1_440_000)
    }

    @After fun tearDown() {
        ListingCache.clear(c)
        FolderCache.clear(c)
        c.getSharedPreferences("recent", Context.MODE_PRIVATE).edit().clear().commit()
        Watched.unmarkAll(c, listOf("drive:m3", "drive:m1", "drive:e24", "drive:l3"))
        WatchHome.forget()
        WatchTmdb.reset()
    }

    /** A library's saved listing, as [ListingCache] names its file. */
    private fun saved(place: DrivePlace.Named) = File(File(c.cacheDir, "listings"), "named-${place.title}.json")

    /** A folder's listing as the TV saves it after listing it once. */
    private fun folder(id: String, files: List<DriveFile>) {
        val d = File(c.cacheDir, "folders").apply { mkdirs() }
        File(d, FolderCache.fileName(id)).writeText(FolderCache.toJson(files))
    }

    @Test fun homeIsOneAnswerWithEverythingTheRootShows() {
        val r = get("/api/watch/home")
        assertEquals(NanoHTTPD.Response.Status.OK, r.status)
        val h = json(r)
        assertEquals(4, h.getJSONObject("movies").getInt("count"))
        assertEquals(2, h.getJSONObject("shows").getInt("count"))
        // A–Z, a leading "The" left out: Backyard Science, then The Lighthouse.
        assertEquals("Backyard Science", h.getJSONObject("shows").getJSONArray("items").getJSONObject(0).getString("title"))
        assertEquals("Ironwood", h.getJSONObject("movies").getJSONArray("items").getJSONObject(0).getString("title"))
        // Featured: titles with wide art.
        val featured = h.getJSONArray("featured")
        assertEquals(2, featured.length())
        for (i in 0 until featured.length()) assertTrue(featured.getJSONObject(i).getString("backdrop").startsWith("https://image.tmdb.org/"))
        // Continue watching, newest first, with the TV's words.
        val cw = h.getJSONArray("continue")
        assertEquals(2, cw.length())
        assertEquals("Backyard Science", cw.getJSONObject(0).getString("name"))
        assertEquals("S2 E4", cw.getJSONObject(0).getString("code"))
        assertEquals("Resume from 45m", cw.getJSONObject(1).getJSONObject("play").getString("label"))
        // Recently added, newest first.
        assertEquals("s1", h.getJSONArray("recent").getJSONObject(0).getString("id"))
        assertTrue(h.has("state") && h.has("tv"))
        // The same home again costs nothing: 304 for the tag the phone holds.
        val tag = r.getHeader("ETag")
        assertEquals(NanoHTTPD.Response.Status.NOT_MODIFIED, get("/api/watch/home", headers = mapOf("if-none-match" to tag)).status)
    }

    @Test fun gridsPageSortFilterAndSearchAsTheTvsGrid() {
        val all = json(get("/api/library", "place" to "movies", "limit" to "2"))
        assertEquals(4, all.getInt("count"))
        assertEquals(2, all.getJSONArray("items").length())
        assertEquals(listOf("Drama", "Family", "Sci-Fi", "Thriller"), (0 until all.getJSONArray("genres").length()).map { all.getJSONArray("genres").getString(it) }.sorted())
        assertTrue(all.getBoolean("rated"))
        val page2 = json(get("/api/library", "place" to "movies", "offset" to "2", "limit" to "2"))
        assertEquals(listOf("Orbit", "Paper Planes"), titles(page2))
        assertEquals(listOf("Midnight Harbor", "Orbit", "Ironwood", "Paper Planes"), titles(json(get("/api/library", "place" to "movies", "sort" to "rated"))))
        assertEquals(listOf("Midnight Harbor", "Ironwood", "Orbit", "Paper Planes"), titles(json(get("/api/library", "place" to "movies", "sort" to "recent"))))
        assertEquals(listOf("Ironwood"), titles(json(get("/api/library", "place" to "movies", "genre" to "Drama"))))
        assertEquals(listOf("Midnight Harbor"), titles(json(get("/api/library", "place" to "movies", "q" to "harb"))))
        assertEquals(listOf("Ironwood"), titles(json(get("/api/library", "place" to "movies", "q" to "2025"))))
        // Unwatched leaves out a film's ✓, and a show already started.
        Watched.mark(c, "drive:m3")
        assertFalse("Orbit" in titles(json(get("/api/library", "place" to "movies", "unwatched" to "1"))))
        assertEquals(listOf("The Lighthouse"), titles(json(get("/api/library", "place" to "shows", "unwatched" to "1"))))
    }

    @Test fun aLibraryTheListingLimitCutSaysSoOnThePhone() {
        // Listed in full: the grid has no "over 20,000 files" line.
        assertTrue(json(get("/api/library", "place" to "movies")).isNull("capped"))
        // Saved as the TV saves a library Drive had more of than its limit: the phone's grid says so.
        val before = ListingCache.savedAt(c, DrivePlace.Movies)
        ListingCache.write(c, DrivePlace.Movies, movies, cutAt = 20_000)
        saved(DrivePlace.Movies).setLastModified(before + 1_000) // a new saved copy, even within the same millisecond
        val cut = json(get("/api/library", "place" to "movies"))
        assertEquals(20_000, cut.getInt("capped"))
        assertEquals(4, cut.getInt("count"))
        assertTrue(json(get("/api/library", "place" to "shows")).isNull("capped"))
    }

    @Test fun searchGroupsTheTvsOneRankedList() {
        val r = json(get("/api/library/search", "q" to "the"))
        assertEquals(listOf("The Lighthouse"), titles(r, "shows"))
        assertEquals("shows", r.getString("first"))
        val s = json(get("/api/library/search", "q" to "o"))
        assertEquals("movies", s.getString("first"))
        // Its title starts with it; then the words are somewhere in it, the better rated first.
        assertEquals(listOf("Orbit", "Midnight Harbor", "Ironwood"), titles(s, "movies"))
        assertFalse(s.getJSONObject("capped").getBoolean("movies"))
    }

    @Test fun thePhonesSearchFindsWhatTheTvsSearchFindsInTheSameOrder() {
        val shows = listOf(backyard, lighthouse)
        for (q in listOf("o", "e", "the", "harbor", "2024", "light", "an", "science 2023", "zebra")) {
            val r = json(get("/api/library/search", "q" to q))
            // The TV's Search over the same libraries: one list, which the phone shows in two sections in its order.
            val tv = TitleSearch.find(q, mapOf(DrivePlace.Movies to movies, DrivePlace.TvShows to shows)).hits
            assertEquals(q, tv.filter { it.place == DrivePlace.Movies }.map { Library.info(it.file).title }, titles(r, "movies"))
            assertEquals(q, tv.filter { it.place == DrivePlace.TvShows }.map { Library.info(it.file).title }, titles(r, "shows"))
            assertEquals(q, if (tv.firstOrNull()?.place == DrivePlace.TvShows) "shows" else "movies", r.getString("first"))
        }
        // A grid's own search field: the same match (a four-digit word is also the year), in the grid's own order.
        assertEquals(listOf("Ironwood"), titles(json(get("/api/library", "place" to "movies", "q" to "2025"))))
        // (A–Z there, where Search would put "Orbit" first.)
        assertEquals(listOf("Ironwood", "Midnight Harbor", "Orbit"), titles(json(get("/api/library", "place" to "movies", "q" to "o"))))
    }

    @Test fun aFilmsTitleSheetResumesAndStartsOver() {
        val t = json(get("/api/library/title", "id" to "m1"))
        assertEquals("movie", t.getString("kind"))
        assertEquals("About Midnight Harbor.", t.getString("overview"))
        assertEquals("Resume from 45m", t.getJSONObject("play").getString("label"))
        assertEquals("Start Over", t.getJSONObject("restart").getString("label"))
        assertEquals(0, t.getJSONArray("seasons").length())
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, serve("/api/library/title", mapOf("id" to "nope")).status)
    }

    @Test fun aShowsTitleSheetOpensAtItsEpisodeToGoOnWith() {
        val t = json(get("/api/library/title", "id" to "s1"))
        assertEquals("show", t.getString("kind"))
        assertEquals(listOf("Season 1", "Season 2"), (0 until t.getJSONArray("seasons").length()).map { t.getJSONArray("seasons").getJSONObject(it).getString("name") })
        assertEquals(1, t.getInt("season")) // where S2 E4 is
        assertEquals("Resume S2 E4", t.getJSONObject("play").getString("label"))
        assertEquals("Play from the Start", t.getJSONObject("restart").getString("label"))
        assertEquals("drive:e11", t.getJSONObject("restart").getString("key"))
        // The line under the buttons names the episode only when TMDB knows its name (as on the TV): here it doesn't.
        assertTrue(t.isNull("playName"))
        assertEquals("Backyard Science S02E04 The Volcano.mkv", t.getJSONObject("play").getString("title"))
        // A show with nothing started: Play S1 E1, its episodes in its own folder ("Episodes").
        val l = json(get("/api/library/title", "id" to "s2"))
        assertEquals("Episodes", l.getJSONArray("seasons").getJSONObject(0).getString("name"))
        assertEquals(WatchRoutes.INSIDE, l.getJSONArray("seasons").getJSONObject(0).getString("id"))
        assertEquals("Play S1 E1", l.getJSONObject("play").getString("label"))
        assertTrue(l.isNull("restart"))
    }

    @Test fun aSeasonsEpisodesWithTheirProgressAndChecks() {
        Watched.mark(c, "drive:e21")
        val r = json(get("/api/library/episodes", "show" to "s1", "season" to "f2"))
        assertEquals("ready", r.getString("state"))
        val eps = r.getJSONArray("episodes")
        assertEquals(2, eps.length())
        val first = eps.getJSONObject(0)
        assertEquals("Episode 1", first.getString("name")) // nothing after its code in the file's name
        assertEquals(1, first.getInt("number"))
        assertTrue(first.getBoolean("watched"))
        val volcano = eps.getJSONObject(1)
        assertEquals("The Volcano", volcano.getString("name"))
        assertEquals("S2 E4", volcano.getString("code"))
        assertEquals(731_000, volcano.getLong("position"))
        assertEquals(24, volcano.getInt("runtime")) // the player's measured length, in minutes
        // No TMDB picture: Drive's frame, through the TV.
        assertEquals("/api/poster?id=e24&kind=still&in=f2&v=1", volcano.getString("still"))
        val inside = json(get("/api/library/episodes", "show" to "s2", "season" to WatchRoutes.INSIDE)).getJSONArray("episodes")
        assertEquals("Arrival", inside.getJSONObject(0).getString("name"))
        Watched.unmark(c, "drive:e21")
    }

    @Test fun newEpisodesReachThePhoneAsTheTvSaysThem() {
        val now = System.currentTimeMillis()
        // A third episode put in The Lighthouse's folder yesterday (the show itself came long ago), as the TV's last TV
        // Shows listing found it and saved it.
        val gale = DriveFile("l3", "The Lighthouse 1x03 Gale.mkv", "video/mp4", 100, 1, null, created = now - DAY)
        folder("s2", listOf(ep("l1", "The Lighthouse 1x01 Arrival.mkv"), ep("l2", "The Lighthouse 1x02 Fog.mkv"), gale))
        ListingCache.writeFresh(c, mapOf("s2" to listOf(FreshEpisode("l3", gale.name, null, null, now - DAY, 1, 3))))

        // First in Recently Added (when its new episode came), saying what's new, its main play that episode.
        val first = json(get("/api/watch/home")).getJSONArray("recent").getJSONObject(0)
        assertEquals("s2", first.getString("id"))
        assertEquals("New · S1 E3", first.getString("caption"))
        assertEquals(now - DAY, first.getLong("added"))
        assertEquals("Play S1 E3", first.getJSONObject("play").getString("label"))
        assertEquals("drive:l3", first.getJSONObject("play").getString("key"))
        // The TV Shows grid's "Recently Added" sort agrees.
        assertEquals("The Lighthouse", titles(json(get("/api/library", "place" to "shows", "sort" to "recent"))).first())

        // Its sheet: Play S1 E3 from its start, Play from the Start beside it; the new episode is marked new.
        val t = json(get("/api/library/title", "id" to "s2"))
        assertEquals("Play S1 E3", t.getJSONObject("play").getString("label"))
        assertEquals(0, t.getJSONObject("play").getLong("position"))
        assertEquals("Play from the Start", t.getJSONObject("restart").getString("label"))
        assertEquals("drive:l1", t.getJSONObject("restart").getString("key"))
        val eps = json(get("/api/library/episodes", "show" to "s2", "season" to WatchRoutes.INSIDE)).getJSONArray("episodes")
        assertEquals(listOf(false, false, true), (0 until eps.length()).map { eps.getJSONObject(it).getBoolean("new") })

        // Watched (here from the phone, or on any TV): nothing new any more.
        post("/api/library/watched", JSONObject().put("key", "drive:l3").put("watched", true))
        val after = json(get("/api/watch/home")).getJSONArray("recent")
        val lighthouse = (0 until after.length()).map { after.getJSONObject(it) }.single { it.getString("id") == "s2" }
        assertTrue(lighthouse.isNull("caption"))
    }

    @Test fun newEpisodesAreReadAgainOnlyWhenTheTvSavesThemAgain() {
        assertTrue(WatchLibrary.freshEpisodes(c).isEmpty()) // none saved
        ListingCache.writeFresh(c, mapOf("s2" to listOf(FreshEpisode("l3", "The Lighthouse 1x03 Gale.mkv", null, null, 5, 1, 3))))
        val read = WatchLibrary.freshEpisodes(c)
        assertEquals(setOf("s2"), read.keys)
        assertTrue("unchanged: not read again", read === WatchLibrary.freshEpisodes(c))
        // Saved again (the TV's next TV Shows listing found more): read again.
        val file = File(File(c.cacheDir, "listings"), "new-episodes.json")
        val before = file.lastModified()
        ListingCache.writeFresh(c, mapOf("s1" to listOf(FreshEpisode("e31", "Backyard Science S03E01.mkv", "f3", "Season 3", 5, 3, 1))))
        file.setLastModified(before + 1_000) // a new saved copy, even within the same millisecond
        assertEquals(setOf("s1"), WatchLibrary.freshEpisodes(c).keys)
        // Signed out (the listings cleared): nothing.
        ListingCache.clear(c)
        assertTrue(WatchLibrary.freshEpisodes(c).isEmpty())
    }

    @Test fun tmdbsEpisodeNamesAndSynopsesReachThePhone() {
        // What TMDB knows of Backyard Science's episodes: asked once per season, ever, and kept (here it answers at once).
        WatchTmdb.episodes = { _, files, _, _ ->
            files.map { f ->
                val (s, e) = Tmdb.episodeNumber(f.name, null) ?: return@map f
                f.copy(posterUrl = "https://image.tmdb.org/t/p/original/s${s}e$e.jpg", episode = EpisodeMeta(s, e, "Name $e", "About episode $e.", 25))
            }
        }
        WatchTmdb.meta = { _, _, name, _ -> Tmdb.episodeNumber(name, null)?.let { (s, e) -> EpisodeMeta(s, e, if (e == 4) "The Volcano" else "Name $e", null, 24) } }
        // A season of it not looked at before.
        folder("s1", listOf(DriveFile("f1", "Season 1", DriveFile.FOLDER_MIME, 0, 1, null), DriveFile("f2", "Season 2", DriveFile.FOLDER_MIME, 0, 1, null), DriveFile("f3-tmdb", "Season 3", DriveFile.FOLDER_MIME, 0, 1, null)))
        folder("f3-tmdb", listOf(ep("e31", "Backyard Science S03E01.mkv"), ep("e32", "Backyard.Science.S03E02.1080p.mkv")))

        val e = json(get("/api/library/episodes", "show" to "s1", "season" to "f3-tmdb")).getJSONArray("episodes").getJSONObject(1)
        assertEquals("Name 2", e.getString("name"))
        assertEquals("About episode 2.", e.getString("overview"))
        assertEquals(25, e.getInt("runtime"))
        assertEquals(2, e.getInt("number"))
        assertEquals("https://image.tmdb.org/t/p/original/s3e2.jpg", e.getString("still"))
        // The player gets its real name, as when the TV's title page starts it.
        assertEquals("Backyard Science S03E02 Name 2", e.getString("playTitle"))

        // The sheet: the main button goes on with S2 E4 under its real name, and the line under the buttons names it.
        val t = json(get("/api/library/title", "id" to "s1"))
        assertEquals("Resume S2 E4", t.getJSONObject("play").getString("label"))
        assertEquals("Backyard Science S02E04 The Volcano", t.getJSONObject("play").getString("title"))
        assertEquals("S2 E4 · The Volcano", t.getString("playName"))
        assertEquals(24, t.getInt("playMinutes"))
        assertEquals("Backyard Science S01E01 Name 1", t.getJSONObject("restart").getString("title"))
    }

    @Test fun marksAndRemovalsChangeTheStoresTheTvUses() {
        post("/api/library/watched", JSONObject().put("key", "drive:m1").put("watched", true))
        assertTrue(Watched.isWatched(c, "drive:m1"))
        assertFalse(RecentStore(c).all().any { it.key == "drive:m1" }) // and off Continue watching, as the TV's menu does
        post("/api/library/watched", JSONObject().put("key", "drive:m1").put("watched", false))
        assertFalse(Watched.isWatched(c, "drive:m1"))
        post("/api/continue/remove", JSONObject().put("key", "drive:e24"))
        assertTrue(RecentStore(c).continueWatching().isEmpty())
        assertEquals(0, json(get("/api/watch/home")).getJSONArray("continue").length())
    }

    @Test fun onlyDriveVideosGoToOtherTvs() {
        val r = serve("/api/tvs/watch", body = JSONObject().put("id", "all").put("key", "/storage/emulated/0/a.mp4").put("title", "a").put("position", 0))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, r.status)
        // Watch together with no other TVs: this TV leads by itself.
        val all = json(serve("/api/tvs/watch", body = JSONObject().put("id", "all").put("key", "drive:m1").put("title", "Midnight Harbor (2024).mkv").put("position", 2_710_000)))
        assertEquals(1, all.getInt("started"))
        assertEquals(1, all.getInt("of"))
        assertTrue(started.single().getBooleanExtra("together", false))
        assertEquals(2_710_000, started.single().getLongExtra("position", -1))
    }

    // ---- A request, as NanoHTTPD hands it over ------------------------------------------------------

    private fun titles(o: JSONObject, list: String = "items") = o.getJSONArray(list).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("title") } }
    private fun json(r: NanoHTTPD.Response): JSONObject = JSONObject(r.data.bufferedReader().readText())
    private fun get(uri: String, vararg params: Pair<String, String>, headers: Map<String, String> = emptyMap()) = serve(uri, params.toMap(), headers)
    private fun post(uri: String, body: JSONObject) = serve(uri, body = body).also { assertEquals(NanoHTTPD.Response.Status.OK, it.status) }

    /** The route's answer, with a bad request turned into a 400 as [TransferServer] does. */
    private fun serve(uri: String, params: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap(), body: JSONObject? = null): NanoHTTPD.Response {
        val bytes = body?.toString()?.toByteArray()
        val s = object : NanoHTTPD.IHTTPSession {
            override fun execute() {}
            override fun getCookies(): NanoHTTPD.CookieHandler? = null
            override fun getHeaders(): Map<String, String> = headers + (if (bytes != null) mapOf("content-length" to bytes.size.toString()) else emptyMap())
            override fun getInputStream() = ByteArrayInputStream(bytes ?: ByteArray(0))
            override fun getMethod() = if (body != null) NanoHTTPD.Method.POST else NanoHTTPD.Method.GET
            @Deprecated("") override fun getParms(): Map<String, String> = params
            override fun getParameters(): Map<String, List<String>> = params.mapValues { listOf(it.value) }
            override fun getQueryParameterString(): String = ""
            override fun getUri() = uri
            override fun parseBody(files: MutableMap<String, String>?) {}
            override fun getRemoteIpAddress() = "127.0.0.1"
            override fun getRemoteHostName() = "localhost"
        }
        return try {
            routes.handle(s) { body ?: JSONObject() } ?: NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "")
        } catch (e: IllegalArgumentException) {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", e.message)
        }
    }
}
