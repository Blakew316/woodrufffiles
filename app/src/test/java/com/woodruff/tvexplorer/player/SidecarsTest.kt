package com.woodruff.tvexplorer.player

import com.woodruff.tvexplorer.data.FileTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Subtitle files beside a video: which video each goes with, and what its name says (its language,
 * forced, SDH), as Plex, Kodi and the subtitle tools name them.
 */
class SidecarsTest {
    private fun pair(videos: List<String>, subs: List<String>, ownFolder: Boolean = false): List<List<Pair<String, SidecarName>>> =
        Sidecars.pairNames(videos, subs, ownFolder).map { matches -> matches.map { it.file to it.name } }

    @Test fun languagesAreReadAsCodesAndNames() {
        assertEquals("en", Sidecars.languageTag("en"))
        assertEquals("en", Sidecars.languageTag("eng"))
        assertEquals("en", Sidecars.languageTag("ENG"))
        assertEquals("en", Sidecars.languageTag("English"))
        assertEquals("es", Sidecars.languageTag("spa"))
        assertEquals("es", Sidecars.languageTag("Spanish"))
        assertEquals("es", Sidecars.languageTag("Español"))
        assertEquals("es", Sidecars.languageTag("espanol"))
        assertEquals("fr", Sidecars.languageTag("fre"))
        assertEquals("fr", Sidecars.languageTag("fra"))
        assertEquals("fr", Sidecars.languageTag("Français"))
        assertEquals("de", Sidecars.languageTag("ger"))
        assertEquals("de", Sidecars.languageTag("deu"))
        assertEquals("de", Sidecars.languageTag("Deutsch"))
        assertEquals("zh", Sidecars.languageTag("chi"))
        assertEquals("nl", Sidecars.languageTag("dut"))
        assertEquals("he", Sidecars.languageTag("heb"))
        assertEquals("he", Sidecars.languageTag("iw"))
    }

    @Test fun regionsAndScriptsStayWithTheLanguage() {
        assertEquals("pt-BR", Sidecars.languageTag("pt-BR"))
        assertEquals("pt-BR", Sidecars.languageTag("pt_br"))
        assertEquals("pt-BR", Sidecars.languageTag("pob"))
        assertEquals("pt-BR", Sidecars.languageTag("Brazilian"))
        assertEquals("es-419", Sidecars.languageTag("es-419"))
        assertEquals("es-419", Sidecars.languageTag("Latino"))
        assertEquals("zh-Hant", Sidecars.languageTag("zh-hant"))
        assertEquals("en-US", Sidecars.languageTag("en-us"))
    }

    @Test fun releaseWordsAndNoLanguageAreNotLanguages() {
        for (word in listOf("und", "1080p", "x264", "BluRay", "WEB", "TS", "II", "DV", "SD", "", "xx", "zzz", "en-x264")) {
            assertNull(word, Sidecars.languageTag(word))
        }
    }

    @Test fun namesSayForcedAndSdh() {
        assertEquals(SidecarName("en", forced = true), Sidecars.describe(listOf("eng", "forced")))
        assertEquals(SidecarName("en", sdh = true), Sidecars.describe(listOf("en", "sdh")))
        assertEquals(SidecarName("en", sdh = true), Sidecars.describe(listOf("en", "cc")))
        assertEquals(SidecarName("en", sdh = true), Sidecars.describe(listOf("English (SDH)")))
        assertEquals(SidecarName("fr", forced = true), Sidecars.describe(listOf("fr", "default", "forced")))
        assertEquals(SidecarName(sdh = true), Sidecars.describe(listOf("sdh")))
        assertEquals(SidecarName(), Sidecars.describe(listOf("")))
        assertEquals(SidecarName(), Sidecars.describe(emptyList()))
    }

    @Test fun hiIsHearingImpairedBesideALanguageElseHindi() {
        // As subtitle tools write them: Movie.en.hi.srt, and Movie.hi.srt for Hindi.
        assertEquals(SidecarName("en", sdh = true), Sidecars.describe(listOf("en", "hi")))
        assertEquals(SidecarName("hi"), Sidecars.describe(listOf("hi")))
    }

    @Test fun theLastLanguageWordCounts() {
        // Another release's name before the language: the language is at the end.
        assertEquals(SidecarName("en"), Sidecars.describe(listOf("1080p", "BluRay", "x264", "eng")))
        // Downloads number their subtitle files.
        assertEquals(SidecarName("en"), Sidecars.describe(listOf("2_English")))
        assertEquals(SidecarName("es"), Sidecars.describe(listOf("3_Spanish")))
    }

