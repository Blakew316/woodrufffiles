package com.woodruff.tvexplorer.tvs

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.nowplaying.NowPlaying
import com.woodruff.tvexplorer.nowplaying.Playing
import com.woodruff.tvexplorer.remote.TvScreen
import com.woodruff.tvexplorer.server.TransferServer
import com.woodruff.tvexplorer.settings.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Another TV this one can see: its address and link key (stored encrypted). */
data class LinkedTv(val id: String, val name: String, val base: String, val key: String)

/** A TV's live status, for the phone's TV picker and the Command center. */
data class TvStatus(val id: String, val name: String, val online: Boolean, val onScreen: Boolean, val playing: Playing?, val version: String? = null)

/** The TVs a message is for: this one ([here]) and linked TVs by id ([others]; null means all of them). */
data class TvTargets(val here: Boolean, val others: Set<String>?) {
    companion object {
        val ALL = TvTargets(true, null)
        val HERE = TvTargets(true, emptySet())
        val NONE = TvTargets(false, emptySet())
    }
}

/**
 * Several TVs, one place to watch them. Each TV running Woodruff Family advertises itself on the
 * home network (mDNS, "_woodruff._tcp"). To link, the other TV shows a 6-digit code for 10 minutes
 * (Settings → Link this TV); this TV finds it on the network and swaps the code for its link key.
 * The link key is not the phone pairing key: it only opens status, screen and remote keys, never
 * files, settings or mail. From then on this TV (the hub) fetches each TV's status, screen and remote keys for the
 * phone, so the phone only ever talks to one TV, at home or through Tailscale.
 *
 * A TV's address can change (a new router, a power cut, another app on port 8080), so each TV also
 * advertises its id. When a linked TV stops answering, the hub looks for it by that id (or, from an
 * older Woodruff, by name), checks it's that TV with its link key, and saves the new address.
 */
object TvLinks {
    const val SERVICE_TYPE = "_woodruff._tcp."
    private const val K_LINKED = "tvs.linked"
    private const val K_ID = "tvs.id"
    private const val K_NAME = "tvs.name"
    private const val K_LINK_KEY = "tvs.linkkey"
    private const val CODE_TTL_MS = 10 * 60_000L
    /** A linked TV that stopped answering is looked for on the network at most this often. */
    private const val REFIND_EVERY_MS = 3 * 60_000L
    /** How long each look lasts. */
    private const val REFIND_SEARCH_MS = 5_000L

    private val _linked = MutableStateFlow<List<LinkedTv>>(emptyList())
    val linked: StateFlow<List<LinkedTv>> = _linked
    @Volatile private var loaded = false

    // ---- This TV ---------------------------------------------------------------------------

    fun id(c: Context): String = SecretStore.get(c).get(K_ID) ?: UUID.randomUUID().toString().also { SecretStore.get(c).put(K_ID, it) }
    fun name(c: Context): String = SecretStore.get(c).get(K_NAME)?.takeIf { it.isNotBlank() } ?: "${Build.MODEL ?: "Android"} TV"
    fun setName(c: Context, name: String) = SecretStore.get(c).put(K_NAME, name.trim().take(40).ifBlank { null })

    /** What other TVs use to reach this one. Only accepted for [LINK_ROUTES]. */
    private fun linkKey(c: Context): String =
        SecretStore.get(c).get(K_LINK_KEY) ?: TransferServer.randomToken().also { SecretStore.get(c).put(K_LINK_KEY, it) }

    /** Unlinks every TV that can see this one; they'd need a new code. */
    fun resetLinkKey(c: Context) { SecretStore.get(c).put(K_LINK_KEY, TransferServer.randomToken()) }

    val LINK_ROUTES = setOf("/api/status", "/api/screen", "/api/key", "/api/watch", "/api/doorbell", "/api/sync", "/api/board", "/api/notice")

    fun isLinkKey(c: Context, k: String?): Boolean =
        k != null && MessageDigest.isEqual(k.toByteArray(), linkKey(c).toByteArray())

