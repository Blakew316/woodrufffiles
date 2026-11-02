package com.woodruff.tvexplorer.server

import android.content.Context
import com.woodruff.tvexplorer.cloud.DriveApi
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DriveHttpException
import com.woodruff.tvexplorer.cloud.DriveListing
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.EpisodeMeta
import com.woodruff.tvexplorer.cloud.FreshEpisode
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.cloud.LibrarySort
import com.woodruff.tvexplorer.cloud.ListingCache
import com.woodruff.tvexplorer.cloud.NewEpisodes
import com.woodruff.tvexplorer.cloud.NewShow
import com.woodruff.tvexplorer.cloud.TitleSearch
import com.woodruff.tvexplorer.cloud.episodeCodeOf
import com.woodruff.tvexplorer.cloud.episodeName
import com.woodruff.tvexplorer.cloud.episodePlayTitle
import com.woodruff.tvexplorer.cloud.fileEpisode
import com.woodruff.tvexplorer.cloud.isGoogleUrl
import com.woodruff.tvexplorer.cloud.matchKeyOf
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.tmdb.Tmdb
import com.woodruff.tvexplorer.ui.screens.MainPlay
import com.woodruff.tvexplorer.ui.screens.episodeCode
import com.woodruff.tvexplorer.ui.screens.folderFilm
import com.woodruff.tvexplorer.ui.screens.showMainPlay
import com.woodruff.tvexplorer.ui.screens.showResume
import com.woodruff.tvexplorer.util.Format
import com.woodruff.tvexplorer.util.isNetworkFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * The family's Movies and TV Shows as the phone's Watch tab sees them, read from what this TV keeps
 * anyway: the saved listings ([ListingCache], with their TMDB details), Continue watching, the ✓s and
 * which show each episode belongs to. A listing is read and parsed once and kept until its saved copy
 * changes, so a phone asking costs a lookup. Drive is only listed here when nothing is saved at all
 * (the TV's cache was cleared, or Woodruff hasn't listed the library since signing in), so the phone
 * never waits on the TV's screen being opened first.
 */
object WatchLibrary {
    /** One library as last saved: its titles, when that copy was saved, and where the listing limit cut it (0: it wasn't). */
    internal class Listing(val files: List<DriveFile>, val at: Long, val cut: Int)

    @Volatile private var movies: Listing? = null
    @Volatile private var shows: Listing? = null
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lister: Job? = null
    @Volatile private var listFailedAt = 0L
    /** Libraries whose folder isn't in Google Drive (found by listing here): with nothing saved for either, Watch is empty, not loading. */
    @Volatile private var missing: Set<String> = emptySet()

    /** Movies and TV Shows as saved now (null: nothing saved for it). Reads a saved copy only when it changed. Blocking: off the main thread. */
    internal fun listings(c: Context): Pair<Listing?, Listing?> = synchronized(lock) {
        movies = fresh(c, DrivePlace.Movies, movies)
        shows = fresh(c, DrivePlace.TvShows, shows)
        movies to shows
    }

    private fun fresh(c: Context, place: DrivePlace, old: Listing?): Listing? {
        val at = ListingCache.savedAt(c, place)
        if (at == 0L) return null // nothing saved: signed out (it was cleared), or never listed
        if (old != null && old.at == at) return old
        val (files, cut) = savedListing(c, place) ?: return old
        return Listing(Library.prepare(Library.withInfo(files)), at, cut)
    }

    /** The saved listing of [place] and where the listing limit cut it (0: it wasn't). The one place that reads [ListingCache] here. */
    private fun savedListing(c: Context, place: DrivePlace): Pair<List<DriveFile>, Int>? =
        ListingCache.read(c, place)?.let { it.files to it.cutAt }

    /** The new episodes as last read, and when that copy was saved. */
    @Volatile private var fresh: Pair<Long, Map<String, List<FreshEpisode>>>? = null

