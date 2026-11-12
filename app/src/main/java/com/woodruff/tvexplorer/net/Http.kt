package com.woodruff.tvexplorer.net

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * The app's one HTTP client: the player's Drive streams and the images share its connections, so
 * a request reuses a warm connection instead of a new handshake each time. Never give it an
 * interceptor that adds the Google token: TMDB and other hosts use it too.
 */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Ipv4First)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * A host's IPv4 addresses before its IPv6 ones.
 *
 * OkHttp tries a host's addresses one after another, each for the whole connect timeout, in the
 * order Android gives them: the IPv6 ones first wherever the TV has an IPv6 address. Where those
 * don't answer (a home network whose IPv6 doesn't get out), every one of them is waited on in turn
 * before the first IPv4 one is tried, and that for each host a film comes from: a film that Google
 * was sending in half a second took over a minute to start. Phones, Macs and Apple TVs try both
 * kinds at once and never show it. IPv6 is still tried, after IPv4, when no IPv4 address answers.
 */
internal object Ipv4First : Dns {
    override fun lookup(hostname: String): List<InetAddress> = order(Dns.SYSTEM.lookup(hostname))

    /** [all] with the IPv4 addresses first, each kind in the order it came. */
    fun order(all: List<InetAddress>): List<InetAddress> = all.filter { it is Inet4Address } + all.filter { it !is Inet4Address }
}
