package com.woodruff.tvexplorer.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.EpisodeShows
import com.woodruff.tvexplorer.cloud.FamilySync
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A show's Skip intro and its audio and subtitle choices, made before this release under its file's
 * name ("backyard science (2019)"), carry on when its title page now names its episodes from TMDB; and
 * Continue watching keeps one tile per show whichever way its episodes were named.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShowChoicesCarryOverTest {
    private val c get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = c.getSharedPreferences("player_shows", Context.MODE_PRIVATE)

    // "Backyard Science (2019)/Season 02/Backyard Science (2019) - S02E05 - The Volcano.mkv", started from its title page.
    private val titlePage = "Backyard Science S02E05 The Volcano"
    private val shows = mapOf("drive:e5" to "folder1")

    @Before fun fresh() {
        FamilySync.setEnabled(c, false)
        EpisodeShows.clear(c)
        ShowPrefs.forgetCarried()
    }

    @After fun reset() {
        EpisodeShows.clear(c)
        ShowPrefs.forgetCarried()
    }

    /** What the show's key ([ShowKeys.of]) is, with what an older key held carried over first, as the player does. */
    private fun keyFor(key: String, title: String, recent: List<RecentItem> = emptyList()): String {
        val show = ShowKeys.of(key, title, shows, ShowKeys.named(recent, shows))
        ShowPrefs.carryOver(c, show, ShowKeys.older(key, title, show, recent, shows), ShowKeys.name(title))
        return show
    }

    @Test fun choicesSavedBeforeThisReleaseCarryOver() {
        // As build 165 kept them: under the file's show, with its year.
        prefs.edit().putLong("i:backyard science (2019)", 95_000).putLong("@i:backyard science (2019)", 100)
            .putString("t:backyard science (2019)", ShowPrefs.OFF).putLong("@t:backyard science (2019)", 100).commit()
        val show = keyFor("drive:e5", titlePage)
        assertEquals("show:folder1", show)
        assertEquals(95_000L, ShowPrefs.introEnd(c, show))
        assertEquals(ShowPrefs.OFF, ShowPrefs.text(c, show))
        assertNull(ShowPrefs.audio(c, show))
        // With when they were made: the family's other TVs still take whichever is newest.
        assertEquals(100L, prefs.getLong("@i:show:folder1", -1))
    }

    @Test fun aShowsOwnChoiceIsntOverwritten() {
        prefs.edit().putLong("i:show:folder1", 30_000).putLong("@i:show:folder1", 500)
            .putLong("i:backyard science (2019)", 95_000).putLong("@i:backyard science (2019)", 100).commit()
        assertEquals(30_000L, ShowPrefs.introEnd(c, keyFor("drive:e5", titlePage)))
    }

    @Test fun theNewestOfTheOlderKeysWins() {
        // Subtitles turned on by a TV that didn't know the show's entry, after build 165 had them off.
        prefs.edit().putString("t:backyard science (2019)", ShowPrefs.OFF).putLong("@t:backyard science (2019)", 100)
            .putString("t:backyard science", "en").putLong("@t:backyard science", 200)
            // Skip intro forgotten there last: it stays forgotten.
            .putLong("i:backyard science (2019)", 95_000).putLong("@i:backyard science (2019)", 100)
            .putLong("@i:backyard science", 300).commit()
        val show = keyFor("drive:e5", titlePage)
        assertEquals("en", ShowPrefs.text(c, show))
        assertEquals(0L, ShowPrefs.introEnd(c, show))
    }

    @Test fun aRemakesChoicesArentTakenForItsOriginal() {
        prefs.edit().putLong("i:ducktales (1987)", 60_000).putLong("i:ducktales (2017)", 30_000).commit()
        val show = ShowKeys.of("drive:d1", "DuckTales S01E01 Woo-oo!", mapOf("drive:d1" to "folder2017"))
        ShowPrefs.carryOver(c, show, ShowKeys.older("drive:d1", "DuckTales S01E01 Woo-oo!", show, emptyList(), mapOf("drive:d1" to "folder2017")), "ducktales")
        assertEquals(0L, ShowPrefs.introEnd(c, show))
        // Its own file's name says which: that one carries over.
        val fromFile = keyFor("drive:e9", "DuckTales (2017) - S01E02 - Daytrip of Doom!")
        assertEquals(30_000L, ShowPrefs.introEnd(c, fromFile))
    }

    @Test fun continueWatchingShowsAShowOnceWhicheverWayItsEpisodesWereNamed() {
        val store = RecentStore(c)
        // Saved by build 165 (or a TV still on it) by the file's name; then started from the title page.
        store.save("drive:e4", "Backyard Science (2019) - S02E04 - The Rain", 600_000, 1_500_000)
        store.save("drive:e5", titlePage, 300_000, 1_500_000)
        store.save("drive:film", "Midnight Harbor (2024)", 900_000, 6_000_000)
        assertEquals(listOf("drive:film", "drive:e5"), store.continueWatching().map { it.key })
        // Taking the show off takes all of it, the older episode too.
        store.removeShow(store.all().first { it.key == "drive:e5" })
        assertEquals(listOf("drive:film"), store.all().map { it.key })
    }

    @Test fun aRemakeAndItsOriginalHaveATileEachWhenTheirEntriesAreKnown() {
        EpisodeShows.remember(c, mapOf("drive:n1" to "folder2017", "drive:o1" to "folder1987"))
        val store = RecentStore(c)
        store.save("drive:o1", "DuckTales (1987) - S01E01 - Treasure of the Golden Suns", 600_000, 1_500_000)
        store.save("drive:n1", "DuckTales S01E01 Woo-oo!", 600_000, 1_500_000)
        assertEquals(listOf("drive:n1", "drive:o1"), store.continueWatching().map { it.key })
    }
}
