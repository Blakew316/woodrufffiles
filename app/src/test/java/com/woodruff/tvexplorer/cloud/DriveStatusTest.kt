package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.cloud.GoogleAuth.TokenResult
import com.woodruff.tvexplorer.ui.components.OFFLINE_GRACE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What the Drive screen does with each outcome of a listing: a network failure keeps the saved
 * library on screen and, after a grace period, says "offline" (never the sign-in QR code); a retry
 * that gets through says "Back online" over the saved library; only a sign-in that really ended
 * brings the QR code; keys Google refuses are their own state, with no grace and no "offline".
 */
class DriveStatusTest {
    private val movies = listOf(
        DriveFile("m1", "Midnight Harbor (2024).mkv", "video/x-matroska", 0, 1, null),
        DriveFile("m2", "Paper Planes (2022).mkv", "video/x-matroska", 0, 2, null),
    )
    /** Signed in, Movies open, the saved listing on screen. */
    private val showingSaved = DriveState(step = SignInStep.CONNECTED, stack = listOf(DrivePlace.Movies), files = movies, token = "t")
    private val explain = { e: Throwable -> e.message ?: "?" }
    private val t0 = 1_800_000_000_000L

    @Test fun offlineKeepsTheSavedLibraryAndNeverAsksToSignIn() {
        val s = showingSaved.after(Fetch.Offline, here = true, now = t0, google = false)
        assertEquals(SignInStep.CONNECTED, s.step)
        assertEquals(movies, s.files)
        assertEquals(DriveStatus.Offline(since = t0, google = false), s.status)
        assertEquals(false, s.busy)
        assertNull(s.error)
    }

    @Test fun nothingIsSaidWithinTheGraceThenTheWordsShow() {
        val s = showingSaved.after(Fetch.Offline, here = true, now = t0, google = false)
        assertFalse(s.status.said)
        // Another failure inside the grace keeps when it began.
        val again = s.after(Fetch.Offline, here = true, now = t0 + 2_000, google = true)
        assertEquals(DriveStatus.Offline(since = t0, google = true), again.status)
        assertEquals(again.status, again.status.graceOver(t0 + OFFLINE_GRACE_MS - 1))
        val said = again.status.graceOver(t0 + OFFLINE_GRACE_MS)
        assertEquals(DriveStatus.Offline(since = t0, google = true, shown = true), said)
        assertTrue(said.said)
        assertTrue(said.down)
    }

    @Test fun gettingThroughWithinTheGraceSaysNothingAtAll() {
        val failing = showingSaved.after(Fetch.Offline, here = true, now = t0, google = false)
        // Google answers 1 s later (a TV whose Wi-Fi came back as it woke).
        assertEquals(DriveStatus.Online, failing.status.reached(saved = true, now = t0 + 1_000))
        assertEquals(DriveStatus.Online, failing.after(Fetch.Done, here = true, now = t0 + 1_000).status)
    }

    @Test fun nothingSavedSaysLoadingThroughTheGraceThenThePage() {
        val loading = showingSaved.copy(files = emptyList(), busy = true)
        val s = loading.after(Fetch.Offline, here = true, now = t0, google = false)
        assertEquals(SignInStep.CONNECTED, s.step)
        // Still "Loading" (it's still trying), not the page, not an error.
        assertEquals(true, s.busy)
        assertNull(s.error)
        // Once the grace is over the page shows instead (the timer clears busy with it).
        assertTrue(s.status.graceOver(t0 + OFFLINE_GRACE_MS).said)
        // Said already, the next failure doesn't bring "Loading" back.
        val said = s.copy(status = s.status.graceOver(t0 + OFFLINE_GRACE_MS), busy = false)
        assertEquals(false, said.after(Fetch.Offline, here = true, now = t0 + 10_000, google = false).busy)
    }

    @Test fun aRetryAfterTheWordsIsReconnectingAndFailingGoesBackToThem() {
        val said = DriveStatus.Offline(since = t0, google = false, shown = true)
        val trying = DriveStatus.Reconnecting(said)
        assertTrue(trying.said && trying.down)
        // The network came back (validated), but Google didn't answer: "Can't reach Google" now.
        val s = showingSaved.copy(status = trying).after(Fetch.Offline, here = true, now = t0 + 60_000, google = true)
        assertEquals(DriveStatus.Offline(since = t0, google = true, shown = true), s.status)
    }

    @Test fun backOnlineOnlyAfterTheWordsShowedOverTheSavedLibrary() {
        val said = DriveStatus.Offline(since = t0, google = false, shown = true)
        assertEquals(DriveStatus.BackOnline(t0 + 9_000), said.reached(saved = true, now = t0 + 9_000))
        assertEquals(DriveStatus.BackOnline(t0 + 9_000), DriveStatus.Reconnecting(said).reached(saved = true, now = t0 + 9_000))
        // With nothing saved the page was up: it stays until the listing replaces it (no "Back online").
        assertEquals(said, said.reached(saved = false, now = t0 + 9_000))
        // Already back online: it stays (its own timer ends it).
        assertEquals(DriveStatus.BackOnline(t0), DriveStatus.BackOnline(t0).reached(saved = true, now = t0 + 500))
        // The listing's own answer, once it's done, keeps "Back online".
        val back = showingSaved.copy(status = DriveStatus.BackOnline(t0))
        assertEquals(DriveStatus.BackOnline(t0), back.after(Fetch.Done, here = true, now = t0 + 800).status)
    }

