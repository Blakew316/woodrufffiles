package com.woodruff.tvexplorer.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back to the top's numbers (browsing-motion §3.3, §6): the glide's jump and the double press. */
class BackToTopRulesTest {
    @Test
    fun nearTheTopItGlidesAllTheWay() {
        for (line in 0..8) assertNull("line $line", BackToTop.jumpFirst(line))
    }

    @Test
    fun farDownItJumpsToTheThirdLineFirst() {
        assertEquals(3, BackToTop.jumpFirst(9))
        assertEquals(3, BackToTop.jumpFirst(75))
    }

    @Test
    fun aSecondBackWithin400msIsADoublePress() {
        assertTrue(BackToTop.isDoublePress(last = 10_000, now = 10_000))
        assertTrue(BackToTop.isDoublePress(last = 10_000, now = 10_399))
        assertFalse(BackToTop.isDoublePress(last = 10_000, now = 10_400))
        assertFalse(BackToTop.isDoublePress(last = 10_000, now = 12_000))
        // No Back that went to the top lately, or a clock that went back: not a double press.
        assertFalse(BackToTop.isDoublePress(last = null, now = 100))
        assertTrue("a clock that starts at 0 still counts", BackToTop.isDoublePress(last = 0, now = 100))
        assertFalse(BackToTop.isDoublePress(last = 10_000, now = 9_000))
    }
}
