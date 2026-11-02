package com.woodruff.tvexplorer.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * A film's sound tracks in the family's words: the Audio menu's rows (every track, the ones this TV
 * can't play marked) and what's said when a film starts without its main sound.
 */
class SoundTracksTest {
    private val us = Locale.US

    /** A message as read: its no-break spaces and hyphens as plain ones. */
    private fun plain(message: String) = message.replace('\u00A0', ' ').replace('\u2011', '-')

    private fun track(
        lang: String? = "en", channels: Int = 6, mime: String = "audio/ac3", name: String? = null, roles: Int = 0,
        default: Boolean = false, playable: Boolean = true, selected: Boolean = false,
    ) = SoundTrack(lang, channels, mime, name, roles, default, playable, selected)

    // ---- Words ----------------------------------------------------------------------------------

    @Test fun formatsHaveTheNamesPeopleKnow() {
        assertEquals("DTS", SoundTracks.codecName("audio/vnd.dts"))
        assertEquals("DTS-HD MA", SoundTracks.codecName("audio/vnd.dts.hd"))
        assertEquals("DTS Express", SoundTracks.codecName("audio/vnd.dts.hd;profile=lbr"))
        assertEquals("DTS:X", SoundTracks.codecName("audio/vnd.dts.uhd;profile=p2"))
        assertEquals("Dolby TrueHD", SoundTracks.codecName("audio/true-hd"))
        assertEquals("Dolby Atmos", SoundTracks.codecName("audio/eac3-joc"))
        assertEquals("Dolby Digital Plus", SoundTracks.codecName("audio/eac3"))
        assertEquals("Dolby Digital", SoundTracks.codecName("audio/ac3"))
        assertEquals("Dolby AC-4", SoundTracks.codecName("audio/ac4"))
        assertEquals("AAC", SoundTracks.codecName("audio/mp4a-latm"))
        assertEquals("Opus", SoundTracks.codecName("audio/opus"))
        assertEquals("FLAC", SoundTracks.codecName("audio/flac"))
        assertEquals("MP3", SoundTracks.codecName("audio/mpeg"))
        assertEquals("MP2", SoundTracks.codecName("audio/mpeg-L2"))
        assertEquals("PCM", SoundTracks.codecName("audio/raw"))
    }

    @Test fun anyOtherFormatIsItsNameInCapitals() {
        assertEquals("IAMF", SoundTracks.codecName("audio/iamf"))
        assertEquals("AMR-WB", SoundTracks.codecName("audio/amr-wb"))
        assertEquals("GSM", SoundTracks.codecName("audio/x-gsm"))
    }

    @Test fun noFormatNameWhenThereIsNothingToSay() {
        assertNull(SoundTracks.codecName(null))
        assertNull(SoundTracks.codecName("audio/x-unknown"))
        assertNull(SoundTracks.codecName("video/avc"))
    }

    @Test fun channelLayoutsInWords() {
        assertEquals("Mono", SoundTracks.channelsName(1))
        assertEquals("Stereo", SoundTracks.channelsName(2))
        assertEquals("5.1", SoundTracks.channelsName(6))
        assertEquals("7.1", SoundTracks.channelsName(8))
        assertEquals("Surround", SoundTracks.channelsName(3))
        assertEquals("Surround", SoundTracks.channelsName(7))
        assertNull(SoundTracks.channelsName(-1)) // Format.NO_VALUE: the file doesn't say
        assertNull(SoundTracks.channelsName(0))
    }

    @Test fun languagesInTheTvsLanguage() {
        assertEquals("English", SoundTracks.languageName("en", us))
        assertEquals("Spanish", SoundTracks.languageName("es", us))
        assertEquals("Spanish (Latin America)", SoundTracks.languageName("es-419", us))
        assertEquals("Japanese", SoundTracks.languageName("ja", us))
        assertEquals("Inglés", SoundTracks.languageName("en", Locale.forLanguageTag("es")))
    }

    @Test fun aTrackThatDoesntSayItsLanguageIsUnknown() {
        listOf(null, "", "und", "mis", "zxx", "qaa").forEach {
            assertEquals(it.toString(), "Unknown language", SoundTracks.languageName(it, us))
        }
    }

