package com.woodruff.tvexplorer.cloud

import android.content.Context
import android.util.Log
import com.woodruff.tvexplorer.player.RecentItem
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.player.ShowPrefs
import com.woodruff.tvexplorer.player.Watched
import com.woodruff.tvexplorer.tvs.TvLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLEncoder

/**
 * Continue watching, ✓s and skip-intro marks shared by every TV signed in to the same Google
 * account, through Drive's app-data folder (hidden; only this app can see it, and it can't touch the
 * family's files). Each TV writes its own small file there and merges the others' in, newest wins.
 */
object FamilySync {
    private const val PREFS = "family_sync"
    private const val TAG = "FamilySync"
    private const val FILES = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    /** Each TV's file is "woodruff-<its id>.json". */
    private const val PREFIX = "woodruff-"
    private const val JSON = "application/json; charset=UTF-8"
    // Stored here: this TV's own file, and the others' as last merged (id to modifiedTime).
    private const val K_FILE = "file"
    private const val K_SEEN = "seen"
    /** Something changed that the other TVs haven't been sent (kept across restarts, for a retry). */
    private const val K_DIRTY = "dirty"
    /** A change goes out this long after it, so a burst of them is one upload. */
    private const val DEBOUNCE_MS = 45_000L
    private const val PULL_EVERY_MS = 2 * 60_000L
    private const val SIGN_IN = "Sign in to Google Drive to share progress between TVs."
    private const val SIGN_IN_AGAIN = "Sign in to Google Drive again to share progress between TVs."

    // Outlives every screen: a push started as playback stops must finish after the player has gone.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One exchange with Drive at a time: two at once could each make this TV's file. */
    private val busy = Mutex()
    // Guarded by this object's lock.
    private var pending: Job? = null
    private var pendingAt = 0L
    private var edits = 0L
    @Volatile private var lastPull = 0L
    @Volatile private var lastProblem: String? = null

    private val _updates = MutableStateFlow(0)
    /** Ticks when another TV's progress was merged in: re-read RecentStore and Watched. */
    val updates: StateFlow<Int> = _updates.asStateFlow()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** "Share progress with other TVs" (Settings), on unless turned off on this TV. */
    fun enabled(c: Context): Boolean = prefs(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) {
        val app = c.applicationContext
        prefs(app).edit().putBoolean("on", on).apply()
        if (on) {
            // Back on: send everything (changes made meanwhile weren't counted) and fetch the others'.
            synchronized(this) { lastPull = 0L }
            markDirty(app)
            pullSoon(app)
        } else synchronized(this) {
            pending?.cancel()
            pending = null
            lastProblem = null
        }
    }

    /** Only Drive videos travel between TVs: a file on this TV's own storage means nothing to another. */
    internal fun isShared(key: String): Boolean = key.startsWith("drive:")

    /** Something changed (progress, a ✓, an intro mark): send it to the other TVs soon. */
    fun changed(c: Context) {
        val app = c.applicationContext
        if (!enabled(app)) return
        markDirty(app)
        schedulePush(app, DEBOUNCE_MS, quick = false)
    }

    /** Playback stopped: send now, before the TV goes to sleep. */
    fun pushNow(c: Context) {
        val app = c.applicationContext
        if (!enabled(app)) return
        // A second's wait, so what stopping saves (just before or after this call) goes with it.
        schedulePush(app, 1_000L, quick = true)
    }

