package com.woodruff.tvexplorer.phones

import android.content.Context
import android.util.Log
import com.woodruff.tvexplorer.server.TransferServer
import com.woodruff.tvexplorer.settings.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
 *  2. An inbox the companion web app checks every few seconds while it's open ([PhoneInbox]: kept on the TV's
 *     disk and read without being taken, so a restart or a lost reply never loses a message).
 */
object Phones {
    private const val KEY = "phones"
    private const val NTFY = "https://ntfy.sh/"
    private val ID = Regex("[A-Za-z0-9-]{8,64}")
    private val _items = MutableStateFlow<List<Phone>>(emptyList())
    val items: StateFlow<List<Phone>> = _items
    @Volatile private var loaded = false

    /**
     * The paired phones, read from the encrypted store once per run. While the Keystore can't read the saved list yet
     * (some TVs, for a while after they turn on) there are none for now, and the next call reads it again: taken as
     * none for the whole run, announcements would skip every phone, and the next phone to say hello would be saved
     * over the list, losing every other phone and its Lock Screen alerts for good (see [write]).
     */
    fun load(c: Context) {
        if (loaded) return
        val store = SecretStore.get(c)
        // Read outside the lock: a Keystore still waking can take a moment, and nobody waits behind it. Asked again
        // when it isn't unreadable: another thread may have read it as the key turned up.
        val saved = store.get(KEY) ?: if (store.isUnreadableNow(KEY)) return else store.get(KEY)
        synchronized(this) {
            if (loaded) return
            loaded = true
            _items.value = runCatching {
                val arr = JSONArray(saved ?: "[]")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Phone(o.getString("id"), o.getString("name"), o.optString("topic").ifBlank { null }, o.optLong("seen"))
                }
            }.getOrDefault(emptyList())
        }
    }

    /** Tests: forgets what this run of the app read (the phones), as the next run would. */
    @androidx.annotation.VisibleForTesting
    internal fun forgetForTest() = synchronized(this) {
        loaded = false
        _items.value = emptyList()
    }

    private const val LIST_NOT_YET = "This TV can’t read its list of phones just now. Try again in a moment."

    /**
     * Registers or renames a phone. [alerts] true creates its ntfy topic, false removes it. While the list of phones
     * can't be read yet ([load]) nothing is saved, and the phone hears so (its own alerts and topic stay as they were).
     */
    @Synchronized
    fun hello(c: Context, id: String, name: String, alerts: Boolean?): Phone {
        load(c)
        require(ID.matches(id)) { "Bad phone id" }
        if (!loaded) throw IOException(LIST_NOT_YET)
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
        // A list not read yet has nothing to remove (and shows none of it).
        if (!loaded) return
        PhoneInbox.remove(c, id)
        write(c, _items.value.filter { it.id != id })
    }

    /**
     * Sends to the given phones (all when [ids] is null). Returns how many phones it was sent to;
     * ntfy delivery happens in the background and failures are only logged.
     *
     * [id]: the message's own id, so a phone shows it once however often it arrives. [link]: an address in the
     * phone app ("#settings/tvs/this"), opened when the banner is tapped; it never goes to ntfy (a pushed address
     * would carry the pairing key). [pushTitle] and [pushBody]: different words for the Lock Screen, where the
     * banner's "Tap to…" can't be done.
     */
    fun announce(
        c: Context, ids: Collection<String>?, title: String, body: String, from: String?,
        id: String? = null, link: String? = null, pushTitle: String = title, pushBody: String = body,
    ): Int {
        load(c)
        val targets = _items.value.filter { ids == null || it.id in ids }
        val msg = JSONObject().put("title", title).put("body", body).put("from", from ?: "TV").put("at", System.currentTimeMillis())
            .apply { id?.let { put("id", it) }; link?.let { put("link", it) } }
        // Kept for each phone's web app until it has them (saved, in the background), whether or not it has ntfy.
        PhoneInbox.add(c, targets.map { it.id }, msg)
        val pushTo = targets.mapNotNull { it.topic }
        if (pushTo.isNotEmpty()) thread(name = "ntfy") { pushTo.forEach { push(it, pushTitle, pushBody) } }
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

    /** Saves [list] as the phones. Never over a list this run couldn't read yet ([load]): every phone not in [list] would be lost. */
    private fun write(c: Context, list: List<Phone>) {
        if (!loaded) throw IOException(LIST_NOT_YET)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("topic", it.topic ?: "").put("seen", it.lastSeen)) }
        SecretStore.get(c).put(KEY, arr.toString())
        _items.value = list
    }
}
