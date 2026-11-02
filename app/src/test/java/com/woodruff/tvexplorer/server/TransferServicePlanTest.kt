package com.woodruff.tvexplorer.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone connection's decisions, apart from Android: what a start does (Stop, a restart Android
 * makes by itself), what to do with the address the TV has now, which ports to try, how long to wait
 * before trying again, and what the notification says.
 */
class TransferServicePlanTest {
    private val home = "192.0.2.10"
    private val moved = "192.0.2.31"

    @Test fun noAddressMeansWaitingHoweverLongItTakes() {
        // Nothing open yet, or open and the network gone: wait either way, never give up.
        assertEquals(NextStep.WAIT, nextStep(open = false, alive = false, shownAt = null, address = null))
        assertEquals(NextStep.WAIT, nextStep(open = true, alive = true, shownAt = home, address = null))
        assertEquals(NextStep.WAIT, nextStep(open = true, alive = false, shownAt = null, address = null))
    }

    @Test fun anAddressOpensItOnce() {
        assertEquals(NextStep.OPEN, nextStep(open = false, alive = false, shownAt = null, address = home))
        assertEquals(NextStep.STAY, nextStep(open = true, alive = true, shownAt = home, address = home))
    }

    @Test fun aNewAddressOrTheOldOneBackIsShown() {
        assertEquals(NextStep.MOVE, nextStep(open = true, alive = true, shownAt = home, address = moved))
        // Back after a while without a network (shown nowhere meanwhile), at the same address.
        assertEquals(NextStep.MOVE, nextStep(open = true, alive = true, shownAt = null, address = home))
    }

    @Test fun aServerWhoseSocketClosedIsOpenedAgain() {
        assertEquals(NextStep.REOPEN, nextStep(open = true, alive = false, shownAt = home, address = home))
        assertEquals(NextStep.REOPEN, nextStep(open = true, alive = false, shownAt = null, address = moved))
    }

    @Test fun stopIsStopAndARestartAsksTheSwitch() {
        var asked = 0
        val on = { asked++; true }
        val off = { asked++; false }
        assertEquals(StartMode.STOP, startMode(TransferService.ACTION_STOP, restarted = false, startsByItself = on))
        assertEquals(StartMode.RUN, startMode(null, restarted = false, startsByItself = off))
        // Only a restart Android made by itself (no intent) asks "Start when the TV turns on".
        assertEquals(0, asked)
        assertEquals(StartMode.RUN, startMode(null, restarted = true, startsByItself = on))
        assertEquals(StartMode.STAY_OFF, startMode(null, restarted = true, startsByItself = off))
        assertEquals(2, asked)
    }

    @Test fun theLastPortComesFirst() {
        assertEquals(listOf(8080, 8090, 8888, 9090, 0), portsToTry(null))
        assertEquals(listOf(8090, 8080, 8888, 9090, 0), portsToTry(8090))
        // Opened on "any free port" last time: that one first, then the usual ones.
        assertEquals(listOf(41234, 8080, 8090, 8888, 9090, 0), portsToTry(41234))
        assertEquals(listOf(8080, 8090, 8888, 9090, 0), portsToTry(0))
    }

    @Test fun triesAgainSoonThenEveryMinute() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), (0..5).map { retryAfterMs(it) })
        assertEquals(60_000L, retryAfterMs(1_000))
    }

    @Test fun theNotificationSaysWhatItsDoing() {
        val open = look(ServerState(running = true, on = true, base = "http://$home:8080", url = "http://$home:8080/?k=secret"))
        assertEquals("Woodruff Family: phone connection is on", open.title)
        // The address without the pairing key.
        assertEquals("http://$home:8080", open.text)
        val waiting = look(ServerState(on = true, waiting = true))
        assertEquals("Woodruff Family: waiting for the network", waiting.title)
        assertTrue(waiting.text!!.contains("Wi-Fi or Ethernet"))
        val starting = look(ServerState(on = true))
        assertEquals("Woodruff Family: starting the phone connection", starting.title)
        assertNull(starting.text)
    }
}
