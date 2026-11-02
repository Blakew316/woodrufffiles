package com.woodruff.tvexplorer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import java.text.Normalizer
import java.util.Locale

/**
 * A subtitle file beside a video (a "sidecar": Heat.mkv and Heat.en.srt), as the player attaches it
 * to the film: where it is, its format, and what its name says about it. Never its name itself: the
 * Subtitles menu calls it by its language ([SubtitleTracks]).
 */
internal data class Sidecar(
    /** Where the player reads it: Drive's link for the file, or a local file:// address. */
    val uri: String,
    /** Media3's type for it: SubRip, WebVTT or SSA ([com.woodruff.tvexplorer.data.FileTypes.subtitleMime]). */
    val mime: String,
    /** Its language, as its name gives it, as a language tag ("en", "pt-BR"); null when the name doesn't say. */
    val language: String? = null,
    /** Signs and foreign dialogue only ("Heat.en.forced.srt"). */
    val forced: Boolean = false,
    /** For the deaf and hard of hearing ("Heat.en.sdh.srt"). */
    val sdh: Boolean = false,
)

/** What a subtitle file's name says beyond its video's ("en.forced"): its language, and whether it's forced or SDH. */
internal data class SidecarName(val language: String? = null, val forced: Boolean = false, val sdh: Boolean = false)

/** A subtitle file that goes with a video, and what its name says about it. [file] is the caller's own handle on it. */
internal data class SidecarMatch<T>(val file: T, val name: SidecarName)

/**
 * Finding the subtitle files that go with each video in a folder, the way Plex, Kodi and Jellyfin do,
 * and reading their names: "Heat (1995).srt", "Heat (1995).en.srt", "Heat (1995).eng.forced.srt",
 * "Heat (1995).English.sdh.srt", "Heat (1995).pt-BR.srt". Pure functions over file names, so the same
 * rules serve Google Drive and USB drives, and they're tested apart from both.
 */
internal object Sidecars {
    /**
     * A subtitle's file name starts with its video's name (without the extension), then a dot and
     * what it says about itself, or nothing more: Heat (1995).mkv takes "Heat (1995).srt" and
     * "Heat (1995).en.forced.srt". When several videos' names fit, the longest one has it ("Heat.2.mkv"
     * keeps "Heat.2.en.srt" from "Heat.mkv"); two copies with the same name (an .mkv and an .mp4)
     * both take it. In the video's own folder ([ownFolder]: one video and no other folders) every
     * subtitle is the video's, whatever it's called ("English.srt", "2_English.srt"). Anything else
     * isn't paired: a stray subtitle never lands on the wrong film.
     *
     * [videos] and [subtitles] are the files of one folder, named by [videoName] and [subtitleName].
     * Returns, for each video in order, its subtitles in the order given. Each subtitle's name is
     * looked up a dot at a time, longest first, among the videos' names: a library's top with
     * thousands of loose films and subtitle files costs no more than its names' length.
     */
    fun <V, S> pair(
        videos: List<V>, videoName: (V) -> String,
        subtitles: List<S>, subtitleName: (S) -> String,
        ownFolder: Boolean,
    ): List<List<SidecarMatch<S>>> {
        val byStem = HashMap<String, MutableList<Int>>()
        videos.forEachIndexed { i, v -> byStem.getOrPut(plain(stem(videoName(v)))) { ArrayList(1) } += i }
        val out = List(videos.size) { ArrayList<SidecarMatch<S>>() }
        for (sub in subtitles) {
            val s = plain(stem(subtitleName(sub)))
            var prefix = s
            var owners: List<Int>? = null
            while (true) {
                owners = byStem[prefix]
                if (owners != null) break
                val cut = prefix.lastIndexOf('.')
                if (cut <= 0) break
                prefix = prefix.substring(0, cut)
            }
            when {
                owners != null -> {
                    val name = describe(s.substring(prefix.length).removePrefix(".").split('.'))
                    for (v in owners) out[v] += SidecarMatch(sub, name)
                }
                ownFolder && videos.size == 1 -> out[0] += SidecarMatch(sub, describe(wordsAfter(plain(stem(videoName(videos[0]))), s)))
            }
        }
        return out
    }

    /** [pair] for plain file names. */
    fun pairNames(videos: List<String>, subtitles: List<String>, ownFolder: Boolean): List<List<SidecarMatch<String>>> =
        pair(videos, { it }, subtitles, { it }, ownFolder)

