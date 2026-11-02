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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import com.woodruff.tvexplorer.data.StorageRepository
import com.woodruff.tvexplorer.mail.MailPoller
import com.woodruff.tvexplorer.nest.Doorbell
import com.woodruff.tvexplorer.settings.SharedKeys
import com.woodruff.tvexplorer.tvs.TvLinks
import com.woodruff.tvexplorer.update.Updater
import com.woodruff.tvexplorer.util.NetworkWatch
import com.woodruff.tvexplorer.util.backoffMs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * What the Phone screen shows. [url] already includes the pairing key; [base] doesn't.
 *
 * Whatever needs the phone connection open (the Google sign-ins' QR codes) waits for [running] or
 * [error]. While the TV has no home network yet it's [waiting], with neither: those just keep
 * waiting, and carry on by themselves the moment it opens.
 */
data class ServerState(
    /** Open, and phones reach it at [url] now. */
    val running: Boolean = false,
    /** Where phones reach it; kept while [waiting] after it was open (the TV usually gets the same address back). */
    val url: String? = null,
    val base: String? = null,
    /** Why it couldn't start, in plain words. Set only when it gave up (it's off). */
    val error: String? = null,
    val log: List<String> = emptyList(),
    /** On: starting, [waiting], or [running]. */
    val on: Boolean = false,
    /**
     * On, but this TV has no home-network address right now (the Wi-Fi still connecting after the TV
     * turned on or woke, the router restarting after a power cut): it opens, or comes back, by itself
     * as soon as there is one, however long that takes.
     */
    val waiting: Boolean = false,
)

/**
 * Foreground service hosting [TransferServer], and with it everything that has to go on without
 * anyone at the TV: the phone app (at home and through Tailscale), the name other TVs find this one
 * by, new-mail checks, update checks, shared keys and the doorbell relay. So it stays on:
 * - It waits for a home-network address for as long as it takes, looking again whenever Wi-Fi or
 *   Ethernet changes ([LanWatch]), and follows the TV to a new address. The background checks wait
 *   for a network, not for the phone connection.
 * - Android brings it back after closing Woodruff for memory, or after a crash (START_STICKY), when
 *   "Start when the TV turns on" is on ([AutoStart]). Android 12 and later may refuse that restart
 *   from the background unless the app may draw over other apps (Settings → Pop-ups over other apps;
 *   on Android 15 and later only while it shows a pop-up); when it refuses, the service stops
 *   cleanly and the next start that's allowed brings it back: Woodruff opening, the TV turning on,
 *   or an update.
 *
 * State is published through [state] rather than binding, since there is only ever one server per
 * process.
 */
class TransferService : Service() {
    /** Proves which instance may publish [state]: one that's ending can't overwrite the next one's. */
    private val me = Any()
    /** A failure no one caught is written down, never a crash that takes the whole app with it. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "phone connection: ${e.message}", e) },
    )
    private val watch by lazy { LanWatch(applicationContext) }
    @Volatile private var server: TransferServer? = null
    /** The address [server] is shown at now; null while there's none (or nothing is open). */
    @Volatile private var shownAt: String? = null
    /** Address and port this TV is announced at, so a new one is announced again (and a flicker isn't). */
    @Volatile private var announced: String? = null
    /** The port last opened: opened again first, so phones' saved links keep working. */
    @Volatile private var lastPort: Int? = null
    /** Since when (elapsedRealtime) the pairing key has been unreadable; null while it's fine. */
    @Volatile private var keyUnreadableSince: Long? = null
    // Main thread only.
    private var inForeground = false
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        synchronized(lock) { live = me }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A sticky restart (Android bringing the service back after a kill) comes without an intent.
        when (startMode(intent?.action, restarted = intent == null) { AutoStart.enabled(this) }) {
            StartMode.STOP -> { stopSelf(); return START_NOT_STICKY }
            StartMode.STAY_OFF -> { stopSelf(startId); return START_NOT_STICKY }
            StartMode.RUN -> Unit
        }
        // Into the foreground once, promptly (Android expects it within seconds of a start). Never
        // again while running: Android checks a second startForeground() against the background
        // rules afresh. Later changes only update the notification.
        if (!inForeground && !enterForeground()) {
            publish { ServerState(error = COULDNT_START) }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            publish { it.copy(on = true, error = null) }
            run()
        }
        return START_STICKY
    }

    /**
     * Shows the ongoing notification as the service enters the foreground. False when Android refuses:
     * on Android 12 and later a restart from the background may need an exemption (see the class).
     */
    private fun enterForeground(): Boolean = try {
        foreground(this, NOTIFICATION_ID, notification(look(_state.value)), foregroundType())
        inForeground = true
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException on Android 12+, or whatever a TV maker's build throws.
        Log.w(TAG, "Android didn't let the phone connection start", e)
        false
    }

    private fun foregroundType(): Int = when {
        // specialUse has no daily time limit, unlike dataSync on Android 15.
        Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    /** Everything else off the main thread: reading the phone page, the pairing key (Keystore), the network and binding a port all take time. */
    private fun run() {
        val app = applicationContext
        scope.launch {
            watch.start()
            keepOpen()
        }
        // Rings reach every TV through this one only once there's a network to pull and relay over.
        // Only while this instance still runs: stopped meanwhile (onDestroy clears [live] first, then
        // stops the doorbell), a start now would leave the doorbell running with no service.
        scope.launch {
            awaitOnline()
            synchronized(lock) { if (live === me) Doorbell.start(app) }
        }
        scope.launch { keepChecking(app) }
    }

    /**
     * Opens the phone connection once there's a home-network address, and keeps it that way: shown at
     * the new address when the TV gets one, opened again if Android closed its socket. Never gives up.
     */
    private suspend fun keepOpen() {
        var failures = 0
        while (true) {
            val mark = watch.changes.value
            val address = addressNow()
            if (!step(address)) {
                delay(retryAfterMs(failures++))
                continue
            }
            failures = 0
            watch.awaitChange(mark, if (address == null) LOOK_AGAIN_WAITING_MS else LOOK_AGAIN_OPEN_MS)
        }
    }

    /**
     * Does what [nextStep] says for [address]. False when opening didn't work yet (tried again
     * shortly); anything unexpected counts the same, so the loop itself never ends.
     */
    private fun step(address: String?): Boolean = try {
        val s = server
        when (nextStep(open = s != null, alive = s?.isAlive == true, shownAt = shownAt, address = address)) {
            NextStep.WAIT -> { showWaiting(); true }
            NextStep.OPEN, NextStep.REOPEN -> address != null && open(address)
            NextStep.MOVE -> { if (address != null) showAt(address); true }
            NextStep.STAY -> true
        }
    } catch (e: PairingKey.NotReadyYet) {
        // Some TVs' Keystore needs a while after they turn on; a made-up key would turn every phone away.
        Log.i(TAG, "waiting for the pairing key to be readable")
        false
    } catch (e: Exception) {
        Log.w(TAG, "couldn't open the phone connection", e)
        false
    }

    private fun open(address: String): Boolean {
        // A server whose socket Android closed: let it go first (its port is free again).
        server?.let { old -> server = null; runCatching { old.stop() } }
        val app = applicationContext
        val token = token(app)
        val page = assets.open("web/index.html").bufferedReader().use { it.readText() }
        val storage = StorageRepository(app)
        val routes = CompanionRoutes(app)
        for (port in portsToTry(lastPort)) {
            val s = TransferServer(port, token, page, storage::roots, ::log, routes::handle, routes::public, homeName = { PhoneIcon.label(app) })
            try {
                s.start(SOCKET_TIMEOUT_MS, true)
            } catch (e: IOException) {
                continue
            }
            synchronized(lock) {
                // Stopped while this was starting: don't leave it open.
                if (live !== me) { s.stop(); return true }
                server = s
            }
            lastPort = s.listeningPort
            showAt(address)
            return true
        }
        return false
    }

    /**
     * The pairing key phones were paired with; [PairingKey.NotReadyYet] while the TV's Keystore can't
     * read it yet, so opening waits. Only if that lasts far longer than any hiccup does it open with a
     * key for this run ([PairingKey.forThisRun]): phones then scan again, but the phone connection
     * is never off for good because of a Keystore that keeps failing.
     */
    private fun token(app: Context): String = try {
        pairingKey(app).also { keyUnreadableSince = null }
    } catch (e: PairingKey.NotReadyYet) {
        val now = SystemClock.elapsedRealtime()
        val since = keyUnreadableSince ?: now.also { keyUnreadableSince = it }
        if (now - since < PAIRING_KEY_PATIENCE_MS) throw e
        Log.w(TAG, "the pairing key stayed unreadable: a key for this run only")
        PairingKey.forThisRun(app)
    }

    /** Phones reach the server at [address] now: say so, and announce it to the other TVs if it's new. */
    private fun showAt(address: String) {
        val s = server ?: return
        val base = "http://$address:${s.listeningPort}"
        val first = announced == null
        shownAt = address
        publish {
            it.copy(
                running = true, waiting = false, error = null, url = "$base/?k=${s.token}", base = base,
                log = if (first) listOf("Phone connection ready") else it.log,
            )
        }
        update()
        val at = "$address:${s.listeningPort}"
        if (announced != at) {
            announced = at
            // Let other Woodruff TVs on the network find this one (Settings → Add another TV), at its new address too.
            runCatching { TvLinks.advertise(applicationContext, s.listeningPort) }
        }
        // Mail checks and the doorbell started with the service, waiting for a network of their own.
    }

    /** No home-network address: say it's waiting (an open server keeps listening, for when it's back). */
    private fun showWaiting() {
        if (shownAt == null && _state.value.waiting) return
        shownAt = null
        publish { it.copy(running = false, waiting = true) }
        update()
    }

    /** New-mail notifications, updates and the keys every TV shares: soon after the start, then every few minutes, whenever there's a network. */
    private suspend fun keepChecking(app: Context) {
        // Not during launch: the first screen gets the network and CPU first.
        delay(FIRST_CHECK_MS)
        while (true) {
            // Nothing to reach without a network: wait for one (just after the TV turns on, after standby).
            awaitOnline()
            runCatching { MailPoller.check(app) }
            runCatching { Updater.maybeAuto(app) }
            // The keys every TV shares: a TV that was off, reset or just linked gets the family's keys.
            runCatching { SharedKeys.sync(app) }
            // And the family message board's last pin, for a TV that was off when it was pinned.
            runCatching { com.woodruff.tvexplorer.notify.Board.sync(app) }
            // A linked TV whose sign-in Google ended: this TV's phones are told, once.
            runCatching { com.woodruff.tvexplorer.tvs.SignInAlerts.sync(app) }
            delay(MAIL_INTERVAL_MS)
        }
    }

    /** Returns once the TV has a network: a home-network address, or any network Android counts as the default. */
    private suspend fun awaitOnline() {
        while (true) {
            val mark = watch.changes.value
            if (addressNow() != null || NetworkWatch.hasNetworkNow(this)) return
            watch.awaitChange(mark, LOOK_AGAIN_WAITING_MS)
        }
    }

    private fun log(line: String) = publish { it.copy(log = (listOf(line) + it.log).take(50)) }

    /** Changes [state], unless this instance has ended (its last coroutines may still be finishing). */
    private fun publish(change: (ServerState) -> ServerState) = synchronized(lock) {
        if (live === me) _state.value = change(_state.value)
    }

    /**
     * The notification follows the state; [Service.startForeground] is never called again for it.
     * Only while this instance runs: posted after the service ended, it would stay up on its own.
     */
    private fun update() {
        synchronized(lock) {
            if (live !== me) return
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(look(_state.value))) }
        }
    }

    override fun onDestroy() {
        val ended = synchronized(lock) { (live === me).also { if (it) live = null } }
        watch.stop()
        TvLinks.stopAdvertising(applicationContext)
        Doorbell.stop()
        scope.cancel()
        synchronized(lock) {
            server?.stop()
            server = null
        }
        // Off, keeping only why it couldn't start, if that's why it ended.
        if (ended) _state.value = ServerState(error = _state.value.error)
        super.onDestroy()
    }

    private fun notification(look: Look): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phone connection", NotificationManager.IMPORTANCE_MIN))
        }
        // Woodruff as it was, as the TV's app row opens it.
        val open = PendingIntent.getActivity(this, 0, com.woodruff.tvexplorer.MainIntents.resume(this), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(look.title)
            .setContentText(look.text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "TransferService"
        internal const val ACTION_STOP = "stop"
        private const val CHANNEL = "transfer"
        private const val NOTIFICATION_ID = 1
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAIL_INTERVAL_MS = 5 * 60_000L
        private const val FIRST_CHECK_MS = 20_000L
        /** How often to look for an address by itself while there's none, in case a TV never reports the network. */
        private const val LOOK_AGAIN_WAITING_MS = 30_000L
        /** How often to look by itself while open (an address change nobody reported, a socket Android closed). */
        private const val LOOK_AGAIN_OPEN_MS = 5 * 60_000L
        /**
         * How long to wait for an unreadable pairing key before opening with one for this run: as
         * long as [com.woodruff.tvexplorer.settings.SecretStore] takes to decide a missing key is gone.
         */
        private const val PAIRING_KEY_PATIENCE_MS = 10 * 60_000L

        /** Said wherever the phone connection is needed and it gave up. */
        const val COULDNT_START = "The phone connection couldn’t start."

        private val lock = Any()
        /** The instance allowed to publish [state] ([TransferService.me]); null while none runs. */
        @Volatile private var live: Any? = null
        private val _state = MutableStateFlow(ServerState())
        val state: StateFlow<ServerState> = _state.asStateFlow()

        /** Where the service finds this TV's home-network address. Tests put in their own. */
        @Volatile internal var addressNow: () -> String? = { lanAddress() }

        /** The phone pairing key ([PairingKey]; throws [PairingKey.NotReadyYet] while unreadable). Tests use their own, apart from the Keystore. */
        @Volatile internal var pairingKey: (Context) -> String = { PairingKey.get(it) }

        /** How the service enters the foreground. Tests make Android refuse. */
        @Volatile internal var foreground: (Service, Int, Notification, Int) -> Unit =
            { service, id, notification, type -> ServiceCompat.startForeground(service, id, notification, type) }

        /** Shows [s] as the state, for the UI gallery (no service runs there). */
        internal fun showForGallery(s: ServerState) { _state.value = s }

        /**
         * Starts the phone connection (or reminds it it's wanted). Never throws: when Android won't
         * let it start (from the background on Android 12+), whatever waits for it hears why.
         */
        fun start(context: Context) {
            if (_state.value.running) return
            // Why an earlier run stopped is no answer for this one.
            synchronized(lock) { if (live == null && _state.value.error != null) _state.value = ServerState() }
            val i = Intent(context, TransferService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i) }
                .onFailure { e ->
                    Log.w(TAG, "couldn't start the phone connection", e)
                    synchronized(lock) { if (live == null) _state.value = ServerState(error = COULDNT_START) }
                }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferService::class.java))
        }

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

        /** First private IPv4 address on an up, non-loopback interface (Wi-Fi or Ethernet). */
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

