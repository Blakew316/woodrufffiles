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

/**
 * A TV's live status, for the phone's TV picker and the Command center. [refused]: it answers but
 * turns down this TV's link key (finding 55: Link again). [seen]: when it last answered. [health]:
 * its health report in a few words, when it sends one ([reportsHealth]: a Woodruff from before
 * health reports sends none). [board]: the newest pinned message it has shown or dismissed (null
 * from a Woodruff that doesn't say).
 */
data class TvStatus(
    val id: String, val name: String, val online: Boolean, val onScreen: Boolean, val playing: Playing?, val version: String? = null,
    val refused: Boolean = false, val seen: Long? = null, val health: HealthSummary? = null, val reportsHealth: Boolean = false,
    val board: Long? = null,
)

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
 * The link key is not the phone pairing key: it only opens status, screen and remote keys (and the
 * keys every TV shares: TMDB, Google's Client ID and secret, the cameras project), never files,
 * mail, sign-ins or any other setting. From then on this TV (the hub) fetches each TV's status, screen and remote keys for the
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
    /** This TV's id and name, and what it knows of linked TVs (refused links, when each last answered): plain settings, none of it secret. */
    private const val PREFS = "tvs"
    private const val P_ID = "id"
    private const val P_NAME = "name"
    private const val P_REFUSED = "refused"
    private const val P_SEEN = "seen"
    /** A TV's last answer is written down at most this often (it's asked every few seconds while a TV list is open). */
    private const val SEEN_WRITE_MS = 5 * 60_000L
    private const val CODE_TTL_MS = 10 * 60_000L
    /** A linked TV that stopped answering is looked for on the network at most this often. */
    private const val REFIND_EVERY_MS = 3 * 60_000L
    /** How long each look lasts. */
    private const val REFIND_SEARCH_MS = 5_000L

    private val _linked = MutableStateFlow<List<LinkedTv>>(emptyList())
    val linked: StateFlow<List<LinkedTv>> = _linked
    @Volatile private var loaded = false

    // ---- This TV ---------------------------------------------------------------------------

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val identity = Any()
    /** While the Keystore can't be read yet, an id for this run only (never saved). */
    @Volatile private var interimId: String? = null

    /**
     * This TV's id. Kept in plain settings (it's announced on the network anyway), so a Keystore that
     * loses its key no longer gives the TV a new identity that its hub can't recognise. Copied once
     * from the encrypted store, where versions before kept it.
     */
    fun id(c: Context): String {
        prefs(c).getString(P_ID, null)?.let { return it }
        synchronized(identity) {
            val p = prefs(c)
            p.getString(P_ID, null)?.let { return it }
            val old = runCatching { SecretStore.get(c).get(K_ID) }.getOrNull()
            if (old == null && savedSecretly(c, K_ID)) {
                // Saved, but the Keystore can't be read just yet (some TVs, just after turning on):
                // not a new identity for good, only one for now.
                return interimId ?: UUID.randomUUID().toString().also { interimId = it }
            }
            val id = old ?: UUID.randomUUID().toString()
            p.edit().putString(P_ID, id).apply()
            return id
        }
    }

    fun name(c: Context): String = givenName(c) ?: "${Build.MODEL ?: "Android"} TV"

    /** The name someone gave this TV (Settings → TVs & phones → This TV's name); null while it has none. Plain settings, like [id]. */
    fun givenName(c: Context): String? {
        val p = prefs(c)
        if (!p.contains(P_NAME)) synchronized(identity) {
            if (!p.contains(P_NAME)) {
                val old = runCatching { SecretStore.get(c).get(K_NAME) }.getOrNull()
                if (old == null && savedSecretly(c, K_NAME)) return null // not readable yet: copied once it is
                p.edit().putString(P_NAME, old?.trim().orEmpty()).apply()
            }
        }
        return p.getString(P_NAME, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setName(c: Context, name: String) {
        prefs(c).edit().putString(P_NAME, name.trim().take(40)).apply()
        runCatching { SecretStore.get(c).remove(K_NAME) }
    }

    /** Whether the encrypted store holds [name] (readable or not). */
    private fun savedSecretly(c: Context, name: String) =
        c.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE).contains(name)

    /** What other TVs use to reach this one. Only accepted for [LINK_ROUTES]. */
    private fun linkKey(c: Context): String =
        SecretStore.get(c).get(K_LINK_KEY) ?: TransferServer.randomToken().also { SecretStore.get(c).put(K_LINK_KEY, it) }

    /** The linked TV [tv] now calls itself [name]: saved in this TV's list (nothing happens when it's the same). */
    private fun renamed(tv: LinkedTv, name: String) {
        val c = appContext ?: return
        if (renamedIn(_linked.value, tv.id, name) == null) return
        edit(c) { list -> renamedIn(list, tv.id, name) ?: list }
    }

    /** Unlinks every TV that can see this one; they'd need a new code. */
    fun resetLinkKey(c: Context) { SecretStore.get(c).put(K_LINK_KEY, TransferServer.randomToken()) }

    /**
     * /api/keys: the keys every TV shares (SharedKeys: TMDB, Google Client ID and secret, cameras
     * project). /api/tmdb: the TMDB key alone, for a hub on 2.0.157, the version before the others.
     * /api/health: this TV's health report (no secrets: keys present or not, never their values).
     * /api/update/check: check for an update now (harmless; it follows the test TV's day as always).
     * /api/media: ↺10, 30↻, Next and Play/Pause for what this TV plays, from the phone through the hub
     * (no more than its remote keys can already do).
     */
    val LINK_ROUTES = setOf(
        "/api/status", "/api/screen", "/api/key", "/api/watch", "/api/doorbell", "/api/sync", "/api/board", "/api/notice", "/api/keys", "/api/tmdb",
        "/api/health", "/api/update/check", "/api/media",
    )

    fun isLinkKey(c: Context, k: String?): Boolean =
        k != null && MessageDigest.isEqual(k.toByteArray(), linkKey(c).toByteArray())

    /**
     * What this TV tells its hub every few seconds: who it is, what it plays, its health in a few
     * words ([Health.summary]) and the newest pinned message it has shown or dismissed, so the hub
     * can catch it up on one it missed while off ([com.woodruff.tvexplorer.notify.Board.sync]).
     */
    fun status(c: Context): JSONObject = JSONObject()
        .put("id", id(c)).put("name", name(c)).put("onScreen", TvScreen.onScreen)
        .put("version", com.woodruff.tvexplorer.BuildConfig.VERSION_NAME)
        .put("playing", NowPlaying.current(c)?.toJson() ?: JSONObject.NULL)
        .put("health", runCatching { Health.summary(c).toJson() }.getOrDefault(JSONObject.NULL))
        .put("board", com.woodruff.tvexplorer.notify.Board.seenAt(c))

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
        val p = prefs(c)
        _refused.value = p.getStringSet(P_REFUSED, emptySet()).orEmpty().toSet()
        runCatching { JSONObject(p.getString(P_SEEN, null) ?: "{}") }.getOrNull()?.let { o ->
            o.keys().forEach { id -> o.optLong(id).takeIf { it > 0 }?.let { seen[id] = it; seenWritten[id] = it } }
        }
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

    fun remove(c: Context, id: String) {
        load(c)
        edit(c) { list -> list.filter { it.id != id } }
        forget(c, setOf(id))
    }

    /** Links the TV at [base] using the code it shows. */
    suspend fun link(c: Context, base: String, code: String, replacing: String? = null): LinkedTv = withContext(Dispatchers.IO) {
        load(c)
        val body = JSONObject().put("code", code.filter { it.isDigit() }).put("from", name(c))
        val r = try {
            request("$base/api/link", "POST", body.toString())
        } catch (e: IOException) {
            throw IOException(if (e.message?.contains("403") == true) "That code didn’t work. Check the code on the other TV." else COULD_NOT_REACH)
        }
        val o = JSONObject(r)
        val tv = LinkedTv(o.getString("id"), o.getString("name"), base, o.getString("key"))
        if (tv.id == id(c)) throw IOException("That’s this TV.")
        var gone = emptySet<String>()
        edit(c) { list ->
            val (kept, replaced) = linkedAfter(list, tv, replacing, _refused.value)
            gone = replaced
            kept
        }
        forget(c, gone - tv.id)
        clearRefused(c, tv.id)
        sawTv(tv.id)
        // The shared keys go to the new TV now (or come from it), not at the next check.
        com.woodruff.tvexplorer.settings.SharedKeys.syncSoon(c)
        tv
    }

    /**
     * Links again a TV whose link stopped working ([id]) with the code it shows: at its saved
     * address, else wherever the network says it is now (by its id, or its name for a reset TV), for
     * up to 5 s. The old entry gives way to the new one, so there's one, not two (finding 55).
     */
    suspend fun relink(c: Context, id: String, code: String): LinkedTv = withContext(Dispatchers.IO) {
        load(c)
        val old = find(id) ?: throw IOException("That TV isn't linked any more.")
        try {
            link(c, old.base, code, replacing = id)
        } catch (e: IOException) {
            if (e.message != COULD_NOT_REACH) throw e
            val at = findOnNetwork(c, old) ?: throw e
            link(c, at, code, replacing = id)
        }
    }

    private const val COULD_NOT_REACH = "Couldn’t reach that TV."

    /** Where [tv] (by its id, else its name) is on the network now, looking for up to 5 s; null when it isn't found. */
    private suspend fun findOnNetwork(c: Context, tv: LinkedTv): String? {
        val found = Channel<Found>(Channel.UNLIMITED)
        val stop = discover(c) { found.trySend(it) }
        try {
            var byName: String? = null
            val byId = withTimeoutOrNull(REFIND_SEARCH_MS) {
                for (f in found) {
                    if (f.id == tv.id) return@withTimeoutOrNull f.base
                    if (byName == null && sameTvName(f.name, tv.name)) byName = f.base
                }
                null
            }
            return byId ?: byName
        } finally {
            stop()
            found.close()
        }
    }

    // ---- Talking to linked TVs ---------------------------------------------------------------

    /** Status checks and relays to other TVs, which outlive the request that started them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var recent: Pair<Long, List<TvStatus>>? = null
    private val checks = StatusChecks(scope) { tv -> statusOf(tv) }

    /**
     * [tv]'s status, asked with its link key. Renamed on that TV (Settings → This TV's name): the
     * list here takes its new name, so the TV list, the phone's TV picker and messages by name all
     * follow it. A TV that doesn't answer, or answers but turns down the key, is looked for on the
     * network ([lost]).
     */
    fun statusOf(tv: LinkedTv): TvStatus {
        try {
            val status = parse(JSONObject(request("${tv.base}/api/status?k=${enc(tv.key)}", "GET", null)), true)
            answered(tv)
            renamed(tv, status.name)
            return status.copy(id = tv.id, name = cleanName(status.name) ?: tv.name)
        } catch (e: Exception) {
            lost(tv, refusedAt = (e as? KeyRefused)?.let { tv.base })
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
        val refused = _refused.value
        val others = checks.statuses(_linked.value).map { it.copy(refused = it.id in refused, seen = seen[it.id]) }
        listOf(me.await()) + others
    }

    /** The statuses last fetched (empty before the first), for a screen to start from. */
    fun lastStatuses(): List<TvStatus> = recent?.second.orEmpty()

    fun find(id: String): LinkedTv? = _linked.value.firstOrNull { it.id == id }

    fun screen(tv: LinkedTv): ByteArray? {
        val conn = open("${tv.base}/api/screen?k=${enc(tv.key)}", "GET")
        try {
            return if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() }.also { answered(tv) } else null
        } catch (e: IOException) {
            lost(tv)
            throw e
        } finally {
            conn.disconnect()
        }
    }

    // A message that fails because the TV moved goes again once it's found at its new address, a
    // few seconds late (a video started there catches up with the leader; a notice shows once per id).

    /**
     * Start [key] (a Drive video) on [tv] at [positionMs]: "watch together", or a phone choosing that
     * TV. Returns what it answered ("started": false when it couldn't come to the front and keeps the
     * video waiting there; older versions don't say). [again]: if [tv] moved, it's sent again once
     * it's found (Watch together: it catches up with the leader); a phone's own start isn't, since a
     * film starting there seconds after the phone said "didn't respond" would be a surprise.
     */
    fun watch(tv: LinkedTv, key: String, title: String, positionMs: Long, again: Boolean = true): JSONObject {
        val body = JSONObject().put("key", key).put("title", title).put("position", positionMs).toString()
        var answer = JSONObject()
        reach(tv, again) { t -> answer = answerOf(request("${t.base}/api/watch?k=${enc(t.key)}", "POST", body)) }
        return answer
    }

    /**
     * ↺10, 30↻, Next or Play/Pause ([action], as the phone words it) for what [tv] plays. Returns its
     * answer ("playing"); [Refused] carries its own words ("Nothing is playing on Den"). Never sent
     * again: a skip arriving seconds late would land in the wrong place.
     */
    fun media(tv: LinkedTv, action: String): JSONObject {
        val json = JSONObject().put("action", action).toString()
        var answer = JSONObject()
        reach(tv, again = false) { t -> answer = answerOf(request("${t.base}/api/media?k=${enc(t.key)}", "POST", json)) }
        return answer
    }

    /**
     * What a TV that did what it was asked said back: its JSON, or nothing to read from an older
     * Woodruff that answers "ok" in plain words. Only the reading is forgiven here: a TV that didn't
     * answer, or said no ([Refused]), still fails the request, so [reach] can look for it and the
     * phone can say "Den didn't respond".
     */
    private fun answerOf(text: String): JSONObject = runCatching { JSONObject(text) }.getOrDefault(JSONObject())

    /** Pin a family message on [tv]'s Home; [at]: when it was pinned here, so [tv] shows it once ([com.woodruff.tvexplorer.notify.Board.receive]). */
    fun board(tv: LinkedTv, title: String, body: String, from: String?, at: Long) {
        val json = JSONObject().put("title", title).put("body", body).put("from", from ?: "").put("at", at).toString()
        reach(tv, again = true) { t -> request("${t.base}/api/board?k=${enc(t.key)}", "POST", json) }
    }

    /**
     * [tv]'s health report, asked with its link key: at most about 4 s (2 to connect, 2 to answer),
     * so a list of TVs never waits long on one. Tells a TV that's off ([HealthAnswer.Unreachable])
     * from one that connected but didn't answer in time ([HealthAnswer.Slow]) and from one that
     * answered without a report ([HealthAnswer.NoReport]: an older Woodruff, or one turning down the key).
     */
    fun health(tv: LinkedTv): HealthAnswer {
        val conn = (URL("${tv.base}/api/health?k=${enc(tv.key)}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_000
            readTimeout = 2_000
        }
        try {
            try {
                conn.connect()
            } catch (e: IOException) {
                lost(tv)
                return HealthAnswer.Unreachable
            }
            val code = try { conn.responseCode } catch (e: java.net.SocketTimeoutException) { return HealthAnswer.Slow }
            if (code != 200) return HealthAnswer.NoReport(code)
            val text = try { conn.inputStream.bufferedReader().use { it.readText() } } catch (e: java.net.SocketTimeoutException) { return HealthAnswer.Slow }
            answered(tv)
            return HealthReport.fromJson(runCatching { JSONObject(text) }.getOrNull())?.let { HealthAnswer.Report(it) } ?: HealthAnswer.NoReport(code)
        } catch (e: IOException) {
            return HealthAnswer.Unreachable
        } finally {
            conn.disconnect()
        }
    }

    /** What asking a linked TV for its health report came to. */
    sealed interface HealthAnswer {
        data class Report(val report: HealthReport) : HealthAnswer
        /** It answered, with no report ([code]): an older Woodruff (no such route for a link key), or a key it turns down. */
        data class NoReport(val code: Int) : HealthAnswer
        /** It connected but didn't answer in time. */
        data object Slow : HealthAnswer
        /** No connection: off, asleep, or gone from that address. */
        data object Unreachable : HealthAnswer
    }

    /** Asks [tv] to check for an update now (it still follows the test TV's day, as its own checks do). */
    fun checkForUpdates(tv: LinkedTv) {
        request("${tv.base}/api/update/check?k=${enc(tv.key)}", "POST", "{}")
        answered(tv)
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

    // The keys every TV shares (SharedKeys). Straight to the TV, not through [reach]: a TV running a
    // Woodruff from before keys were shared refuses the route, which mustn't count as it having moved,
    // and a TV that's off is caught up on the hub's next check anyway.

    /** What shared keys [tv] has: for each ("tmdb", "google", "cameras"), its fingerprint and when it was set ("fp", "at"). */
    fun keys(tv: LinkedTv): JSONObject = JSONObject(request("${tv.base}/api/keys?k=${enc(tv.key)}", "GET", null))

    /** [tv]'s shared key [id] itself ("value"), with its fingerprint and when it was set. */
    fun key(tv: LinkedTv, id: String): JSONObject =
        keys(tv, "&id=${enc(id)}").optJSONObject(id) ?: throw IOException("No $id from ${tv.name}")

    private fun keys(tv: LinkedTv, query: String) = JSONObject(request("${tv.base}/api/keys?k=${enc(tv.key)}$query", "GET", null))

    /** Gives [tv] the shared key [id] ([value], null: none), set at [at]; it keeps it only if it's newer than its own. */
    fun sendKey(tv: LinkedTv, id: String, value: String?, at: Long) {
        val json = JSONObject().put("id", id).put("value", value ?: "").put("at", at).toString()
        request("${tv.base}/api/keys?k=${enc(tv.key)}", "POST", json)
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
        return pickTargets(asked, default, id(c), name(c), _linked.value, seen)
    }

    fun press(tv: LinkedTv, key: String) {
        val json = JSONObject().put("key", key).toString()
        // Never sent again: a key press arriving seconds late could do the wrong thing.
        reach(tv, again = false) { t -> request("${t.base}/api/key?k=${enc(t.key)}", "POST", json) }
    }

    private fun parse(o: JSONObject, online: Boolean) = TvStatus(
        o.optString("id"), o.optString("name"), online, o.optBoolean("onScreen"), Playing.fromJson(o.optJSONObject("playing")),
        o.optString("version").ifBlank { null },
        health = HealthSummary.fromJson(o.optJSONObject("health")), reportsHealth = o.has("health"),
        board = if (o.has("board")) o.optLong("board") else null,
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

    /** Something at the TV's address turned down its link key (HTTP 401): see [lost] for how that's told apart from another device there. */
    class KeyRefused : IOException("HTTP 401")

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
            answered(tv)
        } catch (e: IOException) {
            // A TV that said no (with its reason) is where it should be: nothing to look for.
            if (e !is Refused) lost(tv, if (again) send else null, refusedAt = (e as? KeyRefused)?.let { tv.base })
            throw e
        }
    }

    /**
     * [tv] didn't answer: it may be off, or at a new address. Looks for it on the network in the
     * background, at most every few minutes and without holding anyone up, and sends it [again]
     * once it turns up at a new address. [refusedAt]: it did answer there, turning down its link key
     * (HTTP 401); the search then also says whether it's a Woodruff TV at that address, which means
     * this link stopped working (finding 55). A TV already known to refuse isn't looked for again.
     */
    private fun lost(tv: LinkedTv, again: ((LinkedTv) -> Unit)? = null, refusedAt: String? = null) {
        val c = appContext ?: return
        if (tv.id in _refused.value) return
        if (refusedAt != null) suspects[tv.id] = refusedAt
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
                    // A Woodruff TV advertised where a TV turned down its link key: that link stopped working.
                    for (id in refusedHere(f, left, suspects)) {
                        left.remove(id)
                        markRefused(c, id)
                        Log.i("TvLinks", "A linked TV turns down its link")
                    }
                    if (left.isEmpty()) break
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

    // ---- Links that stopped working, and when each TV last answered -------------------------------

    private val _refused = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Linked TVs (by id) that answer but turn down this TV's link key: reset or reinstalled, or
     * "Stop other TVs seeing this one" was chosen on them. They show "Link again" (finding 55) and
     * aren't looked for on the network any more; any answer with the key, or linking again, clears it.
     */
    val refused: StateFlow<Set<String>> = _refused

    fun refuses(id: String): Boolean = id in _refused.value

    /** A 401 from a TV's address, waiting for the network search to say whether a Woodruff TV is there (by TV id). */
    private val suspects = ConcurrentHashMap<String, String>()
    /** When each linked TV last answered; written down now and then ([SEEN_WRITE_MS]) so it outlives a restart. */
    private val seen = ConcurrentHashMap<String, Long>()
    private val seenWritten = ConcurrentHashMap<String, Long>()

    /** When the linked TV [id] last answered; null when not since it was linked. */
    fun seenAt(id: String): Long? = seen[id]

    private fun markRefused(c: Context, id: String) {
        suspects.remove(id)
        if (find(id) == null) return
        writeRefused(c, _refused.value + id)
        recent = null
    }

    private fun clearRefused(c: Context, id: String) {
        suspects.remove(id)
        if (id in _refused.value) { writeRefused(c, _refused.value - id); recent = null }
    }

    @Synchronized
    private fun writeRefused(c: Context, ids: Set<String>) {
        _refused.value = ids
        prefs(c).edit().putStringSet(P_REFUSED, ids).apply()
    }

    /** [tv] answered with its link key: it's there, and its link works. */
    private fun answered(tv: LinkedTv) {
        sawTv(tv.id)
        val c = appContext ?: return
        if (tv.id in _refused.value || suspects.containsKey(tv.id)) clearRefused(c, tv.id)
    }

    private fun sawTv(id: String) {
        val now = System.currentTimeMillis()
        seen[id] = now
        val c = appContext ?: return
        if (now - (seenWritten[id] ?: 0L) >= SEEN_WRITE_MS) {
            seenWritten[id] = now
            val o = JSONObject()
            seen.forEach { (k, v) -> if (find(k) != null) o.put(k, v) }
            prefs(c).edit().putString(P_SEEN, o.toString()).apply()
        }
    }

    /** Linked TVs [ids] were unlinked or replaced: nothing about them is kept. */
    private fun forget(c: Context, ids: Set<String>) {
        if (ids.isEmpty()) return
        ids.forEach { suspects.remove(it); seen.remove(it); seenWritten.remove(it) }
        if (ids.any { it in _refused.value }) writeRefused(c, _refused.value - ids)
        TvHealth.forget(c, ids)
        recent = null
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

/** A TV's name as it reports it, trimmed to what a TV can be called (40 characters); null when blank. */
internal fun cleanName(name: String?): String? = name?.trim()?.take(40)?.takeIf { it.isNotEmpty() }

/** [list] with the linked TV [id] called [name] (it was renamed on that TV); null when nothing changes. */
internal fun renamedIn(list: List<LinkedTv>, id: String, name: String?): List<LinkedTv>? {
    val clean = cleanName(name) ?: return null
    if (list.none { it.id == id && it.name != clean }) return null
    return list.map { if (it.id == id) it.copy(name = clean) else it }
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
internal fun pickTargets(
    asked: List<String>?, default: TvTargets, meId: String, meName: String, linked: List<LinkedTv>, seen: Map<String, Long> = emptyMap(),
): TvTargets? {
    val wanted = asked.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    if (wanted.isEmpty()) return default
    if (wanted.any { it.equals("all", ignoreCase = true) }) return TvTargets.ALL
    var here = false
    val ids = LinkedHashSet<String>()
    for (w in wanted) {
        if (w.equals("this", ignoreCase = true) || w == meId || w.equals(meName, ignoreCase = true)) here = true
        else (linked.firstOrNull { it.id == w }
            // Two TVs by one name (one of them reset and linked again): the one heard from last.
            ?: linked.filter { it.name.equals(w, ignoreCase = true) }.maxByOrNull { seen[it.id] ?: 0L })?.let { ids.add(it.id) }
    }
    return if (here || ids.isNotEmpty()) TvTargets(here, ids) else null
}

/**
 * The linked TVs after linking [tv]: it replaces its own old entry, the entry it's linked again in
 * place of ([replacing]), and any entry that turns down its link ([refused]) and has its name or
 * address (a TV reset and linked again), keeping the list's order. Also the ids that gave way.
 */
internal fun linkedAfter(list: List<LinkedTv>, tv: LinkedTv, replacing: String?, refused: Set<String>): Pair<List<LinkedTv>, Set<String>> {
    fun replaced(t: LinkedTv) = t.id == tv.id || t.id == replacing ||
        (t.id in refused && (sameTvName(t.name, tv.name) || t.base == tv.base))
    val at = list.indexOfFirst(::replaced)
    val kept = list.filterNot(::replaced)
    val gone = list.filter(::replaced).map { it.id }.toSet()
    return (if (at < 0) kept + tv else kept.take(at) + tv + kept.drop(at)) to gone
}

/**
 * Which of the linked TVs [left] (by id) a TV [found] on the network shows turning down its link:
 * the ones a 401 came from at the very address it's advertised at ([suspects]: id to address). It's
 * a Woodruff TV there that won't take the key (reset, reinstalled, or told to stop other TVs seeing
 * it), not some other device that took the address.
 */
internal fun refusedHere(found: TvLinks.Found, left: Set<String>, suspects: Map<String, String>): List<String> =
    left.filter { suspects[it] == found.base }

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
    return when {
        code == 400 && !message.isNullOrEmpty() -> TvLinks.Refused(message)
        code == 401 -> TvLinks.KeyRefused()
        else -> IOException("HTTP $code")
    }
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
