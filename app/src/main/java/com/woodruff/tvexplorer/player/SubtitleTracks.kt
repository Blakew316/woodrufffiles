package com.woodruff.tvexplorer.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/**
 * One subtitle track of the film playing, as the Subtitles menu talks about it: inside the film, or a
 * subtitle file beside it ([Sidecar]), which Media3 lists after the film's own. Plain values, so the
 * menu's words are worked out, and tested, apart from the player.
 */
internal data class SubtitleTrack(
    /** Its language as Media3 reports it ("en", "pt-BR"); null or "und" when nothing says. */
    val language: String?,
    /** The name the film gives it inside ("Signs & Songs"); subtitle files beside it have none. */
    val label: String? = null,
    /** Signs and foreign dialogue only. */
    val forced: Boolean = false,
    /** For the deaf and hard of hearing (sounds as well as speech). */
    val sdh: Boolean = false,
    /** Its own format ("application/x-subrip"; "application/pgs" for a Blu-ray's). */
    val mime: String? = null,
    /** It's the one showing. */
    val selected: Boolean = false,
) {
    /** Pictures of words, as on Blu-ray films: they keep their own size ([SubtitleLook]). */
    val pictures: Boolean get() = SubtitleLook.isPictures(mime)
}

/** A [SubtitleTrack] with where Media3 keeps it ([group], [index]), so the menu can choose it. */
internal class SubtitleChoice(val track: SubtitleTrack, val group: Tracks.Group, val index: Int)

/**
 * The Subtitles menu (specs/player-feedback.md §3.6, §3.9): Off, every subtitle track this TV can
 * show (the film's own, then the files beside it) by its language in the TV's language, and the size.
 */
internal object SubtitleTracks {
    /** The Subtitles button on a video with none. */
    const val NONE = "No subtitles in this video"

    /** The size row while the TV's own caption settings are on, and what OK on it says. */
    const val SIZE_BY_TV = "Size: set by the TV"
    const val TV_CHOOSES_SIZE = "This TV’s caption settings choose the size."

    /** A size chosen while picture subtitles show. */
    const val PICTURES_KEEP_SIZE = "This film’s subtitles are pictures, so their size can’t change."

    /** A track whose language nothing says. */
    private const val NO_LANGUAGE = "Subtitles"

    /** Every subtitle track in [tracks] this TV can show, in Media3's order (the film's own first), each with whether it's showing. */
    @OptIn(UnstableApi::class)
    fun of(tracks: Tracks): List<SubtitleChoice> =
        tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                val f = g.getTrackFormat(i)
                // A track named after a file ("Heat.en.srt", as some tools name them) is called by its language instead.
                val label = f.label?.trim()?.takeIf { it.isNotEmpty() && !FILE_NAME.containsMatchIn(it) }
                val track = SubtitleTrack(
                    language = f.language,
                    label = label,
                    forced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || FORCED_WORDS.containsMatchIn(label.orEmpty()),
                    sdh = f.roleFlags and (C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0 || SDH_WORDS.containsMatchIn(label.orEmpty()),
                    mime = ownFormat(f),
                    selected = g.isTrackSelected(i),
                )
                SubtitleChoice(track, g, i)
            }
        }

    /**
     * Each track's row: its language in the TV's language ([locale]: "English", "Spanish"), else
     * the name the film gives it, else "Subtitles"; then " (forced)" or " (SDH)". Rows that would
     * read the same are numbered in order, the film's own first ("English", "English · 2").
     */
    fun labels(tracks: List<SubtitleTrack>, locale: Locale): List<String> {
        val out = tracks.map { t ->
            val name = SoundTracks.languageNameOrNull(t.language, locale) ?: t.label ?: NO_LANGUAGE
            when {
                t.forced && !FORCED_WORDS.containsMatchIn(name) -> "$name (forced)"
                t.sdh && !t.forced && !SDH_WORDS.containsMatchIn(name) -> "$name (SDH)"
                else -> name
            }
        }.toMutableList()
        val seen = HashMap<String, Int>()
        for (i in out.indices) {
            val n = (seen[out[i]] ?: 0) + 1
            seen[out[i]] = n
            if (n > 1) out[i] += " · $n"
        }
        return out
    }

    /**
     * The menu's rows: Off (ticked when nothing shows), the tracks (the one showing ticked; OK
     * [choose]s its index), and last the size: "Size: Standard" opening the size menu ([openSize]),
     * or, with the TV's caption settings on ([tvCaptions]), "Size: set by the TV", which says so
     * ([explain]) and opens nothing.
     */
    fun menuRows(
        tracks: List<SubtitleTrack>, locale: Locale, size: SubtitleSize, tvCaptions: Boolean,
        off: () -> Unit, choose: (Int) -> Unit, openSize: () -> Unit, explain: (String) -> Unit,
    ): List<TvMenu.Row> {
        val labels = labels(tracks, locale)
        val sizeRow = if (tvCaptions) TvMenu.Row(SIZE_BY_TV) { explain(TV_CHOOSES_SIZE) } else TvMenu.Row("Size: ${size.label}", action = openSize)
        return listOf(TvMenu.Row("Off", checked = tracks.none { it.selected }, action = off)) +
            tracks.mapIndexed { i, t -> TvMenu.Row(labels[i], checked = t.selected) { choose(i) } } +
            sizeRow
    }

    /** The Subtitle size menu: Small, Standard, Large, the current one ticked. */
    fun sizeRows(current: SubtitleSize, choose: (SubtitleSize) -> Unit): List<TvMenu.Row> =
        SubtitleSize.entries.map { s -> TvMenu.Row(s.label, checked = s == current) { choose(s) } }

    /**
     * A track's own format. Media3 reads subtitles as the film loads and hands them on in a format of
     * its own, noting the original in [Format.codecs].
     */
    @OptIn(UnstableApi::class)
    private fun ownFormat(f: Format): String? =
        if (f.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) f.codecs ?: f.sampleMimeType else f.sampleMimeType

    private val FILE_NAME = Regex("""(?i)\.(srt|vtt|webvtt|ass|ssa|sub|idx|sup)$""")
    private val SDH_WORDS = Regex("""(?i)\b(sdh|cc|hoh|hearing[ -]?impaired)\b""")
    private val FORCED_WORDS = Regex("""(?i)\bforced\b""")
}
