package com.woodruff.tvexplorer.photos

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

class PhotoFilterTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(y: Int, mo: Int, d: Int) = GregorianCalendar(utc).apply { clear(); set(y, mo - 1, d, 12, 0) }.timeInMillis

    private data class Item(val path: String, val folder: Boolean = false)
    private val items = listOf(Item("a.jpg"), Item("Trips", folder = true), Item("b.jpg"), Item("c.jpg"), Item("d.jpg"))
    private val dates = mapOf("a.jpg" to at(2023, 7, 4), "b.jpg" to at(2024, 1, 2), "c.jpg" to at(2024, 3, 9)) // d.jpg unknown

    private fun run(f: PhotoFilter) = PhotoFilter.apply(items, { it.path }, { it.folder }, dates, f, utc).map { it.path }

    @Test fun newestFirstKeepsFoldersOnTop() = assertEquals(listOf("Trips", "c.jpg", "b.jpg", "a.jpg", "d.jpg"), run(PhotoFilter()))
    @Test fun oldestFirst() = assertEquals(listOf("Trips", "d.jpg", "a.jpg", "b.jpg", "c.jpg"), run(PhotoFilter(newestFirst = false)))
    @Test fun oneYear() = assertEquals(listOf("Trips", "c.jpg", "b.jpg"), run(PhotoFilter(year = 2024)))
    @Test fun oneMonth() = assertEquals(listOf("Trips", "b.jpg"), run(PhotoFilter(year = 2024, month = 1)))

    @Test fun choices() {
        assertEquals(listOf(2024, 2023), PhotoFilter.years(dates.values, utc))
        assertEquals(listOf(1, 3), PhotoFilter.months(dates.values, 2024, utc))
    }
}
