package com.woodruff.tvexplorer.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/**
 * One sound track of the file playing, as the Audio menu and the player's messages talk about it.
 * Plain values (built from Media3's [Tracks] by [SoundTracks.of]), so the menu's words and the
 * "this TV can't play…" messages are worked out, and tested, apart from the player.
 */
internal data class SoundTrack(
    /** Its language as Media3 reports it ("en", "es-419"); null or "und" when the file doesn't say. */
    val language: String?,
    /** How many channels; 0 or less when the file doesn't say. */
    val channels: Int,
    /** Its format ("audio/vnd.dts.hd", "audio/true-hd"). */
    val mime: String?,
    /** The name the file gives it ("Director's commentary"), if any. */
    val name: String? = null,
    /** Media3's role flags ([C.ROLE_FLAG_COMMENTARY], [C.ROLE_FLAG_DESCRIBES_VIDEO]). */
    val roles: Int = 0,
    /** The file marks it as the one to play. */
    val isDefault: Boolean = false,
    /** This TV can play it: its own decoders, passing it through to a soundbar, or FFmpeg's. */
    val playable: Boolean = true,
    /** It's the one playing. */
    val selected: Boolean = false,
)

/** A [SoundTrack] with where Media3 keeps it ([group], [index]), so the menu can choose it. */
internal class SoundChoice(val track: SoundTrack, val group: Tracks.Group, val index: Int)

/** What to tell the family about a film's sound as it starts. Nothing at all when it plays as it should. */
internal sealed interface SoundVerdict {
    /** The file has sound, but none of it can play on this TV. [codec]: the main track's format, in words. */
    data class Silent(val codec: String?) : SoundVerdict

    /** The main track can't play on this TV, so track [playing] plays instead. [codec]: the main track's format. */
    data class MainUnplayable(val playing: Int, val codec: String?) : SoundVerdict
}

/**
 * Sound tracks in the family's words: every track the file has, the ones this TV can't play
 * included and marked (they were left out, and a film whose only sound was DTS-HD played silently
 * with an Audio button that did nothing), and what to say when a film starts without its main sound.
 */
internal object SoundTracks {
    /** Said at the right of a track this TV can't play, in the Audio menu. */
    const val CANT_PLAY = "Can’t play"

    /** The Audio button on a video with no sound track at all. */
    const val NO_SOUND = "This video has no sound."

    private const val UNKNOWN_LANGUAGE = "Unknown language"
    private const val SEPARATOR = " · "

