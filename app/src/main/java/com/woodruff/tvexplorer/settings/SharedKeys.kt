package com.woodruff.tvexplorer.settings

import android.content.Context
import android.util.Log
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.server.CompanionHub
import com.woodruff.tvexplorer.tmdb.Tmdb
import com.woodruff.tvexplorer.tvs.LinkedTv
import com.woodruff.tvexplorer.tvs.TvLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The keys every TV in the house shares, so they're entered once: on any TV or from the phone.
 * Sign-ins (scanning a QR code with the phone) stay one per TV; these are what they're built on.
 *
 * [tieGoesToSender]: when two TVs hold different keys from before keys were shared, whether the
 * hub's replaces the other's. Yes for the TMDB key (harmless); no for the Google keys and the
 * cameras project, where replacing them would sign that TV out of something that works.
 */
enum class SharedKey(val id: String, internal val atName: String, val tieGoesToSender: Boolean) {
    TMDB("tmdb", "tmdb.key.at", true),
    /** The Google Client ID and secret, as one: Drive, Photos, Calendar and the cameras use them. */
    GOOGLE("google", "google.keys.at", false),
    /** The Device Access project ID the cameras use. */
    CAMERAS("cameras", "nest.project.at", false);

    companion object {
        fun of(id: String?): SharedKey? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Keeps the [SharedKey]s the same on every TV. A key set on any TV goes at once to the TVs linked
 * to it, and the hub (the TV the others were linked from) checks them every few minutes with its
 * phone connection ([sync]): a TV that was off, one linked later or reset, and a key set on a TV
 * other than the hub are all brought in line. The newest key wins, wherever it was set. Each TV
 * keeps its own copy, encrypted ([SecretStore]), so it stays through restarts and updates.
 *
 * Linked TVs reach this through `/api/keys` with their link key.
 */
object SharedKeys {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One sync at a time: the phone connection's loop, a TV just linked and a key just set can meet. */
    private val syncing = Mutex()

    /** This TV's [key] as one string ("" never: null when it has none, or only half the Google keys). */
    fun value(c: Context, key: SharedKey): String? = when (key) {
        SharedKey.TMDB -> Tmdb.key(c)
        SharedKey.GOOGLE -> googleValue(GoogleAuth.clientId(c), GoogleAuth.clientSecret(c))
        SharedKey.CAMERAS -> GoogleAuth.nestProject(c)
    }

    /** When [key] was set or removed, here or on another TV (0: before keys were shared, or never). */
    fun at(c: Context, key: SharedKey): Long = SecretStore.get(c).get(key.atName)?.toLongOrNull() ?: 0L

    fun version(c: Context, key: SharedKey) = KeyVersion(fingerprint(value(c, key)), at(c, key))

    /**
     * Someone changes [key] on this TV with [change] (saving it as before). If that changed it, it's
     * marked as set now and copied to every linked TV. Removing it is shared only when this TV had it
     * (for the Google keys, only when both are gone): a half-typed pair, or "removing" a key a new TV
     * never had, mustn't wipe the other TVs'.
     */
    fun <T> set(c: Context, key: SharedKey, change: () -> T): T {
        val app = c.applicationContext
        val before = value(app, key)
        val result = change()
        val after = value(app, key)
        if (after == before) return result
        if (after == null && (before == null || !removedEntirely(app, key))) return result
        SecretStore.get(app).put(key.atName, System.currentTimeMillis().toString())
        pushSoon(app, key)
        return result
    }

    private fun removedEntirely(c: Context, key: SharedKey): Boolean = when (key) {
        SharedKey.GOOGLE -> GoogleAuth.clientId(c).isNullOrBlank() && GoogleAuth.clientSecret(c).isNullOrBlank()
        else -> value(c, key) == null
    }

    /** What `/api/keys` answers: each key's fingerprint and when it was set; with [withValue], that key itself too. */
    fun json(c: Context, withValue: SharedKey? = null): JSONObject {
        val o = JSONObject()
        SharedKey.entries.forEach { k ->
            val v = version(c, k)
            o.put(k.id, JSONObject().put("fp", v.fingerprint).put("at", v.at).apply {
                if (k == withValue) put("value", value(c, k) ?: "")
            })
        }
        return o
    }

    /**
     * A linked TV sent [key] ([value], set at [at]). Taken when it's newer than this TV's (see
     * [accepts]); then passed on to the TVs linked to this one, for a chain of links. A TV that
     * already has it takes nothing and passes nothing on, so links both ways don't bounce it back.
     */
    fun receive(c: Context, key: SharedKey, value: String?, at: Long): Boolean {
        val app = c.applicationContext
        if (!take(app, key, value, at)) return false
        pushSoon(app, key)
        return true
    }

    /** Checks the linked TVs now, in the background (a TV was just linked). */
    fun syncSoon(c: Context) {
        val app = c.applicationContext
        scope.launch { runCatching { sync(app) }.onFailure { Log.w("SharedKeys", "sync failed: $it") } }
    }

    /**
     * Brings every linked TV in line: asks each what keys it has (fingerprints), takes the newest of
     * each where a linked TV has it (fetching it from that TV), and sends each key to the TVs whose
     * own is older. A TV that's off, or runs a Woodruff from before keys were shared, is skipped until next time.
     */
    suspend fun sync(c: Context) = withContext(Dispatchers.IO) {
        val app = c.applicationContext
        TvLinks.load(app)
        val tvs = TvLinks.linked.value
        if (tvs.isEmpty()) return@withContext
        syncing.withLock {
            val answers = tvs.map { tv -> async { runCatching { TvLinks.keys(tv) }.getOrNull() } }.awaitAll()
            for (key in SharedKey.entries) {
                val theirs = answers.map { o -> o?.optJSONObject(key.id)?.let { KeyVersion(it.optString("fp"), it.optLong("at")) } }
                val newest = winner(version(app, key), theirs)
                if (newest >= 0) {
                    runCatching {
                        val o = TvLinks.key(tvs[newest], key.id)
                        val value = o.optString("value").ifBlank { null }
                        // It must be the key it described (the fingerprint), or it isn't taken.
                        if (fingerprint(value) == o.optString("fp")) take(app, key, value, o.optLong("at"))
                    }.onFailure { Log.w("SharedKeys", "couldn't fetch ${key.id} from ${tvs[newest].name}: $it") }
                }
                val mine = version(app, key)
                val value = value(app, key)
                tvs.forEachIndexed { i, tv ->
                    val v = theirs[i] ?: return@forEachIndexed
                    if (needs(v, mine, key.tieGoesToSender)) send(tv, key, value, mine.at)
                }
            }
        }
    }

    /** Sends this TV's [key] to every linked TV, in the background; each takes it only if it's newer. */
    private fun pushSoon(app: Context, key: SharedKey) {
        scope.launch {
            TvLinks.load(app)
            val value = value(app, key)
            val at = at(app, key)
            TvLinks.linked.value.map { tv -> async { send(tv, key, value, at) } }.awaitAll()
        }
    }

    private fun send(tv: LinkedTv, key: SharedKey, value: String?, at: Long) {
        // Quietly: a TV that's off gets it on the hub's next check.
        runCatching { TvLinks.sendKey(tv, key.id, value, at) }.onFailure { Log.i("SharedKeys", "${tv.name} didn't take ${key.id} now: $it") }
    }

    /** Saves [value] as [key] (set at [at]) here when [accepts] says so; open screens then use it. */
    @Synchronized
    private fun take(app: Context, key: SharedKey, value: String?, at: Long): Boolean {
        val clean = value?.trim()?.ifBlank { null }
        if (!accepts(version(app, key), KeyVersion(fingerprint(clean), at), key.tieGoesToSender)) return false
        try {
            write(app, key, clean)
        } catch (e: IllegalArgumentException) {
            Log.w("SharedKeys", "another TV sent a ${key.id} this TV can't use: ${e.message}")
            return false
        }
        SecretStore.get(app).put(key.atName, at.toString())
        CompanionHub.settingsChanged.tryEmit(Unit)
        Log.i("SharedKeys", if (clean != null) "took ${key.id} from another TV" else "removed ${key.id}, as another TV did")
        return true
    }

    /** Stores [value] as this TV's [key] the way entering it does (a new Google client or project means signing in again). */
    private fun write(c: Context, key: SharedKey, value: String?) {
        when (key) {
            SharedKey.TMDB -> Tmdb.saveKey(c, value)
            SharedKey.GOOGLE -> {
                val pair = parseGoogle(value)
                if (pair == null) GoogleAuth.resetClient(c) else GoogleAuth.saveClient(c, pair.first, pair.second)
            }
            SharedKey.CAMERAS -> GoogleAuth.saveNestProject(c, value)
        }
    }
}

// ---- Which key wins (plain logic, unit-tested) ----------------------------------------------------

/**
 * A TV's key as TVs compare it: [fingerprint] ("" when it has none) and [at], when someone set or
 * removed it (0 for a key from before keys were shared, or never set).
 */
data class KeyVersion(val fingerprint: String, val at: Long) {
    val has: Boolean get() = fingerprint.isNotEmpty()
}

/** A short, one-way stand-in for [key], so TVs can compare keys without sending them; "" for none. */
internal fun fingerprint(key: String?): String {
    val k = key?.trim().orEmpty()
    if (k.isEmpty()) return ""
    return MessageDigest.getInstance("SHA-256").digest(k.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}

/** The Google Client ID and secret as one value ("id\nsecret"); null unless both are there. */
internal fun googleValue(id: String?, secret: String?): String? {
    val i = id?.trim().orEmpty()
    val s = secret?.trim().orEmpty()
    return if (i.isEmpty() || s.isEmpty()) null else "$i\n$s"
}

/** [googleValue] back into the Client ID and secret; null for none or anything else. */
internal fun parseGoogle(value: String?): Pair<String, String>? {
    val parts = value?.split('\n') ?: return null
    if (parts.size != 2) return null
    val (id, secret) = parts.map { it.trim() }
    return if (id.isEmpty() || secret.isEmpty()) null else id to secret
}

/**
 * Whether [a] is newer than [b]: set later; set at the same time (both from before keys were
 * shared, at 0), a key beats none, so a TV with no key never wipes one.
 */
internal fun newer(a: KeyVersion, b: KeyVersion): Boolean = a.at > b.at || (a.at == b.at && a.has && !b.has)

/**
 * Whether a TV with [mine] takes [incoming]: a different key that's newer. Two different keys from
 * the same time (both from before keys were shared) go the sender's way only if [tieGoesToSender].
 */
internal fun accepts(mine: KeyVersion, incoming: KeyVersion, tieGoesToSender: Boolean = true): Boolean =
    incoming.fingerprint != mine.fingerprint &&
        (newer(incoming, mine) || (tieGoesToSender && !newer(mine, incoming)))

/** Whether a linked TV with [theirs] should be sent [mine]: it would take it. */
internal fun needs(theirs: KeyVersion, mine: KeyVersion, tieGoesToSender: Boolean = true): Boolean =
    accepts(theirs, mine, tieGoesToSender)

/**
 * Whose key every TV should end up with: -1 for this TV ([mine]), else the index in [theirs] (the
 * linked TVs', null for one that didn't answer) of the newest. A tie keeps this TV's.
 */
internal fun winner(mine: KeyVersion, theirs: List<KeyVersion?>): Int {
    var best = -1
    var bestVersion = mine
    theirs.forEachIndexed { i, v ->
        if (v != null && v.fingerprint != bestVersion.fingerprint && newer(v, bestVersion)) { best = i; bestVersion = v }
    }
    return best
}
