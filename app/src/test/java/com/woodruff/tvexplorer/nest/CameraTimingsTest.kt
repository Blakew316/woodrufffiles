package com.woodruff.tvexplorer.nest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The smoothness report's camera section: each start's steps, why one had no picture, and the run's totals. */
class CameraTimingsTest {
    private val time: (Long) -> String = { "7:42 PM" }

    @After fun forget() = CameraTimings.clear()

    @Test fun nothingWhenNoCameraStarted() {
        assertEquals("", timingsSection(emptyList(), 0, 0, 0, time))
        assertEquals("", CameraTimings.section(time))
    }

    @Test fun eachStartShowsItsStepsInMilliseconds() {
        val text = timingsSection(
            listOf(CameraTimings.Start("Front Door", 0, addressesMs = 118, addresses = AddressesEnd.PUBLIC, googleMs = 1_912, linkMs = 240, pictureMs = 410)),
            count = 1, stalls = 0, reconnects = 0, time = time,
        )
        assertTrue(text, text.contains("== Camera starts =="))
        assertTrue(text, text.contains("1 start · 0 stalls · 0 reconnects since Woodruff started."))
        assertTrue(text, text.contains("7:42 PM · Front Door\n addresses 118 (public) · Google 1,912 · link 240 · picture 410\n"))
    }

    @Test fun aStartWithoutAPictureSaysWhy() {
        val text = timingsSection(
            listOf(CameraTimings.Start("Backyard", 0, addressesMs = 3_000, addresses = AddressesEnd.LIMIT, googleMs = 980, outcome = "no link after 20 s")),
            count = 3, stalls = 1, reconnects = 2, time = time,
        )
        assertTrue(text, text.contains("3 starts · 1 stall · 2 reconnects"))
        assertTrue(text, text.contains("7:42 PM · Backyard\n addresses 3,000 (3 s limit) · Google 980 · no link after 20 s\n"))
    }

    @Test fun onlyTheLatestStartsAreKeptNewestFirst() {
        repeat(CameraTimings.KEPT + 3) { CameraTimings.add(CameraTimings.Start("Camera $it", 0, pictureMs = it.toLong())) }
        CameraTimings.stalled()
        CameraTimings.reconnected()
        val text = CameraTimings.section(time)
        val lines = text.lines().filter { it.startsWith("7:42 PM") }
        assertEquals(CameraTimings.KEPT, lines.size)
        assertEquals("7:42 PM · Camera ${CameraTimings.KEPT + 2}", lines.first())
        assertTrue(text, text.contains("${CameraTimings.KEPT + 3} starts · 1 stall · 1 reconnect "))
    }

    @Test fun aVeryLongCameraNameIsCut() {
        val text = timingsSection(listOf(CameraTimings.Start("x".repeat(90), 0, pictureMs = 5)), 1, 0, 0, time)
        assertTrue(text, text.contains("7:42 PM · ${"x".repeat(40)}\n picture 5\n"))
    }
}
