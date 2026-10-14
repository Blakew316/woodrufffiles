package com.woodruff.tvexplorer.phones

import android.content.Context
import android.util.Log
import com.woodruff.tvexplorer.server.TransferServer
import com.woodruff.tvexplorer.settings.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** A phone that opened the companion web app, with the name its owner gave it ("Mom's iPhone"). */
data class Phone(val id: String, val name: String, val topic: String?, val lastSeen: Long)

/**
 * Paired phones and announcements to them.
 *
 * Delivery has two paths, because iPhones only allow lock-screen notifications from HTTPS sites
 * with a push service, which a TV on the home network can't be:
 *  1. Lock-screen alerts through the free ntfy app (ntfy.sh). Each phone gets its own random topic;
 *     the TV posts to it and ntfy pushes to the phone. The topic name is the only secret.
 *  2. An inbox the companion web app checks every few seconds while it's open.
 */
object Phones {
    private const val KEY = "phones"
    private const val NTFY = "https://ntfy.sh/"
    private val ID = Regex("[A-Za-z0-9-]{8,64}")
    private val _items = MutableStateFlow<List<Phone>>(emptyList())
    val items: StateFlow<List<Phone>> = _items
    private val inbox = ConcurrentHashMap<String, MutableList<JSONObject>>()
    @Volatile private var loaded = false

    @Synchronized
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        _items.value = runCatching {
            val arr = JSONArray(SecretStore.get(c).get(KEY) ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Phone(o.getString("id"), o.getString("name"), o.optString("topic").ifBlank { null }, o.optLong("seen"))
            }
        }.getOrDefault(emptyList())
    }

    /** Registers or renames a phone. [alerts] true creates its ntfy topic, false removes it. */
    @Synchronized
    fun hello(c: Context, id: String, name: String, alerts: Boolean?): Phone {
        load(c)
        require(ID.matches(id)) { "Bad phone id" }
        val old = _items.value.firstOrNull { it.id == id }
        val topic = when (alerts) {
            true -> old?.topic ?: ("woodruff-" + TransferServer.randomToken() + TransferServer.randomToken())
            false -> null
            null -> old?.topic
        }
        val label = name.trim().take(40).ifBlank { old?.name ?: "iPhone" }
        val p = Phone(id, label, topic, System.currentTimeMillis())
        write(c, (_items.value.filter { it.id != id } + p).sortedBy { it.name.lowercase() })
        return p
    }

    @Synchronized
    fun remove(c: Context, id: String) {
        load(c)
        inbox.remove(id)
        write(c, _items.value.filter { it.id != id })
    }

    /** Messages waiting for this phone's web app; reading them empties the inbox. */
    fun takeInbox(id: String): JSONArray {
        val list = inbox.remove(id) ?: return JSONArray()
        return synchronized(list) { JSONArray(list) }
    }

    /**
     * Sends to the given phones (all when [ids] is null). Returns how many phones it was sent to;
     * ntfy delivery happens in the background and failures are only logged.
     */
    fun announce(c: Context, ids: Collection<String>?, title: String, body: String, from: String?): Int {
        load(c)
        val targets = _items.value.filter { ids == null || it.id in ids }
        val msg = JSONObject().put("title", title).put("body", body).put("from", from ?: "TV").put("at", System.currentTimeMillis())
        targets.forEach { p ->
            val box = inbox.getOrPut(p.id) { mutableListOf() }
            synchronized(box) { box.add(msg); while (box.size > 20) box.removeAt(0) }
        }
        val pushTo = targets.mapNotNull { it.topic }
        if (pushTo.isNotEmpty()) thread(name = "ntfy") { pushTo.forEach { push(it, title, body) } }
        return targets.size
    }

    private fun push(topic: String, title: String, body: String) {
        try {
            val conn = URL(NTFY).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = JSONObject().put("topic", topic).put("title", title)
                .put("message", body.ifBlank { title }).put("tags", JSONArray().put("tv"))
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) Log.w("Phones", "ntfy returned $code")
            conn.disconnect()
        } catch (e: Exception) {
            Log.w("Phones", "ntfy push failed", e)
        }
    }

    fun toJson(p: Phone, withTopic: Boolean): JSONObject = JSONObject()
        .put("id", p.id).put("name", p.name).put("alerts", p.topic != null).put("seen", p.lastSeen)
        .apply { if (withTopic && p.topic != null) put("topic", p.topic) }

    private fun write(c: Context, list: List<Phone>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("topic", it.topic ?: "").put("seen", it.lastSeen)) }
        SecretStore.get(c).put(KEY, arr.toString())
        _items.value = list
    }
}