    fun status(c: Context): JSONObject = JSONObject()
        .put("id", id(c)).put("name", name(c)).put("onScreen", TvScreen.onScreen)
        .put("version", com.woodruff.tvexplorer.BuildConfig.VERSION_NAME)
        .put("playing", NowPlaying.current(c)?.toJson() ?: JSONObject.NULL)

    // ---- Being linked (the other TV shows a code) -----------------------------------------------

    @Volatile private var code: String? = null
    @Volatile private var codeUntil = 0L
    @Volatile private var wrongTries = 0

    /** A fresh 6-digit code, valid for 10 minutes and one use. */
    fun newCode(): String {
        val c = (100000 + SecureRandom().nextInt(900000)).toString()
        code = c; codeUntil = System.currentTimeMillis() + CODE_TTL_MS; wrongTries = 0
        return c
    }

    fun cancelCode() { code = null }

    /** Called by the hub (public route). Returns this TV's id/name/link key, or null when the code is wrong. */
    @Synchronized
    fun redeem(c: Context, attempt: String): JSONObject? {
        val current = code ?: return null
        if (System.currentTimeMillis() > codeUntil) { code = null; return null }
        if (attempt.trim() != current) {
            // Five wrong guesses kill the code: a 6-digit code can't be brute-forced.
            if (++wrongTries >= 5) code = null
            return null
        }
        code = null
        return JSONObject().put("id", id(c)).put("name", name(c)).put("key", linkKey(c))
    }

    // ---- The hub's list --------------------------------------------------------------------------

