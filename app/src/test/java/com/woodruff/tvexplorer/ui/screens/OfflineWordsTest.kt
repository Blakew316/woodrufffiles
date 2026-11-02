package com.woodruff.tvexplorer.ui.screens

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.calendar.CalendarState
import com.woodruff.tvexplorer.calendar.DayEvent
import com.woodruff.tvexplorer.cloud.DriveStatus
import com.woodruff.tvexplorer.ui.components.OfflineWords
import com.woodruff.tvexplorer.ui.icons.Sym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the screens say when Google can't be reached: calm, true, and never "sign in again". */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OfflineWordsTest {
    @Test fun offlineOrReconnectingByTheNetwork() {
        assertEquals("Offline", OfflineWords.note(networkUp = false, "Google Drive"))
        assertEquals("Reconnecting to Google Drive…", OfflineWords.note(networkUp = true, "Google Drive"))
        assertEquals("You're offline", OfflineWords.title(networkUp = false, "Google Drive"))
        assertEquals("Your library will appear as soon as this TV is back online.", OfflineWords.body(false, "Your library", "Google Drive"))
        assertEquals("Your cameras will appear as soon as Google Home answers.", OfflineWords.body(true, "Your cameras", "Google Home"))
    }

    @Test fun theDriveHeaderSaysOfflineOverTheSavedLibrary() {
        assertNull(driveHeaderStatus(DriveStatus.Online, networkUp = true, hasFiles = true, library = true))
        val offline = driveHeaderStatus(DriveStatus.Offline, networkUp = false, hasFiles = true, library = true)!!
        assertSame(Sym.CloudOff, offline.icon)
        assertEquals("Offline", offline.title)
        assertEquals("Showing your saved library", offline.detail)
        // With a network it's moments from loading: no need to say more (the search field keeps its room).
        val reconnecting = driveHeaderStatus(DriveStatus.Offline, networkUp = true, hasFiles = true, library = true)!!
        assertEquals("Reconnecting to Google Drive…", reconnecting.title)
        assertNull(reconnecting.detail)
        assertEquals("Showing what was here last time", driveHeaderStatus(DriveStatus.Offline, networkUp = false, hasFiles = true, library = false)!!.detail)
        // Nothing on screen: the offline page says it, not the note as well.
        assertNull(driveHeaderStatus(DriveStatus.Offline, networkUp = false, hasFiles = false, library = true))
        // Keys to check is something to fix, not to wait out (and, with nothing on screen, the page says it).
        assertTrue(driveHeaderStatus(DriveStatus.Refused("Check the keys"), networkUp = true, hasFiles = true, library = true)!!.alert)
        assertNull(driveHeaderStatus(DriveStatus.Refused("Check the keys"), networkUp = true, hasFiles = false, library = true))
    }

    @Test fun theMorningBriefingNeverClaimsAnEmptyCalendarWhileOffline() {
        val event = DayEvent("Dentist", null, null, null, 0xFF5592BF)
        assertNull(calendarBriefing(CalendarState(connected = false)))
        assertNull(calendarBriefing(CalendarState(connected = true, offline = true)))
        assertEquals("nothing on the calendar", calendarBriefing(CalendarState(connected = true, loaded = true)))
        assertEquals("1 event today, first Dentist All day", calendarBriefing(CalendarState(connected = true, offline = true, events = listOf(event))))
    }
}
