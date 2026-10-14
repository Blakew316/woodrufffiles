package com.woodruff.tvexplorer.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

class MediaDatesTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0, s: Int = 0) =
        GregorianCalendar(utc).apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis

    @Test fun cameraNames() {
        assertEquals(at(2024, 5, 12, 14, 3, 22), MediaDates.fromName("PXL_20240512_140322123.jpg", utc))
        assertEquals(at(2023, 1, 2, 9, 8, 7), MediaDates.fromName("IMG_20230102_090807.jpg", utc))
        assertEquals(at(2022, 7, 4, 18, 30, 0), MediaDates.fromName("Screenshot_2022-07-04-18-30-00.png", utc))
        assertEquals(at(2021, 12, 25, 8, 15, 0), MediaDates.fromName("2021-12-25 08.15.00.jpg", utc))
        assertEquals(at(2024, 3, 9), MediaDates.fromName("IMG-20240309-WA0012.jpg", utc))
        assertEquals(at(2020, 6, 1), MediaDates.fromName("Trip 2020-06-01.mp4", utc))
    }

    @Test fun rejectsNonDates() {
        assertNull(MediaDates.fromName("IMG_1234.jpg", utc))
        assertNull(MediaDates.fromName("photo_20241399.jpg", utc)) // month 13
        assertNull(MediaDates.fromName("20230230.jpg", utc)) // Feb 30
        assertNull(MediaDates.fromName("12345678901.jpg", utc))
    }

    @Test fun exifAndVideo() {
        assertEquals(at(2024, 5, 12, 14, 3, 22), MediaDates.parseExif("2024:05:12 14:03:22", utc))
        assertNull(MediaDates.parseExif("0000:00:00 00:00:00", utc))
        assertEquals(at(2024, 5, 12, 14, 3, 22), MediaDates.parseVideoDate("20240512T140322.000Z"))
        assertNull(MediaDates.parseVideoDate("19040101T000000.000Z")) // "unknown" on many phones
    }

    @Test fun googleTimes() {
        assertEquals(at(2024, 5, 12, 14, 3, 22), MediaDates.parseRfc3339("2024-05-12T14:03:22Z"))
        assertEquals(at(2024, 5, 12, 14, 3, 22) + 123, MediaDates.parseRfc3339("2024-05-12T14:03:22.123456Z"))
        assertEquals(at(2024, 5, 12, 12, 3, 22), MediaDates.parseRfc3339("2024-05-12T14:03:22+02:00"))
        assertNull(MediaDates.parseRfc3339(""))
    }
}