    @Synchronized
    fun load(c: Context) {
        appContext = c.applicationContext
        if (loaded) return
        loaded = true
        _linked.value = runCatching {
            val arr = JSONArray(SecretStore.get(c).get(K_LINKED) ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LinkedTv(o.getString("id"), o.getString("name"), o.getString("base"), o.getString("key"))
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(c: Context, list: List<LinkedTv>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("base", it.base).put("key", it.key)) }
        SecretStore.get(c).put(K_LINKED, arr.toString())
        _linked.value = list
    }

    /** Changes the list in one step, so a TV linked or unlinked while another's new address is saved keeps both changes. */
    @Synchronized
    private fun edit(c: Context, change: (List<LinkedTv>) -> List<LinkedTv>) = save(c, change(_linked.value))

    fun remove(c: Context, id: String) { load(c); edit(c) { list -> list.filter { it.id != id } } }

    /** Links the TV at [base] using the code it shows. */
    suspend fun link(c: Context, base: String, code: String): LinkedTv = withContext(Dispatchers.IO) {
        load(c)
        val body = JSONObject().put("code", code.filter { it.isDigit() }).put("from", name(c))
        val r = try {
            request("$base/api/link", "POST", body.toString())
        } catch (e: IOException) {
            throw IOException(if (e.message?.contains("403") == true) "That code didn't work. Check the code on the other TV." else "Couldn't reach that TV.")
        }
        val o = JSONObject(r)
        val tv = LinkedTv(o.getString("id"), o.getString("name"), base, o.getString("key"))
        if (tv.id == id(c)) throw IOException("That's this TV.")
        edit(c) { list -> list.filter { it.id != tv.id } + tv }
        tv
    }

    // ---- Talking to linked TVs ---------------------------------------------------------------

    /** Status checks and relays to other TVs, which outlive the request that started them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var recent: Pair<Long, List<TvStatus>>? = null
    private val checks = StatusChecks(scope) { tv ->
        try {
            parse(JSONObject(request("${tv.base}/api/status?k=${enc(tv.key)}", "GET", null)), true).copy(id = tv.id, name = tv.name)
        } catch (e: Exception) {
            // Off, or at a new address: look for it on the network (in the background).
            lost(tv)
            throw e
        }
    }

    /**
     * This TV plus every linked TV, without waiting on a TV that's asleep: each gets about 300 ms to
     * answer, and a slower one shows as it last did while its check finishes in the background (a
     * TV never heard from counts as off after about 1 s). Reused for 3 s, so several phones and the
     * Command center polling at once cost one round of requests.
     */
    suspend fun statuses(c: Context): List<TvStatus> {
        recent?.let { (at, list) -> if (System.currentTimeMillis() - at < 3_000 && list.size == _linked.value.size + 1) return list }
        return fetchStatuses(c).also { recent = System.currentTimeMillis() to it }
    }

    private suspend fun fetchStatuses(c: Context): List<TvStatus> = withContext(Dispatchers.IO) {
        load(c)
        val me = async { parse(status(c), online = true) }
        val others = checks.statuses(_linked.value)
        listOf(me.await()) + others
    }

    /** The statuses last fetched (empty before the first), for a screen to start from. */
    fun lastStatuses(): List<TvStatus> = recent?.second.orEmpty()

    fun find(id: String): LinkedTv? = _linked.value.firstOrNull { it.id == id }

    fun screen(tv: LinkedTv): ByteArray? {
        val conn = open("${tv.base}/api/screen?k=${enc(tv.key)}", "GET")
        try {
            return if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
        } catch (e: IOException) {
            lost(tv)
            throw e
        } finally {
            conn.disconnect()
        }
    }

    // A message that fails because the TV moved goes again once it's found at its new address, a
    // few seconds late (a video started there catches up with the leader; a notice shows once per id).

    /** Start [key] (a Drive video) on [tv] at [positionMs]: "watch together". */
    fun watch(tv: LinkedTv, key: String, title: String, positionMs: Long) {
        val body = JSONObject().put("key", key).put("title", title).put("position", positionMs).toString()
        reach(tv, again = true) { t -> request("${t.base}/api/watch?k=${enc(t.key)}", "POST", body) }
    }

    /** Pin a family message on [tv]'s Home. */
    fun board(tv: LinkedTv, title: String, body: String, from: String?) {
        val json = JSONObject().put("title", title).put("body", body).put("from", from ?: "").toString()
        reach(tv, again = true) { t -> request("${t.base}/api/board?k=${enc(t.key)}", "POST", json) }
    }

    /** Pop up a message on [tv], with its announcement chime; [id] makes a repeat show only once. */
    fun notice(tv: LinkedTv, title: String, body: String, from: String?, id: String, source: NoticeSource = NoticeSource.PHONE) {
        val json = JSONObject().put("title", title).put("body", body).put("from", from ?: "").put("id", id).put("source", source.name).toString()
        reach(tv, again = true) { t -> request("${t.base}/api/notice?k=${enc(t.key)}", "POST", json) }
    }

    /** Watch together: the leader's position and play/pause, for [tv] to follow. */
    fun sync(tv: LinkedTv, key: String, positionMs: Long, playing: Boolean) {
        val json = JSONObject().put("key", key).put("position", positionMs).put("playing", playing).put("at", System.currentTimeMillis()).toString()
        // Never sent again: it would be out of date, and the leader sends a fresh one every few seconds.
        reach(tv, again = false) { t -> request("${t.base}/api/sync?k=${enc(t.key)}", "POST", json) }
    }

    /** Doorbell rang (seen by the TV that watches for it): tell [tv] too. */
    fun doorbell(tv: LinkedTv, camera: String, label: String, kind: String = "doorbell") {
        val json = JSONObject().put("camera", camera).put("label", label).put("kind", kind).toString()
        reach(tv, again = true) { t -> request("${t.base}/api/doorbell?k=${enc(t.key)}", "POST", json) }
    }

    /** Every linked TV, in parallel; failures (TV off) are ignored here, and a TV that moved is looked for in the background ([lost]). */
    suspend fun forEachLinked(c: Context, action: (LinkedTv) -> Unit) = coroutineScope {
        load(c)
        _linked.value.map { tv -> async(Dispatchers.IO) { runCatching { action(tv) } } }.awaitAll()
        Unit
    }

    /**
     * An announcement for the linked TVs [ids] (null: every one), sent in the background so nobody
     * waits on a TV that's off (it's skipped). Returns how many TVs it goes to.
     */
    fun announce(
        c: Context, ids: Set<String>?, title: String, body: String, from: String?,
        source: NoticeSource = NoticeSource.PHONE, id: String = UUID.randomUUID().toString(),
    ): Int {
        val app = c.applicationContext
        load(app)
        val count = _linked.value.count { ids == null || it.id in ids }
        if (count > 0) scope.launch { forEachLinked(app) { tv -> if (ids == null || tv.id in ids) notice(tv, title, body, from, id, source) } }
        return count
    }

    /** [pickTargets] for this TV and the TVs linked to it. */
    fun targets(c: Context, asked: List<String>?, default: TvTargets): TvTargets? {
        load(c)
        return pickTargets(asked, default, id(c), name(c), _linked.value)
    }

    fun press(tv: LinkedTv, key: String) {
        val json = JSONObject().put("key", key).toString()
        // Never sent again: a key press arriving seconds late could do the wrong thing.
        reach(tv, again = false) { t -> request("${t.base}/api/key?k=${enc(t.key)}", "POST", json) }
    }

    private fun parse(o: JSONObject, online: Boolean) = TvStatus(
        o.optString("id"), o.optString("name"), online, o.optBoolean("onScreen"), Playing.fromJson(o.optJSONObject("playing")),
        o.optString("version").ifBlank { null },
    )

    private fun open(url: String, method: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 2_500
        readTimeout = 5_000
    }

    private fun request(url: String, method: String, body: String?): String {
        val conn = open(url, method)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw failure(code, runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull())
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The other TV answered but couldn't do it, and said why ("Netflix isn't installed on this TV"). */
    class Refused(message: String) : IOException(message)

    // ---- Finding a linked TV again after its address changed -----------------------------------

    /** Linked TVs that stopped answering, to look for on the network. */
    private val refinds = Refinds(REFIND_EVERY_MS) { SystemClock.elapsedRealtime() }

    /** For looking for TVs after a status check or a message fails, which have no context of their own (set by [load]). */
    @Volatile private var appContext: Context? = null

    /**
     * Sends something to [tv] with [send]. When [tv] doesn't answer, it's looked for again (see
     * [lost]); with [again], the same goes once more if it turns up at a new address.
     */
    private fun reach(tv: LinkedTv, again: Boolean, send: (LinkedTv) -> Unit) {
        try {
            send(tv)
        } catch (e: IOException) {
            // A TV that said no (with its reason) is where it should be: nothing to look for.
            if (e !is Refused) lost(tv, if (again) send else null)
            throw e
        }
    }

    /**
     * [tv] didn't answer: it may be off, or at a new address. Looks for it on the network in the
     * background, at most every few minutes and without holding anyone up, and sends it [again]
     * once it turns up at a new address.
     */
    private fun lost(tv: LinkedTv, again: ((LinkedTv) -> Unit)? = null) {
        val c = appContext ?: return
        val now = find(tv.id) ?: return
        // Found at its new address since this was sent: straight there.
        if (now.base != tv.base) {
            again?.let { send -> scope.launch { runCatching { send(now) } } }
            return
        }
        if (refinds.lost(tv.id, again)) scope.launch {
            while (true) {
                val ids = refinds.next() ?: break
                runCatching { search(c, ids) }.onFailure { Log.w("TvLinks", "Looking for TVs failed: $it") }
            }
        }
    }

    /**
     * Looks on the network for the linked TVs [ids] for a few seconds. A TV that turns up is asked
     * for its status with its own link key, so the key only goes to an address being checked as
     * that TV, and saved there when its address changed.
     */
    private suspend fun search(c: Context, ids: Set<String>) {
        val left = ids.filterTo(HashSet()) { find(it) != null }
        if (left.isEmpty()) return
        val found = Channel<Found>(Channel.UNLIMITED)
        val stop = discover(c) { found.trySend(it) }
        try {
            withTimeoutOrNull(REFIND_SEARCH_MS) {
                for (f in found) {
                    val tv = candidatesFor(f, _linked.value.filter { it.id in left }).firstOrNull { isTv(it, f.base) } ?: continue
                    left.remove(tv.id)
                    if (f.base != tv.base) moved(c, tv.id, f.base)?.let { now ->
                        Log.i("TvLinks", "Found ${tv.name} at a new address")
                        refinds.found(tv.id).forEach { send -> scope.launch { runCatching { send(now) } } }
                    }
                    if (left.isEmpty()) break
                }
            }
        } finally {
            stop()
            found.close()
        }
    }

    /** Whether the Woodruff at [base] is [tv]: it takes [tv]'s link key and gives [tv]'s id. */
    private fun isTv(tv: LinkedTv, base: String): Boolean = runCatching {
        JSONObject(request("$base/api/status?k=${enc(tv.key)}", "GET", null)).optString("id") == tv.id
    }.getOrDefault(false)

    /** Saves [base] as the address of the linked TV [id]; null when it was unlinked meanwhile. */
    @Synchronized
    private fun moved(c: Context, id: String, base: String): LinkedTv? {
        val tv = find(id)?.copy(base = base) ?: return null
        save(c, _linked.value.map { if (it.id == id) tv else it })
        return tv
    }

    // ---- Finding TVs on the network (mDNS) -----------------------------------------------

    @Volatile private var registration: NsdManager.RegistrationListener? = null
    /** The name this TV is advertised under: Android adds " (2)" when another TV on the network has the same name. */
    @Volatile private var registeredName: String? = null

    /** Announces this TV while its phone connection runs. */
    fun advertise(c: Context, port: Int) {
        stopAdvertising(c)
        val nsd = c.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply { serviceName = name(c); serviceType = SERVICE_TYPE; setPort(port) }
        // So linked TVs can find this one again when its address changes (older versions ignore it).
        runCatching { info.setAttribute(TXT_ID, id(c)) }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { if (registration === this) registeredName = info.serviceName }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.w("TvLinks", "mDNS register failed $error") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { if (registration === listener) registration = null }
    }

    fun stopAdvertising(c: Context) {
        val l = registration ?: return
        registration = null
        registeredName = null
        runCatching { c.getSystemService(NsdManager::class.java)?.unregisterService(l) }
    }

    /** A TV found on the network: its name, address ("http://192.168.1.21:8080") and id (null from older versions). */
    data class Found(val name: String, val base: String, val id: String? = null)

    /** Looks for other TVs until stopped; [onFound] is called on a background thread. */
    fun discover(c: Context, onFound: (Found) -> Unit): () -> Unit {
        val nsd = c.getSystemService(NsdManager::class.java) ?: return {}
        val myId = id(c)
        // This TV's own advertisement, under the name Android gave it.
        val myName = registeredName ?: name(c)
        val resolver = java.util.concurrent.Executors.newSingleThreadExecutor()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) {}
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == myName) return
                // Older Android resolves one service at a time (a second request fails), so queue them.
                runCatching { resolver.execute { resolve(nsd, info) { r ->
                    val host = ipv4(r) ?: return@resolve
                    val id = runCatching { txtId(r.attributes) }.getOrNull()
                    if (id != myId) onFound(Found(r.serviceName, "http://$host:${r.port}", id))
                } } }
            }
        }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        return { runCatching { nsd.stopServiceDiscovery(listener) }; resolver.shutdownNow() }
    }

    /** Resolves [info] (its address, port and TXT record) for [onResolved], waiting up to 4 s. */
    @Suppress("DEPRECATION")
    private fun resolve(nsd: NsdManager, info: NsdServiceInfo, onResolved: (NsdServiceInfo) -> Unit) {
        val done = java.util.concurrent.CountDownLatch(1)
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) { done.countDown() }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    try { onResolved(info) } catch (e: Exception) { Log.w("TvLinks", "mDNS: $e") } finally { done.countDown() }
                }
            })
            done.await(4, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Refused, or stopped while waiting (interrupted): skip this one, quietly.
        }
    }

    /**
     * A resolved TV's home-network IPv4 address (192.168…, 10…), the kind its phone connection
     * listens on: never somewhere outside, where a link key could leak. Android 14+ also lists IPv6 ones.
     */
    @Suppress("DEPRECATION")
    private fun ipv4(info: NsdServiceInfo): String? =
        (if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host))
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
}

