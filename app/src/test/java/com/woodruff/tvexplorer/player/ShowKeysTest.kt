package com.woodruff.tvexplorer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One show, one key, however its episodes were named: the title page's TMDB names, the files' own
 * Plex-style names (which the player queues the rest of a folder and the next season by) and the
 * titles an older Woodruff saved.
 */
class ShowKeysTest {
    // "Backyard Science (2019)/Season 02/Backyard Science (2019) - S02E05 - The Volcano.mkv"
    private val titlePage = "Backyard Science S02E05 The Volcano"
    private val queued = "Backyard Science (2019) - S02E06 - The Lava"
    private val saved165 = "Backyard Science (2019) - S02E04 - The Rain"

    private fun item(key: String, title: String) = RecentItem(key, title, 600_000, 1_500_000, 0)

    @Test fun theTitlePagesNameAndTheFilesNameAreOneShow() {
        assertEquals("backyard science", ShowKeys.name(titlePage))
        assertEquals("backyard science", ShowKeys.name(queued))
        assertEquals("backyard science", ShowKeys.name("Backyard.Science.2019.S02E07.1080p.WEB-DL"))
        // Its entry known for one of them only (the title page remembered it): still one show.
        val shows = mapOf("drive:e5" to "folder1")
        assertTrue(ShowKeys.same("drive:e5", titlePage, "drive:e6", queued, shows))
        assertTrue(ShowKeys.same("drive:e5", titlePage, "drive:e6", queued, emptyMap()))
        assertTrue(ShowKeys.same("drive:e6", queued, "drive:e5", titlePage, shows))
    }

    @Test fun choicesAreKeptUnderTheShowsEntryWhenItsKnownElseItsName() {
        val shows = mapOf("drive:e5" to "folder1", "drive:e6" to "folder1")
        assertEquals("show:folder1", ShowKeys.of("drive:e5", titlePage, shows))
        assertEquals("show:folder1", ShowKeys.of("drive:e6", queued, shows))
        assertEquals("backyard science", ShowKeys.of("drive:e5", titlePage, emptyMap()))
        assertEquals("backyard science", ShowKeys.of("drive:e6", queued, emptyMap()))
        // Not known for this one, but for another episode with its name (saved by another TV, say).
        val named = ShowKeys.named(listOf(item("drive:e5", titlePage)), mapOf("drive:e5" to "folder1"))
        assertEquals("show:folder1", ShowKeys.of("drive:e7", queued, mapOf("drive:e5" to "folder1"), named))
    }

    @Test fun aRemakeAndItsOriginalStayApartWhenTheirEntriesAreKnown() {
        val newer = "DuckTales S01E01 Woo-oo!"
        val older = "DuckTales (1987) - S01E01 - Treasure of the Golden Suns"
        val shows = mapOf("drive:n1" to "folder2017", "drive:o1" to "folder1987")
        assertFalse(ShowKeys.same("drive:n1", newer, "drive:o1", older, shows))
        assertNotEquals(ShowKeys.of("drive:n1", newer, shows), ShowKeys.of("drive:o1", older, shows))
        val both = listOf(item("drive:n1", newer), item("drive:o1", older))
        assertEquals(2, ShowKeys.groups(both, shows).distinct().size)
        // A name two entries share lends neither to an episode that isn't known.
        assertNull(ShowKeys.named(both, shows)["ducktales"])
    }

    @Test fun continueWatchingCountsOneShowOnce() {
        val shows = mapOf("drive:e5" to "folder1")
        val items = listOf(item("drive:e5", titlePage), item("drive:e4", saved165), item("drive:film", "Midnight Harbor (2024)"))
        val groups = ShowKeys.groups(items, shows)
        assertEquals(groups[0], groups[1])
        assertEquals("drive:film", groups[2])
        // Nothing known: by name, still one show.
        val byName = ShowKeys.groups(items, emptyMap())
        assertEquals(byName[0], byName[1])
    }

    @Test fun filmsAndNamelessEpisodesKeepTheirKeys() {
        // A film is started by its file's name every way: its key stays what it was (with its year).
        assertEquals("midnight harbor (2024)", ShowKeys.of("drive:f", "Midnight Harbor (2024)", mapOf("drive:f" to "filmFolder")))
        assertEquals("show:folder9", ShowKeys.of("drive:x", "S01E13_Pilot", mapOf("drive:x" to "folder9")))
        assertEquals(ShowPrefs.showKey("S01E13_Pilot"), ShowKeys.of("drive:x", "S01E13_Pilot", emptyMap()))
        assertNull(ShowKeys.name("S01E13_Pilot"))
        assertNull(ShowKeys.name("Midnight Harbor (2024)"))
        assertEquals(listOf("show:folder9", "drive:y"), ShowKeys.groups(listOf(item("drive:x", "S01E13_Pilot"), item("drive:y", "S01E14_Second")), mapOf("drive:x" to "folder9")))
    }

    @Test fun anOlderWoodruffsKeyIsLookedForWithTheYear() {
        val shows = mapOf("drive:e5" to "folder1", "drive:e4" to "folder1")
        val older = ShowKeys.older("drive:e5", titlePage, "show:folder1", listOf(item("drive:e4", saved165)), shows)
        assertEquals(listOf("backyard science", "backyard science (2019)"), older)
        // From a file's name, its own old key comes straight away.
        assertTrue("backyard science (2019)" in ShowKeys.older("drive:e6", queued, "show:folder1", emptyList(), shows))
    }
}
