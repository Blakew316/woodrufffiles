package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.tmdb.Tmdb
import java.text.Normalizer
import java.util.Calendar

/**
 * What's known about a movie or show: the title and year parsed from its name, plus genres and
 * rating from TMDB when a key is set. The year in the file name wins over TMDB's, since a TMDB
 * match can be the wrong film.
 */
data class MediaInfo(
    val title: String, val year: Int?, val genres: List<String> = emptyList(), val rating: Double? = null,
    /** From TMDB, for the detail page: the synopsis and a wide background image URL. */
    val overview: String? = null, val backdrop: String? = null,
    val tmdbId: Int? = null, val tv: Boolean = false,
) {
    // Worked out on first use, then kept with the listing: filter changes don't redo them.
    internal val sortKey: String by lazy { Library.sortKey(title) }
    internal val searchText: String by lazy { Library.normalize(title) }
}

enum class LibrarySort(val label: String) { TITLE("A–Z"), RECENT("Recently added"), YEAR("Newest"), RATING("Top rated") }

data class LibraryFilter(
    val sort: LibrarySort = LibrarySort.TITLE,
    val genre: String? = null,
    /** 1990 for the 1990s. */
    val decade: Int? = null,
    val query: String = "",
    /** The Overview tab: a featured title and rows (Continue watching, genres) instead of the A–Z grid. */
    val overview: Boolean = false,
    /** Only what hasn't been watched: films without a ✓, shows not started. */
    val unwatched: Boolean = false,
) {
    val narrowed: Boolean get() = genre != null || decade != null || query.isNotBlank() || unwatched
}

/** Sorting and filtering for the Movies / TV Shows grids. Pure functions, so they're unit tested. */
object Library {
    /** TMDB info when it was looked up, else what the name alone tells us. */
    fun info(f: DriveFile): MediaInfo = f.info ?: parsed(f.name, f.isFolder)

    fun parsed(name: String, folder: Boolean): MediaInfo {
        val (title, year) = Tmdb.parse(name, stripExtension = !folder)
        return MediaInfo(title.ifBlank { if (folder) name else name.substringBeforeLast('.') }, year?.toIntOrNull())
    }

    /**
     * Parses every name once (off the main thread, when a listing loads), so sorting, filtering
     * and the filter chips never re-run the name regexes.
     */
    fun withInfo(files: List<DriveFile>): List<DriveFile> = files.map { if (it.info == null) it.copy(info = parsed(it.name, it.isFolder)) else it }

    /**
     * The show a folder or an episode's file name belongs to, for matching the two: "Breaking Bad
     * (2008)", "Breaking.Bad.2008.S01E01.mkv" and "Breaking Bad (2008) - S01E01 - Pilot.mkv" all
     * give "breaking bad". (The player's per-show key keeps the year, so it can't be compared with
     * a folder's title.)
     */
    fun showMatchKey(name: String): String =
        normalize(Tmdb.parse(com.woodruff.tvexplorer.player.ShowPrefs.showKey(name), stripExtension = false).first)

    /**
     * Works out each title's sort and search keys now, on the calling (background) thread, so
     * sorting and filtering on screen only compare ready-made strings. Left to first use, they were
     * worked out for the whole library in the middle of drawing a frame — a stutter on every open.
     */
    fun prepare(files: List<DriveFile>): List<DriveFile> = files.onEach { f -> f.info?.let { i -> i.sortKey; i.searchText } }

    /** "The Matrix (1999)"; just the title when the year is unknown. */
    fun label(f: DriveFile): String = info(f).let { i -> if (i.year != null) "${i.title} (${i.year})" else i.title }

