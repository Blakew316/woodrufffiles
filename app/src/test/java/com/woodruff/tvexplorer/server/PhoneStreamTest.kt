package com.woodruff.tvexplorer.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneStreamTest {
    @Test fun wholeRangesAndOpenEnds() {
        assertEquals(0L..99L, PhoneStream.parseRange("bytes=0-", 100))
        assertEquals(0L..1L, PhoneStream.parseRange("bytes=0-1", 100))
        assertEquals(50L..99L, PhoneStream.parseRange("bytes=50-500", 100))
        assertEquals(90L..99L, PhoneStream.parseRange("bytes=-10", 100))
        assertEquals(0L..99L, PhoneStream.parseRange("bytes=-500", 100))
    }

    @Test fun nothingUsable() {
        assertNull(PhoneStream.parseRange(null, 100))
        assertNull(PhoneStream.parseRange("bytes=100-", 100))
        assertNull(PhoneStream.parseRange("bytes=5-2", 100))
        assertNull(PhoneStream.parseRange("items=0-1", 100))
        assertNull(PhoneStream.parseRange("bytes=x-", 100))
        assertNull(PhoneStream.parseRange("bytes=0-", 0))
    }
}
