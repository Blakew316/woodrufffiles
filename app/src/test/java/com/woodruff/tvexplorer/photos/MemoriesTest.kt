package com.woodruff.tvexplorer.photos

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class MemoriesTest {
    private val zone = TimeZone.getTimeZone("America/Chicago")
    private fun at(y: Int, m: Int, d: Int) = GregorianCalendar(zone).apply { clear(); set(y, m, d, 14, 0) }.timeInMillis

    @Test fun onlySameDayInEarlierYearsOldestFirst() {
        val today = GregorianCalendar(zone).apply { clear(); set(2026, Calendar.OCTOBER, 15, 9, 0) }
        val items = listOf(
            "2024" to at(2024, Calendar.OCTOBER, 15),
            "this year" to at(2026, Calendar.OCTOBER, 15),
            "2019" to at(2019, Calendar.OCTOBER, 15),
            "other day" to at(2025, Calendar.OCTOBER, 14),
        )
        assertEquals(listOf("2019", "2024"), Memories.onThisDay(items, today))
    }
}