// ---- Choosing and merging (plain logic, unit-tested) ------------------------------------------

/**
 * The linked TVs' statuses, asked with [ask] (blocking: the network). Each TV is asked once at a
 * time, and its last answer (or "off", when it last didn't answer) is remembered. A check that runs
 * long finishes in [scope], after the caller has moved on, ready for the next call.
 */
internal class StatusChecks(private val scope: CoroutineScope, private val ask: (LinkedTv) -> TvStatus) {
    private val known: MutableMap<String, TvStatus> = ConcurrentHashMap()
    /** Checks under way, by TV id: a second caller waits on the same one. */
    private val running = HashMap<String, Deferred<TvStatus>>()

    /** [tvs] now: those that answer within [quickMs] fresh, the rest as last heard ([awaitAnswers]). */
    suspend fun statuses(tvs: List<LinkedTv>, quickMs: Long = 300, firstMs: Long = 1_000): List<TvStatus> {
        val heard = known.keys.toSet()
        awaitAnswers(tvs.associate { it.id to check(it) }, heard, quickMs, firstMs)
        return lastKnown(tvs, known)
    }

    private fun check(tv: LinkedTv): Deferred<TvStatus> = synchronized(running) {
        running[tv.id] ?: scope.async {
            runCatching { ask(tv) }.getOrElse { offlineStatus(tv) }.also { known[tv.id] = it }
        }.also { d ->
            running[tv.id] = d
            d.invokeOnCompletion { synchronized(running) { if (running[tv.id] === d) running.remove(tv.id) } }
        }
    }
}

