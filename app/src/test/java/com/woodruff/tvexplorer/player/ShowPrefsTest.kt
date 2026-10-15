package com.woodruff.tvexplorer.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Episodes of one show share a key, so audio/subtitle choices and Skip intro carry over. */
class ShowPrefsTest {
    @Test fun episodesOfAShowShareAKey() {
        val k = ShowPrefs.showKey("The Office S02E03")
        assertEquals("the office", k)
        assertEquals(k, ShowPrefs.showKey("The Office - S2 E4"))
        assertEquals(k, ShowPrefs.showKey("The.Office.s03e01.1080p.mkv"))
        assertEquals(k, ShowPrefs.showKey("The Office 2x05"))
    }

    @Test fun moviesKeepTheirTitle() {
        assertEquals("midnight harbor (2024)", ShowPrefs.showKey("Midnight Harbor (2024)"))
    }

    @Test fun episodesAreToldApartFromMovies() {
        listOf("The Office S02E03", "The Office - S2 E4", "The.Office.s03e01.1080p.mkv", "The Office 2x05", "Episode 7").forEach {
            assertEquals(it, true, ShowPrefs.isEpisode(it))
        }
        listOf("Midnight Harbor (2024)", "Movie.1920x1080.x264.mkv", "Step Up 2", "Deep 3").forEach {
            assertEquals(it, false, ShowPrefs.isEpisode(it))
        }
    }
}
