package com.woodruff.tvexplorer.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A video in Continue watching found under no Movies / TV Shows entry isn't walked up its folders again for a week, after a restart too. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EpisodeShowsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val saved get() = context.getSharedPreferences("episode_unplaced", Context.MODE_PRIVATE)
    private val at = 1_800_000_000_000L

    @Before fun clean() = EpisodeShows.clear(context)

    @Test fun aVideoUnderNoEntryIsntWalkedAgainForAWeek() {
        EpisodeShows.rememberUnplaced(context, listOf("drive:clip", "drive:gone"), at)
        assertTrue(EpisodeShows.unplaced("drive:clip", at + 60_000))
        assertTrue(EpisodeShows.unplaced("drive:gone", at + EpisodeShows.UNPLACED_MS - 1))
        assertFalse(EpisodeShows.unplaced("drive:other", at))
        // A week on (it may have been moved into the library), or the TV's clock set back: walked again.
        assertFalse(EpisodeShows.unplaced("drive:clip", at + EpisodeShows.UNPLACED_MS))
        assertFalse(EpisodeShows.unplaced("drive:clip", at - 60_000))
        // Saved, so a restart (every update) doesn't walk it again.
        assertEquals(at, saved.getLong("drive:clip", 0))
    }

    @Test fun oldOnesGoAndSigningOutForgetsThemAll() {
        EpisodeShows.rememberUnplaced(context, listOf("drive:old"), at)
        EpisodeShows.rememberUnplaced(context, listOf("drive:new"), at + EpisodeShows.UNPLACED_MS)
        assertFalse(saved.contains("drive:old"))
        assertTrue(saved.contains("drive:new"))
        EpisodeShows.clear(context)
        assertFalse(EpisodeShows.unplaced("drive:new", at + EpisodeShows.UNPLACED_MS))
        assertFalse(saved.contains("drive:new"))
    }
}
