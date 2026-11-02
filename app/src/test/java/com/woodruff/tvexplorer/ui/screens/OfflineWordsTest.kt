package com.woodruff.tvexplorer.ui.screens

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.calendar.CalendarState
import com.woodruff.tvexplorer.calendar.DayEvent
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveStatus
import com.woodruff.tvexplorer.cloud.GoogleAuth.Grant
import com.woodruff.tvexplorer.cloud.GoogleAuth.SignIn
import com.woodruff.tvexplorer.cloud.needsAttention
import com.woodruff.tvexplorer.cloud.signInWords
import com.woodruff.tvexplorer.nest.CameraStream
import com.woodruff.tvexplorer.ui.components.FieldStatus
import com.woodruff.tvexplorer.ui.components.OfflineWords
import com.woodruff.tvexplorer.ui.icons.Sym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the screens say when Google can't be reached, exactly as the offline-states spec words it:
 * calm, true, never red, never "sign in again" for a network failure; and what they say when a
 * sign-in really ended or Google refuses the keys.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OfflineWordsTest {
    private val t0 = 1_800_000_000_000L
    private val offline = DriveStatus.Offline(since = t0, google = false, shown = true)
    private val unreachable = DriveStatus.Offline(since = t0, google = true, shown = true)

    @Test fun theVocabulary() {
        assertEquals("Offline", OfflineWords.full(google = false))
        assertEquals("Can’t reach Google right now", OfflineWords.full(google = true))
        assertEquals("Offline", OfflineWords.short(google = false))
        assertEquals("Can’t reach Google", OfflineWords.short(google = true))
        assertEquals("Reconnecting…", OfflineWords.RECONNECTING)
        assertEquals("Back online", OfflineWords.BACK_ONLINE)
    }

    @Test fun theSearchFieldCarriesTheStateOverTheSavedLibrary() {
        assertEquals(FieldStatus(Sym.CloudOff, "Offline"), libraryFieldStatus(offline, hasFiles = true))
        assertEquals(FieldStatus(Sym.CloudOff, "Can’t reach Google"), libraryFieldStatus(unreachable, hasFiles = true))
        assertEquals(FieldStatus(Sym.CloudOff, "Reconnecting…"), libraryFieldStatus(DriveStatus.Reconnecting(offline), hasFiles = true))
        assertEquals(FieldStatus(Sym.Check, "Back online", good = true), libraryFieldStatus(DriveStatus.BackOnline(t0), hasFiles = true))
        // Within the grace: nothing said.
        assertNull(libraryFieldStatus(DriveStatus.Offline(since = t0, google = false), hasFiles = true))
        assertNull(libraryFieldStatus(DriveStatus.Online, hasFiles = true))
        // Keys refused: the page says it, not the field.
        assertNull(libraryFieldStatus(DriveStatus.Refused("x"), hasFiles = true))
        // Nothing saved: the page says it.
        assertNull(libraryFieldStatus(offline, hasFiles = false))
    }

    @Test fun theHintSaysWhatIsSearched() {
        // The field opens Search, which looks in both libraries, from either.
        assertEquals("Search Movies & TV Shows", librarySearchHint("Movies", DriveStatus.Online, hasFiles = true))
        assertEquals("Search your saved library", librarySearchHint("Movies", offline, hasFiles = true))
        assertEquals("Search your saved library", librarySearchHint("TV Shows", DriveStatus.Reconnecting(unreachable), hasFiles = true))
        // "Back online" for its 2 s, then the hint returns with the words gone.
        assertEquals("Search your saved library", librarySearchHint("Movies", DriveStatus.BackOnline(t0), hasFiles = true))
        assertEquals("Search Movies & TV Shows", librarySearchHint("Movies", DriveStatus.Offline(since = t0, google = false), hasFiles = true))
        assertEquals("Search Movies & TV Shows", librarySearchHint("TV Shows", offline, hasFiles = false))
    }

    @Test fun theOfflinePageWhenNothingIsSaved() {
        assertEquals(LibraryPage.Offline(google = false, trying = false), libraryPage(offline, hasFiles = false))
        assertEquals(LibraryPage.Offline(google = true, trying = true), libraryPage(DriveStatus.Reconnecting(unreachable), hasFiles = false))
        assertNull(libraryPage(offline, hasFiles = true))
        // Within the grace "Loading" goes on.
        assertNull(libraryPage(DriveStatus.Offline(since = t0, google = false), hasFiles = false))
        assertNull(libraryPage(DriveStatus.BackOnline(t0), hasFiles = false))
        // Keys refused: even over a saved library.
        assertEquals(LibraryPage.Keys, libraryPage(DriveStatus.Refused("x"), hasFiles = true))
        assertEquals(LibraryPage.Keys, libraryPage(DriveStatus.Refused("x"), hasFiles = false))

        val off = libraryPageWords(LibraryPage.Offline(google = false, trying = false), "Movies")
        assertEquals("This TV is offline", off.title)
        assertEquals("Movies will show here as soon as the TV is back online.", off.body)
        assertEquals("Try again", off.action)
        assertEquals(Sym.CloudOff, off.icon)
        assertEquals(Sym.Refresh, off.actionIcon)
        val google = libraryPageWords(LibraryPage.Offline(google = true, trying = false), "TV shows")
        assertEquals("Can’t reach Google Drive right now", google.title)
        assertEquals("Trying again by itself. TV shows will show here as soon as Google answers.", google.body)
        // Reconnecting: the headline and line stay, the button says it's trying.
        val trying = libraryPageWords(LibraryPage.Offline(google = true, trying = true), "TV shows")
        assertEquals(google.title, trying.title)
        assertEquals(google.body, trying.body)
        assertEquals("Trying…", trying.action)
        val keys = libraryPageWords(LibraryPage.Keys, "Movies")
        assertEquals(Sym.Key, keys.icon)
        assertEquals("Check the Google keys", keys.title)
        assertEquals(
            "Google doesn’t accept the Client ID or secret saved on this TV. Fix them in the phone app (Settings → Connections → Google Drive) or here, then sign in again.",
            keys.body,
        )
        assertEquals("Open Keys", keys.action)
        assertEquals(Sym.Key, keys.actionIcon)
    }

    @Test fun whatThePageSaysWillShow() {
        assertEquals("Movies", libraryPageSubject(DrivePlace.Movies, top = true))
        assertEquals("TV shows", libraryPageSubject(DrivePlace.TvShows, top = true))
        assertEquals("This folder", libraryPageSubject(DrivePlace.Folder("s1", "Season 1"), top = false))
        assertEquals("This folder", libraryPageSubject(DrivePlace.MyDrive, top = false))
    }

    @Test fun theCalendarRow() {
        val now = 1_800_000_000_000L
        val later = DayEvent("Dentist", now + 3_600_000, now + 7_200_000, null, 0xFF5592BF)
        val over = DayEvent("School run", now - 7_200_000, now - 3_600_000, null, 0xFF5592BF)
        // Not connected (and not ended): no row.
        assertNull(calendarGlance(CalendarState(), now, offlineShown = false))
        // Online, as today.
        assertEquals("Dentist", calendarGlance(CalendarState(connected = true, loaded = true, events = listOf(later)), now, false)!!.title)
        assertEquals(CalendarGlance("Nothing else today", "Calendar"), calendarGlance(CalendarState(connected = true, loaded = true), now, false))
        // Offline with today's events: they stay, no words.
        assertEquals("Dentist", calendarGlance(CalendarState(connected = true, loaded = true, offline = true, events = listOf(later)), now, true)!!.title)
        assertEquals(CalendarGlance("Nothing else today", "Calendar"), calendarGlance(CalendarState(connected = true, loaded = true, offline = true, events = listOf(over)), now, true))
        // Offline, nothing loaded today, past the grace.
        assertEquals(
            CalendarGlance("Calendar", "Offline · updates when the TV reconnects"),
            calendarGlance(CalendarState(connected = true, loaded = true, offline = true, noNetwork = true), now, offlineShown = true),
        )
        assertEquals(
            CalendarGlance("Calendar", "Can’t reach Google right now"),
            calendarGlance(CalendarState(connected = true, loaded = true, offline = true, noNetwork = false), now, offlineShown = true),
        )
        // Within the grace, or before the first answer: nothing claimed (not "Nothing else today").
        assertEquals(CalendarGlance("Calendar", "Today"), calendarGlance(CalendarState(connected = true, loaded = true, offline = true), now, offlineShown = false))
        assertEquals(CalendarGlance("Calendar", "Today"), calendarGlance(CalendarState(connected = true), now, offlineShown = false))
        // Google ended it: where to connect again, and OK goes there.
        assertEquals(
            CalendarGlance("Connect Google Calendar again", "Settings → Accounts", opensSettings = true),
            calendarGlance(CalendarState(connected = false, ended = true), now, offlineShown = false),
        )
    }

    @Test fun theMorningBriefingNeverClaimsAnEmptyCalendarItDoesNotKnow() {
        val event = DayEvent("Dentist", null, null, null, 0xFF5592BF)
        assertNull(calendarBriefing(CalendarState(connected = false)))
        assertNull(calendarBriefing(CalendarState(connected = true, loaded = true, offline = true)))
        // Before the first answer.
        assertNull(calendarBriefing(CalendarState(connected = true)))
        assertEquals("nothing on the calendar", calendarBriefing(CalendarState(connected = true, loaded = true)))
        assertEquals("1 event today, first Dentist All day", calendarBriefing(CalendarState(connected = true, loaded = true, offline = true, events = listOf(event))))
    }

    @Test fun theCameras() {
        assertEquals("Offline · the cameras reconnect by themselves", cameraOfflineLine(google = false))
        assertEquals("Can’t reach Google right now · the cameras reconnect by themselves", cameraOfflineLine(google = true))
        assertEquals("Cameras will show when the TV is back online.", NO_CAMERAS_OFFLINE)
        assertNull(streamWords(CameraStream.Status.Live, long = false))
        assertEquals("Connecting…", streamWords(CameraStream.Status.Connecting, long = false))
        // Its frames stopped, or its connection dropped and is made again: reconnecting (a stream has no
        // words of Google's to show), then after 30 s it says so.
        assertEquals(OfflineWords.RECONNECTING, streamWords(CameraStream.Status.Stalled(1), long = false))
        assertEquals(OfflineWords.RECONNECTING, streamWords(CameraStream.Status.Reconnecting(1), long = false))
        assertEquals("Can’t reach this camera right now", streamWords(CameraStream.Status.Reconnecting(1), long = true))
    }

    @Test fun accountsSayEachSignInAsItStands() {
        assertEquals("Signed in", signInWords(Grant.DRIVE, SignIn.SIGNED_IN))
        assertEquals("Sign-in ended", signInWords(Grant.DRIVE, SignIn.ENDED))
        assertEquals("Keys not accepted", signInWords(Grant.DRIVE, SignIn.KEYS_REFUSED))
        assertEquals("Not signed in", signInWords(Grant.DRIVE, SignIn.NOT_SIGNED_IN))
        assertEquals("Not set up", signInWords(Grant.DRIVE, SignIn.NOT_SET_UP))
        for (g in listOf(Grant.PHOTOS, Grant.CALENDAR)) {
            assertEquals("Connected", signInWords(g, SignIn.SIGNED_IN))
            assertEquals("Sign-in ended", signInWords(g, SignIn.ENDED))
            assertEquals("Keys not accepted", signInWords(g, SignIn.KEYS_REFUSED))
            assertEquals("Connect", signInWords(g, SignIn.NOT_SIGNED_IN))
            assertEquals("Connect", signInWords(g, SignIn.NOT_SET_UP))
        }
        assertEquals("Connected", signInWords(Grant.NEST, SignIn.SIGNED_IN))
        assertEquals("Sign-in ended", signInWords(Grant.NEST, SignIn.ENDED))
        assertEquals("Not connected", signInWords(Grant.NEST, SignIn.NOT_SIGNED_IN))
        assertEquals("Not connected", signInWords(Grant.NEST, SignIn.NOT_SET_UP))
        // Amber only for what needs someone.
        assertTrue(SignIn.ENDED.needsAttention)
        assertTrue(SignIn.KEYS_REFUSED.needsAttention)
        for (s in listOf(SignIn.SIGNED_IN, SignIn.NOT_SIGNED_IN, SignIn.NOT_SET_UP)) assertFalse(s.needsAttention)
    }

    @Test fun settingsOpensAtTheAskedPageOnce() {
        SettingsRequests.ask(SettingsAt.KEYS)
        assertEquals(SettingsAt.KEYS, SettingsRequests.take())
        // Taken: the next visit to Settings opens at its first page, as usual.
        assertNull(SettingsRequests.take())
    }

    @Test fun aPageAskedForWhileSettingsIsOnScreenIsTakenThereNotLater() {
        // Search's Open Keys over Settings: Settings, already on screen, sees the ask and takes it…
        SettingsRequests.ask(SettingsAt.KEYS)
        assertEquals(SettingsAt.KEYS, SettingsRequests.asked.value)
        assertEquals(SettingsAt.KEYS, SettingsRequests.take())
        // …so nothing is left to open Settings at Keys the next time someone opens it from the side menu.
        assertNull(SettingsRequests.asked.value)
        assertNull(SettingsRequests.take())
    }

    @Test fun noOfflineWordIsAlarming() {
        // None of the words the TV shows for these states uses what the copy voice bans.
        val banned = listOf("error", "failed", "invalid", "unavailable", "retry", "please", "expired")
        val words = listOf(
            OfflineWords.full(true), OfflineWords.full(false), OfflineWords.RECONNECTING, OfflineWords.BACK_ONLINE,
            libraryPageWords(LibraryPage.Offline(true, false), "Movies").body, libraryPageWords(LibraryPage.Offline(false, true), "Movies").body,
            libraryPageWords(LibraryPage.Keys, "Movies").body, cameraOfflineLine(true), NO_CAMERAS_OFFLINE,
            streamWords(CameraStream.Status.Reconnecting(1), long = true)!!,
        )
        for (w in words) for (b in banned) assertFalse("\"$w\" says \"$b\"", w.lowercase().contains(b))
    }
}
