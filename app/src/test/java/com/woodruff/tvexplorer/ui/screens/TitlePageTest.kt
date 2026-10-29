package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.player.RecentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A title page's choices: what a show goes on with, its season, a film's length and end time, its collection. */
class TitlePageTest {
    private fun item(key: String, title: String, pos: Long, dur: Long, upNext: Boolean = false) = RecentItem(key, title, pos, dur, 0, upNext)
    private val theShow: (RecentItem) -> Boolean = { it.title.startsWith("Show ") }

    @Test fun upNextWinsOverAnOlderEpisodeLeftPartWay() {
        val recent = listOf( // newest first
            item("drive:e5", "Show S01E05.mkv", 0, 0, upNext = true),
            item("drive:x1", "Other S02E01.mkv", 600_000, 1_200_000),
            item("drive:e3", "Show S01E03.mkv", 600_000, 1_200_000),
        )
        assertEquals("drive:e5", showResume(recent, theShow)?.key)
    }

    @Test fun theNewestInProgressIsResumed() {
        val recent = listOf(
            item("drive:e4", "Show S01E04.mkv", 1_190_000, 1_200_000), // 99%: watched
            item("drive:e2", "Show S01E02.mkv", 5_000, 1_200_000), // under 2%: barely started
            item("drive:e1", "Show S01E01.mkv", 600_000, 1_200_000),
        )
        assertEquals("drive:e1", showResume(recent, theShow)?.key)
        assertNull(showResume(recent.take(2), theShow))
        assertNull(showResume(listOf(item("drive:x1", "Other S01E01.mkv", 600_000, 1_200_000)), theShow))
    }

    @Test fun theSeasonListingTheEpisodeElseTheOneItsNameSays() {
        val names = listOf("Specials", "Season 1", "Season 2")
        val listed = listOf(emptySet(), setOf("drive:a"), emptySet<String>())
        assertEquals(1, seasonHolding(names, listed, "drive:a", "S02E05_Pilot.mkv"))
        assertEquals(2, seasonHolding(names, listed, "drive:b", "Show S02E05.mkv"))
        assertNull(seasonHolding(names, listed, "drive:b", "Episode 5.mkv"))
        assertNull(seasonHolding(names, listed, "drive:b", "Show S04E01.mkv"))
    }

    @Test fun runtimes() {
        assertEquals("2 h 32 min", runtimeLabel(152))
        assertEquals("45 min", runtimeLabel(45))
        assertEquals("2 h", runtimeLabel(120))
    }

    @Test fun lengthIsExactOnceStarted() {
        assertEquals(9_300_000L, playLength(9_300_000, 152)) // an extended cut, as the player measured it
        assertEquals(152 * 60_000L, playLength(0, 152))
        assertEquals(152 * 60_000L, playLength(null, 152))
        assertNull(playLength(null, null))
        assertNull(playLength(0, 0))
    }

    @Test fun endsAtCountsFromWhereItStarts() {
        assertEquals(1_000 + 3_600_000L, endsAt(1_000, 3_600_000, 0))
        assertEquals(1_000 + 600_000L, endsAt(1_000, 3_600_000, 3_000_000))
        assertEquals(1_000L, endsAt(1_000, 3_600_000, 9_000_000))
        assertNull(endsAt(1_000, null, 0))
    }

    @Test fun theCollectionsOtherFilmsTheFamilyHasInReleaseOrderOnceEach() {
        fun film(id: String, tmdb: Int?, tv: Boolean = false) =
            DriveFile(id, "$id.mkv", "video/x-matroska", 0, 0, null, null, MediaInfo(id, null, tmdbId = tmdb, tv = tv))
        val fellowship = film("fellowship", 120)
        val towers = film("towers", 121)
        val towers4k = film("towers-4k", 121)
        val king = film("king", 122)
        val show = film("show", 120, tv = true) // a TV show's number is another list's
        val library = listOf(king, film("other", 500), towers, show, towers4k, film("unknown", null), fellowship)
        assertEquals(listOf(fellowship, towers), ownedParts(listOf(120, 121, 122), library, self = 122))
        assertEquals(emptyList<DriveFile>(), ownedParts(listOf(122), library, self = 122))
    }

    @Test fun episodeLabels() {
        assertEquals("S1 E3 · The Title", episodeCardLabel("Show.S01E03.The.Title.1080p.mkv"))
        assertEquals("S1 E5 · The Fifth", episodeCardLabel("1x05 - The Fifth.mp4"))
        assertEquals("Pilot", episodeCardLabel("Pilot.mkv"))
    }
}