    @Test fun commentariesAndAudioDescriptionsByFlagOrName() {
        assertEquals("Commentary", SoundTracks.roleName(track(roles = C.ROLE_FLAG_COMMENTARY)))
        assertEquals("Commentary", SoundTracks.roleName(track(name = "Director's Commentary")))
        assertEquals("Commentary", SoundTracks.roleName(track(name = "commentary with the cast")))
        assertEquals("Audio description", SoundTracks.roleName(track(roles = C.ROLE_FLAG_DESCRIBES_VIDEO)))
        assertEquals("Audio description", SoundTracks.roleName(track(name = "English Descriptive Audio")))
        assertNull(SoundTracks.roleName(track(name = "English DTS-HD MA 5.1")))
        assertNull(SoundTracks.roleName(track()))
    }

    // ---- The Audio menu -------------------------------------------------------------------------

    @Test fun rowsSayLanguageChannelsAndRoleAndTheFormatOfOnesThatCantPlay() {
        val tracks = listOf(
            track(channels = 6, mime = "audio/ac3", default = true, selected = true),
            track(channels = 2, mime = "audio/ac3", name = "Commentary"),
            track(channels = 8, mime = "audio/true-hd", playable = false),
            track(lang = "es", channels = 6, mime = "audio/ac3"),
        )
        assertEquals(
            listOf("English · 5.1", "English · Stereo · Commentary", "English · 7.1 · Dolby TrueHD", "Spanish · 5.1"),
            SoundTracks.labels(tracks, us),
        )
    }

    @Test fun twoTracksThatWouldReadTheSameSayTheirFormats() {
        // A remux's DTS track and its Dolby Digital copy: both play (FFmpeg decodes the DTS).
        val tracks = listOf(track(mime = "audio/vnd.dts"), track(mime = "audio/ac3"))
        assertEquals(listOf("English · 5.1 · DTS", "English · 5.1 · Dolby Digital"), SoundTracks.labels(tracks, us))
    }

    @Test fun tracksStillAlikeAreNumberedInTheFilesOrder() {
        val tracks = listOf(track(), track(), track(lang = "fr"), track())
        assertEquals(
            listOf("English · 5.1 · Dolby Digital", "English · 5.1 · Dolby Digital · 2", "French · 5.1", "English · 5.1 · Dolby Digital · 3"),
            SoundTracks.labels(tracks, us),
        )
    }

    @Test fun aTrackThatCantPlayIsAlreadyToldApartByItsFormat() {
        val tracks = listOf(track(mime = "audio/vnd.dts", playable = false), track(mime = "audio/ac3", selected = true))
        assertEquals(listOf("English · 5.1 · DTS", "English · 5.1"), SoundTracks.labels(tracks, us))
    }

    @Test fun aTrackThatSaysNothingIsStillNamed() {
        assertEquals(listOf("Unknown language"), SoundTracks.labels(listOf(track(lang = null, channels = -1, mime = "audio/mp4a-latm")), us))
    }

    @Test fun menuRowsTickThePlayingTrackAndMarkTheOnesThatCantPlay() {
        val tracks = listOf(
            track(selected = true, default = true),
            track(channels = 8, mime = "audio/true-hd", playable = false),
            track(lang = "es"),
        )
        val chosen = mutableListOf<Int>()
        val said = mutableListOf<String>()
        val rows = SoundTracks.menuRows(tracks, us, choose = { chosen += it }, explain = { said += it })

        assertEquals(listOf(true, false, false), rows.map { it.checked })
        assertEquals(listOf(true, false, true), rows.map { it.enabled })
        assertEquals(listOf(null, SoundTracks.CANT_PLAY, null), rows.map { it.detail })
        rows[2].action()
        assertEquals(listOf(2), chosen)
        rows[1].action()
        assertEquals(listOf("This TV can’t play Dolby TrueHD sound."), said.map(::plain))
        assertEquals(listOf(2), chosen) // a track that can't play is never chosen
    }