    @Test fun subtitlesGoWithTheVideoTheirNameStartsWith() {
        val heat = "Heat (1995).mkv"
        val found = pair(listOf(heat), listOf("Heat (1995).srt", "Heat (1995).en.srt", "Heat (1995).eng.forced.srt", "Heat (1995).English.sdh.srt", "Ronin (1998).en.srt"))
        assertEquals(
            listOf(
                "Heat (1995).srt" to SidecarName(),
                "Heat (1995).en.srt" to SidecarName("en"),
                "Heat (1995).eng.forced.srt" to SidecarName("en", forced = true),
                "Heat (1995).English.sdh.srt" to SidecarName("en", sdh = true),
            ),
            found.single(),
        )
    }

    @Test fun eachEpisodeKeepsItsOwn() {
        val found = pair(
            listOf("Show.S01E01.mkv", "Show.S01E02.mkv"),
            listOf("Show.S01E01.en.srt", "Show.S01E02.en.srt", "Show.S01E02.es.srt", "Show.S01E03.en.srt"),
        )
        assertEquals(listOf("Show.S01E01.en.srt"), found[0].map { it.first })
        assertEquals(listOf("Show.S01E02.en.srt", "Show.S01E02.es.srt"), found[1].map { it.first })
    }

    @Test fun theLongestNameThatFitsHasIt() {
        val found = pair(listOf("Movie.mkv", "Movie.2.mkv"), listOf("Movie.2.en.srt", "Movie.en.srt"))
        assertEquals(listOf("Movie.en.srt" to SidecarName("en")), found[0])
        assertEquals(listOf("Movie.2.en.srt" to SidecarName("en")), found[1])
    }

    @Test fun twoCopiesOfAFilmBothTakeIt() {
        val found = pair(listOf("Movie.mkv", "Movie.mp4"), listOf("Movie.en.srt"))
        assertEquals(listOf("Movie.en.srt"), found[0].map { it.first })
        assertEquals(listOf("Movie.en.srt"), found[1].map { it.first })
    }

    @Test fun caseAndAccentsAsTypedOnAnyComputerMatch() {
        // "Amélie" typed on a Mac (é as e + accent) and on Windows (é).
        val found = pair(listOf("Amélie (2001).mkv", "HEAT.mkv"), listOf("Amélie (2001).fr.srt", "heat.EN.SRT"))
        assertEquals(listOf("Amélie (2001).fr.srt" to SidecarName("fr")), found[0])
        assertEquals(listOf("heat.EN.SRT" to SidecarName("en")), found[1])
    }

    @Test fun aStraySubtitleIsLeftAlone() {
        // Not this film's by name, and the film shares the folder with others: never guessed.
        assertEquals(listOf(emptyList<Pair<String, SidecarName>>(), emptyList()), pair(listOf("Heat.mkv", "Ronin.mkv"), listOf("Inception.en.srt")))
        assertEquals(listOf(emptyList<Pair<String, SidecarName>>()), pair(listOf("Heat.mkv"), listOf("Inception.en.srt"), ownFolder = false))
    }

    @Test fun inAFilmsOwnFolderEverySubtitleIsItsOwn() {
        val found = pair(
            listOf("Heat.1995.1080p.BluRay.x264.mkv"),
            listOf("English.srt", "Heat.1995.720p.eng.srt", "spa.srt", "2_French.srt", "Heat.srt"),
            ownFolder = true,
        )
        assertEquals(
            listOf(
                "English.srt" to SidecarName("en"),
                "Heat.1995.720p.eng.srt" to SidecarName("en"),
                "spa.srt" to SidecarName("es"),
                "2_French.srt" to SidecarName("fr"),
                // Part of the film's own name: says nothing about its language.
                "Heat.srt" to SidecarName(),
            ),
            found.single(),
        )
    }

