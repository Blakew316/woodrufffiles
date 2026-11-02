package com.woodruff.tvexplorer.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Today's events when Google can't be reached: offline, not "sign-in expired", and never yesterday's. */
class CalendarOfflineTest {
    private val dentist = DayEvent("Dentist", 1_800_000_000_000, 1_800_003_600_000, null, 0xFF5592BF)

    @Test fun offlineKeepsTodaysEventsWithNoError() {
        val today = 2026_306
        val before = CalendarState(connected = true, events = listOf(dentist), loaded = true, error = "old error", day = today)
        val s = before.offlineOn(today)
        assertTrue(s.offline)
        assertTrue(s.connected)
        assertNull(s.error)
        assertEquals(listOf(dentist), s.events)
        assertTrue(s.loaded)
    }

    @Test fun offlineSaysWhyItCouldntGetThrough() {
        val today = 2026_306
        assertTrue(CalendarState(connected = true).offlineOn(today, noNetwork = true).noNetwork)
        // The network is fine and Google didn't answer: "Can't reach Google right now", not "Offline".
        assertEquals(false, CalendarState(connected = true, noNetwork = true).offlineOn(today, noNetwork = false).noNetwork)
    }

    @Test fun offlineSinceYesterdayShowsNoneOfYesterdays() {
        // The TV slept through the night; it wakes before its Wi-Fi.
        val s = CalendarState(connected = true, events = listOf(dentist), loaded = true, day = 2026_305).offlineOn(2026_306)
        assertTrue(s.offline)
        assertEquals(emptyList<DayEvent>(), s.events)
        assertEquals(2026_306, s.day)
    }

    @Test fun daysAreToldApart() {
        val a = Calendar.getInstance().apply { set(2026, Calendar.DECEMBER, 31, 23, 59) }
        val b = Calendar.getInstance().apply { set(2027, Calendar.JANUARY, 1, 0, 1) }
        val c = Calendar.getInstance().apply { set(2026, Calendar.DECEMBER, 31, 0, 1) }
        assertTrue(dayKey(a) != dayKey(b))
        assertEquals(dayKey(a), dayKey(c))
    }
}
