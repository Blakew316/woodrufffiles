package com.woodruff.tvexplorer.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/**
 * What tells the phone connection to look at the TV's address again: Wi-Fi and Ethernet coming,
 * going and changing address, with or without internet, until the service ends.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LanWatchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val callbacks get() = shadowOf(cm).networkCallbacks

    @Test fun listensToWiFiAndEthernetEvenWithoutInternet() {
        val request = LanWatch.homeNetworks()
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        // A router whose internet is still coming back already carries the phone connection.
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        // Tailscale (a VPN) isn't the home network.
        assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
    }

    @Test fun everyReportCountsUntilItStops() {
        val watch = LanWatch(context)
        watch.start()
        watch.start() // a second start registers nothing more
        assertEquals(1, callbacks.size)
        val network = ShadowNetwork.newInstance(7)
        val cb = callbacks.single()
        cb.onAvailable(network)
        cb.onLinkPropertiesChanged(network, LinkProperties())
        cb.onLost(network)
        assertEquals(3, watch.changes.value)
        watch.stop()
        assertTrue(callbacks.isEmpty())
    }

    @Test fun aStartThatArrivesAfterTheStopDoesNothing() {
        val watch = LanWatch(context)
        watch.stop()
        watch.start()
        assertTrue(callbacks.isEmpty())
    }

    @Test fun waitsForAChangeOrTheTimeout() = runBlocking {
        val watch = LanWatch(context)
        watch.start()
        val mark = watch.changes.value
        // Nothing reported: back after the timeout.
        val quiet = System.nanoTime()
        watch.awaitChange(mark, timeoutMs = 50)
        assertTrue(System.nanoTime() - quiet >= 50_000_000)
        // A change that came while looking (after the mark was read) counts at once, then settles.
        callbacks.single().onAvailable(ShadowNetwork.newInstance(3))
        val changed = System.nanoTime()
        watch.awaitChange(mark, timeoutMs = 60_000)
        val took = (System.nanoTime() - changed) / 1_000_000
        assertTrue("took $took ms", took in LanWatch.SETTLE_MS..LanWatch.SETTLE_MS + 5_000)
        watch.stop()
    }
}