/** What a start of the service does. */
internal enum class StartMode {
    RUN,
    /** The notification's Stop. */
    STOP,
    /** Android brought it back after closing Woodruff, but it's not to start by itself ("Start when the TV turns on" is off). */
    STAY_OFF,
}

/**
 * What a start of the service does: [action] is the start's, [restarted] when Android brought it back
 * by itself (a sticky restart has no intent). [startsByItself] is only asked then.
 */
internal fun startMode(action: String?, restarted: Boolean, startsByItself: () -> Boolean): StartMode = when {
    action == TransferService.ACTION_STOP -> StartMode.STOP
    restarted && !startsByItself() -> StartMode.STAY_OFF
    else -> StartMode.RUN
}

/** What the phone connection does next, from what the service sees now. */
internal enum class NextStep {
    /** No home-network address: wait for one. An open server keeps listening for when it's back. */
    WAIT,
    /** An address, nothing open yet: open. */
    OPEN,
    /** The server stopped by itself (its socket closed under it): open it again. */
    REOPEN,
    /** A new address, or the address back after a while without one: show the server there. */
    MOVE,
    /** Nothing changed. */
    STAY,
}

/**
 * [open]: a server was opened; [alive]: it still listens; [shownAt]: the address it's shown at (null
 * while waiting); [address]: this TV's home-network address now.
 */
