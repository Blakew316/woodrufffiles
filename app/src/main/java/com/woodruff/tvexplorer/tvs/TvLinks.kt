package com.woodruff.tvexplorer.tvs

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.woodruff.tvexplorer.nowplaying.NowPlaying
import com.woodruff.tvexplorer.nowplaying.Playing
import com.woodruff.tvexplorer.remote.TvScreen
import com.woodruff.tvexplorer.server.TransferServer
import com.woodruff.tvexplorer.settings.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Another TV this one can see: its address and link key (stored encrypted). */
data class LinkedTv(val id: String, val name: String, val base: String, val key: String)

/** A TV's live status, for the phone's TV picker and the Command center. */
data class TvStatus(val id: String, val name: String, val online: Boolean, val onScreen: Boolean, val playing: Playing?)

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

    val LINK_ROUTES = setOf("/api/status", "/api/screen", "/api/key", "/api/watch", "/api/doorbell")

    fun isLinkKey(c: Context, k: String?): Boolean =
        k != null && MessageDigest.isEqual(k.toByteArray(), linkKey(c).toByteArray())

    fun status(c: Context): JSONObject = JSONObject()
        .put("id", id(c)).put("name", name(c)).put("onScreen", TvScreen.onScreen)
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

    @Volatile private var recent: Pair<Long, List<TvStatus>>? = null

    /**
     * This TV plus every linked TV, fetched in parallel (offline TVs answer within 2.5 s). Reused
     * for 3 s, so several phones and the Command center polling at once cost one round of requests.
     */
    suspend fun statuses(c: Context): List<TvStatus> {
        recent?.let { (at, list) -> if (System.currentTimeMillis() - at < 3_000 && list.size == _linked.value.size + 1) return list }
        return fetchStatuses(c).also { recent = System.currentTimeMillis() to it }
    }

    private suspend fun fetchStatuses(c: Context): List<TvStatus> = coroutineScope {
        load(c)
        val me = async(Dispatchers.IO) { parse(status(c), online = true) }
        val others = _linked.value.map { tv ->
            async(Dispatchers.IO) {
                runCatching { parse(JSONObject(request("${tv.base}/api/status?k=${enc(tv.key)}", "GET", null)), true).copy(id = tv.id, name = tv.name) }
                    .getOrElse { TvStatus(tv.id, tv.name, online = false, onScreen = false, playing = null) }
            }
        }
        listOf(me.await()) + others.awaitAll()
    }

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

    fun press(tv: LinkedTv, key: String) {
        request("${tv.base}/api/key?k=${enc(tv.key)}", "POST", JSONObject().put("key", key).toString())
    }

    private fun parse(o: JSONObject, online: Boolean) = TvStatus(
        o.optString("id"), o.optString("name"), online, o.optBoolean("onScreen"), Playing.fromJson(o.optJSONObject("playing")),
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
