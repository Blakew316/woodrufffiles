package com.woodruff.tvexplorer.server

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.FolderCache
import com.woodruff.tvexplorer.cloud.ListingCache
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.player.Watched
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

    @Before fun setUp() {
        WatchHome.forget()
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
        Watched.unmarkAll(c, listOf("drive:m3", "drive:m1", "drive:e24"))
        WatchHome.forget()
    }

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
        assertEquals("S2 E4 · The Volcano", t.getString("playName"))
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
