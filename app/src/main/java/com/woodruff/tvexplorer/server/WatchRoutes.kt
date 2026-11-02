package com.woodruff.tvexplorer.server

import android.content.Context
import android.content.Intent
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.EpisodeShows
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.cloud.FolderCache
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.cloud.LibrarySort
import com.woodruff.tvexplorer.cloud.matchKeyOf
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.player.Watched
import com.woodruff.tvexplorer.tmdb.Tmdb
import com.woodruff.tvexplorer.tvs.HealthGrant
import com.woodruff.tvexplorer.tvs.SignInState
import com.woodruff.tvexplorer.tvs.SignIns
import com.woodruff.tvexplorer.tvs.TvLinks
import com.woodruff.tvexplorer.ui.screens.seasonHolding
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's Watch tab: its home in one request, the Movies and TV Shows grids a page at a time,
 * search, a title's details and a season's episodes, Mark as watched and Remove from Continue
 * watching, Drive-hosted art, and starting something on another TV or on every TV at once.
 * Everything comes from what this TV keeps ([WatchLibrary], [FolderCache], the
 * stores of what's been played and ✓'d, TMDB's cache) on the request's own thread; only a title's
 * own folder (never listed on this TV) and TMDB details not cached yet are asked for, and then only
 * for a moment ([soon]): a request never waits much longer than that.
 * All behind the pairing key (none of these is in TvLinks.LINK_ROUTES).
 */
class WatchRoutes(
    private val ctx: Context,
    /** Starts an activity as the companion server does ([CompanionRoutes]): false when the TV keeps it waiting instead. */
    private val start: (Intent) -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handle(s: IHTTPSession, body: () -> JSONObject): Response? = when {
        s.uri == "/api/watch/home" && s.method == Method.GET -> home(s)
        s.uri == "/api/library" && s.method == Method.GET -> library(s)
        s.uri == "/api/library/title" && s.method == Method.GET -> title(param(s, "id"))
        s.uri == "/api/library/episodes" && s.method == Method.GET -> episodes(param(s, "show"), param(s, "season"))
        s.uri == "/api/library/search" && s.method == Method.GET -> search(s)
        s.uri == "/api/library/watched" && s.method == Method.POST -> watched(body())
        s.uri == "/api/continue/remove" && s.method == Method.POST -> removeContinue(body())
        s.uri == "/api/poster" && s.method == Method.GET -> PhonePosters.serve(ctx, s)
        s.uri == "/api/tvs/watch" && s.method == Method.POST -> relayWatch(body())
        else -> null
    }

    // ---- What's read for every answer --------------------------------------------------------

    private fun facts(m: WatchLibrary.Listing?, sh: WatchLibrary.Listing?): WatchFacts {
        val store = RecentStore(ctx)
        EpisodeShows.load(ctx)
        return WatchFacts(
            m?.files.orEmpty(), sh?.files.orEmpty(), store.all(), store.continueWatching(), EpisodeShows.map.value,
            watched = { Watched.isWatched(ctx, it) }, folder = FolderCache::inMemory,
        )
    }

    /** Where Watch stands ([watchState]), starting a listing here when nothing is saved and nothing is listing. */
    private fun state(m: WatchLibrary.Listing?, sh: WatchLibrary.Listing?): String {
        val saved = m != null || sh != null
        val signIn = runCatching { SignIns.state(ctx, HealthGrant.DRIVE).state }.getOrDefault(SignInState.SIGNED_IN)
        val missing = WatchLibrary.bothMissing()
        if (!saved && !missing && signIn == SignInState.SIGNED_IN && !WatchLibrary.listing) WatchLibrary.listSoon(ctx)
        val empty = (saved && m?.files.isNullOrEmpty() && sh?.files.isNullOrEmpty()) || (!saved && missing)
        return watchState(signIn, saved, empty, WatchLibrary.listing)
    }

    // ---- GET /api/watch/home -------------------------------------------------------------------

    private fun home(s: IHTTPSession): Response {
        // Positions from the family's other TVs, so Continue watching and its progress are current: fetched
        // unless that was done in the last few seconds, waited for half a second at most (it carries on, and
        // the next answer has it).
        runBlocking { FamilySync.awaitFresh(ctx) }
        val h = WatchHome.get { buildHome() }
        if (s.headers["if-none-match"] == h.etag) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_MODIFIED, "application/json", "").apply { addHeader("ETag", h.etag) }
        }
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", h.body)
            .apply { addHeader("ETag", h.etag); addHeader("Cache-Control", "no-cache") }
    }

    private fun buildHome(): String {
        val (m, sh) = WatchLibrary.listings(ctx)
        val state = state(m, sh)
        val facts = facts(m, sh)
        val all = facts.movies + facts.shows
        val o = JSONObject().put("state", state).put("tv", TvLinks.name(ctx))
            .put("updated", maxOf(m?.at ?: 0L, sh?.at ?: 0L).takeIf { it > 0 } ?: JSONObject.NULL)
        o.put("featured", JSONArray().apply { Library.featured(all, Library.today()).forEach { put(WatchJson.title(it, facts)) } })
        o.put("continue", JSONArray().apply { facts.continuing.take(20).forEach { put(WatchJson.continueItem(it, facts, restartFor(it, facts))) } })
        o.put("recent", JSONArray().apply { all.sortedByDescending { it.added }.take(20).forEach { put(WatchJson.title(it, facts)) } })
        o.put("movies", shelf(facts.movies, facts))
        o.put("shows", shelf(facts.shows, facts))
        return o.toString()
    }

    private fun shelf(files: List<DriveFile>, facts: WatchFacts) = JSONObject().put("count", files.size)
        .put("items", JSONArray().apply { WatchJson.firstOf(files).forEach { put(WatchJson.title(it, facts)) } })

    /** A show's "Play from the Start" for its Continue watching tile, when its first episode is already known (listed before). */
    private fun restartFor(r: RecentItem, facts: WatchFacts): JSONObject? {
        val entry = WatchJson.entryOf(r, facts)?.takeIf { facts.isShow(it) } ?: return null
        val top = FolderCache.inMemory(entry.id) ?: return null
        val first = firstEpisode(entry, top, wait = false) ?: return null
        return WatchJson.play(WatchJson.driveKey(first.id), first.name, 0, "Play from the Start", WatchJson.durationOf(facts.recent, WatchJson.driveKey(first.id)))
    }

    // ---- GET /api/library (a grid, a page at a time) -----------------------------------------------

    private fun library(s: IHTTPSession): Response {
        val (m, sh) = WatchLibrary.listings(ctx)
        val shows = s.parameters["place"]?.firstOrNull() == "shows"
        val listing = if (shows) sh else m
        val facts = facts(m, sh)
        val files = listing?.files.orEmpty()
        val sort = when (s.parameters["sort"]?.firstOrNull()) {
            "recent" -> LibrarySort.RECENT
            "newest" -> LibrarySort.YEAR
            "rated" -> LibrarySort.RATING
            else -> LibrarySort.TITLE
        }
        val unwatched = s.parameters["unwatched"]?.firstOrNull() == "1"
        val genre = s.parameters["genre"]?.firstOrNull()?.ifBlank { null }
        val words = WatchJson.words(s.parameters["q"]?.firstOrNull().orEmpty().take(100))
        val offset = (s.parameters["offset"]?.firstOrNull()?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val limit = (s.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 60).coerceIn(1, 120)
        // The Unwatched filter leaves out what the TV's own grid does: a film's ✓, a show already started.
        val started by lazy { Library.startedShows(files, facts.recent, facts.showOf, facts.watched) { matchKeyOf(it) } }
        val seen: (DriveFile) -> Boolean = if (shows) { f -> f.id in started } else { f -> WatchJson.filmOf(f, facts.folder(f.id))?.let { facts.watched(WatchJson.driveKey(it.id)) } == true }
        val base = if (words.isEmpty()) files else files.filter { WatchJson.matches(it, words) }
        val list = Library.apply(base, LibraryFilter(sort = sort, genre = genre, unwatched = unwatched), seen)
        return json(
            JSONObject().put("state", state(m, sh)).put("count", list.size).put("offset", offset)
                .put("items", JSONArray().apply { list.drop(offset).take(limit).forEach { put(WatchJson.title(it, facts)) } })
                .put("genres", JSONArray(Library.genres(files))).put("rated", files.any { Library.info(it).rating != null })
                .put("capped", listing?.cut?.takeIf { it > 0 } ?: JSONObject.NULL)
                .put("updated", listing?.at ?: JSONObject.NULL),
        )
    }

    // ---- GET /api/library/search ------------------------------------------------------------------

    private fun search(s: IHTTPSession): Response {
        val q = s.parameters["q"]?.firstOrNull().orEmpty().take(100)
        val limit = (s.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 100).coerceIn(1, 100)
        val (m, sh) = WatchLibrary.listings(ctx)
        val facts = facts(m, sh)
        val movies = WatchJson.search(facts.movies, q)
        val shows = WatchJson.search(facts.shows, q)
        // The library holding the best match comes first (ties: Movies), as the TV ranks one list.
        val first = when {
            movies.isEmpty() -> if (shows.isEmpty()) "movies" else "shows"
            shows.isEmpty() -> "movies"
            else -> {
                val a = movies.first(); val b = shows.first()
                val ra = Library.info(a.first).rating ?: -1.0; val rb = Library.info(b.first).rating ?: -1.0
                if (b.second < a.second || (b.second == a.second && rb > ra)) "shows" else "movies"
            }
        }
        return json(
            JSONObject().put("state", state(m, sh)).put("q", q).put("first", first)
                .put("movies", JSONArray().apply { movies.take(limit).forEach { put(WatchJson.title(it.first, facts)) } })
                .put("shows", JSONArray().apply { shows.take(limit).forEach { put(WatchJson.title(it.first, facts)) } })
                .put("capped", JSONObject().put("movies", movies.size > limit).put("shows", shows.size > limit)),
        )
    }

    // ---- GET /api/library/title ---------------------------------------------------------------------

    private fun title(id: String): Response {
        val (m, sh) = WatchLibrary.listings(ctx)
        val facts = facts(m, sh)
        val f = facts.byId[id] ?: throw IllegalArgumentException("That title isn’t in Movies or TV Shows any more.")
        val info = Library.info(f)
        val show = facts.isShow(f)
        // TMDB's details (length, age rating, cast): from its cache at once, else asked for, for a moment
        // (then kept, so the next look has them).
        val details = info.tmdbId?.let { tid -> soon(DETAILS_MS) { Tmdb.details(ctx, tid, info.tv) } }
        val o = WatchJson.title(f, facts, withPlay = false)
            .put("overview", info.overview ?: JSONObject.NULL)
            .put("runtime", details?.runtime?.takeIf { !show } ?: JSONObject.NULL)
            .put("ageRating", details?.ageRating ?: JSONObject.NULL)
            .put("cast", JSONArray(details?.cast.orEmpty().take(4)))
            .put("seasons", JSONArray()).put("season", 0).put("films", JSONObject.NULL)
            .put("play", JSONObject.NULL).put("restart", JSONObject.NULL)
        if (show) showDetails(o, f, facts) else filmDetails(o, f, facts)
        return json(o)
    }

    private fun filmDetails(o: JSONObject, f: DriveFile, facts: WatchFacts) {
        val film = if (!f.isFolder) f else {
            val inside = listingOf(f, FOLDER_MS) ?: throw IllegalArgumentException("Couldn’t list that folder. Check that ${TvLinks.name(ctx)} is online.")
            WatchJson.filmOf(f, inside) ?: run {
                // A collection (or a film in parts): its films, each played on its own.
                o.put("kind", "collection").put("films", JSONArray().apply {
                    inside.filter { it.kind == FileKind.VIDEO }.forEach { v -> put(episode(v, null, null, null, facts, collection = true, folder = f.id)) }
                })
                return
            }
        }
        o.put("play", WatchJson.filmPlay(film, facts.recent))
        o.put("restart", WatchJson.filmRestart(film, facts.recent) ?: JSONObject.NULL)
        o.put("playMinutes", o.opt("runtime") ?: JSONObject.NULL)
        o.put("watched", facts.watched(WatchJson.driveKey(film.id)))
        o.put("watchKey", WatchJson.driveKey(film.id))
    }

    private fun showDetails(o: JSONObject, f: DriveFile, facts: WatchFacts) {
        val started = System.currentTimeMillis()
        val top = listingOf(f, FOLDER_MS) ?: throw IllegalArgumentException("Couldn’t list that show. Check that ${TvLinks.name(ctx)} is online.")
        val folders = top.filter { it.isFolder }
        // Season folders, or one "Episodes" season when the episodes sit in the show's own folder.
        val seasons: List<Pair<String, String>> = folders.map { it.id to it.name }
            .ifEmpty { if (top.any { it.kind == FileKind.VIDEO }) listOf(INSIDE to "Episodes") else emptyList() }
        val listed = seasons.map { (id, _) -> keysOf(if (id == INSIDE) top else FolderCache.inMemory(id)) }
        val resume = WatchJson.showResumeItem(f, facts, listed.flatten().toSet())
        val names = seasons.map { it.second }
        val first = WatchJson.firstSeason(names)
        val open = resume?.let { r -> seasonHolding(names, listed, r.key, r.title) } ?: first
        val firstEp = seasons.getOrNull(first)?.let { (id, name) ->
            val left = FOLDER_MS - (System.currentTimeMillis() - started)
            if (id == INSIDE) top.firstOrNull { it.kind == FileKind.VIDEO }
            else listingOf(DriveFile(id, name, DriveFile.FOLDER_MIME, 0, 0, null), left.coerceAtLeast(500))?.firstOrNull { it.kind == FileKind.VIDEO }
        }
        o.put("seasons", JSONArray().apply { seasons.forEach { (id, name) -> put(JSONObject().put("id", id).put("name", name)) } })
        o.put("season", open)
        o.put("play", resume?.let(WatchJson::episodePlay) ?: firstEp?.let { WatchJson.firstEpisodePlay(it, facts.recent) } ?: JSONObject.NULL)
        o.put("restart", if (resume != null && firstEp != null) WatchJson.play(WatchJson.driveKey(firstEp.id), firstEp.name, 0, "Play from the Start", WatchJson.durationOf(facts.recent, WatchJson.driveKey(firstEp.id))) else JSONObject.NULL)
        // The episode the primary button plays, for "S2 E4 · The Volcano · Ends at 11:34 PM": its code and
        // name, and its length (TMDB's, for a moment, when the player hasn't measured it).
        val playFile = resume?.title ?: firstEp?.name
        val playSeason = resume?.let { r -> seasonHolding(names, listed, r.key, r.title) } ?: first
        o.put("playName", playFile?.let { WatchJson.episodeLabel(it) } ?: JSONObject.NULL)
        o.put("playMinutes", playFile?.let { name -> soon(DETAILS_MS) { Tmdb.episodeRuntime(ctx, f.name, name, names.getOrNull(playSeason)) } } ?: JSONObject.NULL)
    }

    /** The first episode of a show's first season ([top]: the show folder's listing), if it's known ([wait]: list it now if not). */
    private fun firstEpisode(show: DriveFile, top: List<DriveFile>, wait: Boolean): DriveFile? {
        val folders = top.filter { it.isFolder }
        if (folders.isEmpty()) return top.firstOrNull { it.kind == FileKind.VIDEO }
        val season = folders[WatchJson.firstSeason(folders.map { it.name })]
        val eps = if (wait) listingOf(season, FOLDER_MS) else FolderCache.inMemory(season.id)
        return eps?.firstOrNull { it.kind == FileKind.VIDEO }
    }

    // ---- GET /api/library/episodes ------------------------------------------------------------------

    private fun episodes(showId: String, seasonId: String): Response {
        val (m, sh) = WatchLibrary.listings(ctx)
        val facts = facts(m, sh)
        val show = facts.byId[showId] ?: throw IllegalArgumentException("That show isn’t in TV Shows any more.")
        val folderId = if (seasonId == INSIDE) show.id else seasonId
        val seasonName = if (seasonId == INSIDE) null else FolderCache.inMemory(show.id)?.firstOrNull { it.id == seasonId }?.name
        val season = Seasons.ready(ctx, folderId) ?: run {
            val job = Seasons.prepare(ctx, folderId, show.name, seasonName)
            val got = runBlocking { withTimeoutOrNull(EPISODES_MS) { job.await() } }
            got ?: Seasons.plain(ctx, folderId)?.takeIf { Seasons.runningFor(folderId) > PREPARE_GRACE_MS }
        } ?: return json(JSONObject().put("state", "loading").put("episodes", JSONArray()))
        val number = seasonName?.let(Tmdb::seasonNumber)
        // Each is this show's, whatever its file is called, when the phone starts one here (as the title
        // page's do): its Skip intro and audio and subtitle choices, and its poster in Continue watching.
        if (facts.isShow(show)) EpisodeShows.remember(ctx, season.files.filter { it.kind == FileKind.VIDEO }.associate { WatchJson.driveKey(it.id) to show.id })
        return json(JSONObject().put("state", "ready").put("episodes", JSONArray().apply {
            season.files.filter { it.kind == FileKind.VIDEO }.forEach { v ->
                put(episode(v, number, season.stills[v.id], season.minutes[v.id], facts, folder = folderId))
            }
        }))
    }

    /** One episode (or a collection's film) for the phone's list: what it is, how far it's watched, and what to start. */
    private fun episode(v: DriveFile, season: Int?, still: String?, minutes: Int?, facts: WatchFacts, collection: Boolean = false, folder: String? = null): JSONObject {
        val key = WatchJson.driveKey(v.id)
        val seen = facts.recent.firstOrNull { it.key == key }
        val number = if (collection) null else Tmdb.episodeNumber(v.name, season)?.second
        val measured = seen?.durationMs?.takeIf { it > 0 }?.let { ((it + 30_000) / 60_000).toInt() }
        return JSONObject()
            .put("key", key).put("playTitle", v.name)
            .put("code", if (collection) JSONObject.NULL else com.woodruff.tvexplorer.ui.screens.episodeCode(v.name) ?: JSONObject.NULL)
            .put("number", number ?: JSONObject.NULL)
            .put("special", season == 0 || Tmdb.episodeNumber(v.name, season)?.first == 0)
            .put("name", if (collection) Library.label(v) else WatchJson.episodeName(v.name, number))
            .put("overview", JSONObject.NULL) // an episode synopsis, once this TV keeps TMDB's
            .put("still", still ?: v.thumbnailLink?.takeIf { com.woodruff.tvexplorer.cloud.isGoogleUrl(it) }?.let { WatchJson.artRoute(v.id, "still", v.modified, folder) } ?: JSONObject.NULL)
            .put("runtime", minutes ?: measured ?: JSONObject.NULL)
            .put("position", WatchJson.resumeAt(facts.recent, key).takeIf { seen?.upNext != true } ?: 0L)
            .put("duration", seen?.durationMs ?: 0L)
            .put("watched", facts.watched(key))
            .put("upNext", seen?.upNext == true)
    }

    private fun keysOf(files: List<DriveFile>?): Set<String> =
        files.orEmpty().filter { it.kind == FileKind.VIDEO }.mapTo(HashSet()) { WatchJson.driveKey(it.id) }

    /**
     * [folder]'s listing: in memory, else saved on this TV, else listed from Drive now, waiting at most
     * [waitMs] (the listing carries on, and is kept, if it takes longer). Null when none was had in time.
     */
    private fun listingOf(folder: DriveFile, waitMs: Long): List<DriveFile>? {
        FolderCache.peek(ctx, folder.id)?.let { refreshLater(folder); return it }
        return Folders.list(ctx, folder, waitMs)
    }

    /** A folder shown from what was saved is listed again in the background now and then, so the next look is current. */
    private fun refreshLater(folder: DriveFile) = Folders.refresh(ctx, folder)

    // ---- POST: Mark as watched, Remove from Continue watching ---------------------------------------

    /**
     * Mark as watched (or not): as the TV's own menus do it, the ✓ and, when watched, off Continue
     * watching; both reach the family's other TVs.
     */
    private fun watched(b: JSONObject): Response {
        val keys = (b.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf(b.optString("key")))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(500)
        require(keys.isNotEmpty()) { "Nothing to mark" }
        if (b.optBoolean("watched", true)) {
            if (keys.size == 1) Watched.mark(ctx, keys[0]) else Watched.markAll(ctx, keys)
            val store = RecentStore(ctx)
            keys.forEach { store.remove(it) }
        } else {
            if (keys.size == 1) Watched.unmark(ctx, keys[0]) else Watched.unmarkAll(ctx, keys)
        }
        WatchLibrary.changed()
        return json(JSONObject().put("ok", true))
    }

    /** Remove from Continue watching: its whole show, as the TV's tile menu does (an older episode would take its place). */
    private fun removeContinue(b: JSONObject): Response {
        val key = b.optString("key").trim()
        require(key.isNotEmpty()) { "Nothing to remove" }
        val store = RecentStore(ctx)
        val item = store.all().firstOrNull { it.key == key } ?: RecentItem(key, b.optString("title"), 0, 0, 0)
        store.removeShow(item)
        WatchLibrary.changed()
        return json(JSONObject().put("ok", true))
    }

    // ---- POST /api/tvs/watch: start it on a linked TV, or on every TV together -------------------------

    private fun relayWatch(b: JSONObject): Response {
        val id = b.optString("id")
        val key = b.optString("key")
        val title = b.optString("title").ifBlank { "Video" }
        val position = b.optLong("position").coerceAtLeast(0)
        require(MediaRef.parse(key) is MediaRef.Drive) { "Only Google Drive videos can be watched together" }
        WatchLibrary.changed()
        // Every TV from the same spot, worked out here once the family's latest is in.
        if (id == "all") return json(WatchStart.spotted(ctx, b, title).let { watchTogether(it.key, it.title, it.positionMs ?: 0L) })
        if (id == "this") {
            val s = WatchStart.of(ctx, b, title)
            return json(JSONObject().put("ok", true).put("started", start(PlayerActivity.intent(ctx, listOf(s.key), listOf(s.title), s.key, s.positionMs))).put("tv", TvLinks.name(ctx)))
        }
        val tv = TvLinks.find(id) ?: throw IllegalArgumentException("That TV isn’t linked any more.")
        val answer = try {
            // A resume starts where that TV knows the family last stopped (its own spot included), not at this TV's.
            TvLinks.watch(tv, key, title, position, again = false, resume = WatchStart.resumes(b), latest = WatchStart.goesOn(b))
        } catch (e: TvLinks.Refused) {
            throw IllegalArgumentException("${tv.name}: ${e.message}")
        } catch (e: IOException) {
            throw IllegalArgumentException("${tv.name} didn’t respond")
        }
        return json(JSONObject().put("ok", true).put("started", answer.optBoolean("started", true))
            .put("message", answer.optString("message").ifBlank { null } ?: JSONObject.NULL).put("tv", tv.name))
    }

    /**
     * Watch together, as Home's tile menu starts it: every linked TV first (each answers within its
     * usual few seconds), then this TV, which leads (its pauses and seeks keep the others in step).
     */
    private fun watchTogether(key: String, title: String, position: Long): JSONObject {
        TvLinks.load(ctx)
        val linked = TvLinks.linked.value
        val results = runBlocking {
            linked.map { tv ->
                scope.async {
                    tv.name to runCatching { TvLinks.watch(tv, key, title, position).optBoolean("started", true) }.getOrDefault(false)
                }
            }.awaitAll()
        }
        val here = start(PlayerActivity.intent(ctx, listOf(key), listOf(title), key, position, together = true))
        val failed = results.filter { !it.second }.map { it.first } + (if (here) emptyList() else listOf(TvLinks.name(ctx)))
        return JSONObject().put("ok", true).put("started", results.count { it.second } + (if (here) 1 else 0))
            .put("of", linked.size + 1).put("failed", JSONArray(failed))
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    /** [work], waited for at most [waitMs]; it carries on (and keeps what it finds) if it takes longer. Null then, or when it failed. */
    private fun <T> soon(waitMs: Long, work: suspend () -> T): T? {
        val d = scope.async { runCatching { work() }.getOrNull() }
        return runBlocking { withTimeoutOrNull(waitMs) { d.await() } }
    }

    private fun param(s: IHTTPSession, name: String): String =
        s.parameters[name]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing $name")

    private fun json(o: JSONObject) = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())

    companion object {
        /** The season id of episodes kept in the show's own folder. */
        const val INSIDE = "inside"
        /** How long a title waits for TMDB's details it hasn't cached yet. */
        private const val DETAILS_MS = 1_500L
        /** How long a title waits for its own folder to be listed from Drive (never listed on this TV). */
        private const val FOLDER_MS = 8_000L
        /** How long a season's episodes wait for their listing and TMDB's pictures. */
        private const val EPISODES_MS = 1_500L
        /** After this, a season whose listing is known is answered without TMDB's pictures rather than "loading" again. */
        private const val PREPARE_GRACE_MS = 3_000L
    }
}

