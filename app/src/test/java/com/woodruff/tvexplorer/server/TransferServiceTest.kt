package com.woodruff.tvexplorer.server

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone connection as a service: it waits for the network for as long as it takes and opens the
 * moment there is one, follows the TV to a new address, comes back after Android closes it (unless
 * "Start when the TV turns on" is off), and stops cleanly when Android won't let it come back.
 * The TV's address is put in by the test; the server opens for real, on a free port, and closes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TransferServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)
    @Volatile private var address: String? = null
    private val defaultForeground = TransferService.foreground
    private val defaultPairingKey = TransferService.pairingKey
    private var controller: ServiceController<TransferService>? = null

    @Before fun setUp() {
        TransferService.addressNow = { address }
        TransferService.pairingKey = { "test-pairing-key" }
        AutoStart.setEnabled(context, true)
    }

    @After fun tearDown() {
        controller?.destroy()
        TransferService.addressNow = { TransferService.lanAddress() }
        TransferService.pairingKey = defaultPairingKey
        TransferService.foreground = defaultForeground
        AutoStart.setEnabled(context, true)
    }

    private fun service(): TransferService =
        Robolectric.buildService(TransferService::class.java).create().also { controller = it }.get()

    private val start get() = Intent(context, TransferService::class.java)
    private val state get() = TransferService.state.value

    /** What Android tells the service when Wi-Fi or Ethernet changes. */
    private fun networkChanged() {
        val network = ShadowNetwork.newInstance(11)
        shadowOf(cm).networkCallbacks.toList().forEach {
            it.onAvailable(network)
            it.onLinkPropertiesChanged(network, LinkProperties())
        }
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!check()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for $what (state: $state)")
            Thread.sleep(20)
        }
    }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun shownTitle() = shadowOf(nm).getNotification(1)?.title()

    @Test fun waitsForTheNetworkAsLongAsItTakesThenOpensAndFollowsIt() {
        val service = service()
        assertEquals(Service.START_STICKY, service.onStartCommand(start, 0, 1))
        // In the foreground at once.
        assertEquals("Woodruff Family: starting the phone connection", shadowOf(service).lastForegroundNotification.title())
        waitFor("waiting") { state.waiting }
        assertTrue(state.on)
        assertFalse(state.running)
        // Nobody waiting on it is told to give up.
        assertNull(state.error)
        waitFor("the notification to say so") { shownTitle() == "Woodruff Family: waiting for the network" }
        assertFalse(shadowOf(service).isStoppedBySelf)

        // Wi-Fi connects.
        address = "192.0.2.10"
        networkChanged()
        waitFor("open") { state.running }
        val port = state.base!!.substringAfterLast(':')
        assertEquals("http://192.0.2.10:$port", state.base)
        assertEquals("http://192.0.2.10:$port/?k=test-pairing-key", state.url)
        assertFalse(state.waiting)
        assertEquals(listOf("Phone connection ready"), state.log)
        waitFor("the notification to show the address") { shownTitle() == "Woodruff Family: phone connection is on" }

        // The router hands out a new address: shown there, same port, so the server didn't close.
        address = "192.0.2.31"
        networkChanged()
        waitFor("the new address") { state.base == "http://192.0.2.31:$port" }
        assertTrue(state.running)

        // The network goes (standby, the router restarting): waiting again, the server still listening.
        address = null
        networkChanged()
        waitFor("waiting again") { state.waiting && !state.running }
        assertTrue(state.on)

        // And back.
        address = "192.0.2.31"
        networkChanged()
        waitFor("open again") { state.running && state.base == "http://192.0.2.31:$port" }

        controller!!.destroy()
        controller = null
        assertFalse(state.on)
        assertFalse(state.running)
        assertTrue("stopped listening to the network", shadowOf(cm).networkCallbacks.isEmpty())
    }

    @Test fun waitsForThePairingKeyWhenTheKeystoreIsSlowAfterTheTvTurnsOn() {
        val readable = AtomicBoolean(false)
        TransferService.pairingKey = { if (readable.get()) "the-saved-key" else throw PairingKey.NotReadyYet() }
        address = "192.0.2.10"
        service().onStartCommand(start, 0, 1)
        Thread.sleep(1_500)
        // Still starting: there is a network, so not "waiting"; nothing opened with a made-up key,
        // and nobody waiting for it is told to give up.
        assertTrue(state.on)
        assertFalse(state.running)
        assertFalse(state.waiting)
        assertNull(state.error)
        // The Keystore finds its key: it opens by itself, with the key the phones were paired with.
        readable.set(true)
        waitFor("open") { state.running }
        assertTrue(state.url!!.endsWith("/?k=the-saved-key"))
    }

    @Test fun startsAgainWhileRunningChangeNothing() {
        val service = service()
        service.onStartCommand(start, 0, 1)
        waitFor("waiting") { state.waiting }
        val first = shadowOf(service).lastForegroundNotification
        // Woodruff opening again, a sign-in asking for it: no second startForeground().
        assertEquals(Service.START_STICKY, service.onStartCommand(start, 0, 2))
        assertTrue(first === shadowOf(service).lastForegroundNotification)
        assertEquals(1, shadowOf(cm).networkCallbacks.size)
    }

    @Test fun stopIsNotSticky() {
        val service = service()
        service.onStartCommand(start, 0, 1)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(start.setAction(TransferService.ACTION_STOP), 0, 2))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun androidBringsItBackAfterAKill() {
        val service = service()
        // A sticky restart comes without an intent.
        assertEquals(Service.START_STICKY, service.onStartCommand(null, Service.START_FLAG_RETRY, 1))
        assertEquals("Woodruff Family: starting the phone connection", shadowOf(service).lastForegroundNotification.title())
        waitFor("waiting") { state.waiting }
    }

    @Test fun notBroughtBackWhenItsNotToStartByItself() {
        AutoStart.setEnabled(context, false)
        val service = service()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, Service.START_FLAG_RETRY, 1))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(service).lastForegroundNotification)
        // Opening Woodruff still starts it.
        AutoStart.setEnabled(context, true)
    }

    @Test fun whenAndroidRefusesTheRestartItStopsCleanly() {
        TransferService.foreground = { _, _, _, _ -> throw ForegroundServiceStartNotAllowedException("not allowed from the background") }
        val service = service()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, Service.START_FLAG_RETRY, 1))
        assertTrue(shadowOf(service).isStoppedBySelf)
        // Anything waiting for it hears it couldn't start, instead of waiting forever.
        assertEquals(TransferService.COULDNT_START, state.error)
        assertFalse(state.on)
        controller!!.destroy()
        controller = null
        assertEquals(TransferService.COULDNT_START, state.error)
        // The next start that's allowed (Woodruff opening) begins afresh.
        TransferService.foreground = defaultForeground
        TransferService.start(context)
        assertNull(state.error)
    }
}
