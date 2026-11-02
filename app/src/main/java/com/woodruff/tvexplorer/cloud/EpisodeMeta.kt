package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.tmdb.Tmdb

/**
 * What's known about one episode file beyond its name: its season and number, and from TMDB its own
 * name, a few lines about it and its length. Carried on the episode's [DriveFile.episode], apart from
 * [DriveFile.info], so search and labels for episode files keep meaning the file. Whatever TMDB doesn't
 * know is null; season 0 is the show's specials.
 */
data class EpisodeMeta(
    val season: Int,
    val number: Int,
    val name: String? = null,
    /** A few lines about it, kept to [OVERVIEW_MAX] characters (TMDB's cache is one file, rewritten whole). */
    val overview: String? = null,
    /** Its length in minutes, as TMDB lists it. */
    val minutes: Int? = null,
) {
    companion object {
        /** A synopsis is kept to this many characters; a card shows three lines of it at most. */
        const val OVERVIEW_MAX = 300
    }
}

/**
 * An episode card's small line over its name: "EPISODE 3 · 42 MIN", "SPECIAL · 24 MIN" for season 0.
 * The number is TMDB's, else the file's own code ("S01E03", "1x03", "Episode 3"); the length is TMDB's,
 * else [measuredMs] (how long the player found it, once started), else left off. Neither known: " ",
 * so the line keeps its height and the names in a row stay level.
 */
fun episodeEyebrow(meta: EpisodeMeta?, fileName: String, measuredMs: Long?): String {
    val file = if (meta == null) fileEpisode(fileName) else null
    val season = meta?.season ?: file?.first
    val number = meta?.number ?: file?.second
    val minutes = meta?.minutes?.takeIf { it > 0 } ?: measuredMs?.takeIf { it >= 60_000 }?.let { ((it + 30_000) / 60_000).toInt() }
    val which = when {
        season == 0 -> "SPECIAL"
        number != null -> "EPISODE $number"
        else -> null
    }
    return listOfNotNull(which, minutes?.let { "$it MIN" }).joinToString(" · ").ifEmpty { " " }
}

/**
 * An episode's name as its card shows it: TMDB's, else what its file is called after its code
 * ("Show.S01E03.The.Title.1080p.mkv" → "The Title"), else "Episode 3", else the file's name without its
 * extension. Never a raw file name with dots and release tags when there's anything better.
 */
fun episodeName(meta: EpisodeMeta?, fileName: String): String {
    meta?.name?.takeIf { it.isNotBlank() }?.let { return it }
    fileEpisodeName(fileName)?.let { return it }
    val number = meta?.number ?: fileEpisode(fileName)?.second
    if (number != null) return "Episode $number"
    return fileName.substringBeforeLast('.').ifBlank { fileName }
}

/**
 * An episode's menu title: "S2 E5 · The Volcano"; its code alone when it has no name of its own
 * ("S2 E5"); its name when there's no code.
 */
fun episodeMenuTitle(meta: EpisodeMeta?, fileName: String): String {
    val code = episodeCodeOf(meta, fileName)
    val own = meta?.name?.takeIf { it.isNotBlank() } ?: fileEpisodeName(fileName)
    return when {
        code != null && own != null -> "$code · $own"
        code != null -> code
        else -> episodeName(meta, fileName)
    }
}

/** "S2 E5": an episode's code, from TMDB's numbers or else its file's name; null when neither says. */
fun episodeCodeOf(meta: EpisodeMeta?, fileName: String): String? {
    if (meta != null) return "S${meta.season} E${meta.number}"
    val (season, number) = fileEpisode(fileName) ?: return null
    return season?.let { "S$it E$number" }
}

/**
 * What the player is given as an episode's title when the title page starts it: "Backyard Science
 * S02E05 The Volcano" when TMDB knows its name, which the player splits into "Backyard Science · S2, E5"
 * over "The Volcano" (and Up next and Continue watching read the same); else its file's name, as
 * before. [show] is the show's title ("Backyard Science", without its year). One helper for every
 * way an episode is started, on the TV and from the phone.
 */