    @Test fun aTrackThatCantPlayIsNeverTicked() {
        // Even if it was the one picked (it can't be: the player only picks what it can play).
        val rows = SoundTracks.menuRows(listOf(track(playable = false, selected = false)), us, {}, {})
        assertFalse(rows.single().checked)
        assertEquals("Can’t play", rows.single().detail)
    }

    // ---- What's said as a film starts -----------------------------------------------------------

    @Test fun nothingToSayWhenTheMainSoundPlays() {
        assertNull(SoundTracks.verdict(listOf(track(default = true, selected = true), track(lang = "es")), null))
    }

    @Test fun nothingToSayAboutAVideoWithNoSoundTrack() {
        // The Audio button dims and says so when pressed instead.
        assertNull(SoundTracks.verdict(emptyList(), null))
    }

    @Test fun noTrackThatCanPlayIsSilent() {
        val v = SoundTracks.verdict(listOf(track(channels = 8, mime = "audio/vnd.dts.hd", playable = false, default = true)), null)
        assertEquals(SoundVerdict.Silent("DTS-HD MA"), v)
        assertEquals("This TV can’t play this film’s sound (DTS-HD MA), so it plays silently.", plain(SoundTracks.startMessage(v!!, emptyList())))
    }

    @Test fun silentWithAFormatWithoutANameStillSaysIt() {
        assertEquals("This TV can’t play this film’s sound, so it plays silently.", SoundTracks.startMessage(SoundVerdict.Silent(null), emptyList()))
    }

    @Test fun silentNamesTheMainTracksFormat() {
        val tracks = listOf(
            track(channels = 2, mime = "audio/vnd.dts", playable = false, name = "Commentary"),
            track(channels = 8, mime = "audio/true-hd", playable = false, default = true),
        )
        assertEquals(SoundVerdict.Silent("Dolby TrueHD"), SoundTracks.verdict(tracks, null))
    }

    @Test fun playingTheCommentaryBecauseTheMainSoundCantPlayIsSaid() {
        val tracks = listOf(
            track(channels = 6, mime = "audio/vnd.dts.hd", playable = false, default = true),
            track(channels = 2, mime = "audio/ac3", name = "Commentary", selected = true),
        )
        val v = SoundTracks.verdict(tracks, null)
        assertEquals(SoundVerdict.MainUnplayable(playing = 1, codec = "DTS-HD MA"), v)
        assertEquals(
            "Playing English · Stereo · Commentary: this TV can’t play the main sound (DTS-HD MA).",
            plain(SoundTracks.startMessage(v!!, SoundTracks.labels(tracks, us))),
        )
    }

    @Test fun playingAnotherLanguageBecauseTheMainSoundCantPlayIsSaid() {
        val tracks = listOf(
            track(channels = 8, mime = "audio/true-hd", playable = false, default = true),
            track(lang = "es", channels = 6, mime = "audio/ac3", selected = true),
        )
        assertEquals(SoundVerdict.MainUnplayable(1, "Dolby TrueHD"), SoundTracks.verdict(tracks, null))
    }

    @Test fun withoutADefaultFlagTheFirstTrackIsTheMainOne() {
        val tracks = listOf(track(mime = "audio/vnd.dts", playable = false), track(lang = "fr", selected = true))
        assertEquals(SoundVerdict.MainUnplayable(1, "DTS"), SoundTracks.verdict(tracks, null))
    }

    @Test fun theSameSoundInAFormatThisTvCanPlayIsNothingToTell() {
        // A remux's DTS main track and its Dolby Digital copy, same language, as many channels.
        val tracks = listOf(
            track(channels = 6, mime = "audio/vnd.dts", playable = false, default = true),
            track(channels = 6, mime = "audio/ac3", selected = true),
        )
        assertNull(SoundTracks.verdict(tracks, null))
    }

    @Test fun aCopyWithFewerChannelsIsSaid() {
        val tracks = listOf(
            track(channels = 8, mime = "audio/true-hd", playable = false, default = true),
            track(channels = 6, mime = "audio/ac3", selected = true),
        )
        assertEquals(SoundVerdict.MainUnplayable(1, "Dolby TrueHD"), SoundTracks.verdict(tracks, null))
    }

