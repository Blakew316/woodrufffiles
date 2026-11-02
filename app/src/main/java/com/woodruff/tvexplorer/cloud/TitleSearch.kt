package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.data.FileKind

/**
 * Finding a film or a show by name across Movies and TV Shows: the one definition the TV's Search
 * page and the phone's search share (search spec §3.3), so the same letters find the same titles in
 * the same order on both. Pure, so it runs on a background thread (cancelled by the next letter) and
 * is unit tested.
 *
 * Matching is [Library.matches]: every word in the title, accents and case ignored, or a four-digit
 * word as the release year. Order: titles that start with what's typed (a leading "the", "a" or "an"
 * ignored), then titles with a word that starts with it, then the rest; ties go to the library the
 * search was opened from, then the better rated, then A–Z.
 */
object TitleSearch {
    /** Titles a search shows at most; past that it says to keep typing. */
    const val LIMIT = 100

    /** The longest search kept: 60 characters. */
    const val QUERY_MAX = 60

    /** A title found: [file] (a library's film or show) in [place] (Movies or TV Shows). */
    data class Hit(val place: DrivePlace, val file: DriveFile)

    /**
     * What a search found: the first [LIMIT] matches, best first ([hits]), and how many matched in all
     * ([total]). [capped]: there were more than it shows.
     */
    data class Found(val hits: List<Hit>, val total: Int) {
        val capped: Boolean get() = total > hits.size

        companion object {
            val NONE = Found(emptyList(), 0)
        }
    }

    /**
     * The titles of [libraries] (Movies and TV Shows, each a library's top level as listed) matching
     * [query], best first (see [TitleSearch]), at most [limit]. [prefer]: the library the search was
     * opened from, whose titles come first among equals. Only films and shows count (a library's
     * folders and videos): never a poster, a subtitle file or anything else lying beside them. An empty
     * query finds nothing.
     */
    fun find(query: String, libraries: Map<out DrivePlace, List<DriveFile>>, prefer: DrivePlace? = null, limit: Int = LIMIT): Found {
        val words = Library.queryWords(query)
        if (words.isEmpty()) return Found.NONE
        val seen = HashSet<String>()
        val matched = ArrayList<Scored>()
        for ((place, files) in libraries) {
            for (f in files) {
                if (!f.isFolder && f.kind != FileKind.VIDEO) continue
                val info = Library.info(f)
                if (!Library.matches(words, info) || !seen.add(f.id)) continue
                matched += Scored(Hit(place, f), rank(words, info), if (place == prefer) 0 else 1, info.rating ?: -1.0, info.sortKey)
            }
        }
        matched.sortWith(BEST_FIRST)
        return Found(matched.asSequence().take(limit).map { it.hit }.toList(), matched.size)
    }

    /**
     * How well a matching title ([info]) answers [words]: 1, its title starts with them (a leading
     * article ignored on both sides: "last li" and "the last li" both start "The Last Light"); 2, a word
     * of its title starts with them ("harb" in "Midnight Harbor"); 3, they're only somewhere in it. Words
     * that matched only as the year ("2024") don't count here: "harbor 2024" ranks as "harbor" does, and
     * a year alone ranks every title alike.
     */
    internal fun rank(words: List<String>, info: MediaInfo): Int {
        val title = info.searchText
        val typed = words.filter { it in title }.joinToString(" ")
        if (typed.isEmpty()) return 3
        return when {
            title.startsWith(typed) || info.sortKey.startsWith(Library.sortKey(typed)) -> 1
            " $title".contains(" $typed") -> 2
            else -> 3
        }
    }

    /** A match with what orders it. */
    private class Scored(val hit: Hit, val rank: Int, val library: Int, val rating: Double, val sortKey: String)

    private val BEST_FIRST: Comparator<Scored> = compareBy<Scored> { it.rank }
        .thenBy { it.library }
        .thenByDescending { it.rating }
        .thenBy { it.sortKey }

    /** "1 result", "2 results", "100+ results": the count at the search field's end; null when nothing's found. */
    fun countWords(found: Found): String? = when {
        found.total == 0 -> null
        found.capped -> "${found.hits.size}+ results"
        found.total == 1 -> "1 result"
        else -> "${found.total} results"
    }
}