/** A linked TV that didn't answer. */
internal fun offlineStatus(tv: LinkedTv) = TvStatus(tv.id, tv.name, online = false, onScreen = false, playing = null)

/** Each linked TV as last heard from (just now, when it answered in time), or off if never heard from. */
internal fun lastKnown(tvs: List<LinkedTv>, known: Map<String, TvStatus>): List<TvStatus> =
    tvs.map { tv -> known[tv.id]?.copy(id = tv.id, name = tv.name) ?: offlineStatus(tv) }

/**
 * Waits up to [quickMs] for every check in [running] (by TV id); a TV not in [heard] (nothing to
 * show for it yet) gets up to [firstMs] in all. Checks still going then carry on in the background.
 */
internal suspend fun awaitAnswers(running: Map<String, Job>, heard: Set<String>, quickMs: Long, firstMs: Long) {
    if (withTimeoutOrNull(quickMs) { running.values.joinAll() } != null) return
    val firstTime = running.filterKeys { it !in heard }.values
    if (firstTime.isNotEmpty()) withTimeoutOrNull(firstMs - quickMs) { firstTime.joinAll() }
}

/**
 * Reads a message's "tvs" ([asked]): "all", "this", or TVs by id or name, where this TV's own id or
 * name counts as "this". Nothing asked: [default]. Null when it names no TV there is.
 */
