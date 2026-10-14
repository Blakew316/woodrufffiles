package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.tmdb.Tmdb
import java.text.Normalizer

/**
 * What's known about a movie or show: the title and year parsed from its name, plus genres and
 * rating from TMDB when a key is set. The year in the file name wins over TMDB's, since a TMDB
 * match can be the wrong film.
 */
data class MediaInfo(val title: String, val year: Int?, val genres: List<String> = emptyList(), val rating: Double? = null)

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
        // Parse each name once, not on every comparison.
        val infos = files.associateWith { info(it) }
        val keys = infos.mapValues { sortKey(it.value.title) }
        val words = normalize(filter.query).split(' ').filter { it.isNotEmpty() }
        val kept = files.filter { f ->
            val i = infos.getValue(f)
            (filter.genre == null || filter.genre in i.genres) &&
                (filter.decade == null || (i.year != null && i.year / 10 * 10 == filter.decade)) &&
                (words.isEmpty() || keys.getValue(f).let { t -> words.all { it in t } } || normalize(i.title).let { t -> words.all { it in t } })
        }
        val byTitle = compareBy<DriveFile> { keys.getValue(it) }
        return when (filter.sort) {
            LibrarySort.TITLE -> kept.sortedWith(byTitle)
            LibrarySort.RECENT -> kept.sortedWith(compareByDescending<DriveFile> { it.modified }.then(byTitle))
            // Unknown years / ratings go last, not first.
            LibrarySort.YEAR -> kept.sortedWith(compareByDescending<DriveFile> { infos.getValue(it).year ?: Int.MIN_VALUE }.then(byTitle))
            LibrarySort.RATING -> kept.sortedWith(compareByDescending<DriveFile> { infos.getValue(it).rating ?: -1.0 }.then(byTitle))
        }
    }

    /** Sorts "The Matrix" under M, ignores accents and case. */
    fun sortKey(title: String): String = normalize(title).replace(Regex("^(the|a|an) "), "")

    /** Lowercase, accents removed, punctuation as spaces: "Amélie!" → "amelie". */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