    /**
     * The TV shows' videos put in Drive lately, as the TV's last TV Shows listing found and saved them
     * ([ListingCache.readFresh]): read again only when that copy changes, as the listings are.
     * Blocking: off the main thread.
     */
    internal fun freshEpisodes(c: Context): Map<String, List<FreshEpisode>> {
        val at = ListingCache.freshSavedAt(c)
        if (at == 0L) return emptyMap()
        fresh?.let { (was, kept) -> if (was == at) return kept }
        return ListingCache.readFresh(c).orEmpty().also { fresh = at to it }
    }

    /** Whether Drive is being listed here because nothing was saved. */
    val listing: Boolean get() = lister?.isActive == true

    /** Neither library's folder is in Drive (as listing here found). */
    internal fun bothMissing(): Boolean = missing.size == 2

    /**
     * Lists Movies and TV Shows from Drive (with their posters and TMDB details, as the TV's own
     * screen does) and saves them, when nothing is saved for either: one listing at a time, and not
     * again within 30 s of one that couldn't get through.
     */
    fun listSoon(c: Context) {
        val app = c.applicationContext
        synchronized(lock) {
            if (lister?.isActive == true || System.currentTimeMillis() - listFailedAt < 30_000) return
            lister = scope.launch { listNow(app) }
        }
    }

    private suspend fun listNow(c: Context) {
        // Read first, as the TV's own listing does: signed out meanwhile, nothing of this listing is saved.
        val gen = ListingCache.generation()
        val token = GoogleAuth.token(c) ?: run { listFailedAt = System.currentTimeMillis(); return }
        for ((place, mode) in listOf(DrivePlace.Movies to Tmdb.Mode.MOVIES, DrivePlace.TvShows to Tmdb.Mode.SHOWS)) {
            try {
                val listing = listFresh(token, place)
                val files = Library.prepare(Library.withInfo(listing.files))
                // Saved with where the listing limit cut it, so the TV's own screen and the phone both say so.
                ListingCache.write(c, place, Library.prepare(Tmdb.fill(c, files, mode)), cutAt = listing.cutAt, gen = gen)
                missing = missing - place.title
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Drive answered that there's no such folder (not the network, not an HTTP error).
                if (e is IOException && e !is DriveHttpException && !isNetworkFailure(e)) missing = missing + place.title
                else listFailedAt = System.currentTimeMillis()
            }
        }
    }

    /** A library listed from Drive (up to [DriveApi.LIBRARY_LIMIT] entries) with its posters, and where the limit cut it. */
    private suspend fun listFresh(token: String, place: DrivePlace): DriveListing {
        val listing = DriveApi.listing(token, place)
        return listing.copy(files = DriveApi.withPosters(token, listing.files))
    }

    /** Signed out, or the phone asked for something that changed what Watch shows: built afresh next time. */
    fun changed() { WatchHome.forget() }
}

/** What the Watch tab's words and rows are built from: everything read once per request, so the builders below stay pure. */
internal class WatchFacts(
    val movies: List<DriveFile>,
    val shows: List<DriveFile>,
    /** Everything played, newest first ([com.woodruff.tvexplorer.player.RecentStore.all]). */
    val recent: List<RecentItem>,
    /** Continue watching ([com.woodruff.tvexplorer.player.RecentStore.continueWatching]): one per show, newest first. */
    val continuing: List<RecentItem>,
    /** Which library entry each played video sits under ([com.woodruff.tvexplorer.cloud.EpisodeShows]). */
    val showOf: Map<String, String>,
    val watched: (String) -> Boolean,
    /** A folder's listing if it's in memory (never read from disk while building a list). */
    val folder: (String) -> List<DriveFile>?,
    /** The TV shows with new episodes, by show id, as the TV's own screens work them out ([WatchJson.newShows]). */
    val news: Map<String, NewShow> = emptyMap(),
    /** Now, for whether an episode still counts as new ([NewEpisodes.isNew]). */
    val now: Long = System.currentTimeMillis(),
) {
    private val showIds: Set<String> by lazy { shows.mapTo(HashSet()) { it.id } }
    fun isShow(f: DriveFile): Boolean = f.id in showIds
    val byId: Map<String, DriveFile> by lazy { (movies + shows).associateBy { it.id } }
}