    @Test fun theShowsOwnAudioLanguageDecidesWhichTrackIsTheMainOne() {
        // Spanish chosen for this show: the English DTS track was never going to play.
        val tracks = listOf(
            track(channels = 6, mime = "audio/vnd.dts", playable = false, default = true),
            track(lang = "es", channels = 6, mime = "audio/ac3", selected = true),
        )
        assertNull(SoundTracks.verdict(tracks, "es"))
        // English chosen: playing its commentary instead is worth saying.
        val withCommentary = listOf(
            track(channels = 6, mime = "audio/vnd.dts", playable = false, default = true),
            track(channels = 2, mime = "audio/ac3", name = "Commentary", selected = true),
            track(lang = "es"),
        )
        assertEquals(SoundVerdict.MainUnplayable(1, "DTS"), SoundTracks.verdict(withCommentary, "en"))
        // A language the file doesn't have: as if none was chosen.
        assertEquals(SoundVerdict.MainUnplayable(1, "DTS"), SoundTracks.verdict(withCommentary, "de"))
    }

    @Test fun aCommentaryIsNeverTheMainTrackWhileThereIsAnother() {
        assertEquals(1, SoundTracks.mainIndex(listOf(track(name = "Commentary", default = true), track()), null))
        assertEquals(0, SoundTracks.mainIndex(listOf(track(name = "Commentary")), null))
        assertNull(SoundTracks.mainIndex(emptyList(), null))
    }

    @Test fun somethingThatCouldPlayButIsntPickedIsNotSilent() {
        // Not this TV's doing (nothing turns sound off today): nothing to blame it for.
        assertNull(SoundTracks.verdict(listOf(track(playable = false, default = true), track(lang = "es")), null))
    }

    @Test fun aSongWithNoSoundThisTvCanPlayCantBePlayed() {
        assertEquals("Can’t play Morning Song: this TV can’t play its sound (FLAC).", SoundTracks.songMessage("Morning Song", "FLAC"))
        assertEquals("Can’t play Morning Song: this TV can’t play its sound.", SoundTracks.songMessage("Morning Song", null))
    }

    @Test fun formatNamesAndTrackLabelsNeverBreakAcrossLines() {
        // A wrapped message never ends a line with "(DTS-" or leaves "MA)." alone on the next.
        assertTrue(SoundTracks.startMessage(SoundVerdict.Silent("DTS-HD MA"), emptyList()).contains("(DTS\u2011HD\u00A0MA)"))
        val playing = SoundTracks.startMessage(SoundVerdict.MainUnplayable(1, "Dolby TrueHD"), listOf("x", "English · Stereo"))
        assertTrue(playing, playing.startsWith("Playing English\u00A0·\u00A0Stereo: this TV can’t play the main sound (Dolby\u00A0TrueHD)."))
        assertEquals("This TV can’t play Dolby\u00A0Digital\u00A0Plus sound.", SoundTracks.cantPlayMessage(track(mime = "audio/eac3")))
        // The rest of the sentence wraps as usual.
        assertEquals("Can’t play The Long Song: this TV can’t play its sound (FLAC).", SoundTracks.songMessage("The Long Song", "FLAC"))
    }

    @Test fun okOnATrackThatCantPlaySaysWhy() {
        assertEquals("This TV can’t play DTS:X sound.", SoundTracks.cantPlayMessage(track(mime = "audio/vnd.dts.uhd;profile=p2", playable = false)))
        assertEquals("This TV can’t play this track.", SoundTracks.cantPlayMessage(track(mime = "audio/x-unknown", playable = false)))
    }

    @Test fun messagesUseTheAppsTypography() {
        // Curly apostrophes and the " · " separator, never a code.
        val all = listOf(
            SoundTracks.startMessage(SoundVerdict.Silent("DTS"), emptyList()),
            SoundTracks.songMessage("x", "FLAC"),
            SoundTracks.cantPlayMessage(track(mime = "audio/true-hd")),
            SoundTracks.CANT_PLAY,
        )
        all.forEach { assertFalse(it, it.contains("'")) }
        assertTrue(SoundTracks.NO_SOUND.endsWith("."))
    }
}