fun episodePlayTitle(show: String, ep: DriveFile): String {
    val meta = ep.episode
    val name = meta?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return ep.name
    if (show.isBlank()) return ep.name
    return String.format(java.util.Locale.US, "%s S%02dE%02d %s", show.trim(), meta.season, meta.number, name)
}

/**
 * What the player is given as a film's title: the title the library shows ("Midnight Harbor"), as the
 * Apple TV app names a film, never its Drive file's name ("Midnight.Harbor.2024.2160p.WEB-DL.mkv"). The
 * player's controls, Continue watching and the phone's now playing all read it. A film kept in its own
 * [folder] (Plex style) goes by the folder's title, as its poster and page do. The file's name only when
 * there's no title at all.
 */
fun filmPlayTitle(film: DriveFile, folder: DriveFile? = null): String =
    Library.info(folder ?: film).title.trim().ifEmpty { film.name }

/**
 * [text] as it's kept: at most [max] characters, cut at a word with "…" when it's longer. Whitespace
 * runs (TMDB's synopses have line breaks) become single spaces.
 */
fun trimSynopsis(text: String, max: Int = EpisodeMeta.OVERVIEW_MAX): String {
    val t = text.replace(SPACE_RUN, " ").trim()
    if (t.length <= max) return t
    val cut = t.substring(0, max - 1)
    val word = cut.lastIndexOf(' ').takeIf { it >= max / 2 } ?: cut.length
    return cut.substring(0, word).trimEnd(' ', ',', ';', ':', '-', '–', '—', '.') + "…"
}

/**
 * The season and number a file's name gives: "S02E04" / "2x04" → (2, 4); "Episode 4" → (null, 4), its
 * season being its folder's. Null when the name doesn't say.
 */
internal fun fileEpisode(fileName: String): Pair<Int?, Int>? {
    Tmdb.episodeNumber(fileName, null)?.let { (s, e) -> return s to e }
    return Tmdb.episodeWord(fileName)?.let { null to it }
}

/**
 * What a file is called after its episode code: "Show.S01E03.The.Title.1080p.mkv" → "The Title",
 * "Show - 1x05 - The Fifth.mp4" → "The Fifth", "Episode 4 - The Volcano.mkv" → "The Volcano". Null when
 * nothing worth showing follows the code (only release tags), or there's no code.
 */
internal fun fileEpisodeName(fileName: String): String? {
    val stem = fileName.substringBeforeLast('.').ifBlank { fileName }
    val after = AFTER_CODE.find(stem)?.groupValues?.get(1) ?: return null
    val name = after.replace(DOTS, " ").replace(RELEASE_TAGS, "").replace(SPACE_RUN, " ").trim(' ', '-', '·', ':', '–')
    return name.takeIf { it.isNotEmpty() && !ONLY_CODE.matches(it) }
}

// Compiled once: every episode card works these out (once, remembered with its file name).
private val AFTER_CODE = Regex("""(?i)(?:s\d{1,2}\s*e\d{1,3}|(?<!\d)\d{1,2}x\d{2,3}(?!\d)|\b(?:episode|ep)\s*\d{1,3}\b)[\s._\-–·:]*(.*)""")
private val DOTS = Regex("""[._]""")
// Only words no episode is called (not "web" or "proper": "Charlotte's Web" stays whole).
private val RELEASE_TAGS = Regex("""(?i)\b(\d{3,4}p|x26[45]|h\s?26[45]|hevc|web[- ]?dl|webrip|blu\s?ray|brrip|bdrip|hdtv|dvdrip|aac|ac3|ddp?5\s?1|remux|10bit)\b.*""")
private val ONLY_CODE = Regex("""(?i)(s\d{1,2}\s*e\d{1,3}|\d{1,2}x\d{2,3}|(episode|ep)\s*\d{1,3})""")
private val SPACE_RUN = Regex("""\s+""")