    /**
     * The files of a "Subs" folder beside the video, or a folder in it named after the video (as many
     * downloads come: Subs/2_English.srt, Subs/Show.S01E01/2_English.srt). Its files are read whole:
     * none of their name is the video's ("2_English" says English).
     */
    fun inVideosFolder(videoName: String, subtitle: String): SidecarName = describe(wordsAfter(plain(stem(videoName)), plain(stem(subtitle))))

    /** Whether a folder's name is one that holds subtitles ("Subs", "Subtitles"). */
    fun isSubsFolder(folderName: String): Boolean = folderName.trim().lowercase(Locale.ROOT) in SUBS_FOLDERS

    /** Whether [folderName] (a folder inside "Subs") is named after the video [videoName]. */
    fun namedAfter(folderName: String, videoName: String): Boolean = plain(folderName) == plain(stem(videoName))

    /**
     * What a subtitle's name says about it, from its words after the video's name: its language
     * ("en", "eng", "English", "Español", "pt-BR"), and whether it's forced or SDH ("forced", "sdh",
     * "cc"; "hi" when a language is given too, else it's Hindi, as subtitle tools write them). These
     * come last in a name, so it's read from the end, and stops at the first word that's none of them
     * (nor a number, as in "Heat.en.2.srt"): a title's own words ("Ice Age 2") never pass for a language.
     */
    fun describe(words: List<String>): SidecarName {
        val parts = words.flatMap { it.split(WORD_BREAKS) }.map { it.trim().replace(NUMBERED, "") }.filter { it.isNotEmpty() }
        var forced = false
        var sdh = false
        var language: String? = null
        var hindi = false
        for (word in parts.asReversed()) {
            val w = word.lowercase(Locale.ROOT)
            when {
                w in FORCED -> forced = true
                w in SDH -> sdh = true
                w == "hi" -> hindi = true
                w in IGNORED || w.all { it.isDigit() } -> Unit
                language == null -> language = languageTag(w) ?: break
                else -> break
            }
        }
        if (hindi) { if (language != null) sdh = true else language = "hi" }
        return SidecarName(language, forced, sdh)
    }

    /**
     * A word as a language tag: an ISO 639 code (two letters, or three in either of ISO 639-2's
     * forms), optionally with a region or script ("en-US", "pt_BR", "zh-Hant", "es-419"), or a
     * language's name in English or in itself ("English", "Español", "Deutsch"). Null when it isn't
     * one. A few two-letter codes that are mostly something else in file names (TS for telesync, II
     * for part two, DV for Dolby Vision, SD) don't count.
     */
    fun languageTag(word: String): String? {
        val w = word.trim().lowercase(Locale.ROOT).replace('_', '-')
        if (w.isEmpty()) return null
        SPECIAL[w]?.let { return it }
        val parts = w.split('-')
        val main = parts[0]
        val language = when {
            main.length == 2 && main.all { it in 'a'..'z' } -> main.takeIf { it in ISO_639_1 && it !in NOT_LANGUAGES }
            main.length == 3 && main.all { it in 'a'..'z' } -> ISO_639_2[main]
            parts.size == 1 -> NAMES[unaccented(main)]
            else -> null
        }?.let { LEGACY[it] ?: it } ?: return null
        if (parts.size == 1) return language
        // A region ("US", "419") or a script ("Hant") after it; anything else and it isn't a tag.
        val rest = parts.drop(1).map { sub ->
            when {
                sub.length == 2 && sub.all { it in 'a'..'z' } -> sub.uppercase(Locale.ROOT)
                sub.length == 3 && sub.all { it in '0'..'9' } -> sub
                sub.length == 4 && sub.all { it in 'a'..'z' } -> sub.replaceFirstChar { it.uppercaseChar() }
                else -> return null
            }
        }
        return (listOf(language) + rest).joinToString("-")
    }

    /**
     * [sidecar] as Media3 attaches it to the film's media item: its address marked as a sidecar's
     * ([SidecarLoading]: one that can't be read leaves no subtitles, rather than failing the film), its
     * format, language and flags. One whose name gives no language is marked undetermined ("und"), so
     * choosing it is still remembered for the show ([ShowPrefs]).
     */
    @OptIn(UnstableApi::class)
    fun configuration(sidecar: Sidecar): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(SidecarLoading.mark(Uri.parse(sidecar.uri)))
            .setMimeType(sidecar.mime)
            .setLanguage(sidecar.language ?: C.LANGUAGE_UNDETERMINED)
            .setSelectionFlags(if (sidecar.forced) C.SELECTION_FLAG_FORCED else 0)
            .setRoleFlags(if (sidecar.sdh) C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND else 0)
            .build()

