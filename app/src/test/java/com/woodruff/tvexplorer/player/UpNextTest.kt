package com.woodruff.tvexplorer.player

import com.woodruff.tvexplorer.cloud.DriveFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What's offered after an episode: the next one queued, or the next season's first. */
class UpNextTest {
    private fun folder(id: String, name: String) = DriveFile(id, name, DriveFile.FOLDER_MIME, 0, 0, null)
    private fun video(id: String, name: String) = DriveFile(id, name, "video/x-matroska", 0, 0, null)

    @Test fun nextIsTheOneAfterTheFinishedEpisodesOwnPlace() {
        val queue = listOf("drive:e1", "drive:e2", "drive:e3")
        assertEquals(1, UpNext.indexAfter(queue, "drive:e1"))
        assertEquals(2, UpNext.indexAfter(queue, "drive:e2"))
        assertNull(UpNext.indexAfter(queue, "drive:e3")) // the season's last
        assertNull(UpNext.indexAfter(queue, "drive:elsewhere"))
    }

    private val show = listOf(
        folder("specials", "Specials"), folder("s2", "Season 2"), folder("s1", "Season 1"),
        folder("s10", "Season 10"), folder("bts", "Behind the scenes"), video("trailer", "Trailer.mp4"),
    )

    @Test fun theSeasonIsTheEpisodesFolderElseItsName() {
        assertEquals(2, UpNext.seasonOf(show, "s2", "Show S01E09.mkv")) // the folder it's in wins
        assertEquals(3, UpNext.seasonOf(show, "somewhere else", "Show S03E10.mkv"))
        assertNull(UpNext.seasonOf(show, "bts", "Pilot.mkv"))
    }

    @Test fun theNextSeasonIsTheLowestNumberAbove() {
        assertEquals("s2", UpNext.nextSeason(show, 1)?.id)
        assertEquals("s10", UpNext.nextSeason(show, 2)?.id) // numbers, not names: "Season 10" after "Season 2"
        assertNull(UpNext.nextSeason(show, 10))
        assertEquals("s1", UpNext.nextSeason(show, 0)?.id) // after the specials, season 1
    }

    @Test fun firstEpisodeByNumberNotByName() {
        val season = listOf(
            folder("extras", "Featurettes"), video("e10", "Show S02E10.mkv"),
            video("e2", "Show S02E02.mkv"), video("e1", "Show S02E01 - Pilot.mkv"),
        )
        assertEquals("e1", UpNext.firstEpisode(season, 2)?.id)
        // "Episode 3" style names: the season comes from the folder.
        assertEquals("w1", UpNext.firstEpisode(listOf(video("w3", "Episode 3.mp4"), video("w1", "Episode 1.mp4")), 4)?.id)
    }

    @Test fun noFirstEpisodeWhenNothingLooksLikeOne() {
        assertNull(UpNext.firstEpisode(listOf(video("b", "Behind the scenes.mp4"), folder("f", "S01E01")), 2))
        assertNull(UpNext.firstEpisode(emptyList(), 2))
    }
}
