package com.woodruff.tvexplorer.ui.screens

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

    @Test fun upNextSaysWhichEpisode() {
        assertEquals("Next · S2 E5", upNextCaption("The Office S02E05.mkv"))
        assertEquals("Next · S1 E10", upNextCaption("Backyard Science 1x10"))
        assertEquals("Up next", upNextCaption("Pilot"))
    }
}