/** Phone-facing words and objects for Watch: pure, so each rule is unit tested. */
internal object WatchJson {
    private const val DRIVE = "drive:"
    /** A film resumes ("Resume from 45m", with Start Over) only past its first minute, as the TV's title page decides. */
    private const val RESUME_FROM_MS = 60_000L

    fun driveKey(id: String) = DRIVE + id

    /** "Resume from 45m", as the TV's title page writes it. */
    fun resumeLabel(ms: Long) = "Resume from ${Format.duration(ms)}"

    /** Where a film or episode resumes: where it stopped; 0 when it's not there or was finished (past 95 %). */
    fun resumeAt(recent: List<RecentItem>, key: String): Long =
        recent.firstOrNull { it.key == key }?.takeIf { it.progress < 0.95f }?.positionMs ?: 0L

    /**
     * Everything needed to start something, so the phone never builds titles or positions itself.
     * [resume]: the TV that plays it starts it where the family last stopped ([WatchStart]; [position]
     * is what this TV knew, for the button and a TV on an older Woodruff); [latest]: a show's "go on",
     * its newest episode then. Neither: from [position] (Start Over, Play from the Start).
     */
    fun play(key: String, title: String, position: Long, label: String, duration: Long, resume: Boolean = false, latest: Boolean = false): JSONObject =
        JSONObject().put("key", key).put("title", title).put("position", position.coerceAtLeast(0)).put("label", label).put("duration", duration.coerceAtLeast(0))
            .apply { if (resume) put("resume", true); if (latest) put("latest", true) }

    /**
     * The film [f] stands for: itself, or the film kept in its own folder when that folder's listing
     * is known ([listing]); null for a collection, or a folder not listed yet.
     */
    fun filmOf(f: DriveFile, listing: List<DriveFile>?): DriveFile? = if (!f.isFolder) f else listing?.let(::folderFilm)

    /** A film's primary button: "Resume from 45m" past its first minute, else "Play" (from where it was, as the TV's player does). */
    fun filmPlay(film: DriveFile, recent: List<RecentItem>): JSONObject {
        val key = driveKey(film.id)
        val at = resumeAt(recent, key)
        return play(key, film.name, at, if (at > RESUME_FROM_MS) resumeLabel(at) else "Play", durationOf(recent, key), resume = true)
    }

    /** A film's Start Over, only when its primary button resumes. */
    fun filmRestart(film: DriveFile, recent: List<RecentItem>): JSONObject? {
        val key = driveKey(film.id)
        return if (resumeAt(recent, key) > RESUME_FROM_MS) play(key, film.name, 0, "Start Over", durationOf(recent, key)) else null
    }

    /** How long [key] runs, as the player measured it (0: not known). */
    fun durationOf(recent: List<RecentItem>, key: String): Long = recent.firstOrNull { it.key == key }?.durationMs ?: 0L

    /**
     * A show's episode to go on with: the newest in progress or up next of those known to be its own
     * (played from its page, listed in its folders, or named after it), as its TV title page finds it.
     */
    fun showResumeItem(show: DriveFile, facts: WatchFacts, listed: Set<String> = emptySet()): RecentItem? {
        val key = matchKeyOf(show.name)
        return showResume(facts.recent) { r -> facts.showOf[r.key] == show.id || r.key in listed || matchKeyOf(r.title) == key }
    }

    /**
     * "Resume S2 E4" (in progress) / "Play S2 E5" (up next), the TV's words; without a code "Resume" / "Play Next Episode".
     * [title]: what the player is given; the title it was last played with unless the episode's real name is known.
     * A show's "go on": the TV that plays it starts the show's newest episode there, where the family last stopped.
     */
    fun episodePlay(r: RecentItem, title: String = r.title): JSONObject {
        val code = episodeCode(r.title)
        val label = if (r.upNext) code?.let { "Play $it" } ?: "Play Next Episode" else code?.let { "Resume $it" } ?: "Resume"
        return play(r.key, title, if (r.upNext) 0 else r.positionMs, label, r.durationMs, resume = true, latest = true)
    }

