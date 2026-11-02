package com.woodruff.tvexplorer.util

import com.woodruff.tvexplorer.cloud.DriveHttpException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** When the network counts as back (worth trying again at once), and what counts as the network failing. */
class NetworkWatchTest {
    private val wifi = "wifi"
    private val ethernet = "ethernet"

    @Test fun theNetworkAlreadyUpWhenWatchingStartsIsNoComeback() {
        val t = NetworkTracker()
        assertEquals(NetworkTracker.Change(online = true, cameBack = false), t.reset(wifi, validated = true))
        // The callback's first reports describe what's already known.
        assertFalse(t.available(wifi).cameBack)
        assertFalse(t.capabilities(wifi, validated = true).cameBack)
    }

    @Test fun wakingFromStandbyIsAComebackTwice() {
        val t = NetworkTracker()
        t.reset(wifi, validated = true)
        // Standby: the Wi-Fi goes.
        assertEquals(NetworkTracker.Change(online = false, cameBack = false), t.lost(wifi))
        // It joins again (names may not resolve yet): worth a try, but not online until checked...
        assertEquals(NetworkTracker.Change(online = false, cameBack = true), t.available(wifi))
        assertEquals(NetworkTracker.Change(online = false, cameBack = false), t.capabilities(wifi, validated = false))
        // ...then Android's check passes: online, and worth another try.
        assertEquals(NetworkTracker.Change(online = true, cameBack = true), t.capabilities(wifi, validated = true))
        // Nothing new after that.
        assertFalse(t.capabilities(wifi, validated = true).cameBack)
    }

    @Test fun startingWithNoNetworkThenGettingOne() {
        val t = NetworkTracker()
        assertEquals(NetworkTracker.Change(online = false, cameBack = false), t.reset(null, validated = false))
        assertTrue(t.available(wifi).cameBack)
    }

    @Test fun switchingNetworksIsAComeback() {
        val t = NetworkTracker()
        t.reset(wifi, validated = true)
        assertTrue(t.available(ethernet).cameBack)
        // The old one going afterwards doesn't take the new one with it (not checked yet, so not online yet).
        assertEquals(NetworkTracker.Change(online = false, cameBack = false), t.lost(wifi))
        assertEquals(NetworkTracker.Change(online = true, cameBack = true), t.capabilities(ethernet, validated = true))
        assertEquals(NetworkTracker.Change(online = true, cameBack = false), t.lost(wifi))
    }

    @Test fun aNetworkThatNeverPassesTheCheckIsNotOnline() {
        // A Wi-Fi with no internet behind it (the router's line down): joined, but offline.
        val t = NetworkTracker()
        t.reset(null, validated = false)
        assertEquals(NetworkTracker.Change(online = false, cameBack = true), t.available(wifi))
        assertEquals(NetworkTracker.Change(online = false, cameBack = false), t.capabilities(wifi, validated = false))
    }

    @Test fun aNetworkReportedWithoutBeingAnnouncedCounts() {
        val t = NetworkTracker()
        t.reset(null, validated = false)
        assertTrue(t.capabilities(wifi, validated = false).cameBack)
        assertTrue(t.capabilities(wifi, validated = true).cameBack)
    }

    @Test fun losingTheCheckAndPassingItAgainIsAComeback() {
        val t = NetworkTracker()
        t.reset(wifi, validated = true)
        // The router lost its internet: offline, though the Wi-Fi is still joined.
        assertEquals(NetworkTracker.Change(online = false, cameBack = false), t.capabilities(wifi, validated = false))
        assertEquals(NetworkTracker.Change(online = true, cameBack = true), t.capabilities(wifi, validated = true)) // and got it back
    }

    @Test fun theNetworkFailingIsToldApartFromAServersAnswer() {
        for (e in listOf(
            UnknownHostException("Unable to resolve host \"www.googleapis.com\""),
            SocketTimeoutException("timeout"),
            ConnectException("Failed to connect"),
            SocketException("Connection reset"),
            SSLHandshakeException("Chain validation failed"), // a clock still wrong after a power cut
            EOFException("unexpected end of stream"),
            IOException("unexpected end of stream on Connection", EOFException()),
            RuntimeException("wrapped", IOException("deeper", UnknownHostException())),
            object : IOException("Can't reach Google right now."), NetworkFailure {},
        )) assertTrue("$e", isNetworkFailure(e))
        for (e in listOf(
            IOException("Google Drive error 500"),
            DriveHttpException(401, "Google sign-in expired."),
            DriveHttpException(404, "That file is no longer in Google Drive."),
            IllegalStateException("Connection lost"),
            IOException("There's no \"Movies\" folder in your Google Drive yet."),
        )) assertFalse("$e", isNetworkFailure(e))
    }

    @Test fun pausesGrowAndStopGrowing() {
        assertEquals(5_000, backoffMs(0, 5_000, 300_000))
        assertEquals(10_000, backoffMs(1, 5_000, 300_000))
        assertEquals(160_000, backoffMs(5, 5_000, 300_000))
        assertEquals(300_000, backoffMs(6, 5_000, 300_000))
        assertEquals(300_000, backoffMs(1_000, 5_000, 300_000))
        assertEquals(5_000, backoffMs(-1, 5_000, 300_000))
    }

    @Test fun waitingForTheNetworkStopsAtTheTimeoutOrAComebackAlreadyMissed() = runBlocking {
        val now = NetworkWatch.comebacks.value
        // Nothing happens: the pause runs out.
        assertFalse(NetworkWatch.awaitComeback(now, 30))
        // It came back while the failed try was under way: no waiting at all.
        assertTrue(NetworkWatch.awaitComeback(now - 1, 60_000))
    }
}
