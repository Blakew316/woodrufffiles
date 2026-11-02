package com.woodruff.tvexplorer.notify

import android.content.Context
import com.woodruff.tvexplorer.tvs.TvLinks
import com.woodruff.tvexplorer.tvs.TvStatus
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
 *
 * A TV that was off when the message was pinned gets it later: each pin keeps the time it was
 * pinned on the hub, every TV remembers the newest pin it has shown or dismissed ([seenAt], which
 * it reports to its hub), and the hub sends its last pin again, for a week, to any linked TV that
 * reports an older one ([sync]). A pin dismissed on a TV keeps its time there, so it never comes back.
 */
object Board {
    data class Pin(val title: String, val body: String, val from: String?, val at: Long)

    private const val PREFS = "board"
    /** How long the hub keeps catching TVs up on its last pin. */
    private const val RESEND_FOR_MS = 7 * 24 * 3_600_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _pin = MutableStateFlow<Pin?>(null)
    val pin: StateFlow<Pin?> = _pin
    @Volatile private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        _pin.value = read(prefs(c), "pin")
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Pins a message here (from a phone) and, when [relay], on every linked TV. */
    fun pin(c: Context, title: String, body: String, from: String?, relay: Boolean) {
        val app = c.applicationContext
        val p = Pin(title.take(200), body.take(1000), from, System.currentTimeMillis())
        show(app, p)
        if (relay) {
            // The hub's last pin, kept after it's dismissed here, for the TVs that were off.
            write(prefs(app), "last", p)
            scope.launch { TvLinks.forEachLinked(app) { TvLinks.board(it, p.title, p.body, p.from, p.at) } }
        }
    }

    /**
     * A message the hub pinned at [at] (its clock): shown here unless this TV already showed or
     * dismissed that one, or a newer one. Its time stays the hub's, so the hub knows this TV has it.
     */
    fun receive(c: Context, title: String, body: String, from: String?, at: Long): Boolean {
        val app = c.applicationContext
        if (at <= seenAt(app)) return false
        show(app, Pin(title.take(200), body.take(1000), from, at))
        return true
    }

    private fun show(c: Context, p: Pin) {
        loaded = true
        _pin.value = p
        val prefs = prefs(c)
        write(prefs, "pin", p)
        if (p.at > seenAt(c)) prefs.edit().putLong("seenAt", p.at).apply()
    }

    fun dismiss(c: Context) {
        _pin.value = null
        prefs(c).edit().remove("pin").apply()
    }

    /** The newest pin this TV has shown or dismissed, by the time it was pinned (0: none). */
    fun seenAt(c: Context): Long = prefs(c).getLong("seenAt", 0L)

    /**
     * The hub's catch-up, run with its shared-keys check every few minutes: its last pin goes again
     * to every linked TV that's on and reports an older one ([boardCatchUp]).
     */
    suspend fun sync(c: Context) {
        val app = c.applicationContext
        val last = read(prefs(app), "last") ?: return
        if (System.currentTimeMillis() - last.at >= RESEND_FOR_MS) return
        val due = boardCatchUp(last.at, System.currentTimeMillis(), TvLinks.statuses(app))
        for (id in due) TvLinks.find(id)?.let { tv -> runCatching { TvLinks.board(tv, last.title, last.body, last.from, last.at) } }
    }

    private fun read(p: android.content.SharedPreferences, key: String): Pin? {
        val s = p.getString(key, null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            Pin(o.getString("title"), o.optString("body"), o.optString("from").ifBlank { null }, o.optLong("at"))
        }.getOrNull()
    }

    private fun write(p: android.content.SharedPreferences, key: String, pin: Pin) {
        p.edit().putString(key, JSONObject().put("title", pin.title).put("body", pin.body).put("from", pin.from ?: "").put("at", pin.at).toString()).apply()
    }
}

/**
 * Which linked TVs (ids) should get the hub's last pin (pinned at [pinnedAt]) again at [now]: those
 * on now ([statuses], this TV first and left out) that report an older pin. A TV on a Woodruff that
 * doesn't report one never gets it again, or it would come back every few minutes after being
 * dismissed. Nothing after a week.
 */
internal fun boardCatchUp(pinnedAt: Long, now: Long, statuses: List<TvStatus>): List<String> {
    if (now - pinnedAt >= 7 * 24 * 3_600_000L) return emptyList()
    return statuses.drop(1).filter { it.online && !it.refused && (it.board ?: Long.MAX_VALUE) < pinnedAt }.map { it.id }
}