    /**
     * What the player is given as [ep]'s title, as the TV's title page gives it ([episodePlayTitle], one helper for both):
     * "Backyard Science S02E05 The Volcano" when TMDB's name for it is known ([DriveFile.episode]), else its file's name.
     */
    fun episodeTitle(show: DriveFile, ep: DriveFile): String = episodePlayTitle(Library.info(show).title, ep)

    /**
     * "Play S1 E1" for [show]'s first episode [ep] ("Play First Episode" when nothing says its number): from its start,
     * unless another TV has started it meanwhile (a resume: the TV that plays it knows).
     */
    fun firstEpisodePlay(show: DriveFile, ep: DriveFile, recent: List<RecentItem>): JSONObject =
        play(driveKey(ep.id), episodeTitle(show, ep), 0, "Play " + (episodeCodeOf(ep.episode, ep.name) ?: "First Episode"), durationOf(recent, driveKey(ep.id)), resume = true)

    /**
     * "Play S3 E2" for [show]'s first new episode [e] (episodes spec §3.5; "Play New Episode" when its name doesn't say), from
     * its start unless another TV has started it meanwhile, as "Play S1 E1"; [meta] is TMDB's word on it, when known, for
     * the player's title.
     */
    fun newEpisodePlay(show: DriveFile, e: FreshEpisode, meta: EpisodeMeta?, recent: List<RecentItem>): JSONObject =
        play(e.key, episodeTitle(show, standIn(e.id, e.name, meta)), 0, "Play " + (e.code ?: "New Episode"), durationOf(recent, e.key), resume = true)

    /** "Play from the Start": [show]'s first episode [first] from 0, wherever anyone stopped, beside a button that goes on with the show. */
    fun restartPlay(show: DriveFile, first: DriveFile, recent: List<RecentItem>): JSONObject =
        play(driveKey(first.id), episodeTitle(show, first), 0, "Play from the Start", durationOf(recent, driveKey(first.id)))

    /**
     * A show's main play, decided as its TV title page and title menu decide it ([showMainPlay], episodes spec §3.5): the
     * episode to go on with; else its first new episode ([WatchFacts.news]), unless that's its first episode anyway; else
     * its first episode ([first], when it's known). Null when none is known.
     */
    fun showMain(show: DriveFile, facts: WatchFacts, listed: Set<String> = emptySet(), first: DriveFile? = null): MainPlay? =
        showMainPlay(
            showResumeItem(show, facts, listed)?.let { MainPlay.GoOn(it) },
            facts.news[show.id]?.first?.let { MainPlay.New(it) },
            first, emptyList(),
        )

    /**
     * [main] (a show's, see [showMain]) as the phone starts it, titled for the player as the TV's title page titles it;
     * [meta]: TMDB's word on its episode, when it's known (an episode played before keeps the title it had otherwise).
     */
    fun mainPlay(show: DriveFile, main: MainPlay, recent: List<RecentItem>, meta: EpisodeMeta? = null): JSONObject? = when (main) {
        is MainPlay.GoOn -> episodePlay(main.item, episodeTitle(show, standIn(main.item.key.removePrefix(DRIVE), main.item.title, meta)))
        is MainPlay.New -> newEpisodePlay(show, main.episode, meta, recent)
        is MainPlay.First -> firstEpisodePlay(show, if (meta != null) main.episode.copy(episode = meta) else main.episode, recent)
        is MainPlay.Film -> null
    }

    /** An episode known only by its id and name (and maybe what TMDB knows of it), for its title. */
    private fun standIn(id: String, name: String, meta: EpisodeMeta?) = DriveFile(id, name, "", 0, 0, null, episode = meta)

    /**
     * A show's first episode as its title page finds it, from the listings already known ([folder]: a folder's listing,
     * when it's known): the first video of its first season (Season 1, not Specials), or of its own folder when it keeps
     * its episodes there. Null when those aren't known.
     */
    fun firstEpisode(show: DriveFile, folder: (String) -> List<DriveFile>?): DriveFile? {
        val top = folder(show.id) ?: return null
        val seasons = top.filter { it.isFolder }
        if (seasons.isEmpty()) return top.firstOrNull { it.kind == FileKind.VIDEO }
        return folder(seasons[firstSeason(seasons.map { it.name })].id)?.firstOrNull { it.kind == FileKind.VIDEO }
    }

