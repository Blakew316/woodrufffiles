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

    @Test fun aDayThatsOverTakesItsEventsWithItWhileNothingIsFetched() {
        // Woodruff wasn't on screen at midnight, so the new day's events weren't fetched: none of yesterday's show.
        val yesterday = CalendarState(connected = true, events = listOf(dentist), loaded = true, day = 2026_305)
        val s = yesterday.on(2026_306)
        assertEquals(emptyList<DayEvent>(), s.events)
        // Not known yet, so nothing is claimed ("nothing on the calendar" would be wrong).
        assertEquals(false, s.loaded)
        assertTrue(s.connected)
        // Today's stay as they are, and so does a calendar not loaded yet.
        val today = CalendarState(connected = true, events = listOf(dentist), loaded = true, day = 2026_306)
        assertEquals(today, today.on(2026_306))
        assertEquals(CalendarState(connected = true), CalendarState(connected = true).on(2026_306))
    }

    @Test fun oneCalendarGoogleDoesntAnswerFailsTheRefreshRatherThanEmptyingTheDay() {
        // Google busy, failing or over its limits: like a network failure, today's events stay and it's tried again soon.
        for (code in listOf(408, 429, 500, 503)) {
            val e = TodayCalendar.failure(code, null)
            assertTrue("$code", e is TodayCalendar.Unreachable && com.woodruff.tvexplorer.util.isNetworkFailure(e))
        }
        for (reason in listOf("rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded", "quotaExceeded")) {
            assertTrue(reason, TodayCalendar.failure(403, reason) is TodayCalendar.Unreachable)
        }
        // The token not taken: renewed once, then tried again.
        assertTrue(TodayCalendar.failure(401, null) is TodayCalendar.Unauthorized)
        // A calendar gone, or not allowed: a refusal (that calendar alone is left out), in Woodruff's own words.
        val gone = TodayCalendar.failure(404, "notFound")
        assertTrue(gone is TodayCalendar.Refused && !com.woodruff.tvexplorer.util.isNetworkFailure(gone))
        assertEquals("Can’t reach Google Calendar right now.", gone.message)
        assertTrue(TodayCalendar.failure(403, "accessNotConfigured").message!!.startsWith("Google didn’t allow Google Calendar."))
    }

    @Test fun theListOfCalendarsIsKeptForAFewHours() {
        val at = 1_800_000_000_000L
        assertTrue(TodayCalendar.calendarsFresh(at, at + TodayCalendar.CALENDARS_MS - 1))
        assertEquals(false, TodayCalendar.calendarsFresh(at, at + TodayCalendar.CALENDARS_MS))
        // The TV's clock set back: listed again, not kept for ever.
        assertEquals(false, TodayCalendar.calendarsFresh(at, at - 60_000))
    }
}

