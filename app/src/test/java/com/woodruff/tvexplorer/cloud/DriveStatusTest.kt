package com.woodruff.tvexplorer.cloud

import com.woodruff.tvexplorer.cloud.GoogleAuth.TokenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What the Drive screen does with each outcome of a listing: a network failure keeps the saved
 * library on screen and says "offline" (never the sign-in QR code); only a sign-in that really
 * ended brings the QR code.
 */
class DriveStatusTest {
    private val movies = listOf(
        DriveFile("m1", "Midnight Harbor (2024).mkv", "video/x-matroska", 0, 1, null),
        DriveFile("m2", "Paper Planes (2022).mkv", "video/x-matroska", 0, 2, null),
    )
    /** Signed in, Movies open, the saved listing on screen. */
    private val showingSaved = DriveState(step = SignInStep.CONNECTED, stack = listOf(DrivePlace.Movies), files = movies, token = "t")
    private val explain = { e: Throwable -> e.message ?: "?" }

    @Test fun offlineKeepsTheSavedLibraryAndNeverAsksToSignIn() {
        val s = showingSaved.after(Fetch.Offline, here = true)
        assertEquals(SignInStep.CONNECTED, s.step)
        assertEquals(movies, s.files)
        assertEquals(DriveStatus.Offline, s.status)
        assertEquals(false, s.busy)
        assertNull(s.error)
    }

    @Test fun offlineWithNothingSavedIsTheOfflinePageNotAnError() {
        val loading = showingSaved.copy(files = emptyList(), busy = true)
        val s = loading.after(Fetch.Offline, here = true)
        assertEquals(SignInStep.CONNECTED, s.step)
        assertEquals(DriveStatus.Offline, s.status)
        assertEquals(false, s.busy)
        assertNull(s.error)
    }

    @Test fun anotherPlacesLoadingIsLeftAloneButTheConnectionIsNot() {
        // The viewer moved on to a folder that's still loading.
        val elsewhere = showingSaved.copy(busy = true, error = null)
        val s = elsewhere.after(Fetch.Offline, here = false)
        assertEquals(true, s.busy)
        assertEquals(DriveStatus.Offline, s.status)
    }

    @Test fun backOnlineTheStatusClears() {
        val offline = showingSaved.after(Fetch.Offline, here = true)
        assertEquals(DriveStatus.Online, offline.after(Fetch.Done, here = true).status)
    }

    @Test fun onlyAnEndedSignInBringsTheQrCode() {
        val s = showingSaved.after(Fetch.SignedOut, here = true)
        assertEquals(SignInStep.SIGN_IN, s.step)
        assertEquals(DriveStatus.Online, s.status)
        // The library is kept for after the next sign-in.
        assertEquals(movies, s.files)
    }

    @Test fun refusedKeysKeepTheLibraryAndSayWhatToFix() {
        val s = showingSaved.after(Fetch.Refused("Check the Client ID and secret."), here = true)
        assertEquals(SignInStep.CONNECTED, s.step)
        assertEquals(DriveStatus.Refused("Check the Client ID and secret."), s.status)
        assertEquals(movies, s.files)
    }

    @Test fun otherFailuresShowOnlyWhenThereIsNothingToShow() {
        val withList = showingSaved.after(Fetch.Failed("Google Drive error 500"), here = true)
        assertNull(withList.error)
        assertEquals(movies, withList.files)
        val empty = showingSaved.copy(files = emptyList(), busy = true).after(Fetch.Failed("There's no \"Movies\" folder"), here = true)
        assertEquals("There's no \"Movies\" folder", empty.error)
        // Google answered, so it isn't offline.
        assertEquals(DriveStatus.Online, empty.status)
    }

    @Test fun aWholeMorningAfterStandby() {
        // The TV wakes; the Wi-Fi is still joining: twice offline, then the network is back.
        var s = showingSaved
        for (outcome in listOf(Fetch.Offline, Fetch.Offline)) {
            s = s.after(outcome, here = true)
            assertEquals(SignInStep.CONNECTED, s.step)
            assertEquals(movies, s.files)
        }
        s = s.after(Fetch.Done, here = true)
        assertEquals(DriveStatus.Online, s.status)
        assertEquals(SignInStep.CONNECTED, s.step)
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
        assertEquals(Fetch.Failed("That file is no longer in Google Drive."), fetchFailure(DriveHttpException(404, "That file is no longer in Google Drive."), explain))
        assertEquals(Fetch.Failed("Google Drive error 500"), fetchFailure(IOException("Google Drive error 500"), explain))
    }
}