    @Test fun anotherEpisodesSubtitlesNeverGoWithTheOneEpisodeInItsFolder() {
        // A season folder with only one episode downloaded so far, beside every episode's subtitles.
        val found = pair(
            listOf("Show.S01E01.1080p.WEB.mkv"),
            listOf("Show.S01E02.en.srt", "Show S01 E03.en.srt", "Show.1x04.en.srt", "Show.S01E01.WEB-DL.en.srt", "English.srt"),
            ownFolder = true,
        )
        // Its own (by another release's name), and the one whose name says no episode at all.
        assertEquals(listOf("Show.S01E01.WEB-DL.en.srt" to SidecarName("en"), "English.srt" to SidecarName("en")), found.single())
        // A show's subtitles left in a film's own folder aren't the film's either.
        assertEquals(emptyList<Pair<String, SidecarName>>(), pair(listOf("Heat (1995).mkv"), listOf("Show.S02E05.en.srt"), ownFolder = true).single())
        // Release words that look a little like a code are none: 1920x1080, x264.
        assertTrue(Sidecars.sameEpisode("Heat.1995.mkv", "Heat.1995.1920x1080.x264.en.srt"))
        assertTrue(Sidecars.sameEpisode("Show.S01E01.mkv", "show.s1e1.en.srt"))
        assertTrue(Sidecars.sameEpisode("Show 1x01.mkv", "Show.S01E01.en.srt"))
    }

    @Test fun aTitleWordIsNeverTakenForALanguage() {
        // "It" (2017): its subtitle file "It.srt" isn't Italian.
        assertEquals(listOf("It.srt" to SidecarName()), pair(listOf("It (2017).mkv"), listOf("It.srt"), ownFolder = true).single())
        assertEquals(listOf("It.en.srt" to SidecarName("en")), pair(listOf("It (2017).mkv"), listOf("It.en.srt"), ownFolder = true).single())
        // Named after the film: the words after its name do say.
        assertEquals(listOf("It (2017).it.srt" to SidecarName("it")), pair(listOf("It (2017).mkv"), listOf("It (2017).it.srt")).single())
    }

    @Test fun onlyTheEndOfANameSaysItsLanguage() {
        // A sequel's subtitle left in the film's folder: "Ice" is no Icelandic, "2" no language.
        assertEquals(listOf("Ice Age 2.srt" to SidecarName()), pair(listOf("Ice Age (2002).mkv"), listOf("Ice Age 2.srt"), ownFolder = true).single())
        // Several in one language, numbered.
        assertEquals(SidecarName("en"), Sidecars.describe(listOf("en", "2")))
        assertEquals(SidecarName("en", forced = true), Sidecars.describe(listOf("forced", "en")))
    }

    @Test fun aBigLibrarysTopPairsQuickly() {
        // Thousands of loose films and their subtitles at a library's top, "Film 1" beside "Film 10".
        val videos = (1..5_000).map { "Film $it (2001).mkv" }
        val subs = (1..5_000).map { "Film $it (2001).en.srt" }
        val started = System.nanoTime()
        val found = Sidecars.pairNames(videos, subs, ownFolder = false)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("$ms ms", ms < 3_000)
        assertTrue(found.withIndex().all { (i, matches) -> matches.single().file == "Film ${i + 1} (2001).en.srt" })
    }

    @Test fun subsFoldersAreKnownByName() {
        assertTrue(Sidecars.isSubsFolder("Subs"))
        assertTrue(Sidecars.isSubsFolder("subtitles"))
        assertFalse(Sidecars.isSubsFolder("Season 1"))
        assertTrue(Sidecars.namedAfter("Show.S01E01.1080p", "Show.S01E01.1080p.mkv"))
        assertFalse(Sidecars.namedAfter("Show.S01E02.1080p", "Show.S01E01.1080p.mkv"))
        assertEquals(SidecarName("en"), Sidecars.inVideosFolder("Show.S01E01.1080p.mkv", "2_English.srt"))
    }

    @Test fun subtitleFilesAndTheirCompanionsAreKnownByExtension() {
        assertEquals("application/x-subrip", FileTypes.subtitleMime("Heat.en.srt"))
        assertEquals("text/vtt", FileTypes.subtitleMime("Heat.VTT"))
        assertEquals("text/x-ssa", FileTypes.subtitleMime("Heat.ass"))
        assertEquals("text/x-ssa", FileTypes.subtitleMime("Heat.ssa"))
        assertNull(FileTypes.subtitleMime("Heat.sub"))
        assertNull(FileTypes.subtitleMime("Heat.mkv"))
        for (name in listOf("a.srt", "a.vtt", "a.ass", "a.ssa", "a.sub", "a.idx", "a.sup", "a.nfo")) assertTrue(name, FileTypes.isSidecar(name))
        for (name in listOf("a.mkv", "a.mp4", "a.jpg", "a.txt")) assertFalse(name, FileTypes.isSidecar(name))
    }
}
