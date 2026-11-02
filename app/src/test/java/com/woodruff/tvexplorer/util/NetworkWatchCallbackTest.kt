package com.woodruff.tvexplorer.util

import android.app.Application
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
    }
}
