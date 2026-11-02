package com.woodruff.tvexplorer.player

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the player keeps in its saved state (PlayerKeep): the queue around the item playing, which
 * item and where, playing or not, and when it stopped; at most a hundred items, centred on the one
 * playing. And how a film comes back: at its spot, unless the family has moved on with it since.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerKeepTest {
    private fun queue(n: Int) = (0 until n).map { "drive:file$it" } to (0 until n).map { "Show S01E%02d".format(it + 1) }

    @Test
    fun aSeasonIsKeptWhole() {
        val (keys, titles) = queue(24)
        val b = Bundle().also { PlayerKeep.save(it, keys, titles, index = 2, positionMs = 754_000, playing = false) }
        assertEquals(PlayerKeep.Kept(keys, titles, 2, 754_000, false), PlayerKeep.restore(b))
    }

    @Test
    fun aLongQueueIsKeptAroundTheItemPlaying() {
        assertEquals(0 until 100, PlayerKeep.window(size = 500, index = 10))
        assertEquals(250 until 350, PlayerKeep.window(size = 500, index = 300))
        assertEquals(400 until 500, PlayerKeep.window(size = 500, index = 490))
        val (keys, titles) = queue(500)
        val kept = PlayerKeep.restore(Bundle().also { PlayerKeep.save(it, keys, titles, index = 300, positionMs = 5_000, playing = true) })!!
        assertEquals(PlayerKeep.MAX, kept.keys.size)
        // Still the same item, at the same spot.
        assertEquals("drive:file300", kept.keys[kept.index])
        assertEquals("Show S01E301", kept.titles[kept.index])
        assertEquals(5_000L, kept.positionMs)
    }

    @Test
    fun missingTitlesAndOddNumbersDoNoHarm() {
        val b = Bundle().also { PlayerKeep.save(it, listOf("a", "b"), listOf("A"), index = 7, positionMs = -40, playing = true) }
        assertEquals(PlayerKeep.Kept(listOf("a", "b"), listOf("A", ""), 1, 0, true), PlayerKeep.restore(b))
    }

    @Test
    fun aFirstOpeningKeptNothing() {
        assertNull(PlayerKeep.restore(null))
        assertNull(PlayerKeep.restore(Bundle()))
        assertNull(PlayerKeep.restore(Bundle().also { PlayerKeep.save(it, emptyList(), emptyList(), 0, 0, true) }))
    }

    @Test
    fun whenItStoppedIsKeptToo() {
        val (keys, titles) = queue(3)
        val b = Bundle().also { PlayerKeep.save(it, keys, titles, index = 1, positionMs = 1_800_000, playing = false, stoppedAt = 1_700_000_000_000) }
        assertEquals(1_700_000_000_000L, PlayerKeep.restore(b)?.stoppedAt)
        // Kept by an older version: not known.
        assertNull(PlayerKeep.restore(Bundle().also { PlayerKeep.save(it, keys, titles, 1, 0, true) })?.stoppedAt)
    }

    // ---- Coming back ---------------------------------------------------------------------------

    private val stopped = 1_000_000L
    private fun entry(at: Long, positionMs: Long = 3_600_000, upNext: Boolean = false) =
        RecentItem("drive:x", "Midnight Harbor (2024)", positionMs, 7_200_000, at, upNext)

    @Test
    fun withNothingNewerItComesBackAsItWas() {
        // Its own save as it stopped is never newer than the stop.
        assertEquals(PlayerKeep.ComeBack.AsItWas, PlayerKeep.comeBack("drive:x", stopped, listOf(entry(at = stopped)), emptyMap()))
        assertEquals(PlayerKeep.ComeBack.AsItWas, PlayerKeep.comeBack("drive:x", stopped, emptyList(), mapOf("drive:x" to stopped - 1)))
        assertEquals(PlayerKeep.ComeBack.AsItWas, PlayerKeep.comeBack("drive:x", stopped, listOf(entry(at = stopped + 5).copy(key = "drive:other")), emptyMap()))
        // Stopped by an older version: not known when, so as it was.
        assertEquals(PlayerKeep.ComeBack.AsItWas, PlayerKeep.comeBack("drive:x", null, listOf(entry(at = stopped + 5)), emptyMap()))
    }

    @Test
    fun watchedOnAnotherTvSinceItComesBackWhereTheFamilyGotTo() {
        // Paused here at 30:00; watched on to 60:00 in the bedroom since.
        assertEquals(PlayerKeep.ComeBack.At(3_600_000), PlayerKeep.comeBack("drive:x", stopped, listOf(entry(at = stopped + 1)), emptyMap()))
        // Rewound there: newest still wins, as on every TV.
        assertEquals(PlayerKeep.ComeBack.At(600_000), PlayerKeep.comeBack("drive:x", stopped, listOf(entry(at = stopped + 1, positionMs = 600_000)), emptyMap()))
        // Offered there as the next episode only: no spot to go by, so its own.
        assertEquals(PlayerKeep.ComeBack.AsItWas, PlayerKeep.comeBack("drive:x", stopped, listOf(entry(at = stopped + 1, positionMs = 0, upNext = true)), emptyMap()))
    }

    @Test
    fun finishedOnAnotherTvSinceItStaysAtItsSpotPaused() {
        assertEquals(PlayerKeep.ComeBack.Finished, PlayerKeep.comeBack("drive:x", stopped, emptyList(), mapOf("drive:x" to stopped + 1)))
        // Into its credits there.
        assertEquals(PlayerKeep.ComeBack.Finished, PlayerKeep.comeBack("drive:x", stopped, listOf(entry(at = stopped + 1, positionMs = 7_000_000)), emptyMap()))
        // Finished, then started again there: the newer of the two wins.
        assertEquals(PlayerKeep.ComeBack.At(3_600_000), PlayerKeep.comeBack("drive:x", stopped, listOf(entry(at = stopped + 9)), mapOf("drive:x" to stopped + 2)))
        assertEquals(PlayerKeep.ComeBack.Finished, PlayerKeep.comeBack("drive:x", stopped, listOf(entry(at = stopped + 2)), mapOf("drive:x" to stopped + 9)))
    }

    @Test
    fun onlyAFilmPlayedOrMovedSinceItStartedHasAnythingToKeep() {
        val start = "drive:x" to 1_800_000L
        // Opened at 30:00 and left again, paused: nothing done with it.
        assertFalse(PlayerKeep.touched(played = false, start = start, key = "drive:x", positionMs = 1_800_000))
        // Landing on a clean frame just after the spot isn't moving it.
        assertFalse(PlayerKeep.touched(false, start, "drive:x", 1_801_400))
        assertTrue(PlayerKeep.touched(played = true, start = start, key = "drive:x", positionMs = 1_800_000))
        // Scrubbed to another spot, paused: that's where they chose.
        assertTrue(PlayerKeep.touched(false, start, "drive:x", 1_810_000))
        assertTrue(PlayerKeep.touched(false, start, "drive:x", 1_790_000))
        // Another item (Previous or Next).
        assertTrue(PlayerKeep.touched(false, start, "drive:y", 1_800_000))
        // Not known where it started: kept, as before.
        assertTrue(PlayerKeep.touched(false, null, "drive:x", 1_800_000))
    }
}