    /**
     * The TV shows with new episodes now, by show id, worked out as the TV's own screens work them out: of the videos
     * last found put in Drive lately under each show ([fresh], as saved by the TV's TV Shows listing), those of the
     * shows [shows] lists that no TV has watched ([watched]) or started ([recent]) ([NewEpisodes.shows]).
     */
    fun newShows(
        fresh: Map<String, List<FreshEpisode>>, shows: List<DriveFile>, now: Long, watched: (String) -> Boolean, recent: List<RecentItem>,
    ): Map<String, NewShow> {
        if (fresh.isEmpty() || shows.isEmpty()) return emptyMap()
        val progress = recent.associate { it.key to it.progress }
        return NewEpisodes.shows(fresh, shows.associate { it.id to it.added }, now, watched) { progress[it] }
    }

    /**
     * What [f]'s poster says about its new episodes: "New · S3 E2", "New season", "3 new episodes" ([NewShow.caption]),
     * only once the show itself is older than the new-episode window (a show added whole lately is simply new), as on
     * the TV's posters. Null for anything else.
     */
    fun caption(f: DriveFile, facts: WatchFacts): String? = facts.news[f.id]?.takeIf { it.announced }?.caption

    /**
     * "S2 E4 · The Volcano": the episode a show's main button plays, as the line under the TV title page's buttons names
     * it, only when TMDB knows its name ([meta]); null otherwise (the line then says only when it would end).
     */
    fun playName(meta: EpisodeMeta?): String? = meta?.name?.takeIf { it.isNotBlank() }?.let { "S${meta.season} E${meta.number} · $it" }

    /**
     * One of a season's episodes for the phone's list ([collection]: a film of a collection), as the TV's episode card
     * says it: TMDB's number, name, synopsis and length when [v] carries them ([DriveFile.episode]), else what its file's
     * name says; whether it's watched or how far it got, whether it's new (put in Drive lately, not watched or started on
     * any TV: the card's NEW badge), and what to start, titled for the player as the TV's title page titles it. [artFolder]:
     * the folder it's listed in, through which the TV fetches Drive's own frame of it.
     */
    fun episode(v: DriveFile, show: DriveFile, facts: WatchFacts, artFolder: String, collection: Boolean = false): JSONObject {
        val key = driveKey(v.id)
        val seen = facts.recent.firstOrNull { it.key == key }
        val watched = facts.watched(key)
        val meta = v.episode.takeIf { !collection }
        val file = if (collection || meta != null) null else fileEpisode(v.name)
        val number = meta?.number ?: file?.second
        val measured = seen?.durationMs?.takeIf { it >= 60_000 }?.let { ((it + 30_000) / 60_000).toInt() }
        return JSONObject()
            .put("key", key).put("playTitle", if (collection) v.name else episodeTitle(show, v))
            .put("code", (if (collection) null else episodeCodeOf(meta, v.name)) ?: JSONObject.NULL)
            .put("number", number ?: JSONObject.NULL)
            .put("special", (meta?.season ?: file?.first) == 0)
            .put("name", if (collection) Library.label(v) else episodeName(meta, v.name))
            .put("overview", meta?.overview ?: JSONObject.NULL)
            .put("still", v.posterUrl?.takeIf(::isTmdbUrl) ?: v.thumbnailLink?.takeIf { isGoogleUrl(it) }?.let { artRoute(v.id, "still", v.modified, artFolder) } ?: JSONObject.NULL)
            .put("runtime", meta?.minutes?.takeIf { it > 0 } ?: measured ?: JSONObject.NULL)
            .put("position", resumeAt(facts.recent, key).takeIf { seen?.upNext != true } ?: 0L)
            .put("duration", seen?.durationMs ?: 0L)
            .put("watched", watched)
            .put("upNext", seen?.upNext == true)
            .put("new", !collection && NewEpisodes.isNew(v.created, facts.now, watched, seen?.progress))
    }

