package com.woodruff.tvexplorer.net

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
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

    /**
     * For the app's short calls ([open]): [client]'s address order, on connections of their own, one
     * request at a time on each (HTTP/1.1), as those calls always had. They stay off the connection
     * a film is streaming on.
     */
    private val calls: OkHttpClient by lazy {
        client.newBuilder().connectionPool(ConnectionPool()).protocols(listOf(Protocol.HTTP_1_1)).build()
    }

    /**
     * A connection to [url] for a short call (a listing, a camera, a poster's details, the weather):
     * used exactly as Android's own, which `URL(url).openConnection()` gives, but IPv4 first
     * ([Ipv4First]). Every call to Google and the other services goes through this or [client]; only
     * the updater and the TVs' calls to each other (addresses on the home network) keep Android's.
     */
    fun open(url: String): HttpURLConnection = OkConnection(URL(url), calls)
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
