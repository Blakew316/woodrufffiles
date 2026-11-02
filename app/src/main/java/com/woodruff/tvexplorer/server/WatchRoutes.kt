package com.woodruff.tvexplorer.server

import android.content.Context
import android.content.Intent
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.EpisodeMeta
import com.woodruff.tvexplorer.cloud.EpisodeShows
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.cloud.FolderCache
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.cloud.LibrarySort
import com.woodruff.tvexplorer.cloud.NewEpisodes
import com.woodruff.tvexplorer.cloud.TitleSearch
import com.woodruff.tvexplorer.cloud.matchKeyOf
import com.woodruff.tvexplorer.cloud.newestAdded
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
import com.woodruff.tvexplorer.ui.screens.MainPlay
import com.woodruff.tvexplorer.ui.screens.seasonHolding
import com.woodruff.tvexplorer.ui.screens.seasonOfEpisode
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
import kotlinx.coroutines.coroutineScope
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
        val recent = store.all()
        val watched: (String) -> Boolean = { Watched.isWatched(ctx, it) }
        val shows = sh?.files.orEmpty()
        val now = System.currentTimeMillis()
        // The shows' new episodes as the TV's own Home and TV Shows have them: what its last TV Shows listing found.
        val news = if (shows.isEmpty()) emptyMap() else WatchJson.newShows(WatchLibrary.freshEpisodes(ctx), shows, now, watched, recent)
        return WatchFacts(
            m?.files.orEmpty(), shows, recent, store.continueWatching(), EpisodeShows.map.value,
            watched = watched, folder = FolderCache::inMemory, news = news, now = now,
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
        // A file kept in both libraries (Drive's older files can sit in two folders) is one title, shown once.
        val all = (facts.movies + facts.shows).distinctBy { it.id }
        val o = JSONObject().put("state", state).put("tv", TvLinks.name(ctx))
            .put("updated", maxOf(m?.at ?: 0L, sh?.at ?: 0L).takeIf { it > 0 } ?: JSONObject.NULL)
        o.put("featured", JSONArray().apply { Library.featured(all, Library.today()).forEach { put(WatchJson.title(it, facts)) } })
        o.put("continue", JSONArray().apply { facts.continuing.take(20).forEach { put(WatchJson.continueItem(it, facts, restartFor(it, facts))) } })
        // The TV's Recently added, by its own rule (newestAdded): newest first, a show whose new episodes came lately
        // by the newest of them, each file once.
        o.put("recent", JSONArray().apply {
            newestAdded(listOf(DrivePlace.Movies to facts.movies, DrivePlace.TvShows to facts.shows), facts.news).forEach { (_, f) -> put(WatchJson.title(f, facts)) }
        })
        o.put("movies", shelf(facts.movies, facts))
        o.put("shows", shelf(facts.shows, facts))
        return o.toString()
    }

    private fun shelf(files: List<DriveFile>, facts: WatchFacts) = JSONObject().put("count", files.size)
        .put("items", JSONArray().apply { WatchJson.firstOf(files).forEach { put(WatchJson.title(it, facts)) } })

    /** A show's "Play from the Start" for its Continue watching tile, when its first episode is already known (listed before). */
    private fun restartFor(r: RecentItem, facts: WatchFacts): JSONObject? {
        val entry = WatchJson.entryOf(r, facts)?.takeIf { facts.isShow(it) } ?: return null
        val first = WatchJson.firstEpisode(entry, FolderCache::inMemory) ?: return null
        return WatchJson.restartPlay(entry, first, facts.recent)
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
        val query = s.parameters["q"]?.firstOrNull().orEmpty().take(TitleSearch.QUERY_MAX)
        val offset = (s.parameters["offset"]?.firstOrNull()?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val limit = (s.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 60).coerceIn(1, 120)
        // The Unwatched filter leaves out what the TV's own grid does: a film's ✓, a show already started.
        val started by lazy { Library.startedShows(files, facts.recent, facts.showOf, facts.watched) { matchKeyOf(it) } }
        val seen: (DriveFile) -> Boolean = if (shows) { f -> f.id in started } else { f -> WatchJson.filmOf(f, facts.folder(f.id))?.let { facts.watched(WatchJson.driveKey(it.id)) } == true }
        // Filtered, matched (Library.queryWords / matches: the TV's one rule, a four-digit word also its year) and sorted
        // as the TV's grid does it; "Recently Added" with a show whose new episodes came lately by the newest of them.
        val list = Library.apply(files, LibraryFilter(sort = sort, genre = genre, query = query, unwatched = unwatched), seen) { NewEpisodes.addedAt(it, facts.news) }
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
        val q = s.parameters["q"]?.firstOrNull().orEmpty().take(TitleSearch.QUERY_MAX)
        val limit = (s.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: TitleSearch.LIMIT).coerceIn(1, TitleSearch.LIMIT)
        val (m, sh) = WatchLibrary.listings(ctx)
        val facts = facts(m, sh)
        // The TV's Search: one list, matched and ranked by TitleSearch, which the phone groups into its two
        // libraries, the one holding the best match first (search spec §3.3), each kept to [limit].
        val found = WatchJson.sections(TitleSearch.find(q, mapOf(DrivePlace.Movies to facts.movies, DrivePlace.TvShows to facts.shows), limit = Int.MAX_VALUE))
        return json(
            JSONObject().put("state", state(m, sh)).put("q", q).put("first", if (found.showsFirst) "shows" else "movies")
                .put("movies", JSONArray().apply { found.movies.take(limit).forEach { put(WatchJson.title(it, facts)) } })
                .put("shows", JSONArray().apply { found.shows.take(limit).forEach { put(WatchJson.title(it, facts)) } })
                .put("capped", JSONObject().put("movies", found.movies.size > limit).put("shows", found.shows.size > limit)),
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
                    inside.filter { it.kind == FileKind.VIDEO }.forEach { v -> put(WatchJson.episode(v, f, facts, f.id, collection = true)) }
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
        val names = seasons.map { it.second }
        val first = WatchJson.firstSeason(names)
        val firstEp = seasons.getOrNull(first)?.let { (id, name) ->
            val left = FOLDER_MS - (System.currentTimeMillis() - started)
            if (id == INSIDE) top.firstOrNull { it.kind == FileKind.VIDEO }
            else listingOf(DriveFile(id, name, DriveFile.FOLDER_MIME, 0, 0, null), left.coerceAtLeast(500))?.firstOrNull { it.kind == FileKind.VIDEO }
        }
        // What the main button plays, as the TV's title page decides it (the episode to go on with, else the first new
        // one, else the first), and the season the sheet opens at: that episode's.
        val main = WatchJson.showMain(f, facts, listed.flatten().toSet(), firstEp)
        val open = when (main) {
            is MainPlay.GoOn -> seasonHolding(names, listed, main.item.key, main.item.title)
            is MainPlay.New -> seasonOfEpisode(seasons.map { (id, _) -> id.takeIf { it != INSIDE } }, names, listed, main.episode.id, listOf(main.episode))
            else -> null
        } ?: first
        // "Play from the Start" beside a button that goes on with the show.
        val restart = firstEp?.takeIf { main is MainPlay.GoOn || main is MainPlay.New }
        // TMDB's word on those episodes (their real names for the player and the line under the buttons, "S2 E4 · The
        // Volcano · Ends at 11:34 PM", and the main one's length), from its cache at once, else asked for, for a moment.
        val (mainMeta, restartMeta) = episodeMetas(
            f,
            when (main) {
                is MainPlay.GoOn -> main.item.title to names.getOrNull(open)
                is MainPlay.New -> main.episode.name to main.episode.seasonName
                is MainPlay.First -> main.episode.name to names.getOrNull(first)
                else -> null
            },
            restart?.let { it.name to names.getOrNull(first) },
        )
        o.put("seasons", JSONArray().apply { seasons.forEach { (id, name) -> put(JSONObject().put("id", id).put("name", name)) } })
        o.put("season", open)
        o.put("play", main?.let { WatchJson.mainPlay(f, it, facts.recent, mainMeta) } ?: JSONObject.NULL)
        o.put("restart", restart?.let { WatchJson.restartPlay(f, it.copy(episode = restartMeta ?: it.episode), facts.recent) } ?: JSONObject.NULL)
        o.put("playName", WatchJson.playName(mainMeta) ?: JSONObject.NULL)
        o.put("playMinutes", mainMeta?.minutes ?: JSONObject.NULL)
    }

    /**
     * What TMDB knows of [show]'s [episodes] (each a file name and its season folder's name, or null for none): from
     * its cache at once, else asked for, all together, for a moment (then kept, so the next look has them).
     */
    private fun episodeMetas(show: DriveFile, vararg episodes: Pair<String, String?>?): List<EpisodeMeta?> {
        val none = episodes.map { null }
        if (episodes.all { it == null }) return none
        return soon(DETAILS_MS) {
            coroutineScope { episodes.map { e -> async { e?.let { (name, folder) -> WatchTmdb.meta(ctx, show.name, name, folder) } } }.awaitAll() }
        } ?: none
    }

    // ---- GET /api/library/episodes ------------------------------------------------------------------

    private fun episodes(showId: String, seasonId: String): Response {
        val (m, sh) = WatchLibrary.listings(ctx)
        val facts = facts(m, sh)
        val show = facts.byId[showId]?.takeIf { facts.isShow(it) && it.isFolder } ?: throw IllegalArgumentException("That show isn’t in TV Shows any more.")
        // Only the show's own folder, or a season folder its own listing holds: the phone names the season by its
        // folder's id, and nothing else of the Drive is ever listed for it, whatever id it sends. The show's
        // listing is the one its title sheet was just made from; listed now, for a moment, if this TV lost it.
        val seasonFolder = if (seasonId == INSIDE) null else {
            val top = listingOf(show, EPISODES_MS) ?: return seasonLoading()
            WatchJson.seasonOf(top, seasonId) ?: throw IllegalArgumentException("That season isn’t in ${Library.info(show).title} any more.")
        }
        val folderId = seasonFolder?.id ?: show.id
        val season = Seasons.ready(ctx, folderId) ?: run {
            val job = Seasons.prepare(ctx, folderId, show.name, seasonFolder?.name)
            val got = runBlocking { withTimeoutOrNull(EPISODES_MS) { job.await() } }
            got ?: Seasons.plain(ctx, folderId)?.takeIf { Seasons.runningFor(folderId) > PREPARE_GRACE_MS }
        } ?: return seasonLoading()
        // Each is this show's, whatever its file is called, when the phone starts one here (as the title
        // page's are): its Skip intro and audio and subtitle choices, and its poster in Continue watching.
        // Only ever a season of this show (checked above), so no other folder's videos are filed under it.
        EpisodeShows.remember(ctx, season.videos.associate { WatchJson.driveKey(it.id) to show.id })
        // Each as the TV's episode card says it: TMDB's numbers, names, synopses and lengths where it knows them.
        return json(JSONObject().put("state", "ready").put("episodes", JSONArray().apply {
            season.videos.forEach { v -> put(WatchJson.episode(v, show, facts, folderId)) }
        }))
    }

    /** A season not ready yet: the phone asks again in a moment (each second, ten times at most). */
    private fun seasonLoading() = json(JSONObject().put("state", "loading").put("episodes", JSONArray()))

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
 * Seasons' episodes made ready for the phone: the season's listing with what TMDB knows of each
 * episode (its picture, number, name, synopsis and length, as the TV's title page and season folders
 * show them) looked up once, kept for the 20 seasons looked at last.
 */
private object Seasons {
    /**
     * A season made ready: its listing as this TV holds it ([files]), its videos as TMDB knows them
     * ([videos], see [com.woodruff.tvexplorer.cloud.EpisodeMeta]), and whether TMDB could be asked then.
     */
    class Season(val files: List<DriveFile>, val videos: List<DriveFile>, val withTmdb: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kept = object : LinkedHashMap<String, Season>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Season>?) = size > 20
    }
    private val running = ConcurrentHashMap<String, Pair<Long, Deferred<Season?>>>()

    /**
     * The season ready, if it was made from the listing this TV holds now (listed again since: made
     * again), and with TMDB if TMDB can be asked now (a key added since: made again, with the names).
     */
    fun ready(c: Context, folderId: String): Season? {
        val s = synchronized(kept) { kept[folderId] } ?: return null
        return s.takeIf { FolderCache.peek(c, folderId) == s.files && (s.withTmdb || !Tmdb.isConfigured(c)) }
    }

    /** Its listing alone (nothing from TMDB), when one is held. */
    fun plain(c: Context, folderId: String): Season? =
        FolderCache.peek(c, folderId)?.let { Season(it, it.filter { f -> f.kind == FileKind.VIDEO }, withTmdb = false) }

    fun runningFor(folderId: String): Long = running[folderId]?.let { System.currentTimeMillis() - it.first } ?: 0L

    /** Every season made again when next asked for (TMDB's answers came from somewhere else meanwhile: a test). */
    fun forget() = synchronized(kept) { kept.clear() }

    fun prepare(c: Context, folderId: String, show: String, seasonFolder: String?): Deferred<Season?> = synchronized(running) {
        running[folderId]?.second?.takeIf { it.isActive } ?: scope.async {
            runCatching {
                val files = FolderCache.peek(c, folderId)
                    ?: FolderCache.list(c, null, DriveFile(folderId, seasonFolder ?: show, DriveFile.FOLDER_MIME, 0, 0, null))
                    ?: return@runCatching null
                val videos = files.filter { it.kind == FileKind.VIDEO }
                val withTmdb = Tmdb.isConfigured(c)
                // Each episode as TMDB knows it, on its file (TMDB's answers are kept on disk: each season asked for once, ever).
                val known = runCatching { WatchTmdb.episodes(c, videos, show, seasonFolder) }.getOrDefault(videos)
                Season(files, known, withTmdb).also { synchronized(kept) { kept[folderId] = it } }
            }.getOrNull()
        }.also { d -> running[folderId] = System.currentTimeMillis() to d }
    }
}

/**
 * Where Watch asks TMDB about a show's episodes: TMDB itself, through its cache on disk (a season is
 * asked for once, ever). [episodes]: a season's videos, each with what TMDB knows of it (its picture,
 * number, name, synopsis and length); [meta]: one episode's. Tests answer here instead of TMDB.
 */
internal object WatchTmdb {
    @Volatile var episodes: suspend (Context, List<DriveFile>, String, String?) -> List<DriveFile> = Tmdb::withEpisodeStills
    @Volatile var meta: suspend (Context, String, String, String?) -> EpisodeMeta? = Tmdb::episodeMeta

    /** TMDB itself again (after a test), and no season kept as other answers made it. */
    fun reset() {
        episodes = Tmdb::withEpisodeStills
        meta = Tmdb::episodeMeta
        Seasons.forget()
    }
}
