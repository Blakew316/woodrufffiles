package com.woodruff.tvexplorer.notify

import android.content.Context
import com.woodruff.tvexplorer.tvs.TvLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Family message board: one pinned message, sent from a phone, shown on Home on every TV until
 * someone dismisses it there (each TV dismisses its own copy). Kept across restarts.
 */
object Board {
    data class Pin(val title: String, val body: String, val from: String?, val at: Long)

    private const val PREFS = "board"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _pin = MutableStateFlow<Pin?>(null)
    val pin: StateFlow<Pin?> = _pin
    @Volatile private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val s = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pin", null) ?: return
        _pin.value = runCatching {
            val o = JSONObject(s)
            Pin(o.getString("title"), o.optString("body"), o.optString("from").ifBlank { null }, o.optLong("at"))
        }.getOrNull()
    }

    /** Pins a message here and (when [relay]) on every linked TV. */
    fun pin(c: Context, title: String, body: String, from: String?, relay: Boolean) {
        val app = c.applicationContext
        val p = Pin(title.take(200), body.take(1000), from, System.currentTimeMillis())
        loaded = true
        _pin.value = p
        save(app, p)
        if (relay) scope.launch { TvLinks.forEachLinked(app) { TvLinks.board(it, p.title, p.body, p.from) } }
    }

    fun dismiss(c: Context) {
        _pin.value = null
        save(c.applicationContext, null)
    }

    private fun save(c: Context, p: Pin?) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (p == null) prefs.remove("pin")
        else prefs.putString("pin", JSONObject().put("title", p.title).put("body", p.body).put("from", p.from ?: "").put("at", p.at).toString())
        prefs.apply()
    }
}
