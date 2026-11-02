package com.woodruff.tvexplorer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The Subtitles menu's words (specs/player-feedback.md §3.6, §3.9): tracks by language in the TV's
 * language, never a file name, "(forced)" and "(SDH)" from the file, numbered when alike, and the
 * size row last.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SubtitleTracksTest {
    private val en = Locale.US

    @Test fun tracksReadAsTheirLanguageWithForcedAndSdh() {
        val labels = SubtitleTracks.labels(
            listOf(
                SubtitleTrack("en"),
                SubtitleTrack("en", sdh = true),
                SubtitleTrack("es"),
                SubtitleTrack("pt-BR"),
                SubtitleTrack("und"),
                SubtitleTrack(null, forced = true),
                SubtitleTrack("en", forced = true),
            ),
            en,
        )
        assertEquals(
            listOf("English", "English (SDH)", "Spanish", "Portuguese (Brazil)", "Subtitles", "Subtitles (forced)", "English (forced)"),
            labels,
        )
    }

    @Test fun foreignSpeechGetsFullEnglishSubtitlesBeforeSdhAndSignsOnly() {
        // "Heat.en.forced.srt", "Heat.en.sdh.srt" and "Heat.en.srt" beside a film in French: as a
        // folder sorts them, the plain English comes last, and it's the one to show.
        val beside = listOf(SubtitleTrack("fr"), SubtitleTrack("en", forced = true), SubtitleTrack("en", sdh = true), SubtitleTrack("en"))
        assertEquals(3, SubtitleTracks.englishForForeignSpeech(beside))
        // Only SDH and signs-only: SDH, which has all the dialogue.
        assertEquals(1, SubtitleTracks.englishForForeignSpeech(listOf(SubtitleTrack("en", forced = true), SubtitleTrack("eng", sdh = true))))
        // Only signs-only: better than nothing.
        assertEquals(0, SubtitleTracks.englishForForeignSpeech(listOf(SubtitleTrack("en-GB", forced = true))))
        // No English at all, or none that says so.
        assertEquals(null, SubtitleTracks.englishForForeignSpeech(listOf(SubtitleTrack("fr"), SubtitleTrack(null), SubtitleTrack("und"))))
    }

    @Test fun rowsThatWouldReadTheSameAreNumberedInOrder() {
        // The film's own English, then two subtitle files beside it that say English too.
        val labels = SubtitleTracks.labels(listOf(SubtitleTrack("en"), SubtitleTrack("en"), SubtitleTrack("es"), SubtitleTrack("en"), SubtitleTrack(null), SubtitleTrack("und")), en)
        assertEquals(listOf("English", "English · 2", "Spanish", "English · 3", "Subtitles", "Subtitles · 2"), labels)
    }

    @Test fun aTrackWithoutALanguageUsesTheFilmsNameForItButNeverAFileName() {
        assertEquals(listOf("Signs & Songs"), SubtitleTracks.labels(listOf(SubtitleTrack(null, label = "Signs & Songs")), en))
        // Already says it: not said twice.
        assertEquals(listOf("English SDH"), SubtitleTracks.labels(listOf(SubtitleTrack(null, label = "English SDH", sdh = true)), en))
        // A language wins over the film's own name for it.
        assertEquals(listOf("English"), SubtitleTracks.labels(listOf(SubtitleTrack("en", label = "Main")), en))
    }

    @Test fun tracksAreReadFromMedia3sOwnList() {
        fun text(id: String, build: Format.Builder.() -> Unit) = Format.Builder().setId(id).setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).apply(build).build()
        val embedded = text("1") { setLanguage("eng") }
        val sdh = text("2") { setLanguage("en").setRoleFlags(C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) }
        val forced = text("3") { setLanguage("en").setSelectionFlags(C.SELECTION_FLAG_FORCED) }
        // Read as the film loads, as Media3 does: its own format noted in codecs.
        val pgs = Format.Builder().setId("4").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(MimeTypes.APPLICATION_PGS).setLanguage("fr").build()
        val namedAfterAFile = text("5") { setLabel("Heat.en.srt") }
        val labelledSdh = text("6") { setLabel("English [SDH]").setLanguage("en") }
        val cannotShow = text("7") { setLanguage("de") }
        val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage("en").build()
        val tracks = Tracks(listOf(
            Tracks.Group(TrackGroup("a", audio), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
            Tracks.Group(TrackGroup("t1", embedded), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
            Tracks.Group(TrackGroup("t2", sdh), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
            Tracks.Group(TrackGroup("t3", forced), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
            Tracks.Group(TrackGroup("t4", pgs), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
            Tracks.Group(TrackGroup("t5", namedAfterAFile), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
            Tracks.Group(TrackGroup("t6", labelledSdh), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
            Tracks.Group(TrackGroup("t7", cannotShow), false, intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE), booleanArrayOf(false)),
        ))

        val choices = SubtitleTracks.of(tracks)

        assertEquals(listOf("t1", "t2", "t3", "t4", "t5", "t6"), choices.map { it.group.mediaTrackGroup.id })
        assertEquals(
            listOf("English", "English (SDH)", "English (forced)", "French", "Subtitles", "English (SDH) · 2"),
            SubtitleTracks.labels(choices.map { it.track }, en),
        )
        assertEquals(listOf(false, true, false, false, false, false), choices.map { it.track.selected })
        assertEquals(listOf(false, false, false, true, false, false), choices.map { it.track.pictures })
    }

    @Test fun theMenuIsOffTheTracksThenTheSize() {
        val picked = ArrayList<String>()
        val rows = SubtitleTracks.menuRows(
            listOf(SubtitleTrack("en", selected = true), SubtitleTrack("es")), en, SubtitleSize.STANDARD, tvCaptions = false,
            off = { picked += "off" }, choose = { picked += "track $it" }, openSize = { picked += "size" }, explain = { picked += it },
        )
        assertEquals(listOf("Off", "English", "Spanish", "Size: Standard"), rows.map { it.label })
        assertEquals(listOf(false, true, false, false), rows.map { it.checked })
        rows.forEach { it.action() }
        assertEquals(listOf("off", "track 0", "track 1", "size"), picked)
    }

    @Test fun nothingShowingTicksOff() {
        val rows = SubtitleTracks.menuRows(listOf(SubtitleTrack("en")), en, SubtitleSize.LARGE, false, {}, {}, {}, {})
        assertTrue(rows.first().checked)
        assertEquals("Size: Large", rows.last().label)
        assertFalse(rows.last().checked)
    }

    @Test fun withTheTvsCaptionSettingsOnTheSizeIsTheirs() {
        val said = ArrayList<String>()
        var opened = false
        val rows = SubtitleTracks.menuRows(listOf(SubtitleTrack("en")), en, SubtitleSize.STANDARD, tvCaptions = true, {}, {}, { opened = true }, { said += it })
        assertEquals("Size: set by the TV", rows.last().label)
        rows.last().action()
        assertFalse(opened)
        assertEquals(listOf("This TV’s caption settings choose the size."), said)
    }

    @Test fun theSizeMenuTicksTheOneChosen() {
        var chosen: SubtitleSize? = null
        val rows = SubtitleTracks.sizeRows(SubtitleSize.STANDARD) { chosen = it }
        assertEquals(listOf("Small", "Standard", "Large"), rows.map { it.label })
        assertEquals(listOf(false, true, false), rows.map { it.checked })
        rows[2].action()
        assertEquals(SubtitleSize.LARGE, chosen)
    }

    @Test fun sizesCycleAndAreKeptByName() {
        assertEquals(SubtitleSize.STANDARD, SubtitleSize.of(null))
        assertEquals(SubtitleSize.STANDARD, SubtitleSize.of("huge"))
        assertEquals(SubtitleSize.LARGE, SubtitleSize.of("large"))
        assertEquals(listOf(SubtitleSize.STANDARD, SubtitleSize.LARGE, SubtitleSize.SMALL), SubtitleSize.entries.map { it.next() })
        // Media3's own size on a 16:9 film is Standard.
        assertEquals(0.0533f, SubtitleSize.STANDARD.fraction, 0.0001f)
    }

    @Test fun picturesKeepTheirOwnSize() {
        assertTrue(SubtitleLook.isPictures(MimeTypes.APPLICATION_PGS))
        assertTrue(SubtitleLook.isPictures(MimeTypes.APPLICATION_VOBSUB))
        assertTrue(SubtitleLook.isPictures(MimeTypes.APPLICATION_DVBSUBS))
        assertFalse(SubtitleLook.isPictures(MimeTypes.APPLICATION_SUBRIP))
        assertFalse(SubtitleLook.isPictures(null))
    }
}