internal fun nextStep(open: Boolean, alive: Boolean, shownAt: String?, address: String?): NextStep = when {
    address == null -> NextStep.WAIT
    !open -> NextStep.OPEN
    !alive -> NextStep.REOPEN
    address != shownAt -> NextStep.MOVE
    else -> NextStep.STAY
}

/** The phone connection's usual ports, in order; 0 is any free one. */
private val PORTS = listOf(8080, 8090, 8888, 9090, 0)

/** The ports to try: the last one first (phones keep links with it), then the usual ones. */
internal fun portsToTry(last: Int?): List<Int> = (listOfNotNull(last?.takeIf { it > 0 }) + PORTS).distinct()

/** The pause before open attempt number [n] + 1 after [n] that didn't work: 5 s, doubling, at most a minute. */
internal fun retryAfterMs(n: Int): Long = backoffMs(n, 5_000L, 60_000L)

/** The ongoing notification's words. */
internal data class Look(val title: String, val text: String?)

internal fun look(s: ServerState): Look = when {
    s.running -> Look("Woodruff Family: phone connection is on", s.base)
    s.waiting -> Look("Woodruff Family: waiting for the network", "The phone connection opens once this TV is on Wi-Fi or Ethernet")
    else -> Look("Woodruff Family: starting the phone connection", null)
}