/**
 * Where the phone's Watch tab stands: "ready"; "loading" (nothing saved yet, being listed);
 * "offline" / "unreachable" (the phone shows the saved library if there is one, or says so);
 * "expired" (Google ended the sign-in, the saved library stays) or "expiredEmpty" (nothing saved);
 * "notSignedIn"; "notSetUp" (no Google keys); "keysRejected" (Google turns the keys down); "empty"
 * (signed in, both libraries empty or missing). Visible for tests.
 */
internal fun watchState(signIn: SignInState, saved: Boolean, empty: Boolean, listing: Boolean): String = when (signIn) {
    SignInState.NOT_SET_UP -> "notSetUp"
    SignInState.NOT_SIGNED_IN -> "notSignedIn"
    SignInState.EXPIRED -> if (saved) "expired" else "expiredEmpty"
    SignInState.KEYS_REJECTED -> "keysRejected"
    SignInState.OFFLINE -> "offline"
    SignInState.UNREACHABLE -> "unreachable"
    SignInState.SIGNED_IN -> when {
        empty -> "empty"
        saved -> "ready"
        listing -> "loading"
        // Nothing saved, and listing it here couldn't get through (it tries again shortly): Google doesn't answer.
        else -> "unreachable"
    }
}

/**
 * Watch's home, as last built: phones asking at once (or within 10 s) share one, and an unchanged one
 * costs a 304 ([etag]). Built afresh after anything the phone changed ([forget]).
 */
