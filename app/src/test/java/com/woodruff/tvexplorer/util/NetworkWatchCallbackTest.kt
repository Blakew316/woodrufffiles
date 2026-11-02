package com.woodruff.tvexplorer.util

import android.app.Application
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/** Android's network reports reach [NetworkWatch]: standby and wake as offline, then a comeback. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NetworkWatchCallbackTest {
    @Test fun standbyAndWakeAsAndroidReportsThem() {
        // Starting twice (Drive, the calendar and the cameras all ask) watches once, and never throws.
        val app = ApplicationProvider.getApplicationContext<Application>()
        NetworkWatch.start(app)
        NetworkWatch.start(app)
        // The first start asks Android on a thread of its own: let it finish before reporting.
        Thread.sleep(300)

        val cb = NetworkWatch.callback
        val wifi = ShadowNetwork.newInstance(101)
        val validated = ShadowNetworkCapabilities.newInstance().also {
            org.robolectric.Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            org.robolectric.Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        cb.onAvailable(wifi)
        cb.onCapabilitiesChanged(wifi, validated)
        assertTrue(NetworkWatch.online.value)

        // Standby: the Wi-Fi goes, and nothing to retry yet.
        val before = NetworkWatch.comebacks.value
        cb.onLost(wifi)
        assertFalse(NetworkWatch.online.value)
        assertEquals(before, NetworkWatch.comebacks.value)

        // Wake: it joins again, then passes Android's internet check: two chances to try again, and
        // online only once the check has passed.
        cb.onAvailable(wifi)
        assertFalse(NetworkWatch.online.value)
        assertEquals(before + 1, NetworkWatch.comebacks.value)
        cb.onCapabilitiesChanged(wifi, ShadowNetworkCapabilities.newInstance())
        assertFalse(NetworkWatch.online.value)
        cb.onCapabilitiesChanged(wifi, validated)
        assertTrue(NetworkWatch.online.value)
        assertEquals(before + 2, NetworkWatch.comebacks.value)

        // A VPN that's up counts as online, even unchecked (some TVs never mark one validated).
        val vpn = ShadowNetwork.newInstance(102)
        val vpnCaps = ShadowNetworkCapabilities.newInstance().also {
            org.robolectric.Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            org.robolectric.Shadows.shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        }
        cb.onAvailable(vpn)
        cb.onCapabilitiesChanged(vpn, vpnCaps)
        assertTrue(NetworkWatch.online.value)
    }

    @Test fun anyNetworkRightNowAsAndroidSaysIt() {
        // The phone connection's background checks and an update check that failed ask this, there and then.
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        assertTrue("Wi-Fi up", NetworkWatch.hasNetworkNow(app))
        org.robolectric.Shadows.shadowOf(cm).setDefaultNetworkActive(false)
        assertFalse("no network at all (just after the TV turned on)", NetworkWatch.hasNetworkNow(app))
        // A TV that can't say: worth a try.
        val cantSay = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? = throw SecurityException("not on this TV")
        }
        assertTrue(NetworkWatch.hasNetworkNow(cantSay))
    }
}