    /** Genres in this list, most common first. */
    fun genres(files: List<DriveFile>): List<String> =
        files.flatMap { info(it).genres }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }

    /**
     * The Overview's featured titles: five of the library's well-rated titles with wide art, picked
     * at random — a new five each [day] ([today]), the same five all day, on every TV.
     */
    fun featured(files: List<DriveFile>, day: Long): List<DriveFile> {
        val pool = files.filter { info(it).backdrop != null }
        val good = pool.filter { (info(it).rating ?: 0.0) >= 6.5 }.let { if (it.size >= 5) it else pool }
        return good.shuffled(java.util.Random(day)).take(5)
    }

    /** Today, for [featured]: a number that changes at midnight. */
    fun today(): Long = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000L + it.get(Calendar.DAY_OF_YEAR) }

    /**
     * What each of [recent] (newest first) is in this library, for the Overview's Continue watching
     * row: a film by its own key; an episode, or a film in its own folder, by the entry it's known
     * to sit under ([shows]: media key → entry id), else a show by its name. Each entry once, with
     * its newest item. [matchKey] is [showMatchKey] (or a remembered copy of it).
     */
    fun continuing(
        files: List<DriveFile>, recent: List<RecentItem>, shows: Map<String, String>,
        matchKey: (String) -> String = { showMatchKey(it) },
    ): List<Pair<DriveFile, RecentItem>> {
        val byId = files.associateBy { it.id }
        // The folders' names are only worked out if something isn't found by its key.
        val byName by lazy { files.filter { it.isFolder }.associateBy { matchKey(it.name) } }
        return recent.mapNotNull { r ->
            val own = if (r.key.startsWith(DRIVE)) byId[r.key.removePrefix(DRIVE)] else null
            val entry = own ?: shows[r.key]?.let { byId[it] } ?: byName[matchKey(r.title)]
            entry?.let { it to r }
        }.distinctBy { it.first.id }
    }

    /** The Overview's rows: Continue watching ([watching]) when something's in progress, then [overviewRows]. */
    fun overview(files: List<DriveFile>, watching: List<DriveFile>): List<Pair<String, List<DriveFile>>> = buildList {
        if (watching.isNotEmpty()) add("Continue watching" to watching)
        addAll(overviewRows(files, watching.mapTo(HashSet()) { it.id }))
    }

    /**
     * What the Overview shows first, to load ahead: the first featured title's wide art (on [day]),
     * and the posters of the first few titles in its top two rows ([watching]: Continue watching).
     */
    fun overviewArt(files: List<DriveFile>, watching: List<DriveFile>, day: Long): Pair<String?, List<String>> {
        val backdrop = featured(files, day).firstOrNull()?.let { info(it).backdrop }
        val posters = overview(files, watching).take(2).flatMap { (_, row) -> row.take(6) }.mapNotNull { it.posterUrl }
        return backdrop to posters
    }

    /**
     * The TV Shows entries already started, for the Unwatched filter: an episode known to sit under
     * one ([shows]: media key → entry id) has a ✓ ([watched]), or something of it is in [recent]
     * (found as [continuing] finds it). A loose video counts by its own ✓.
     */
    fun startedShows(
        files: List<DriveFile>, recent: List<RecentItem>, shows: Map<String, String>, watched: (String) -> Boolean,
        matchKey: (String) -> String = { showMatchKey(it) },
    ): Set<String> {
        val started = HashSet<String>()
        shows.forEach { (key, entry) -> if (watched(key)) started += entry }
        files.forEach { if (!it.isFolder && watched(DRIVE + it.id)) started += it.id }
        continuing(files, recent, shows, matchKey).forEach { started += it.first.id }
        return started
    }

    /**
     * The Overview's rows under Continue watching ([shown]: the ids already in it): Recently added,
     * Top rated, then the biggest genres — each title in one row only, so the rows don't repeat the
     * same few films (a film tagged Action, Adventure and Sci-Fi used to fill all three, and Top
     * rated too). The rows above claim a title first; among the genres, it goes to its own (the
     * first TMDB lists) unless that row is full. A row too short to be worth one is left out, its
     * titles going back to the others. Rows are shorter in a small library, so there's some left
     * for the genres.
     */
    fun overviewRows(files: List<DriveFile>, shown: Set<String>): List<Pair<String, List<DriveFile>>> {
        val size = (files.size / 6).coerceIn(MIN_ROW + 2, 20)
        val used = shown.toHashSet()
        fun claim(candidates: List<DriveFile>): List<DriveFile> {
            val out = ArrayList<DriveFile>(size)
            for (f in candidates) {
                if (out.size == size) break
                if (used.add(f.id)) out += f
            }
            return out
        }
        val recent = claim(files.sortedByDescending { it.added })
        // Top rated, when enough are rated to make a row; otherwise those go back to the genres.
        val top = claim(files.filter { info(it).rating != null }.sortedByDescending { info(it).rating })
        if (top.size < MIN_ROW) used.removeAll(top.map { it.id }.toSet())
        return buildList {
            if (recent.isNotEmpty()) add("Recently added" to recent)
            if (top.size >= MIN_ROW) add("Top rated" to top)
            addAll(genreRows(files.filter { it.id !in used }, size))
        }
    }

    /** Up to [GENRE_ROWS] genre rows of [size] from [files], none sharing a title, none under [MIN_ROW]. */
    private fun genreRows(files: List<DriveFile>, size: Int): List<Pair<String, List<DriveFile>>> {
        var genres = genres(files).take(GENRE_ROWS)
        while (genres.isNotEmpty()) {
            val rows = draft(files, genres, size)
            val short = rows.filter { it.second.size < MIN_ROW }.map { it.first }.toSet()
            if (short.isEmpty()) return rows
            genres = genres.filter { it !in short } // their titles go back to the rest
        }
        return emptyList()
    }

    /**
     * The genres take turns picking their next title not yet picked, so each gets a fair share: its
     * own titles first (its genre listed first), then the rest, best rated first. Each row is then
     * shown best first.
     */
    private fun draft(files: List<DriveFile>, genres: List<String>, size: Int): List<Pair<String, List<DriveFile>>> {
        val pools = genres.map { g ->
            files.filter { g in info(it).genres }.sortedWith(
                compareBy<DriveFile> { if (info(it).genres.firstOrNull() == g) 0 else 1 }.thenByDescending { info(it).rating ?: 0.0 },
            )
        }
        val rows = genres.map { ArrayList<DriveFile>(size) }
        val next = IntArray(genres.size)
        val taken = HashSet<String>()
        var picked = true
        while (picked) {
            picked = false
            for (r in genres.indices) {
                if (rows[r].size >= size) continue
                val pool = pools[r]
                while (next[r] < pool.size && pool[next[r]].id in taken) next[r]++
                if (next[r] < pool.size) {
                    val f = pool[next[r]++]
                    rows[r] += f
                    taken += f.id
                    picked = true
                }
            }
        }
        return genres.mapIndexed { r, g -> g to rows[r].sortedByDescending { info(it).rating ?: 0.0 } }
    }

    /** Fewer titles than this isn't worth a row of its own. */
    private const val MIN_ROW = 6
    private const val GENRE_ROWS = 8

    /** Decades in this list, newest first. */
    fun decades(files: List<DriveFile>): List<Int> =
        files.mapNotNull { info(it).year?.let { y -> y / 10 * 10 } }.distinct().sortedDescending()

    /**
     * [files] narrowed and sorted as [filter] says. [watched] is what the Unwatched filter leaves
     * out (a film's ✓, a show started): the screen knows that, so this stays pure.
     */
    fun apply(files: List<DriveFile>, filter: LibraryFilter, watched: (DriveFile) -> Boolean = { false }): List<DriveFile> {
        // Each file paired with its info once: comparisons then read the pair, rather than looking
        // the file up in a map (which hashes the whole DriveFile on every comparison).
        val words = normalize(filter.query).split(' ').filter { it.isNotEmpty() }
        val kept = files.map { it to info(it) }.filter { (f, i) ->
            (filter.genre == null || filter.genre in i.genres) &&
                (filter.decade == null || (i.year != null && i.year / 10 * 10 == filter.decade)) &&
                (words.isEmpty() || words.all { it in i.searchText }) &&
                (!filter.unwatched || !watched(f))
        }
        val byTitle = compareBy<Pair<DriveFile, MediaInfo>> { it.second.sortKey }
        return when (filter.sort) {
            LibrarySort.TITLE -> kept.sortedWith(byTitle)
            LibrarySort.RECENT -> kept.sortedWith(compareByDescending<Pair<DriveFile, MediaInfo>> { it.first.added }.then(byTitle))
            // Unknown years / ratings go last, not first.
            LibrarySort.YEAR -> kept.sortedWith(compareByDescending<Pair<DriveFile, MediaInfo>> { it.second.year ?: Int.MIN_VALUE }.then(byTitle))
            LibrarySort.RATING -> kept.sortedWith(compareByDescending<Pair<DriveFile, MediaInfo>> { it.second.rating ?: -1.0 }.then(byTitle))
        }.map { it.first }
    }

    /** Sorts "The Matrix" under M, ignores accents and case. */
    fun sortKey(title: String): String = normalize(title).replace(ARTICLE, "")

    /** Lowercase, accents removed, punctuation as spaces: "Amélie!" → "amelie". */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
            .replace(NON_WORD, " ").trim()

    /** How a Drive file's media key starts ("drive:<id>", as MediaRef.Drive writes it). */
    private const val DRIVE = "drive:"

    // Compiled once: these run for every title on every sort, filter and search keystroke.
    private val ARTICLE = Regex("^(the|a|an) ")
    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}