internal fun pickTargets(asked: List<String>?, default: TvTargets, meId: String, meName: String, linked: List<LinkedTv>): TvTargets? {
    val wanted = asked.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    if (wanted.isEmpty()) return default
    if (wanted.any { it.equals("all", ignoreCase = true) }) return TvTargets.ALL
    var here = false
    val ids = LinkedHashSet<String>()
    for (w in wanted) {
        if (w.equals("this", ignoreCase = true) || w == meId || w.equals(meName, ignoreCase = true)) here = true
        else linked.firstOrNull { it.id == w || it.name.equals(w, ignoreCase = true) }?.let { ids.add(it.id) }
    }
    return if (here || ids.isNotEmpty()) TvTargets(here, ids) else null
}

// ---- Finding a linked TV again (plain logic, unit-tested) -------------------------------------

/** The key of a TV's id in its mDNS TXT record. */
private const val TXT_ID = "id"

/** Android's " (2)" (or " (3)"…) at the end of an advertised name that another device already had. */
private val RENAMED = Regex("""\s\(\d+\)$""")

/** The id in a TV's TXT record ([attributes]); null from versions that don't advertise it. */
internal fun txtId(attributes: Map<String, ByteArray?>): String? =
    attributes.entries.firstOrNull { it.key.equals(TXT_ID, ignoreCase = true) }?.value
        ?.toString(Charsets.UTF_8)?.trim()?.ifEmpty { null }