    /** Fetch the other TVs' progress (at most every couple of minutes); [updates] ticks when something merged in. */
    fun pullSoon(c: Context) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastPull < PULL_EVERY_MS) return
            lastPull = now
        }
        val app = c.applicationContext
        // Also sends what an earlier push couldn't (the TV slept, or the network was down).
        scope.launch { sync(app, pull = true, quick = false) }
    }

    /** Why sharing isn't working, for Settings; null when it works or is turned off. */
    fun problem(c: Context): String? = when {
        !enabled(c) -> null
        !GoogleAuth.isConnected(c) -> SIGN_IN
        !GoogleAuth.hasScope(c, GoogleAuth.APPDATA) -> SIGN_IN_AGAIN
        else -> lastProblem
    }

    /**
     * Signed in to Drive, but without the shared folder: from before it was asked for, or its box
     * was unticked on Google's consent screen. Signing in once more (one QR scan) fixes it.
     */
    fun needsNewSignIn(c: Context): Boolean =
        enabled(c) && GoogleAuth.isConnected(c) && !GoogleAuth.hasScope(c, GoogleAuth.APPDATA)

    /** Drive was just signed in to: fetch the other TVs' progress now and send this TV's. */
    internal fun signedIn(c: Context) {
        val app = c.applicationContext
        if (!enabled(app)) return
        synchronized(this) {
            lastPull = 0L
            lastProblem = null
        }
        markDirty(app)
        pullSoon(app)
    }

    // ---- When to talk to Drive ---------------------------------------------------------------

    private fun dirty(app: Context) = prefs(app).getBoolean(K_DIRTY, false)

    @Synchronized
    private fun markDirty(app: Context) {
        edits++
        if (!dirty(app)) prefs(app).edit().putBoolean(K_DIRTY, true).apply()
    }

    /** Sent: clean again, unless something changed while it was being sent. */
    @Synchronized
    private fun sent(app: Context, editsWhenRead: Long) {
        if (edits == editsWhenRead) prefs(app).edit().putBoolean(K_DIRTY, false).apply()
    }

    /** A push in [delayMs], unless one is already due sooner. */
    @Synchronized
    private fun schedulePush(app: Context, delayMs: Long, quick: Boolean) {
        val at = System.currentTimeMillis() + delayMs
        if (pending?.isActive == true && pendingAt <= at) return
        pending?.cancel()
        pendingAt = at
        pending = scope.launch {
            delay(delayMs)
            // Running now: a change from here on needs a push of its own.
            started(coroutineContext[Job])
            sync(app, pull = false, quick = quick)
        }
    }

    @Synchronized
    private fun started(job: Job?) {
        if (pending === job) pending = null
    }

    private suspend fun sync(app: Context, pull: Boolean, quick: Boolean) {
        busy.withLock { round(app, pull, quick) }
    }

    /** One round with Drive: fetch and merge the other TVs' files ([pull]), then send this TV's if it changed. */
    private fun round(app: Context, pull: Boolean, quick: Boolean) {
        // Without the shared folder this stays off quietly; Settings says why (problem).
        if (!enabled(app) || !GoogleAuth.isConnected(app) || !GoogleAuth.hasScope(app, GoogleAuth.APPDATA)) return
        // A push with nothing new to send (an earlier round sent it).
        if (!pull && !dirty(app)) return
        try {
            val token = GoogleAuth.blockingToken(app) ?: return
            val tv = TvLinks.id(app)
            val name = "$PREFIX$tv.json"
            var mine = prefs(app).getString(K_FILE, null)
            if (pull) {
                val files = list(token, quick)
                // The listing knows best: null means this TV's file isn't there (yet, or any more).
                mine = files.filter { it.name == name }.maxByOrNull { it.modified }?.id
                val seen = readSeen(app)
                val listed = HashMap<String, String>()
                val fresh = ArrayList<SyncData>()
                for (f in files) {
                    if (f.name == name || !f.name.startsWith(PREFIX) || !f.name.endsWith(".json")) continue
                    listed[f.id] = f.modified
                    // Only what changed since it was last merged is downloaded.
                    if (seen[f.id] == f.modified) continue
                    decode(request(token, "GET", "$FILES/${enc(f.id)}?alt=media", quick))?.let { fresh += it }
                }
                if (fresh.isNotEmpty() && mergeIn(app, fresh)) _updates.value = _updates.value + 1
                prefs(app).edit().putString(K_SEEN, JSONObject().apply { listed.forEach { (id, at) -> put(id, at) } }.toString())
                    .putString(K_FILE, mine).apply()
            }
            if (dirty(app) || (pull && mine == null)) {
                val editsWhenRead = synchronized(this) { edits }
                val body = encode(local(app), tv, TvLinks.name(app)).toByteArray()
                // Just listed: a file that wasn't there needn't be looked for again.
                val id = upload(token, name, mine, look = !pull, body = body, quick = quick)
                prefs(app).edit().putString(K_FILE, id).apply()
                sent(app, editsWhenRead)
            }
            lastProblem = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sharing progress failed", e)
            lastProblem = when {
                e is HttpError && (e.code == 401 || (e.code == 403 && e.message.orEmpty().contains("scope", ignoreCase = true))) -> SIGN_IN_AGAIN
                e is HttpError -> "Couldn't share progress: ${e.message}"
                else -> "Couldn't reach Google Drive. Trying again soon."
            }
        }
    }

    // ---- This TV's side --------------------------------------------------------------------------

    /** What this TV sends: its Drive progress and ✓s, removals included, and its per-show choices. */
    private fun local(app: Context): SyncData {
        val store = RecentStore(app)
        val (on, off) = Watched.stamps(app)
        return SyncData(
            recent = store.all().filter { isShared(it.key) },
            gone = store.removals().filterKeys { isShared(it) },
            watched = on.filterKeys { isShared(it) },
            unwatched = off.filterKeys { isShared(it) },
            shows = ShowPrefs.stamps(app),
        )
    }

    /** Merges the other TVs' into each store (each in one locked step); true when anything here changed. */
    private fun mergeIn(app: Context, others: List<SyncData>): Boolean {
        val recent = RecentStore(app).syncWith { items, gone -> FamilyMerge.recent(items, gone, others) }
        val watched = Watched.syncWith(app) { on, off -> FamilyMerge.watched(on, off, others) }
        val shows = ShowPrefs.syncWith(app) { choices -> FamilyMerge.shows(choices, others) }
        return recent || watched || shows
    }

    private fun readSeen(app: Context): Map<String, String> {
        val o = prefs(app).getString(K_SEEN, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        val out = HashMap<String, String>()
        for (k in o.keys()) out[k] = o.optString(k)
        return out
    }

    // ---- The file ----------------------------------------------------------------------------------

    private fun encode(d: SyncData, tv: String, tvName: String): String = JSONObject()
        .put("v", 1).put("tv", tv).put("name", tvName).put("at", System.currentTimeMillis())
        .put("recent", JSONArray().apply {
            d.recent.forEach {
                put(JSONObject().put("key", it.key).put("title", it.title).put("pos", it.positionMs).put("dur", it.durationMs).put("at", it.updatedAt)
                    .apply { if (it.upNext) put("nx", true) })
            }
        })
        .put("gone", timesJson(d.gone))
        .put("watched", timesJson(d.watched))
        .put("unwatched", timesJson(d.unwatched))
        .put("shows", JSONObject().apply {
            d.shows.forEach { (k, s) -> put(k, JSONObject().put("at", s.at).apply { if (s.value != null) put("v", s.value) }) }
        })
        .toString()

    private fun timesJson(m: Map<String, Long>): JSONObject = JSONObject().apply { m.forEach { (k, at) -> put(k, at) } }

    /** Another TV's file; anything that doesn't belong in one (a file path, a stray setting) is left out. */
    private fun decode(text: String): SyncData? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val recent = ArrayList<RecentItem>()
        val arr = o.optJSONArray("recent")
        for (i in 0 until (arr?.length() ?: 0)) {
            val r = arr?.optJSONObject(i) ?: continue
            val key = r.optString("key")
            if (!isShared(key)) continue
            recent += RecentItem(key, r.optString("title"), r.optLong("pos").coerceAtLeast(0), r.optLong("dur").coerceAtLeast(0), r.optLong("at"), r.optBoolean("nx"))
        }
        val shows = HashMap<String, Stamped>()
        val s = o.optJSONObject("shows")
        if (s != null) {
            for (k in s.keys()) {
                val v = s.optJSONObject(k) ?: continue
                val value = if (v.has("v") && !v.isNull("v")) v.optString("v") else null
                if (!ShowPrefs.isChoice(k) || (k.startsWith("i:") && value != null && value.toLongOrNull() == null)) continue
                shows[k] = Stamped(value, v.optLong("at"))
            }
        }
        return SyncData(recent, timesFrom(o.optJSONObject("gone")), timesFrom(o.optJSONObject("watched")), timesFrom(o.optJSONObject("unwatched")), shows)
    }

    private fun timesFrom(o: JSONObject?): Map<String, Long> {
        if (o == null) return emptyMap()
        val out = HashMap<String, Long>()
        for (k in o.keys()) if (isShared(k)) out[k] = o.optLong(k)
        return out
    }

    // ---- Drive's app-data folder (www.googleapis.com only) -------------------------------------------

    private class Remote(val id: String, val name: String, val modified: String)

    private fun list(token: String, quick: Boolean): List<Remote> {
        val json = JSONObject(request(token, "GET", "$FILES?spaces=appDataFolder&pageSize=100&fields=${enc("files(id,name,modifiedTime)")}", quick))
        val files = json.optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).mapNotNull { i ->
            val f = files.optJSONObject(i) ?: return@mapNotNull null
            Remote(f.optString("id"), f.optString("name"), f.optString("modifiedTime")).takeIf { it.id.isNotEmpty() }
        }
    }

    /**
     * Writes this TV's file: replaces its contents when it's known (or, with [look], found),
     * otherwise makes it. Returns its id.
     */
    private fun upload(token: String, name: String, known: String?, look: Boolean, body: ByteArray, quick: Boolean): String {
        val id = known ?: if (look) find(token, name, quick) else null
        if (id != null) {
            try {
                request(token, "PATCH", "$UPLOAD/${enc(id)}?uploadType=media&fields=id", quick, body, JSON)
                return id
            } catch (e: HttpError) {
                if (e.code != 404) throw e // gone (deleted, or another account now): make it again
            }
        }
        val boundary = "woodruff" + System.nanoTime()
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder")).toString()
        val head = "--$boundary\r\nContent-Type: $JSON\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: $JSON\r\n\r\n"
        val made = request(
            token, "POST", "$UPLOAD?uploadType=multipart&fields=id", quick,
            head.toByteArray() + body + "\r\n--$boundary--\r\n".toByteArray(), "multipart/related; boundary=$boundary",
        )
        return JSONObject(made).getString("id")
    }

    /** This TV's file when it's there but its id isn't known here (app data cleared): never make a second. */
    private fun find(token: String, name: String, quick: Boolean): String? {
        val q = enc("name = '$name'")
        val json = JSONObject(request(token, "GET", "$FILES?spaces=appDataFolder&q=$q&fields=${enc("files(id)")}", quick))
        return json.optJSONArray("files")?.optJSONObject(0)?.optString("id")?.ifEmpty { null }
    }

    private class HttpError(val code: Int, message: String) : IOException(message)

    /** [quick]: playback just stopped and the TV may be about to sleep, so don't wait long. */
    private fun request(token: String, method: String, url: String, quick: Boolean, body: ByteArray? = null, type: String? = null): String {
        // The token only ever goes to Google.
        if (!isGoogleUrl(url)) throw IOException("Not a Google address")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = if (quick) 5_000 else 15_000
            conn.readTimeout = if (quick) 10_000 else 30_000
            if (method == "PATCH") {
                // Android's HttpURLConnection takes PATCH; Google also reads it from this header, should one not.
                try {
                    conn.requestMethod = "PATCH"
                } catch (e: ProtocolException) {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                }
            } else {
                conn.requestMethod = method
            }
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", type ?: JSON)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                val reason = runCatching { JSONObject(err).getJSONObject("error").optString("message") }.getOrNull()?.ifBlank { null }
                throw HttpError(code, reason ?: "HTTP $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/** A setting and when it was set; a null [value] was cleared then. */
internal data class Stamped(val value: String?, val at: Long)

/** What one TV shares with the others, each entry with when it last changed (merged by [FamilyMerge]). */
internal data class SyncData(
    /** Continue watching: Drive videos only. */
    val recent: List<RecentItem> = emptyList(),
    /** Taken out of Continue watching (finished, or removed by hand): key to when. */
    val gone: Map<String, Long> = emptyMap(),
    /** ✓s: key to when each was added (0: before times were kept, so the oldest). */
    val watched: Map<String, Long> = emptyMap(),
    /** ✓s taken off again: key to when. */
    val unwatched: Map<String, Long> = emptyMap(),
    /** Skip-intro marks and languages ("i:", "a:", "t:" + show) with when each was set; null: cleared. */
    val shows: Map<String, Stamped> = emptyMap(),
)

/**
 * Merging the TVs' progress, kept free of Android so it's unit tested. Per key the newest wins,
 * and a removal is an entry like any other: newer than the progress, it takes it away everywhere;
 * older, the progress comes back. As new as each other, the removal wins; two settings as new as
 * each other keep this TV's (only ones from before times were kept can tie).
 */
internal object FamilyMerge {
    /** Every part at once: what this TV would hold after merging [others] in. */
    fun merge(local: SyncData, others: List<SyncData>): SyncData {
        val (recent, gone) = recent(local.recent, local.gone, others)
        val (watched, unwatched) = watched(local.watched, local.unwatched, others)
        return SyncData(recent, gone, watched, unwatched, shows(local.shows, others))
    }

    /** Continue watching: the newest entry or removal per key; the [RecentStore.MAX] newest entries, newest first. */
    fun recent(items: List<RecentItem>, gone: Map<String, Long>, others: List<SyncData>): Pair<List<RecentItem>, Map<String, Long>> {
        val latest = LinkedHashMap<String, RecentItem>()
        val removed = HashMap(gone)
        fun offer(item: RecentItem) {
            val had = latest[item.key]
            if (had == null || item.updatedAt > had.updatedAt) latest[item.key] = item
        }
        items.forEach { offer(it) }
        for (o in others) {
            o.recent.forEach { offer(it) }
            o.gone.forEach { (k, at) -> if (at > (removed[k] ?: Long.MIN_VALUE)) removed[k] = at }
        }
        val kept = latest.values.filter { it.updatedAt > (removed[it.key] ?: Long.MIN_VALUE) }
        // Watched again after a removal: the removal is spent.
        kept.forEach { removed.remove(it.key) }
        // Stable: entries as new as each other keep this TV's order.
        return kept.sortedByDescending { it.updatedAt }.take(RecentStore.MAX) to newest(removed, RecentStore.MAX_GONE)
    }

    /** ✓s: per key the newest of a mark and an unmark. */
    fun watched(on: Map<String, Long>, off: Map<String, Long>, others: List<SyncData>): Pair<Map<String, Long>, Map<String, Long>> {
        val marks = HashMap(on)
        val unmarks = HashMap(off)
        for (o in others) {
            o.watched.forEach { (k, at) -> if (at > (marks[k] ?: Long.MIN_VALUE)) marks[k] = at }
            o.unwatched.forEach { (k, at) -> if (at > (unmarks[k] ?: Long.MIN_VALUE)) unmarks[k] = at }
        }
        val kept = marks.filter { (k, at) -> at > (unmarks[k] ?: Long.MIN_VALUE) }
        val taken = unmarks.filterKeys { it !in kept }
        return newest(kept, Watched.MAX) to newest(taken, Watched.MAX_OFF)
    }

    /** Per-show choices: per key the newest (a cleared one included). */
    fun shows(local: Map<String, Stamped>, others: List<SyncData>): Map<String, Stamped> {
        val out = HashMap(local)
        for (o in others) o.shows.forEach { (k, s) -> if (s.at > (out[k]?.at ?: Long.MIN_VALUE)) out[k] = s }
        return out
    }

    /** The [max] newest of [times] (all of them when there are no more than that). */
    fun newest(times: Map<String, Long>, max: Int): Map<String, Long> =
        if (times.size <= max) times
        else times.entries.sortedByDescending { it.value }.take(max).associate { it.key to it.value }
}
