package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.tmdb.Tmdb
import java.text.Normalizer

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
) {
    val narrowed: Boolean get() = genre != null || decade != null || query.isNotBlank()
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

    /** "The Matrix (1999)"; just the title when the year is unknown. */
    fun label(f: DriveFile): String = info(f).let { i -> if (i.year != null) "${i.title} (${i.year})" else i.title }

    /** Genres in this list, most common first. */
    fun genres(files: List<DriveFile>): List<String> =
        files.flatMap { info(it).genres }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }

    /** Decades in this list, newest first. */
    fun decades(files: List<DriveFile>): List<Int> =
        files.mapNotNull { info(it).year?.let { y -> y / 10 * 10 } }.distinct().sortedDescending()

    fun apply(files: List<DriveFile>, filter: LibraryFilter): List<DriveFile> {
        // Each file paired with its info once: comparisons then read the pair, rather than looking
        // the file up in a map (which hashes the whole DriveFile on every comparison).
        val words = normalize(filter.query).split(' ').filter { it.isNotEmpty() }
        val kept = files.map { it to info(it) }.filter { (_, i) ->
            (filter.genre == null || filter.genre in i.genres) &&
                (filter.decade == null || (i.year != null && i.year / 10 * 10 == filter.decade)) &&
                (words.isEmpty() || words.all { it in i.searchText })
        }
        val byTitle = compareBy<Pair<DriveFile, MediaInfo>> { it.second.sortKey }
        return when (filter.sort) {
            LibrarySort.TITLE -> kept.sortedWith(byTitle)
            LibrarySort.RECENT -> kept.sortedWith(compareByDescending<Pair<DriveFile, MediaInfo>> { it.first.modified }.then(byTitle))
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

    // Compiled once: these run for every title on every sort, filter and search keystroke.
    private val ARTICLE = Regex("^(the|a|an) ")
    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}