    /** The art a tile shows: its poster (TMDB's, or the owner's own through the TV), else a video's own frame; null for the dark title card. */
    fun posterUrl(f: DriveFile): String? {
        val art = f.posterUrl ?: f.thumbnailLink?.takeIf { f.kind == FileKind.VIDEO }?.let(DriveApi::big) ?: return null
        return when {
            isTmdbUrl(art) -> art
            isGoogleUrl(art) -> artRoute(f.id, "poster", f.modified)
            else -> null
        }
    }

    /** A wide picture: TMDB's backdrop, else a loose video's own frame; null for the dark header with the poster. */
    fun backdropUrl(f: DriveFile): String? {
        Library.info(f).backdrop?.takeIf(::isTmdbUrl)?.let { return it }
        if (f.kind == FileKind.VIDEO && f.thumbnailLink?.let(::isGoogleUrl) == true) return artRoute(f.id, "backdrop", f.modified)
        return null
    }

    /** Drive-hosted art, fetched by the TV with its own token: the phone's `<img>` asks the TV (its cookie is the key). */
    fun artRoute(id: String, kind: String, version: Long, folder: String? = null): String =
        "/api/poster?id=${enc(id)}&kind=$kind" + (folder?.let { "&in=${enc(it)}" } ?: "") + "&v=$version"

    fun isTmdbUrl(url: String): Boolean = url.startsWith("https://image.tmdb.org/")

    /**
     * A title for the phone: [kind] "movie" or "show"; its ✓ (films only, as on the TV's posters: a
     * show is never "watched"); its new episodes' caption ([caption]); when it counts as added (a show by
     * its newest new episode, as in the TV's Recently added); [play] when it's cheap to know (a film; a
     * show's main play from what's known without listing anything, as the TV's title menu has it).
     */
    fun title(f: DriveFile, facts: WatchFacts, withPlay: Boolean = true): JSONObject {
        val i = Library.info(f)
        val show = facts.isShow(f)
        val film = if (show) null else filmOf(f, facts.folder(f.id))
        val play = when {
            !withPlay -> null
            show -> showMain(f, facts, first = firstEpisode(f, facts.folder))?.let { mainPlay(f, it, facts.recent) }
            film != null -> filmPlay(film, facts.recent)
            else -> null
        }
        return JSONObject()
            .put("id", f.id).put("kind", if (show) "show" else "movie").put("title", i.title)
            .put("year", i.year ?: JSONObject.NULL)
            .put("poster", posterUrl(f) ?: JSONObject.NULL).put("backdrop", backdropUrl(f) ?: JSONObject.NULL)
            .put("genres", JSONArray(i.genres)).put("rating", i.rating ?: JSONObject.NULL)
            .put("watched", film != null && facts.watched(driveKey(film.id)))
            .put("caption", caption(f, facts) ?: JSONObject.NULL)
            .put("added", NewEpisodes.addedAt(f, facts.news))
            .put("play", play ?: JSONObject.NULL)
    }

    /** The library entry [r] belongs to: by its own id, by the show it's known to sit under, else by its show's or film's name. */
    fun entryOf(r: RecentItem, facts: WatchFacts): DriveFile? {
        val own = if (r.key.startsWith(DRIVE)) facts.byId[r.key.removePrefix(DRIVE)] else null
        if (own != null) return own
        facts.showOf[r.key]?.let { facts.byId[it] }?.let { return it }
        val key = matchKeyOf(r.title).takeIf { it.isNotBlank() } ?: return null
        return (facts.shows + facts.movies).firstOrNull { it.isFolder && matchKeyOf(it.name) == key }
    }

