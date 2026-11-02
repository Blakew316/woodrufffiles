package com.woodruff.tvexplorer.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Says when to look at this TV's home-network address again: each time Wi-Fi or Ethernet comes,
 * goes, or changes address (a new lease from the router, Wi-Fi swapped for Ethernet). The phone
 * connection ([TransferService]) waits on it for as long as it takes after the TV turns on or wakes,
 * instead of giving up, and follows the TV to a new address.
 *
 * It listens to Wi-Fi and Ethernet themselves rather than to the app's default network: with
 * Tailscale on the TV, the default network is Tailscale's, which says nothing when the Wi-Fi under
 * it gets a new address. A home network without internet counts too: the phone connection only
 * needs the home network. Android reports on a thread of its own; a report only bumps [changes].
 * If a TV refuses the callback, [changes] never moves and the service falls back on looking again
 * every so often by itself.
 */
internal class LanWatch(private val context: Context) {
    private val _changes = MutableStateFlow(0)

    /**
     * Goes up with each report. Read it before looking at the address, then [awaitChange] with
     * that value, so a change that comes while looking still counts.
     */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var stopped = false

    /** Starts listening (a call into Android's network service: not on the main thread). */
    @Synchronized fun start() {
        if (stopped || callback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = poke()
            override fun onLost(network: Network) = poke()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = poke()
        }
        runCatching { cm.registerNetworkCallback(homeNetworks(), cb) }
            .onSuccess { callback = cb }
            .onFailure { Log.w(TAG, "can't follow the network on this TV; looking every so often instead", it) }
    }

    /** Stops listening for good (the service is going); a [start] still on its way does nothing. */
    @Synchronized fun stop() {
        stopped = true
        val cb = callback ?: return
        callback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    private fun poke() = _changes.update { it + 1 }

    /**
     * Waits until [changes] moves on from [mark] or [timeoutMs] passes. After a change, waits a
     * moment more ([SETTLE_MS]): a network comes with a burst of reports, and its address can land
     * a moment after the first of them.
     */
    suspend fun awaitChange(mark: Int, timeoutMs: Long) {
        val changed = withTimeoutOrNull(timeoutMs) { changes.first { it != mark } } != null
        if (changed) delay(SETTLE_MS)
    }

    companion object {
        private const val TAG = "LanWatch"
        const val SETTLE_MS = 500L

        /**
         * Wi-Fi or Ethernet, with or without internet (a router whose internet is still coming up
         * after a power cut already carries the phone connection). VPNs stay out, as by default.
         */
        fun homeNetworks(): NetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
    }
}
