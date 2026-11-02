package com.woodruff.tvexplorer.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Streaming's Continue watching row taking a tile off from its menu (title-access §4.2): the tile waits
 * for the focus to move off it, then goes for good; asking twice doesn't take it twice.
 */
class RowRemovalsTest {
    @Test fun aTileWaitsForTheFocusThenGoes() {
        val r = RowRemovals()
        r.remove("film")
        assertEquals(listOf("film"), r.pending.toList())
        assertTrue(r.gone.isEmpty())
        // The row moved the focus on: now it goes.
        r.done(listOf("film"))
        assertTrue(r.pending.isEmpty())
        assertEquals(listOf("film"), r.gone.toList())
    }

    @Test fun askingAgainChangesNothing() {
        val r = RowRemovals()
        r.remove("film")
        r.remove("film")
        assertEquals(listOf("film"), r.pending.toList())
        r.done(listOf("film"))
        r.remove("film")
        assertTrue(r.pending.isEmpty())
        r.done(listOf("film"))
        assertEquals(listOf("film"), r.gone.toList())
    }

    @Test fun severalGoTogetherInOrder() {
        val r = RowRemovals()
        r.remove("a")
        r.remove("b")
        r.done(r.pending.toList())
        assertEquals(listOf("a", "b"), r.gone.toList())
        assertTrue(r.pending.isEmpty())
    }
}
