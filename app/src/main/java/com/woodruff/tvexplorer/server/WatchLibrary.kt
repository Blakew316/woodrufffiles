package com.woodruff.tvexplorer.server

import android.content.Context
import com.woodruff.tvexplorer.cloud.DriveApi
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DriveHttpException
import com.woodruff.tvexplorer.cloud.DriveListing
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.cloud.LibrarySort
import com.woodruff.tvexplorer.cloud.ListingCache
import com.woodruff.tvexplorer.cloud.isGoogleUrl
import com.woodruff.tvexplorer.cloud.matchKeyOf
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.tmdb.Tmdb
import com.woodruff.tvexplorer.ui.screens.episodeCode
import com.woodruff.tvexplorer.ui.screens.folderFilm
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
     * its newest episode then. Neither: from [position] (Start Over).
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

    /** "Resume S2 E4" (in progress) / "Play S2 E5" (up next), the TV's words; without a code "Resume" / "Play Next Episode". */
    fun episodePlay(r: RecentItem): JSONObject {
        val code = episodeCode(r.title)
        val label = if (r.upNext) code?.let { "Play $it" } ?: "Play Next Episode" else code?.let { "Resume $it" } ?: "Resume"
        return play(r.key, r.title, if (r.upNext) 0 else r.positionMs, label, r.durationMs, resume = true, latest = true)
    }

    /** "Play S1 E1" for a show's first episode ("Play First Episode" when its name has no code). */
    fun firstEpisodePlay(ep: DriveFile, recent: List<RecentItem>): JSONObject =
        play(driveKey(ep.id), ep.name, 0, "Play " + (episodeCode(ep.name) ?: "First Episode"), durationOf(recent, driveKey(ep.id)), resume = true)

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
     * show is never "watched"); [play] when it's cheap to know (a film; a show with an episode to go on with).
     */
    fun title(f: DriveFile, facts: WatchFacts, withPlay: Boolean = true): JSONObject {
        val i = Library.info(f)
        val show = facts.isShow(f)
        val film = if (show) null else filmOf(f, facts.folder(f.id))
        val play = when {
            !withPlay -> null
            show -> showResumeItem(f, facts)?.let(::episodePlay)
            film != null -> filmPlay(film, facts.recent)
            else -> null
        }
        return JSONObject()
            .put("id", f.id).put("kind", if (show) "show" else "movie").put("title", i.title)
            .put("year", i.year ?: JSONObject.NULL)
            .put("poster", posterUrl(f) ?: JSONObject.NULL).put("backdrop", backdropUrl(f) ?: JSONObject.NULL)
            .put("genres", JSONArray(i.genres)).put("rating", i.rating ?: JSONObject.NULL)
            .put("watched", film != null && facts.watched(driveKey(film.id)))
            .put("caption", JSONObject.NULL) // "New · S3 E2", once this TV tracks new episodes
            .put("added", f.added)
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
     * A Continue watching tile: what to start ([key], [playTitle], the position), the show's or film's
     * name, the episode's code and name, and its poster. Episode names come from the file until this
     * TV keeps TMDB's.
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
            .put("name", name).put("code", code ?: JSONObject.NULL).put("episode", code?.let { episodeName(r.title, null) } ?: JSONObject.NULL)
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
     * An episode's name as its card shows it without TMDB's: the file's words after its code
     * ("Show.S01E03.The.Title.1080p.mkv" → "The Title"); "Episode 4" when nothing follows the code;
     * the file's name when there's no number either.
     */
    fun episodeName(fileName: String, number: Int?): String {
        nameAfterCode(fileName)?.let { return it }
        val n = number ?: Tmdb.episodeNumber(fileName, null)?.second
        return if (n != null) "Episode $n" else Format.title(fileName)
    }

    /** "S2 E4 · The Volcano" (or "S2 E4") for the episode in [fileName]; null when its name has no code. */
    fun episodeLabel(fileName: String): String? {
        val code = episodeCode(fileName) ?: return null
        return nameAfterCode(fileName)?.let { "$code · $it" } ?: code
    }

    /** The words after an episode's code in its file name, without release tags; null when there are none (or no code). */
    private fun nameAfterCode(fileName: String): String? {
        val t = Format.title(fileName)
        val m = EPISODE_CODE.find(t) ?: return null
        return t.substring(m.range.last + 1).replace(TIDY, " ").replace(RELEASE_TAGS, "")
            .replace(SPACES, " ").trim(' ', '-', '·', ':').ifBlank { null }
    }

    /** The first numbered season (Specials and Extras come first in a listing, but a show starts at Season 1). */
    fun firstSeason(names: List<String>): Int =
        names.withIndex().filter { (_, n) -> (Tmdb.seasonNumber(n) ?: 1) >= 1 }.minByOrNull { (_, n) -> Tmdb.seasonNumber(n) ?: Int.MAX_VALUE }?.index ?: 0

    /** The A–Z first page of a library for Watch's rows. */
    fun firstOf(files: List<DriveFile>, n: Int = 20): List<DriveFile> = Library.apply(files, LibraryFilter(sort = LibrarySort.TITLE)).take(n)

    // ---- Search: the TV's matching and ranking ------------------------------------------------------

    /**
     * Whether [f] matches [words] (normalized): every word in its title, or a four-digit word as its
     * release year. The one rule the TV's Search, Streaming's grid and the phone share.
     */
    fun matches(f: DriveFile, words: List<String>): Boolean {
        val i = Library.info(f)
        val text = Library.normalize(i.title)
        return words.all { w -> w in text || (w.length == 4 && w.all(Char::isDigit) && i.year?.toString() == w) }
    }

    /** The query's words as matched: accents and case ignored, punctuation as spaces. */
    fun words(query: String): List<String> = Library.normalize(query).split(' ').filter { it.isNotEmpty() }

    /**
     * How well [f] answers [query]: 0 when its title starts with it (a leading "the", "a", "an" left
     * out on both sides), 1 when a word in its title does, 2 when the words are only somewhere in it.
     */
    fun rank(f: DriveFile, query: String): Int {
        val q = Library.sortKey(query)
        val title = Library.normalize(Library.info(f).title)
        return when {
            q.isEmpty() -> 2
            Library.sortKey(title).startsWith(q) -> 0
            " $title".contains(" $q") -> 1
            else -> 2
        }
    }

    /** [files] matching [query], best first: by [rank], then the higher TMDB rating, then A–Z. */
    fun search(files: List<DriveFile>, query: String): List<Pair<DriveFile, Int>> {
        val w = words(query)
        if (w.isEmpty()) return emptyList()
        return files.asSequence().filter { matches(it, w) }.map { it to rank(it, query) }
            .sortedWith(compareBy<Pair<DriveFile, Int>> { it.second }.thenByDescending { Library.info(it.first).rating ?: -1.0 }.thenBy { Library.sortKey(Library.info(it.first).title) })
            .toList()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val EPISODE_CODE = Regex("""(?i)(?<![a-z0-9])(s\d{1,2}\s*[ex]\s*\d{1,3}|\d{1,2}x\d{2})(?![0-9])""")
    private val TIDY = Regex("""[._]+""")
    private val RELEASE_TAGS = Regex("""(?i)\b(\d{3,4}p|x26[45]|h\.? ?26[45]|hevc|web[- ]?dl|webrip|bluray|hdtv|aac|ddp?5[ .]1)\b.*""")
    private val SPACES = Regex("""\s+""")
}