internal object WatchHome {
    /** [synced]: [FamilySync.updates] as it was built: the other TVs' progress merged in since then builds it afresh. */
    class Built(val body: String, val etag: String, val at: Long, val synced: Int = 0)
    private const val KEEP_MS = 10_000L
    @Volatile private var last: Built? = null
    /** Moves on with every [forget]: an answer that was being built meanwhile isn't kept (it may predate a mark). */
    @Volatile private var generation = 0

    fun get(build: () -> String): Built {
        val synced = FamilySync.updates.value
        last?.let { if (kept(it, synced)) return it }
        synchronized(this) {
            last?.let { if (kept(it, synced)) return it }
            val was = generation
            val body = build()
            return Built(body, etagOf(body), System.currentTimeMillis(), synced).also { if (generation == was) last = it }
        }
    }

    private fun kept(b: Built, synced: Int) = b.synced == synced && System.currentTimeMillis() - b.at in 0 until KEEP_MS

    fun forget() { generation++; last = null }

    /** A short strong tag for [body]: the same body, the same tag. */
    fun etagOf(body: String): String =
        "\"" + MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).take(12).joinToString("") { "%02x".format(it) } + "\""
}

/** Folders listed for the phone (a title's own folder never opened on this TV): one listing per folder at a time, kept in [FolderCache]. */
private object Folders {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<String, Deferred<List<DriveFile>?>>()
    private val refreshed = ConcurrentHashMap<String, Long>()

