package com.woodruff.tvexplorer.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.woodruff.tvexplorer.MainActivity
import com.woodruff.tvexplorer.data.StorageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import com.woodruff.tvexplorer.mail.MailPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/** What the Phone screen shows. [url] already includes the pairing key; [base] doesn't. */
data class ServerState(
    val running: Boolean = false,
    val url: String? = null,
    val base: String? = null,
    val error: String? = null,
    val log: List<String> = emptyList(),
)

/**
 * Foreground service hosting [TransferServer], so transfers keep going while the user watches
 * something else. State is published through [state] rather than binding, since there is only
 * ever one server per process.
 */
class TransferService : Service() {
    @Volatile private var server: TransferServer? = null
    private var waiting: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (server == null && waiting?.isActive != true) start()
        return START_NOT_STICKY
    }

    private fun start() {
        // Must enter the foreground promptly after startForegroundService, even if we then fail.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Starting…"),
            when {
                // specialUse has no daily time limit, unlike dataSync on Android 15.
                Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else -> 0
            },
        )
        // Everything else off the main thread: this runs as Woodruff opens, and reading the phone
        // page, the pairing key (Keystore), the network interfaces and binding a port all take time.
        waiting = scope.launch {
            lanAddress()?.let { open(it); return@launch }
            // Right after the TV starts, Wi-Fi often isn't connected yet: wait for it (up to 3 minutes).
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Waiting for the network…"))
            repeat(36) {
                delay(5_000)
                lanAddress()?.let { open(it); return@launch }
            }
            _state.value = ServerState(error = "Not connected to a network. Connect the TV to Wi-Fi or Ethernet first.")
            stopSelf()
        }
    }

    private fun open(ip: String) {
        val page = assets.open("web/index.html").bufferedReader().use { it.readText() }
        val storage = StorageRepository(applicationContext)
        var lastError: IOException? = null
        val routes = CompanionRoutes(applicationContext)
        val token = PairingKey.get(applicationContext)
        for (port in PORTS) {
            val s = TransferServer(port, token, page, storage::roots, ::log, routes::handle, routes::public)
            try {
                s.start(SOCKET_TIMEOUT_MS, true)
                // Stopped while this was starting (it runs in the background now): don't leave it open.
                if (!scope.isActive) { s.stop(); return }
                server = s
                val base = "http://$ip:${s.listeningPort}"
                _state.value = ServerState(running = true, url = "$base/?k=${s.token}", base = base, log = listOf("Phone connection ready"))
                // Let other Woodruff TVs on the network find this one (Settings → Add another TV).
                com.woodruff.tvexplorer.tvs.TvLinks.advertise(applicationContext, s.listeningPort)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(base))
                startMailChecks()
                com.woodruff.tvexplorer.nest.Doorbell.start(applicationContext)
                return
            } catch (e: IOException) {
                lastError = e
            }
        }
        _state.value = ServerState(error = "Couldn't open a port: ${lastError?.message}")
        stopSelf()
    }

    private fun log(line: String) {
        val cur = _state.value
        _state.value = cur.copy(log = (listOf(line) + cur.log).take(50))
    }

    /** New-mail notifications: check right away, then every few minutes while the TV app runs. */
    private fun startMailChecks() {
        scope.launch {
            // Not during launch: the first screen gets the network and CPU first.
            delay(20_000)
            while (isActive) {
                runCatching { MailPoller.check(applicationContext) }
                runCatching { com.woodruff.tvexplorer.update.Updater.maybeAuto(applicationContext) }
                delay(MAIL_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        com.woodruff.tvexplorer.tvs.TvLinks.stopAdvertising(applicationContext)
        scope.cancel()
        server?.stop()
        server = null
        if (_state.value.running) _state.value = ServerState()
        super.onDestroy()
    }

    private fun notification(address: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phone connection", NotificationManager.IMPORTANCE_MIN))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Woodruff Family: phone connection is on")
            .setContentText(address)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val ACTION_STOP = "stop"
        private const val CHANNEL = "transfer"
        private const val NOTIFICATION_ID = 1
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAIL_INTERVAL_MS = 5 * 60_000L
        private val PORTS = listOf(8080, 8090, 8888, 9090, 0)

        private val _state = MutableStateFlow(ServerState())
        val state: StateFlow<ServerState> = _state.asStateFlow()

        fun start(context: Context) {
            if (_state.value.running) return
            val i = Intent(context, TransferService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferService::class.java))
        }

        /** First private IPv4 address on an up, non-loopback interface (Wi-Fi or Ethernet). */
        /**
         * The TV's Tailscale address (100.64.0.0/10), when Tailscale is running on it: the same
         * phone connection then works from anywhere, encrypted end to end by Tailscale.
         */
        fun tailscaleAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { a -> val b = a.address; (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64 }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }

        /** "http://100.x.y.z:port/?k=…" for use away from home, or null without Tailscale. */
        fun awayUrl(): String? {
            val s = state.value
            val port = s.base?.substringAfterLast(':') ?: return null
            val key = s.url?.substringAfter("?k=", "")?.ifBlank { null } ?: return null
            return tailscaleAddress()?.let { "http://$it:$port/?k=$key" }
        }

        fun lanAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
