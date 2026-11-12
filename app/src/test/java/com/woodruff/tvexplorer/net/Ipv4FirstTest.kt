package com.woodruff.tvexplorer.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

/** A host's IPv4 addresses are tried before its IPv6 ones: a film never waits on IPv6 addresses that lead nowhere. */
class Ipv4FirstTest {
    private fun address(text: String): InetAddress = InetAddress.getByName(text)

    @Test fun ipv4AddressesComeFirstEachKindInItsOwnOrder() {
        val six = listOf("2001:db8::1", "2001:db8::2").map(::address)
        val four = listOf("192.0.2.10", "192.0.2.20", "192.0.2.30").map(::address)
        // As Android hands them over where the TV has an IPv6 address: IPv6 first.
        assertEquals(four + six, Ipv4First.order(six + four))
        // Mixed, and already in order: each kind keeps the order it came in.
        assertEquals(four + six, Ipv4First.order(listOf(six[0], four[0], four[1], six[1], four[2])))
        assertEquals(four + six, Ipv4First.order(four + six))
    }

    @Test fun aHostWithOneKindOfAddressIsLeftAsItIs() {
        val six = listOf("2001:db8::1", "2001:db8::2").map(::address)
        val four = listOf("192.0.2.10", "192.0.2.20").map(::address)
        assertEquals(six, Ipv4First.order(six))
        assertEquals(four, Ipv4First.order(four))
        assertEquals(emptyList<InetAddress>(), Ipv4First.order(emptyList()))
    }
}
