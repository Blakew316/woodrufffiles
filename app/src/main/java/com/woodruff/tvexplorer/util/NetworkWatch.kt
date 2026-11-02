package com.woodruff.tvexplorer.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether this TV has a network, and a tick each time it comes (back) online.
 *
 * The Drive library, the calendar and the cameras wait on [comebacks] to try again the moment the
 * Wi-Fi is back after standby (or a router restart), instead of on a fixed timer that left them
 * out of date for minutes after the network returned. One default-network callback for the whole
 * app, registered by the first [start]. If a TV refuses it, [online] stays true and [comebacks]
 * never ticks, so everything falls back on its own timer.
 */
object NetworkWatch {
    private val tracker = NetworkTracker()
    private val _online = MutableStateFlow(true)
    private val _comebacks = MutableStateFlow(0)
    @Volatile private var started = false

    /**
     * Whether the TV has a network Android has checked reaches the internet (its default network,
     * validated). Unknown counts as online. For telling "Offline" (no such network) from "Can't reach
     * Google" (the network is fine, Google didn't answer), and for not starting what can't work
     * offline (a camera stream): what to retry, and when, goes by [comebacks].
     */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /**
     * Ticks each time a network comes up, and again once Android has checked it reaches the
     * internet. Read it before a try; when the try fails for want of a network, [awaitComeback]
     * with that value, so a comeback during the try itself still counts.
     */
    val comebacks: StateFlow<Int> = _comebacks.asStateFlow()

    /**
     * Starts watching (once per process; later calls do nothing). Safe from any thread: the asking
     * (calls into Android's network service) happens on a thread of its own, so app start-up never
     * waits on it.
     */
    fun start(c: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = c.applicationContext
        Thread({ watch(app) }, "network-watch").start()
    }

    private fun watch(app: Context) {
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            // Where things stand now, so the callback's first report (of the network already up)
            // isn't taken for a comeback.
            val now = cm.activeNetwork
            note(tracker.reset(now, now?.let { cm.getNetworkCapabilities(it) }?.validated() == true))
            cm.registerDefaultNetworkCallback(callback)
        }.onFailure { Log.w("NetworkWatch", "can't watch the network on this TV", it) }
    }

    /** What Android reports about the default network (on its own thread), into [tracker]. */
    internal val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = note(tracker.available(network))
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = note(tracker.capabilities(network, caps.validated()))
        override fun onLost(network: Network) = note(tracker.lost(network))
    }

    /**
     * Waits until the network comes back after [mark] (a [comebacks] value read before the try that
     * failed), or [timeoutMs] passes, whichever is first. True when it came back.
     */
    suspend fun awaitComeback(mark: Int, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { comebacks.first { it != mark } } != null

    private fun note(change: NetworkTracker.Change) {
        _online.value = change.online
        if (change.cameBack) _comebacks.update { it + 1 }
    }

    /**
     * Whether Android checked the network reaches the internet. A VPN counts as checked: some TVs
     * (Android 9 and 10) never mark one validated, and a VPN that's up reaches what it reaches.
     */
    private fun NetworkCapabilities.validated() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) || hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

/**
 * The default network's comings and goings as [NetworkWatch] reports them, kept apart from Android
 * so it can be tested. A network is identified by anything with equals (Android's [Network]).
 */
internal class NetworkTracker {
    /**
     * [online]: there's a default network and Android's internet check passed on it (a Wi-Fi that has
     * only just joined isn't online yet). [cameBack]: worth trying again now.
     */
    data class Change(val online: Boolean, val cameBack: Boolean)

    private var current: Any? = null
    private var validated = false

    /** How things stand when watching starts: never a comeback. */
    @Synchronized fun reset(network: Any?, validated: Boolean): Change {
        current = network
        this.validated = network != null && validated
        return Change(online = this.validated, cameBack = false)
    }

    /**
     * A network became the default: a comeback, unless it's the one already known. A new one isn't
     * online until Android's check passes on it ([capabilities]).
     */
    @Synchronized fun available(network: Any): Change {
        val fresh = network != current
        if (fresh) { current = network; validated = false }
        return Change(online = validated, cameBack = fresh)
    }

    /**
     * What the default network can do changed. Passing Android's internet check is a comeback (Wi-Fi
     * associates a moment before names resolve, so a try made as it came up may have failed).
     */
    @Synchronized fun capabilities(network: Any, validated: Boolean): Change {
        // A network not announced first (some Android 7 TVs): it's the default now.
        val fresh = network != current
        if (fresh) { current = network; this.validated = false }
        val gained = validated && !this.validated
        this.validated = validated
        return Change(online = validated, cameBack = fresh || gained)
    }

    /** The default network went away (standby, the router restarting, Wi-Fi switched off). */
    @Synchronized fun lost(network: Any): Change {
        if (network == current) { current = null; validated = false }
        return Change(online = current != null && validated, cameBack = false)
    }
}

/**
 * Marks a failure as the network's (no connection, or Google unreachable just now), for
 * [isNetworkFailure]: it's worth trying again once the network is back, and it's never a reason to
 * sign in again.
 */
interface NetworkFailure

/**
 * Whether [e] (or what caused it) is the network failing rather than a server's answer: no
 * connection yet, a name that won't resolve, a timeout, a connection that dropped, a secure
 * connection that couldn't be set up (a TV whose clock is still wrong after a power cut, a Wi-Fi
 * sign-in page), or a [NetworkFailure].
 */
fun isNetworkFailure(e: Throwable): Boolean {
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth++ < 8) {
        when (t) {
            is NetworkFailure,
            is java.net.UnknownHostException,
            // ConnectException, NoRouteToHostException, "connection reset", "software caused connection abort".
            is java.net.SocketException,
            // SocketTimeoutException, and an interrupted read.
            is java.io.InterruptedIOException,
            is javax.net.ssl.SSLException,
            // The connection closed part-way through an answer.
            is java.io.EOFException,
            -> return true
        }
        t = t.cause
    }
    return false
}

/** The pause before retry number [n] (0 for the first): [firstMs], doubling each time, at most [maxMs]. */
fun backoffMs(n: Int, firstMs: Long, maxMs: Long): Long = (firstMs shl n.coerceIn(0, 20)).coerceAtMost(maxMs)
