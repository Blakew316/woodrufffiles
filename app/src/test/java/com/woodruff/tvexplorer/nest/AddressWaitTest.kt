package com.woodruff.tvexplorer.nest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wait for this TV's network addresses before a camera's offer goes to Google (finding 35):
 * over at the first public address, half a second after the first local one, or when every network
 * has answered, and never longer than 3 s. No network is left out.
 */
class AddressWaitTest {
    private val start = 50_000L

    private fun waitFrom(start: Long) = AddressWait().apply { begin(start) }

    @Test fun aPublicAddressEndsTheWaitAtOnce() {
        val w = waitFrom(start)
        w.found("host", start + 20)
        w.found("srflx", start + 90)
        assertEquals(0L, w.left(start + 90))
        assertEquals(AddressesEnd.PUBLIC, w.end)
    }

    @Test fun everyPartOfTheOfferGetsItsPublicAddress() {
        // The sound, the picture and the data channel ask for addresses of their own until Google's
        // answer joins them: the first public address (the picture's) doesn't end it alone.
        val w = waitFrom(start)
        for (part in 0..2) w.found("host", start + 5 + part, part)
        w.found("srflx", start + 61, part = 1)
        assertNull(w.end)
        assertTrue(w.left(start + 61) > 0)
        w.found("srflx", start + 62, part = 0)
        assertNull(w.end)
        w.found("srflx", start + 64, part = 2)
        assertEquals(AddressesEnd.PUBLIC, w.end)
        assertEquals(0L, w.left(start + 64))
    }

    @Test fun aPartThatNeverGetsAPublicAddressWaitsOnlyHalfASecond() {
        val w = waitFrom(start)
        w.found("host", start + 10, part = 0)
        w.found("host", start + 11, part = 1)
        w.found("srflx", start + 70, part = 0)
        assertNull(w.end)
        assertTrue(w.left(start + 10 + LOCAL_GRACE_MS) <= 0)
        assertEquals(AddressesEnd.LOCAL, w.end)
    }

    @Test fun onlyLocalAddressesWaitHalfASecondMore() {
        val w = waitFrom(start)
        w.found("host", start + 30)
        assertEquals(LOCAL_GRACE_MS, w.left(start + 30))
        assertTrue(w.left(start + 30 + LOCAL_GRACE_MS - 1) > 0)
        assertNull(w.end)
        assertTrue(w.left(start + 30 + LOCAL_GRACE_MS) <= 0)
        assertEquals(AddressesEnd.LOCAL, w.end)
    }

    @Test fun aPublicAddressInThatHalfSecondStillCounts() {
        val w = waitFrom(start)
        w.found("host", start + 30)
        w.left(start + 200)
        w.found("srflx", start + 300)
        assertEquals(AddressesEnd.PUBLIC, w.end)
    }

    @Test fun everyNetworkAnsweringEndsIt() {
        val w = waitFrom(start)
        w.found("host", start + 10)
        w.complete()
        assertEquals(0L, w.left(start + 40))
        assertEquals(AddressesEnd.ALL, w.end)
    }

    @Test fun neverLongerThanThreeSeconds() {
        val w = waitFrom(start)
        assertEquals(ADDRESS_LIMIT_MS, w.left(start))
        assertTrue(w.left(start + ADDRESS_LIMIT_MS - 1) > 0)
        assertTrue(w.left(start + ADDRESS_LIMIT_MS) <= 0)
        assertEquals(AddressesEnd.LIMIT, w.end)
    }

    @Test fun aLocalAddressFoundLateDoesntStretchTheLimit() {
        val w = waitFrom(start)
        w.found("host", start + 2_800)
        // Not 3.3 s: the 3 s limit stands.
        assertEquals(200L, w.left(start + 2_800))
        assertTrue(w.left(start + ADDRESS_LIMIT_MS) <= 0)
        assertEquals(AddressesEnd.LIMIT, w.end)
    }

    @Test fun theFirstReasonStands() {
        val w = waitFrom(start)
        w.found("srflx", start + 50)
        w.complete()
        w.found("host", start + 60)
        assertEquals(AddressesEnd.PUBLIC, w.end)
    }

    @Test fun aVpnsAddressCountsLikeAnyOther() {
        // A VPN on the TV (its tun interface) may be its only way out: its addresses aren't ignored.
        val w = waitFrom(start)
        w.found(candidateType("candidate:1 1 udp 2122194687 100.101.102.103 43210 typ host generation 0 network-id 3"), start + 15)
        assertEquals(LOCAL_GRACE_MS - 5, w.left(start + 20))
    }

    @Test fun relayedAndUnknownAddressesNeitherEndNorStartTheWait() {
        val w = waitFrom(start)
        w.found("relay", start + 10)
        w.found(null, start + 20)
        assertEquals(ADDRESS_LIMIT_MS - 100, w.left(start + 100))
        assertNull(w.end)
    }

    @Test fun theKindOfAnAddressComesFromItsLine() {
        assertEquals("host", candidateType("candidate:842163049 1 udp 1677729535 192.168.1.20 46154 typ host generation 0 ufrag x"))
        assertEquals("srflx", candidateType("candidate:3 1 udp 1686052607 203.0.113.7 56143 typ srflx raddr 192.168.1.20 rport 56143"))
        assertEquals("relay", candidateType("candidate:4 1 udp 41885439 198.51.100.2 3478 typ relay raddr 203.0.113.7 rport 56143"))
        assertEquals("prflx", candidateType("candidate:5 1 tcp 1518280447 192.168.1.20 9 typ prflx tcptype active"))
        assertNull(candidateType("candidate:6 1 udp 1 192.168.1.20 9"))
        assertNull(candidateType(null))
    }
}