    fun list(c: Context, folder: DriveFile, waitMs: Long): List<DriveFile>? {
        val d = job(c, folder)
        return runBlocking { withTimeoutOrNull(waitMs) { d.await() } }
    }

    /** Listed again in the background, at most every 10 minutes per folder. */
    fun refresh(c: Context, folder: DriveFile) {
        val now = System.currentTimeMillis()
        if (now - (refreshed[folder.id] ?: 0L) < 10 * 60_000L) return
        refreshed[folder.id] = now
        job(c, folder)
    }

    private fun job(c: Context, folder: DriveFile): Deferred<List<DriveFile>?> = synchronized(running) {
        running[folder.id]?.takeIf { it.isActive } ?: scope.async { runCatching { FolderCache.list(c, null, folder) }.getOrNull() }
            .also { d -> running[folder.id] = d; d.invokeOnCompletion { synchronized(running) { if (running[folder.id] === d) running.remove(folder.id) } } }
    }
}

/**
 * Seasons' episodes made ready for the phone: the season's listing with TMDB's pictures and lengths
 * looked up once (as the TV's title page does), kept for the 20 seasons looked at last.
 */
private object Seasons {
    class Season(val files: List<DriveFile>, val stills: Map<String, String>, val minutes: Map<String, Int>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kept = object : LinkedHashMap<String, Season>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Season>?) = size > 20
    }
    private val running = ConcurrentHashMap<String, Pair<Long, Deferred<Season?>>>()

    /** The season ready, if it was made from the listing this TV holds now (listed again since: made again). */
    fun ready(c: Context, folderId: String): Season? {
        val s = synchronized(kept) { kept[folderId] } ?: return null
        return s.takeIf { FolderCache.peek(c, folderId) == s.files }
    }

    /** Its listing alone (no TMDB pictures), when one is held. */
    fun plain(c: Context, folderId: String): Season? = FolderCache.peek(c, folderId)?.let { Season(it, emptyMap(), emptyMap()) }

    fun runningFor(folderId: String): Long = running[folderId]?.let { System.currentTimeMillis() - it.first } ?: 0L

    fun prepare(c: Context, folderId: String, show: String, seasonFolder: String?): Deferred<Season?> = synchronized(running) {
        running[folderId]?.second?.takeIf { it.isActive } ?: scope.async {
            runCatching {
                val files = FolderCache.peek(c, folderId)
                    ?: FolderCache.list(c, null, DriveFile(folderId, seasonFolder ?: show, DriveFile.FOLDER_MIME, 0, 0, null))
                    ?: return@runCatching null
                val videos = files.filter { it.kind == FileKind.VIDEO }
                val withStills = runCatching { Tmdb.withEpisodeStills(c, videos, show, seasonFolder) }.getOrDefault(videos)
                val stills = withStills.mapNotNull { v -> v.posterUrl?.takeIf(WatchJson::isTmdbUrl)?.let { v.id to it } }.toMap()
                val minutes = if (stills.isEmpty() && !Tmdb.isConfigured(c)) emptyMap()
                else videos.mapNotNull { v -> runCatching { Tmdb.episodeRuntime(c, show, v.name, seasonFolder) }.getOrNull()?.let { v.id to it } }.toMap()
                Season(files, stills, minutes).also { synchronized(kept) { kept[folderId] = it } }
            }.getOrNull()
        }.also { d -> running[folderId] = System.currentTimeMillis() to d }
    }
}