/** [name] without the " (2)" Android adds to a TV's advertisement when another TV on the network has the same name. */
internal fun withoutRenameSuffix(name: String): String = name.trim().replace(RENAMED, "").trimEnd()

/** Whether a TV advertised as [advertised] may be the TV called [name]. */
internal fun sameTvName(advertised: String, name: String): Boolean =
    withoutRenameSuffix(advertised).equals(withoutRenameSuffix(name), ignoreCase = true)

/**
 * Which of [lost] (linked TVs that stopped answering) the TV [found] on the network may be: the one
 * with its id or, when it advertises none (an older Woodruff), any with its name. Each is then
 * checked with its own link key before its new address is saved.
 */
internal fun candidatesFor(found: TvLinks.Found, lost: List<LinkedTv>): List<LinkedTv> {
    val id = found.id ?: return lost.filter { sameTvName(found.name, it.name) }
    return lost.filter { it.id == id }
}

/** Whether a TV last looked for at [last] (null: never) may be looked for again at [now]. */
/**
 * What a failed request to another TV means. A 400 with a message is that TV saying why it can't
 * (Woodruff answers that way); anything else may not be that TV at all, so it counts as unreachable.
 */
internal fun failure(code: Int, body: String?): IOException {
    val message = body?.trim()?.take(200)
    return if (code == 400 && !message.isNullOrEmpty()) TvLinks.Refused(message) else IOException("HTTP $code")
}

internal fun refindDue(last: Long?, now: Long, everyMs: Long): Boolean =
    last == null || now < last || now - last >= everyMs

/**
 * Linked TVs to look for on the network after they stopped answering: each at most once every
 * [everyMs] ([now] is the clock), all of them in one search at a time. For each, it keeps what to
 * send again if the TV turns up at a new address.
 */
internal class Refinds(private val everyMs: Long, private val now: () -> Long) {
    private val lastTry = HashMap<String, Long>()
    /** Waiting for the next search, by TV id, with what to send each again. */
    private val queued = LinkedHashMap<String, MutableList<(LinkedTv) -> Unit>>()
    /** In the search under way. */
    private val searching = HashMap<String, MutableList<(LinkedTv) -> Unit>>()
    private var busy = false

    /**
     * The TV [id] didn't answer ([again]: what to send it if it turns up at a new address). True
     * when a search should start; it then takes [next] until that's null.
     */
    @Synchronized
    fun lost(id: String, again: ((LinkedTv) -> Unit)?): Boolean {
        // Already being looked for: this goes too, if it turns up.
        (searching[id] ?: queued[id])?.let { waiting ->
            if (again != null) waiting.add(again)
            return false
        }
        val t = now()
        if (!refindDue(lastTry[id], t, everyMs)) return false
        lastTry[id] = t
        queued[id] = listOfNotNull(again).toMutableList()
        if (busy) return false
        busy = true
        return true
    }

    /** The TVs (ids) for the next search, or null when none are waiting and searching stops. */
    @Synchronized
    fun next(): Set<String>? {
        // What the last search didn't find is dropped, as for a TV that's off.
        searching.clear()
        if (queued.isEmpty()) {
            busy = false
            return null
        }
        searching.putAll(queued)
        queued.clear()
        return searching.keys.toSet()
    }

    /** The TV [id] turned up at a new address: what to send it there, each once. */
    @Synchronized
    fun found(id: String): List<(LinkedTv) -> Unit> = searching.remove(id).orEmpty()
}
