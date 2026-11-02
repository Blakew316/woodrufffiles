package com.woodruff.tvexplorer.player

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the player keeps in its saved state (PlayerKeep): the queue around the item playing, which
 * item and where, playing or not; at most a hundred items, centred on the one playing.
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
}
