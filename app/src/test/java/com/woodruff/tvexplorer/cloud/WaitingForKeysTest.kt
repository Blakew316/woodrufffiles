package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.calendar.CalendarState
import com.woodruff.tvexplorer.calendar.afterNetworkFailure
import com.woodruff.tvexplorer.nest.NestState
import com.woodruff.tvexplorer.nest.unreachable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A sign-in the TV can't read just yet (its Keystore still waking after a power cut) isn't Google out of reach:
 * Streaming, the calendar and the cameras keep what they show, or go on loading, and never say "Can't reach Google"
 * for it. They try again the moment the Keystore wakes (ListsAfterKeystoreWakesTest).
 */
class WaitingForKeysTest {
    private val offline = GoogleAuth.TokenResult.Offline(IOException("The Google keys can't be read just now."))

    @Test fun streamingKeepsWhatItShowsAndSaysNothing() {
        assertEquals(Fetch.Waiting, fetchFor(offline, waitingForKeys = true))
        assertEquals(Fetch.Offline, fetchFor(offline, waitingForKeys = false))
        assertEquals(Fetch.Offline, fetchFor(offline))
        // Nothing saved yet: still "Loading", with no offline status starting its grace.
        val loading = DriveState(busy = true)
        assertEquals(loading, loading.after(Fetch.Waiting, here = true, now = 1_000L, google = true))
        // Over the saved library: as it was.
        val shown = DriveState(files = listOf(DriveFile("1", "Paper Planes (2022).mkv", "video/x-matroska", 0, 1, null)), status = DriveStatus.Online)
        assertEquals(shown, shown.after(Fetch.Waiting, here = true, now = 1_000L))
        // Google out of reach is still said, after its grace.
        assertTrue(shown.after(Fetch.Offline, here = true, now = 1_000L).status is DriveStatus.Offline)
    }

    @Test fun theCalendarClaimsNothing() {
        val today = 2_026_306
        val fresh = CalendarState(connected = true)
        assertEquals(fresh, fresh.afterNetworkFailure(today, noNetwork = false, waitingForKeys = true))
        assertTrue(fresh.afterNetworkFailure(today, noNetwork = false, waitingForKeys = false).offline)
    }

    @Test fun theCamerasGoOnLoading() {
        val listing = NestState(loading = true)
        val waiting = listing.unreachable(waitingForKeys = true)
        assertTrue(waiting.loading)
        assertFalse(waiting.offline)
        val unreachable = listing.unreachable(waitingForKeys = false)
        assertFalse(unreachable.loading)
        assertTrue(unreachable.offline)
        // Already offline (the network went first): it stays so, not "loading" over the offline words.
        val wasOffline = NestState(offline = true).unreachable(waitingForKeys = true)
        assertTrue(wasOffline.offline)
        assertFalse(wasOffline.loading)
    }
}
