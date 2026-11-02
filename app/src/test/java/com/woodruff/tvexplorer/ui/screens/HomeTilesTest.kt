package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.player.RecentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Home's camera tile that plays in "one at a time", and an up-next tile's caption. */
class HomeTilesTest {
    private val tiles = listOf("door", "yard", "garage", "drive")

    @Test fun theTileYoureOnPlays() = assertEquals("garage", rotatingTile(tiles, "garage", turn = 7))

    @Test fun otherwiseTheyTakeTurns() {
        assertEquals("door", rotatingTile(tiles, null, 0))
        assertEquals("yard", rotatingTile(tiles, null, 1))
        assertEquals("door", rotatingTile(tiles, null, 4))
        assertEquals("yard", rotatingTile(listOf("door", "yard"), null, 3))
    }

    @Test fun aCameraNoLongerShownDoesNotStopTheTurns() = assertEquals("yard", rotatingTile(tiles, "attic", 1))

    @Test fun noTilesNoneLive() = assertNull(rotatingTile(emptyList(), null, 3))

    @Test fun aTitleWithAResumeTileIsntRepeatedAmongTheAddedPosters() {
        val show = DriveFile("show", "Backyard Science (2019)", DriveFile.FOLDER_MIME, 0, 0, null)
        val other = DriveFile("other", "Kitchen Rules", DriveFile.FOLDER_MIME, 0, 0, null)
        val film = DriveFile("film", "Orbit (2021).mp4", "video/mp4", 0, 0, null)
        val added = listOf(DrivePlace.TvShows to show, DrivePlace.TvShows to other, DrivePlace.Movies to film)
        val resuming = listOf(
            RecentItem("drive:e5", "Backyard Science (2019) - S02E05.mkv", 0, 0, 0, upNext = true), // the show, by its name
            RecentItem("drive:film", "Orbit (2021).mp4", 600_000, 6_000_000, 0), // the film, by its own key
        )
        assertEquals(listOf("other"), notResuming(added, resuming, emptyMap()).map { it.second.id })
        // Known to sit under it, whatever its file is called.
        assertEquals(listOf("show", "film"), notResuming(added, listOf(RecentItem("drive:x", "S01E013_Pilot.mkv", 1, 2, 0)), mapOf("drive:x" to "other")).map { it.second.id })
        // Nothing in progress: all of them.
        assertEquals(added, notResuming(added, emptyList(), emptyMap()))
    }

    @Test fun upNextSaysWhichEpisode() {
        assertEquals("Next · S2 E5", upNextCaption("The Office S02E05.mkv"))
        assertEquals("Next · S1 E10", upNextCaption("Backyard Science 1x10"))
        assertEquals("Up next", upNextCaption("Pilot"))
    }
}
