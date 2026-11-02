package com.woodruff.tvexplorer.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an episode card, its menu and the player say about an episode, from TMDB or else its file's name. */
class EpisodeMetaTest {
    private val volcano = EpisodeMeta(2, 5, "The Volcano", "The kids build a baking-soda volcano.", 42)

    private fun file(name: String, meta: EpisodeMeta? = null) = DriveFile("id", name, "video/x-matroska", 0, 0, null, episode = meta)

    @Test fun theSmallLineSaysWhichEpisodeAndHowLong() {
        assertEquals("EPISODE 5 · 42 MIN", episodeEyebrow(volcano, "whatever.mkv", null))
        // TMDB's length wins over the player's.
        assertEquals("EPISODE 5 · 42 MIN", episodeEyebrow(volcano, "whatever.mkv", 30 * 60_000L))
        // A special (season 0).
        assertEquals("SPECIAL · 24 MIN", episodeEyebrow(EpisodeMeta(0, 1, "Holiday", null, 24), "x.mkv", null))
        // No TMDB: the file's own code, and the length the player measured (to the nearest minute).
        assertEquals("EPISODE 3 · 43 MIN", episodeEyebrow(null, "Show.S01E03.1080p.mkv", 42 * 60_000L + 40_000))
        assertEquals("EPISODE 4", episodeEyebrow(null, "Episode 4.mkv", null))
        assertEquals("SPECIAL", episodeEyebrow(null, "Show S00E02.mkv", null))
        // A length under a minute isn't one worth saying.
        assertEquals("EPISODE 3", episodeEyebrow(null, "1x03.mkv", 20_000))
        // Nothing known: a space, so the line keeps its height and the names in a row line up.
        assertEquals(" ", episodeEyebrow(null, "Pilot.mkv", null))
        assertEquals("24 MIN", episodeEyebrow(null, "Pilot.mkv", 24 * 60_000L))
    }

    @Test fun theNameIsTmdbsElseTheFilesWithoutTags() {
        assertEquals("The Volcano", episodeName(volcano, "Show.S02E05.mkv"))
        assertEquals("The Title", episodeName(null, "Show.S01E03.The.Title.1080p.mkv"))
        assertEquals("The Fifth", episodeName(null, "Show - 1x05 - The Fifth.mp4"))
        assertEquals("Pilot", episodeName(null, "S01E013_Pilot.mkv"))
        assertEquals("The Volcano", episodeName(null, "Backyard Science - Episode 4 - The Volcano.mkv"))
        // A word some release tags share stays: only tags no episode is called go.
        assertEquals("Charlotte's Web", episodeName(null, "Show S01E02 Charlotte's Web.mkv"))
        assertEquals("Charlotte's Web", episodeName(null, "Show.S01E02.Charlotte's.Web.720p.WEB-DL.x264.mkv"))
        // Only tags, or only the code, after the code: "Episode 3".
        assertEquals("Episode 3", episodeName(null, "Show.S01E03.1080p.WEB-DL.mkv"))
        assertEquals("Episode 3", episodeName(null, "Show S01E03.mkv"))
        assertEquals("Episode 4", episodeName(null, "Episode 4.mkv"))
        assertEquals("Episode 7", episodeName(EpisodeMeta(1, 7), "Show S01E07.mkv"))
        // No number at all: the file's name without its extension.
        assertEquals("Behind the Scenes", episodeName(null, "Behind the Scenes.mp4"))
    }

    @Test fun codesFromTmdbOrTheFile() {
        assertEquals("S2 E5", episodeCodeOf(volcano, "x.mkv"))
        assertEquals("S1 E3", episodeCodeOf(null, "Show.S01E03.mkv"))
        assertEquals("S1 E5", episodeCodeOf(null, "1x05 - The Fifth.mp4"))
        // "Episode 4" alone doesn't say its season.
        assertNull(episodeCodeOf(null, "Episode 4.mkv"))
        assertNull(episodeCodeOf(null, "Pilot.mkv"))
    }

    @Test fun theMenuTitleIsTheCodeAndTheName() {
        assertEquals("S2 E5 · The Volcano", episodeMenuTitle(volcano, "Show.S02E05.mkv"))
        assertEquals("S1 E3 · The Title", episodeMenuTitle(null, "Show.S01E03.The.Title.1080p.mkv"))
        // No name of its own: just the code, never "S1 E3 · Episode 3".
        assertEquals("S1 E3", episodeMenuTitle(null, "Show.S01E03.1080p.mkv"))
        assertEquals("S1 E7", episodeMenuTitle(EpisodeMeta(1, 7), "Show S01E07.mkv"))
        assertEquals("Pilot", episodeMenuTitle(null, "Pilot.mkv"))
    }

    @Test fun thePlayerIsGivenTheShowTheCodeAndTheRealName() {
        assertEquals("Backyard Science S02E05 The Volcano", episodePlayTitle("Backyard Science", file("Backyard.Science.S02E05.1080p.mkv", volcano)))
        assertEquals("Backyard Science S10E112 Long Run", episodePlayTitle(" Backyard Science ", file("x.mkv", EpisodeMeta(10, 112, "Long Run"))))
        // TMDB doesn't know its name: the file's name, as before.
        assertEquals("Backyard.Science.S02E05.mkv", episodePlayTitle("Backyard Science", file("Backyard.Science.S02E05.mkv", EpisodeMeta(2, 5))))
        assertEquals("Backyard.Science.S02E05.mkv", episodePlayTitle("Backyard Science", file("Backyard.Science.S02E05.mkv")))
        assertEquals("S02E05.mkv", episodePlayTitle("", file("S02E05.mkv", volcano)))
    }

    @Test fun thePlayersTitleSplitsBackIntoShowAndName() {
        val label = com.woodruff.tvexplorer.player.EpisodeLabel.of(episodePlayTitle("Backyard Science", file("x.mkv", volcano)))
        assertEquals("Backyard Science · S2, E5", label.eyebrow)
        assertEquals("The Volcano", label.title)
    }

    @Test fun synopsesAreKeptShortAtAWord() {
        assertEquals("A short one.", trimSynopsis("  A short\none.  "))
        val long = "word ".repeat(100).trim() // 499 characters
        val cut = trimSynopsis(long)
        assertTrue(cut.length <= EpisodeMeta.OVERVIEW_MAX)
        assertTrue(cut.endsWith("word…"))
        // Never cut in the middle of a word.
        assertTrue(cut.removeSuffix("…").split(' ').all { it == "word" })
        // Exactly the limit: as it is.
        val exact = "a".repeat(EpisodeMeta.OVERVIEW_MAX)
        assertEquals(exact, trimSynopsis(exact))
    }

    @Test fun theFilesOwnEpisode() {
        assertEquals(2 to 4, fileEpisode("Show.S02E04.mkv"))
        assertEquals(1 to 5, fileEpisode("1x05.mkv"))
        assertEquals(null to 4, fileEpisode("Episode 4.mkv"))
        assertNull(fileEpisode("Pilot.mkv"))
        assertNull(fileEpisodeName("Show.S01E03.mkv"))
        assertNull(fileEpisodeName("Pilot.mkv"))
    }
}