    /**
     * A Continue watching tile: what to start ([key], [playTitle], the position, as the TV's own tile
     * starts it), the show's or film's name, the episode's code and name (as its title names it: the
     * real name once it was started by it, as the TV's title page starts it), and its poster.
     */
    fun continueItem(r: RecentItem, facts: WatchFacts, restart: JSONObject? = null): JSONObject {
        val entry = entryOf(r, facts)
        val code = episodeCode(r.title)
        val kind = when {
            entry != null -> if (facts.isShow(entry)) "show" else "movie"
            code != null -> "show"
            else -> "movie"
        }
        val name = entry?.let { Library.info(it).title } ?: if (code != null) showName(r.title) else Library.parsed(r.title, folder = false).title
        return JSONObject()
            .put("key", r.key).put("playTitle", r.title).put("titleId", entry?.id ?: JSONObject.NULL).put("kind", kind)
            .put("name", name).put("code", code ?: JSONObject.NULL).put("episode", code?.let { episodeName(null, r.title) } ?: JSONObject.NULL)
            .put("position", r.positionMs).put("duration", r.durationMs).put("upNext", r.upNext)
            .put("poster", entry?.let(::posterUrl) ?: JSONObject.NULL)
            .put("drive", r.key.startsWith(DRIVE))
            .put("play", if (kind == "show") episodePlay(r) else play(r.key, r.title, resumeAt(listOf(r), r.key), if (r.positionMs > RESUME_FROM_MS) resumeLabel(r.positionMs) else "Play", r.durationMs, resume = true))
            .put("restart", restart ?: if (kind == "movie" && r.positionMs > RESUME_FROM_MS) play(r.key, r.title, 0, "Start Over", r.durationMs) else JSONObject.NULL)
            .put("where", JSONObject.NULL)
    }

    /** The show an episode's file names ("Backyard Science S02E04 The Volcano.mkv" → "Backyard Science"); the file's title when it names none. */
    fun showName(title: String): String {
        val t = Format.title(title)
        val at = EPISODE_CODE.find(t)?.range?.first ?: return t
        return t.substring(0, at).replace(TIDY, " ").trim(' ', '-', '·', ':').replace(SPACES, " ").ifBlank { t }
    }

    /**
     * The season folder [id] names, if it's one of a show's own: a folder in the show folder's listing ([top]).
     * Null for anything else (another show's season, any other folder of the Drive, a file), which the phone's
     * episode list never lists.
     */
    fun seasonOf(top: List<DriveFile>, id: String): DriveFile? = top.firstOrNull { it.isFolder && it.id == id }

    /** The first numbered season (Specials and Extras come first in a listing, but a show starts at Season 1). */
    fun firstSeason(names: List<String>): Int =
        names.withIndex().filter { (_, n) -> (Tmdb.seasonNumber(n) ?: 1) >= 1 }.minByOrNull { (_, n) -> Tmdb.seasonNumber(n) ?: Int.MAX_VALUE }?.index ?: 0

    /** The A–Z first page of a library for Watch's rows. */
    fun firstOf(files: List<DriveFile>, n: Int = 20): List<DriveFile> = Library.apply(files, LibraryFilter(sort = LibrarySort.TITLE)).take(n)

    // ---- Search: the TV's one ranked list, in the phone's two sections ----------------------------------

    /**
     * A search's titles as the phone shows them: Movies and TV Shows, each in the order the TV's Search ranks
     * them; [showsFirst]: TV Shows holds the best match, so its section comes first.
     */
    class Sections(val movies: List<DriveFile>, val shows: List<DriveFile>, val showsFirst: Boolean)

    /**
     * [found] (the TV's Search over both libraries, [TitleSearch.find]: the same letters find the same titles in
     * the same order on both) grouped as the phone shows it (search spec §3.3).
     */
    fun sections(found: TitleSearch.Found): Sections = Sections(
        found.hits.filter { it.place == DrivePlace.Movies }.map { it.file },
        found.hits.filter { it.place == DrivePlace.TvShows }.map { it.file },
        found.hits.firstOrNull()?.place == DrivePlace.TvShows,
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val EPISODE_CODE = Regex("""(?i)(?<![a-z0-9])(s\d{1,2}\s*[ex]\s*\d{1,3}|\d{1,2}x\d{2})(?![0-9])""")
    private val TIDY = Regex("""[._]+""")
    private val SPACES = Regex("""\s+""")
}
