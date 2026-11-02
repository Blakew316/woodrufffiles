package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.player.RecentItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** A Continue watching tile resumes where the family is now, once the other TVs' progress is in. */
class ResumeLatestTest {
    private fun item(key: String, title: String, pos: Long = 600_000, upNext: Boolean = false) =
        RecentItem(key, title, pos, 2_400_000, 0, upNext)

    @Test fun aFilmPlaysItself() {
        val tile = item("drive:film", "Midnight Harbor (2024)", pos = 60_000)
        val merged = listOf(item("drive:film", "Midnight Harbor (2024)", pos = 2_400_000 / 2), item("drive:other", "Orbit (2021)"))
        assertEquals("drive:film", latestOf(tile, merged).key)
    }

    @Test fun aShowAnotherTvWentOnWithPlaysTheNextEpisode() {
        // This TV still shows S2 E4; the bedroom TV finished it, and S2 E5 is up next.
        val tile = item("drive:e4", "Backyard Science - S02E04.mkv")
        val merged = listOf(item("drive:e5", "Backyard Science - S02E05.mkv", pos = 0, upNext = true), item("drive:film", "Orbit (2021)"))
        assertEquals("drive:e5", latestOf(tile, merged).key)
    }

    @Test fun theNextEpisodeIsFoundWhicheverWayEachWasNamed() {
        // The tile was saved by its file's name (an older Woodruff); the other TV started the next
        // episode from its title page, which names it from TMDB, without the year.
        val tile = item("drive:e4", "Backyard Science (2019) - S02E04 - The Rain")
        val merged = listOf(item("drive:e5", "Backyard Science S02E05 The Volcano", pos = 0, upNext = true))
        assertEquals("drive:e5", latestOf(tile, merged).key)
    }

    @Test fun anotherShowsEpisodeIsntTaken() {
        val tile = item("drive:e4", "Backyard Science - S02E04.mkv")
        val merged = listOf(item("drive:k1", "Kitchen Rules - S01E01.mkv"))
        assertEquals(tile, latestOf(tile, merged))
    }

    @Test fun takenOffContinueWatchingElsewhereItPlaysAsAsked() {
        val tile = item("drive:film", "Paper Planes (2022)")
        assertEquals(tile, latestOf(tile, emptyList()))
    }
}