    @Test fun aWholeMorningAfterStandby() {
        // The TV wakes; the Wi-Fi is still joining: offline, said after the grace, a retry that's slow,
        // then the network is back and the listing gets through: Back online, then online.
        var s = showingSaved.after(Fetch.Offline, here = true, now = t0, google = false)
        s = s.copy(status = s.status.graceOver(t0 + OFFLINE_GRACE_MS))
        assertTrue(s.status.said)
        s = s.copy(status = DriveStatus.Reconnecting(s.status as DriveStatus.Offline))
        s = s.after(Fetch.Offline, here = true, now = t0 + 5_000, google = false)
        assertEquals(DriveStatus.Offline(since = t0, google = false, shown = true), s.status)
        s = s.copy(status = DriveStatus.Reconnecting(s.status as DriveStatus.Offline))
        s = s.copy(status = s.status.reached(saved = s.files.isNotEmpty(), now = t0 + 9_000))
        assertEquals(DriveStatus.BackOnline(t0 + 9_000), s.status)
        s = s.after(Fetch.Done, here = true, now = t0 + 9_500)
        assertEquals(DriveStatus.BackOnline(t0 + 9_000), s.status)
        assertEquals(SignInStep.CONNECTED, s.step)
        assertEquals(movies, s.files)
    }

    @Test fun anotherPlacesLoadingIsLeftAloneButTheConnectionIsNot() {
        // The viewer moved on to a folder that's still loading.
        val elsewhere = showingSaved.copy(busy = true, error = null)
        val s = elsewhere.after(Fetch.Offline, here = false, now = t0, google = false)
        assertEquals(true, s.busy)
        assertEquals(DriveStatus.Offline(since = t0, google = false), s.status)
    }

    @Test fun onlyAnEndedSignInBringsTheQrCode() {
        val s = showingSaved.after(Fetch.SignedOut, here = true)
        assertEquals(SignInStep.SIGN_IN, s.step)
        assertEquals(DriveStatus.Online, s.status)
        // The library is kept for after the next sign-in.
        assertEquals(movies, s.files)
    }

    @Test fun refusedKeysAreTheirOwnStateNeverOffline() {
        val s = showingSaved.after(Fetch.Refused("Check the Client ID and secret."), here = true)
        assertEquals(SignInStep.CONNECTED, s.step)
        assertEquals(DriveStatus.Refused("Check the Client ID and secret."), s.status)
        assertFalse(s.status.down)
        assertFalse(s.status.said)
        assertEquals(movies, s.files)
        // Over an offline state too: no grace, no retrying words.
        val offline = showingSaved.copy(status = DriveStatus.Offline(t0, google = true, shown = true))
        assertEquals(DriveStatus.Refused("x"), offline.after(Fetch.Refused("x"), here = true).status)
        // Google taking the keys again (a listing got through): online.
        assertEquals(DriveStatus.Online, DriveStatus.Refused("x").reached(saved = true, now = t0))
    }

    @Test fun otherFailuresShowOnlyWhenThereIsNothingToShow() {
        val withList = showingSaved.after(Fetch.Failed("Can’t reach Google Drive right now."), here = true)
        assertNull(withList.error)
        assertEquals(movies, withList.files)
        val empty = showingSaved.copy(files = emptyList(), busy = true).after(Fetch.Failed("There’s no “Movies” folder"), here = true)
        assertEquals("There’s no “Movies” folder", empty.error)
        // Google answered, so it isn't offline.
        assertEquals(DriveStatus.Online, empty.status)
        // Even over the offline page: the error shows instead.
        val page = showingSaved.copy(files = emptyList(), status = DriveStatus.Offline(t0, google = false, shown = true))
        assertEquals(DriveStatus.Online, page.after(Fetch.Failed("There’s no “Movies” folder"), here = true).status)
    }

    @Test fun retriesComeQuicklyThenEveryMinute() {
        assertEquals(listOf(5_000L, 15_000L, 30_000L, 60_000L, 60_000L), (0..4).map { retryPause(it) })
    }

    @Test fun tokenAnswersBecomeOutcomes() {
        assertNull(fetchFor(TokenResult.Ok("t")))
        assertEquals(Fetch.SignedOut, fetchFor(TokenResult.SignedOut))
        assertEquals(Fetch.Offline, fetchFor(TokenResult.Offline(UnknownHostException())))
        assertEquals(Fetch.Refused("Check the keys"), fetchFor(TokenResult.Rejected("Check the keys")))
    }

    @Test fun failuresWhileListingBecomeOutcomes() {
        // The network dropping part-way through the listing, however it shows.
        assertEquals(Fetch.Offline, fetchFailure(UnknownHostException(), explain))
        assertEquals(Fetch.Offline, fetchFailure(SocketTimeoutException(), explain))
        assertEquals(Fetch.Offline, fetchFailure(GoogleAuth.AuthException.Offline(UnknownHostException()), explain))
        assertEquals(Fetch.SignedOut, fetchFailure(GoogleAuth.AuthException.SignedOut("Signed out"), explain))
        assertEquals(Fetch.Refused("Check the keys"), fetchFailure(GoogleAuth.AuthException.Rejected("Check the keys"), explain))
        // Drive busy or failing, or refusing a token it has just renewed: "Can't reach Google", tried again.
        for (code in listOf(401, 408, 429, 500, 503)) assertEquals("$code", Fetch.Offline, fetchFailure(DriveHttpException(code, "x"), explain))
        assertEquals(Fetch.Offline, fetchFailure(DriveHttpException(403, "x", busy = true), explain))
        // Anything else Drive answered is said, not retried.
        assertEquals(Fetch.Failed("That file is no longer in Google Drive."), fetchFailure(DriveHttpException(404, "That file is no longer in Google Drive."), explain))
        assertEquals(Fetch.Failed("x"), fetchFailure(DriveHttpException(403, "x"), explain))
        assertEquals(Fetch.Failed("No Movies folder"), fetchFailure(IOException("No Movies folder"), explain))
    }
}