    /** Every sound track in [tracks], in the file's order, each with whether it can play and whether it's playing. */
    @OptIn(UnstableApi::class)
    fun of(tracks: Tracks): List<SoundChoice> =
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g ->
            (0 until g.length).map { i ->
                val f = g.getTrackFormat(i)
                val selected = g.isTrackSelected(i)
                val track = SoundTrack(
                    language = f.language,
                    channels = f.channelCount,
                    mime = f.sampleMimeType,
                    name = f.label,
                    roles = f.roleFlags,
                    isDefault = f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    // Only what a decoder fully handles can be chosen (as in Media3's own menus): a
                    // track it merely might manage counts only once the player itself picked it.
                    playable = g.isTrackSupported(i) || selected,
                    selected = selected,
                )
                SoundChoice(track, g, i)
            }
        }

    /**
     * The format's name as people know it ("DTS-HD MA", "Dolby TrueHD"), from its MIME type;
     * anything not listed: the part after "audio/", in capitals. Null when there's nothing to say.
     */
    fun codecName(mime: String?): String? {
        val m = mime?.lowercase(Locale.ROOT)?.replace(" ", "") ?: return null
        if (!m.startsWith("audio/")) return null
        CODECS[m]?.let { return it }
        // "audio/vnd.dts.uhd;profile=p2" (DTS:X) and the like: the format without its details.
        val base = m.substringBefore(';')
        CODECS[base]?.let { return it }
        val sub = base.removePrefix("audio/").removePrefix("x-")
        return sub.takeIf { it.isNotEmpty() && it != "unknown" }?.uppercase(Locale.ROOT)
    }

    /** "Mono", "Stereo", "5.1", "7.1", or "Surround" for other layouts; null when unknown. */
    fun channelsName(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "Mono"
        count == 2 -> "Stereo"
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> "Surround"
    }

    /** The language's name in the TV's language ([locale]), capitalised; "Unknown language" when the file doesn't say. */
    fun languageName(tag: String?, locale: Locale): String = languageNameOrNull(tag, locale) ?: UNKNOWN_LANGUAGE

    /**
     * The language's name in the TV's language ([locale]), capitalised ("English", "Portuguese
     * (Brazil)"); null when the tag names none. The Subtitles menu says "Subtitles" then instead.
     */
    fun languageNameOrNull(tag: String?, locale: Locale): String? {
        val t = tag?.trim().orEmpty()
        if (t.isEmpty() || t.lowercase(Locale.ROOT) in NO_LANGUAGE) return null
        val name = runCatching { Locale.forLanguageTag(t).getDisplayName(locale) }.getOrNull().orEmpty()
        // A code Android has no name for comes back as itself.
        if (name.isBlank() || name.equals(t, ignoreCase = true)) return null
        val first = name.offsetByCodePoints(0, 1)
        return name.substring(0, first).uppercase(locale) + name.substring(first)
    }

    /**
     * "Commentary" or "Audio description" when the track is one: flagged so, or (as most films
     * have it) named so in the file.
     */
    fun roleName(t: SoundTrack): String? = when {
        t.roles and C.ROLE_FLAG_COMMENTARY != 0 || COMMENTARY.containsMatchIn(t.name.orEmpty()) -> "Commentary"
        t.roles and C.ROLE_FLAG_DESCRIBES_VIDEO != 0 || DESCRIPTION.containsMatchIn(t.name.orEmpty()) -> "Audio description"
        else -> null
    }

    /**
     * What each track's row in the Audio menu says, in the player's " · " style rather than Media3's
     * own ("English, 5.1 surround sound"): language · channels · role, plus the format on a track
     * this TV can't play. Two playable tracks that would read the same (a remux's DTS track and its
     * Dolby Digital copy) say their formats too; any still alike are numbered in the file's order.
     */
    fun labels(tracks: List<SoundTrack>, locale: Locale): List<String> {
        val out = tracks.map { t ->
            val words = listOfNotNull(languageName(t.language, locale), channelsName(t.channels), roleName(t))
            val codec = codecName(t.mime)?.takeIf { !t.playable }
            (words + listOfNotNull(codec)).joinToString(SEPARATOR)
        }.toMutableList()
        val counts = out.groupingBy { it }.eachCount()
        for (i in out.indices) {
            if (tracks[i].playable && counts.getValue(out[i]) > 1) codecName(tracks[i].mime)?.let { out[i] += SEPARATOR + it }
        }
        val seen = HashMap<String, Int>()
        for (i in out.indices) {
            val n = (seen[out[i]] ?: 0) + 1
            seen[out[i]] = n
            if (n > 1) out[i] += SEPARATOR + n
        }
        return out
    }

    /**
     * The track the family would hear if this TV could play every one: as Media3 picks, one in the
     * show's own audio language ([preferredLanguage], chosen once in the menu) if there is one,
     * then not a commentary, then the one the file marks as the default, then the first.
     */
    fun mainIndex(tracks: List<SoundTrack>, preferredLanguage: String?): Int? {
        if (tracks.isEmpty()) return null
        val all = tracks.indices.toList()
        val pool = preferredLanguage?.let { lang -> all.filter { sameLanguage(tracks[it].language, lang) } }.orEmpty().ifEmpty { all }
        val plain = pool.filter { roleName(tracks[it]) == null }.ifEmpty { pool }
        return plain.firstOrNull { tracks[it].isDefault } ?: plain.first()
    }

    /**
     * What to say as a film starts: nothing when its main sound plays, or when there's no sound
     * track at all (the Audio button says so instead); [SoundVerdict.Silent] when no track can play;
     * [SoundVerdict.MainUnplayable] when another one plays instead of the main one. Not when that one
     * is the same sound in a format this TV can play (same language, same role, as many channels).
     */
    fun verdict(tracks: List<SoundTrack>, preferredLanguage: String?): SoundVerdict? {
        val main = mainIndex(tracks, preferredLanguage) ?: return null
        val m = tracks[main]
        val playing = tracks.indexOfFirst { it.selected }
        if (playing < 0) return if (tracks.none { it.playable }) SoundVerdict.Silent(codecName(m.mime)) else null
        if (playing == main || m.playable) return null
        val p = tracks[playing]
        val sameSound = sameLanguage(m.language, p.language) && roleName(m) == roleName(p) && (m.channels <= 0 || p.channels >= m.channels)
        return if (sameSound) null else SoundVerdict.MainUnplayable(playing, codecName(m.mime))
    }

    /**
     * The message for [verdict] as a film starts. [labels]: every track's row in the Audio menu, as
     * [SoundTracks.labels] words them. A track's label and a format's name never break across lines
     * ([whole]).
     */
    fun startMessage(verdict: SoundVerdict, labels: List<String>): String = when (verdict) {
        is SoundVerdict.Silent -> "This TV can’t play this film’s sound${inBrackets(verdict.codec)}, so it plays silently."
        is SoundVerdict.MainUnplayable ->
            "Playing ${whole(labels[verdict.playing])}: this TV can’t play the main sound${inBrackets(verdict.codec)}."
    }

    /** A song (no picture) with no sound this TV can play: as unplayable as a file it can't open. */
    fun songMessage(title: String, codec: String?): String = "Can’t play $title: this TV can’t play its sound${inBrackets(codec)}."

    /** OK on a track marked "Can't play" in the Audio menu. */
    fun cantPlayMessage(t: SoundTrack): String =
        codecName(t.mime)?.let { "This TV can’t play ${whole(it)} sound." } ?: "This TV can’t play this track."

    /**
     * [words] kept on one line when a message wraps: no-break spaces and hyphens (Inter has both),
     * so "(DTS-HD MA)" never ends a line as "(DTS-" or leaves "MA)." alone on the next.
     */
    fun whole(words: String): String = words.replace(' ', '\u00A0').replace('-', '\u2011')

    /**
     * The Audio menu's rows: every track, the one playing ticked, OK choosing it ([choose], with the
     * track's index in [tracks]); a track this TV can't play is dimmed and marked [CANT_PLAY], and OK
     * on it keeps the menu up and [explain]s why.
     */
    fun menuRows(tracks: List<SoundTrack>, locale: Locale, choose: (Int) -> Unit, explain: (String) -> Unit): List<TvMenu.Row> {
        val labels = labels(tracks, locale)
        return tracks.mapIndexed { i, t ->
            if (t.playable) TvMenu.Row(labels[i], checked = t.selected) { choose(i) }
            else TvMenu.Row(labels[i], detail = CANT_PLAY, enabled = false) { explain(cantPlayMessage(t)) }
        }
    }

    private fun inBrackets(codec: String?) = codec?.let { " (${whole(it)})" }.orEmpty()

    /** Same language, by the main part of the tag ("en" for "en-US"); never for a track that doesn't say. */
    private fun sameLanguage(a: String?, b: String?): Boolean {
        fun main(tag: String?) = tag?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
            ?.takeIf { it.isNotEmpty() && it !in NO_LANGUAGE }
        val ma = main(a)
        return ma != null && ma == main(b)
    }

    /** Tags that name no language: undetermined, uncoded, no speech. */
    private val NO_LANGUAGE = setOf("und", "mis", "zxx")

    private val COMMENTARY = Regex("""(?i)\bcomment""")
    private val DESCRIPTION = Regex("""(?i)\b(audio description|descriptive|described)""")

    /** Formats by their MIME type (lower case), as the family knows them. */
    private val CODECS = mapOf(
        "audio/vnd.dts" to "DTS",
        "audio/vnd.dts.hd" to "DTS-HD MA",
        "audio/vnd.dts.hd;profile=lbr" to "DTS Express",
        "audio/vnd.dts.uhd" to "DTS:X",
        "audio/true-hd" to "Dolby TrueHD",
        "audio/eac3-joc" to "Dolby Atmos",
        "audio/eac3" to "Dolby Digital Plus",
        "audio/ac3" to "Dolby Digital",
        "audio/ac4" to "Dolby AC-4",
        "audio/mp4a-latm" to "AAC",
        "audio/opus" to "Opus",
        "audio/flac" to "FLAC",
        "audio/mpeg" to "MP3",
        "audio/mpeg-l2" to "MP2",
        "audio/mpeg-l1" to "MP1",
        "audio/vorbis" to "Vorbis",
        "audio/alac" to "ALAC",
        "audio/raw" to "PCM",
        "audio/3gpp" to "AMR",
        "audio/mha1" to "MPEG-H",
        "audio/mhm1" to "MPEG-H",
    )
}
