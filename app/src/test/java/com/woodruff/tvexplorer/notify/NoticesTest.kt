package com.woodruff.tvexplorer.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticesTest {
    @Test fun aRelayedMessageShowsOnce() {
        val seen = RecentIds(10)
        assertTrue(seen.add("event-1"))
        assertFalse(seen.add("event-1"))
        assertTrue(seen.add("event-2"))
    }

    @Test fun messagesWithoutAnIdAlwaysShow() {
        val seen = RecentIds(10)
        assertTrue(seen.add(null))
        assertTrue(seen.add(""))
        assertTrue(seen.add(" "))
        assertTrue(seen.add(""))
    }

    @Test fun onlyTheLatestIdsAreRemembered() {
        val seen = RecentIds(2)
        seen.add("a"); seen.add("b"); seen.add("c")
        // "a" was forgotten to make room; "b" and "c" are still known.
        assertTrue(seen.add("a"))
        assertFalse(seen.add("c"))
    }

    @Test fun theDoorbellStaysUpLonger() {
        fun notice(chime: Chime.Sound?) = Notice(1, NoticeSource.SYSTEM, "Someone's at the door", "Front door", 0, chime = chime)
        assertEquals(25_000L, notice(Chime.Sound.DOORBELL).showMs)
        assertEquals(7_000L, notice(Chime.Sound.ANNOUNCE).showMs)
        assertEquals(7_000L, notice(null).showMs)
    }
}
