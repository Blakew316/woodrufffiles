package com.woodruff.tvexplorer.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/** Drive's times (RFC 3339, UTC), read with one parser per listing. */
class DriveTimesTest {
    @Test fun readsDrivesTimes() {
        val times = DriveApi.Rfc3339()
        assertEquals(1_760_402_903_123L, times.parse("2025-10-14T00:48:23.123Z"))
        assertEquals(0L, times.parse("1970-01-01T00:00:00.000Z"))
        // The same parser, again and again, as a listing uses it.
        assertEquals(951_782_400_000L, times.parse("2000-02-29T00:00:00.000Z"))
        assertEquals(1_760_402_903_123L, times.parse("2025-10-14T00:48:23.123Z"))
    }

    @Test fun aTimeItCantReadIsZero() {
        val times = DriveApi.Rfc3339()
        assertEquals(0L, times.parse(""))
        assertEquals(0L, times.parse("yesterday"))
    }
}
