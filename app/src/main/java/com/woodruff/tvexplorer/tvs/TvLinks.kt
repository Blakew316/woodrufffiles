package com.woodruff.tvexplorer.tvs

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
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
 */
object TvLinks {
    const val SERVICE_TYPE = "_woodruff._tcp."
    private const val K_LINKED = "tvs.linked"
    private const val K_ID = "tvs.id"
    private const val K_NAME = "tvs.name"
    private const val K_LINK_KEY = "tvs.linkkey"
    private const val CODE_TTL_MS = 10 * 60_000L

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

    fun remove(c: Context, id: String) { load(c); save(c, _linked.value.filter { it.id != id }) }

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
        save(c, _linked.value.filter { it.id != tv.id } + tv)
        tv
    }

    // ---- Talking to linked TVs ---------------------------------------------------------------

    /** Status checks and relays to other TVs, which outlive the request that started them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var recent: Pair<Long, List<TvStatus>>? = null
    private val checks = StatusChecks(scope) { tv ->
        parse(JSONObject(request("${tv.base}/api/status?k=${enc(tv.key)}", "GET", null)), true).copy(id = tv.id, name = tv.name)
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
        } finally {
            conn.disconnect()
        }
    }

    /** Start [key] (a Drive video) on [tv] at [positionMs]: "watch together". */
    fun watch(tv: LinkedTv, key: String, title: String, positionMs: Long) {
        request("${tv.base}/api/watch?k=${enc(tv.key)}", "POST", JSONObject().put("key", key).put("title", title).put("position", positionMs).toString())
    }

    /** Pin a family message on [tv]'s Home. */
    fun board(tv: LinkedTv, title: String, body: String, from: String?) {
        request("${tv.base}/api/board?k=${enc(tv.key)}", "POST", JSONObject().put("title", title).put("body", body).put("from", from ?: "").toString())
    }

    /** Pop up a message on [tv], with its announcement chime; [id] makes a repeat show only once. */
    fun notice(tv: LinkedTv, title: String, body: String, from: String?, id: String, source: NoticeSource = NoticeSource.PHONE) {
        request("${tv.base}/api/notice?k=${enc(tv.key)}", "POST",
            JSONObject().put("title", title).put("body", body).put("from", from ?: "").put("id", id).put("source", source.name).toString())
    }

    /** Watch together: the leader's position and play/pause, for [tv] to follow. */
    fun sync(tv: LinkedTv, key: String, positionMs: Long, playing: Boolean) {
        request("${tv.base}/api/sync?k=${enc(tv.key)}", "POST",
            JSONObject().put("key", key).put("position", positionMs).put("playing", playing).put("at", System.currentTimeMillis()).toString())
    }

    /** Doorbell rang (seen by the TV that watches for it): tell [tv] too. */
    fun doorbell(tv: LinkedTv, camera: String, label: String, kind: String = "doorbell") {
        request("${tv.base}/api/doorbell?k=${enc(tv.key)}", "POST", JSONObject().put("camera", camera).put("label", label).put("kind", kind).toString())
    }

    /** Every linked TV, in parallel; failures (TV off) are ignored. */
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
        request("${tv.base}/api/key?k=${enc(tv.key)}", "POST", JSONObject().put("key", key).toString())
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
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---- Finding TVs on the network (mDNS) -----------------------------------------------

    private var registration: NsdManager.RegistrationListener? = null

    /** Announces this TV while its phone connection runs. */
    fun advertise(c: Context, port: Int) {
        stopAdvertising(c)
        val nsd = c.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply { serviceName = name(c); serviceType = SERVICE_TYPE; setPort(port) }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.w("TvLinks", "mDNS register failed $error") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); registration = listener }
    }

    fun stopAdvertising(c: Context) {
        val l = registration ?: return
        registration = null
        runCatching { c.getSystemService(NsdManager::class.java)?.unregisterService(l) }
    }

    /** A TV found on the network: its name and address ("http://192.168.1.21:8080"). */
    data class Found(val name: String, val base: String)

    /** Looks for other TVs for a few seconds; [onFound] is called on a background thread. */
    fun discover(c: Context, onFound: (Found) -> Unit): () -> Unit {
        val nsd = c.getSystemService(NsdManager::class.java) ?: return {}
        val myName = name(c)
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
                runCatching { resolver.execute {
                    val done = java.util.concurrent.CountDownLatch(1)
                    @Suppress("DEPRECATION")
                    runCatching {
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, error: Int) { done.countDown() }
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                val host = info.host?.hostAddress
                                if (host != null && ':' !in host) onFound(Found(info.serviceName, "http://$host:${info.port}"))
                                done.countDown()
                            }
                        })
                    }.onFailure { done.countDown() }
                    done.await(4, java.util.concurrent.TimeUnit.SECONDS)
                } }
            }
        }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        return { runCatching { nsd.stopServiceDiscovery(listener) }; resolver.shutdownNow() }
    }
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
