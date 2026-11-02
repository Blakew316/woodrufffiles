package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** "Updated today at 3:12 AM" in Settings for a day after an update, instead of a notice. */
class UpdatedLatelyTest {
    private val zone = TimeZone.getTimeZone("America/Chicago")
    private val time = SimpleDateFormat("h:mm a", Locale.US)
    private val installed = at(2026, Calendar.OCTOBER, 12, 9, 10)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply { clear(); set(year, month, day, hour, minute) }.timeInMillis

    private fun lately(updated: Long, now: Long, short: Boolean = false) = updatedLately(installed, updated, now, time, short, zone)

    @Test fun todayThenYesterdayThenNothing() {
        val updated = at(2026, Calendar.NOVEMBER, 2, 3, 12)
        assertEquals("Updated today at 3:12 AM", lately(updated, at(2026, Calendar.NOVEMBER, 2, 21, 0)))
        assertEquals("Updated today", lately(updated, at(2026, Calendar.NOVEMBER, 2, 21, 0), short = true))
        assertEquals("Updated yesterday at 3:12 AM", lately(updated, at(2026, Calendar.NOVEMBER, 3, 3, 11)))
        assertEquals("Updated yesterday", lately(updated, at(2026, Calendar.NOVEMBER, 3, 3, 11), short = true))
        // A day on: Settings says what it said before ("Up to date").
        assertNull(lately(updated, at(2026, Calendar.NOVEMBER, 3, 3, 12)))
    }

    @Test fun lateAtNightIsYesterdayTheNextMorning() {
        val updated = at(2026, Calendar.NOVEMBER, 1, 23, 40)
        assertEquals("Updated yesterday at 11:40 PM", lately(updated, at(2026, Calendar.NOVEMBER, 2, 8, 0)))
    }

    @Test fun aFreshInstallOrATimeAheadOfTheClockSaysNothing() {
        // Installed fresh (not updated): Android gives the same time for both.
        assertNull(updatedLately(installed, installed, installed + 60_000, time, zone = zone))
        // The TV's clock went back after the update.
        val updated = at(2026, Calendar.NOVEMBER, 2, 3, 12)
        assertNull(lately(updated, updated - 60_000))
    }

    @Test fun newsSinceTheUpdateComesFirst() {
        val updated = "Updated today at 3:12 AM"
        assertEquals(updated, versionStatus(Updater.State(Updater.Step.UP_TO_DATE, 172), updated))
        assertEquals(updated, versionStatus(Updater.State(Updater.Step.IDLE), updated))
        assertEquals("Checking…", versionStatus(Updater.State(Updater.Step.CHECKING), updated))
        assertEquals("2.0.174 ready · Install", versionStatus(Updater.State(Updater.Step.READY, 174), updated))
        // Waiting for the test TV's day with a newer one: that's newer news.
        assertEquals("2.0.174 arrives in about 9 h (after the test TV)",
            versionStatus(Updater.State(Updater.Step.UP_TO_DATE, 174, message = "2.0.174 arrives in about 9 h (after the test TV)"), updated))
        assertEquals("Up to date", versionStatus(Updater.State(Updater.Step.UP_TO_DATE, 172), null))
    }
}