    /** A file's name without its extension. */
    private fun stem(name: String): String = name.substringBeforeLast('.', name)

    /** Compared as people read names: one Unicode form, any case ("Amélie" typed on a Mac and on Windows match). */
    private fun plain(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /**
     * The words of subtitle name [sub] that aren't the video's: its dot-separated parts after those
     * that also begin the video's name [video] ("It.en" beside "It (2017).mkv" says "en"; "It" alone
     * says nothing, and never Italian).
     */
    private fun wordsAfter(video: String, sub: String): List<String> {
        val parts = sub.split('.')
        val videoWords = words(video)
        var keep = 0
        while (keep < parts.size) {
            val prefix = words(parts.subList(0, keep + 1).joinToString("."))
            if (prefix.size > videoWords.size || videoWords.subList(0, prefix.size) != prefix) break
            keep++
        }
        return parts.drop(keep)
    }

    private fun words(s: String): List<String> = s.split(TITLE_BREAKS).filter { it.isNotEmpty() }

    /** A name's words, for telling which begin a video's name: "It (2017)" is "it", "2017". */
    private val TITLE_BREAKS = Regex("""[^\p{L}\p{N}]+""")

    /** Within one dot-separated part: "English (SDH)", "[eng]". Hyphens and underscores stay: "pt-BR", "pt_BR". */
    private val WORD_BREAKS = Regex("""[\s()\[\]{},+]+""")

    /** Many downloads number their subtitle files: "2_English", "3_Spanish". */
    private val NUMBERED = Regex("""^\d+_""")

    private val FORCED = setOf("forced", "foreign", "forcednarrative")
    private val SDH = setOf("sdh", "cc", "hoh", "deaf", "hearingimpaired", "hearing-impaired")
    private val IGNORED = setOf("default", "full", "normal", "complete", "dialogue", "dialog", "ext", "subs", "sub", "subtitles")
    private val SUBS_FOLDERS = setOf("subs", "sub", "subtitles", "subtitle")

    /** Two-letter codes that are mostly release words in file names: telesync, part two, Dolby Vision, standard definition. */
    private val NOT_LANGUAGES = setOf("ts", "ii", "dv", "sd")

    /** Subtitle sites' own codes for Brazilian Portuguese and Latin American Spanish. */
    private val SPECIAL = mapOf("pob" to "pt-BR", "pb" to "pt-BR", "ptbr" to "pt-BR", "brazilian" to "pt-BR", "latino" to "es-419", "castellano" to "es")

    /** Every two-letter code, in both the current and the old spellings Java may give (he and iw, id and in, yi and ji). */
    private val ISO_639_1: Set<String> by lazy { Locale.getISOLanguages().toHashSet() + LEGACY.keys + LEGACY.values }

    /** Java's old spellings of three languages, as tags spell them now. */
    private val LEGACY = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    /**
     * Three-letter codes to two-letter ones: ISO 639-2's terminology codes as Java knows them
     * ("deu", "fra", "zho"), its bibliographic ones (the "ger", "fre", "chi" that subtitle files
     * often use), and three-letter codes with no two-letter one ("fil"), as they are. "und", "mul"
     * and "zxx" say no language at all.
     */
    private val ISO_639_2: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        for (code in Locale.getISOLanguages()) {
            runCatching { Locale(code).isO3Language }.getOrNull()?.takeIf { it.length == 3 }?.let { out.putIfAbsent(it, code) }
        }
        for ((b, t) in BIBLIOGRAPHIC) out[t]?.let { out.putIfAbsent(b, it) }
        for (code in listOf("fil", "haw", "yue")) out.putIfAbsent(code, code)
        out
    }

    /** ISO 639-2's bibliographic codes and their terminology twins. */
    private val BIBLIOGRAPHIC = mapOf(
        "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces", "dut" to "nld",
        "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri",
        "may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
    )

    /**
     * Languages by name, in English and in themselves, without accents ("english", "spanish",
     * "espanol", "deutsch", "francais"), for subtitle files named "Heat.English.srt".
     */
    private val NAMES: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        for (code in Locale.getISOLanguages()) {
            val locale = Locale(code)
            for (n in listOf(locale.getDisplayLanguage(Locale.ENGLISH), locale.getDisplayLanguage(locale))) {
                val key = unaccented(n.lowercase(Locale.ROOT))
                if (key.length >= 4 && key != code && key.none { it == ' ' }) out.putIfAbsent(key, code)
            }
        }
        out
    }

    private fun unaccented(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "")

    private val MARKS = Regex("""\p{M}+""")
}
