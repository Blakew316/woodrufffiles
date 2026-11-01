package com.woodruff.tvexplorer.tmdb

import android.content.Context
import android.util.Log
import com.woodruff.tvexplorer.server.CompanionHub
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
 * One TMDB key for every TV in the house. A key set on any TV (on the TV, or from the phone) goes
 * at once to the TVs linked to it, and a TV that was off then, linked later, or where the key was
 * set first is brought in line by the hub's check, which runs with the phone connection every few
 * minutes ([sync]): the newest key wins, wherever it was set. Each TV keeps its own copy, encrypted
 * like its other keys, so it stays through restarts and updates.
 *
 * Linked TVs reach this through `/api/tmdb` with their link key: the key and when it was set.
 */
object TmdbKeyShare {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One sync at a time: the phone connection's loop, a TV just linked and a key just set can meet. */
    private val syncing = Mutex()

    /** This TV's key, as compared with other TVs'. */
    fun version(c: Context): KeyVersion = KeyVersion(fingerprint(Tmdb.key(c)), Tmdb.keyAt(c))

    /** Someone set (or, blank, removed) the key on this TV: saved here, then copied to every linked TV. */
    fun set(c: Context, key: String?) {
        val app = c.applicationContext
        Tmdb.saveKey(app, key, at = System.currentTimeMillis())
        pushSoon(app)
    }

    /** What `/api/tmdb` answers: the key's fingerprint and when it was set, and the key itself when [withKey]. */
    fun json(c: Context, withKey: Boolean): JSONObject {
        val v = version(c)
        return JSONObject().put("fp", v.fingerprint).put("at", v.at).apply { if (withKey) put("key", Tmdb.key(c) ?: "") }
    }

    /**
     * A linked TV sent its key ([key], set at [at]). Taken when it's newer than this TV's (see
     * [accepts]); then passed on to the TVs linked to this one, for a chain of links. A TV that
     * already has it takes nothing and passes nothing on, so links both ways don't bounce it back.
     */
    fun receive(c: Context, key: String?, at: Long): Boolean {
        val app = c.applicationContext
        if (!take(app, key, at)) return false
        pushSoon(app)
        return true
    }

    /** Checks the linked TVs now, in the background (a TV was just linked). */
    fun syncSoon(c: Context) {
        val app = c.applicationContext
        scope.launch { runCatching { sync(app) }.onFailure { Log.w("TmdbKeyShare", "sync failed: $it") } }
    }

    /**
     * Brings every linked TV in line: asks each for its key's fingerprint, takes the newest one when
     * a linked TV has it (fetching the key from that TV), and sends the key to each TV whose own is
     * older. A TV that's off, or runs a Woodruff from before keys were shared, is skipped until next time.
     */
    suspend fun sync(c: Context) = withContext(Dispatchers.IO) {
        val app = c.applicationContext
        TvLinks.load(app)
        val tvs = TvLinks.linked.value
        if (tvs.isEmpty()) return@withContext
        syncing.withLock {
            val theirs = tvs.map { tv -> async { runCatching { versionOf(TvLinks.tmdb(tv, withKey = false)) }.getOrNull() } }.awaitAll()
            val newest = winner(version(app), theirs)
            if (newest >= 0) {
                runCatching {
                    val o = TvLinks.tmdb(tvs[newest], withKey = true)
                    val key = o.optString("key").ifBlank { null }
                    // What it sends must be the key it described (the fingerprint), or it isn't taken.
                    if (fingerprint(key) == o.optString("fp")) take(app, key, o.optLong("at"))
                }.onFailure { Log.w("TmdbKeyShare", "couldn't fetch the key from ${tvs[newest].name}: $it") }
            }
            val mine = version(app)
            val key = Tmdb.key(app)
            tvs.forEachIndexed { i, tv ->
                val v = theirs[i] ?: return@forEachIndexed
                if (needs(v, mine)) send(tv, key, mine.at)
            }
        }
    }

    /** Sends this TV's key to every linked TV, in the background; each takes it only if it's newer. */
    private fun pushSoon(app: Context) {
        scope.launch {
            TvLinks.load(app)
            val key = Tmdb.key(app)
            val at = Tmdb.keyAt(app)
            TvLinks.linked.value.map { tv -> async { send(tv, key, at) } }.awaitAll()
        }
    }

    private fun send(tv: LinkedTv, key: String?, at: Long) {
        // Quietly: a TV that's off gets it on the hub's next check.
        runCatching { TvLinks.sendTmdb(tv, key, at) }.onFailure { Log.i("TmdbKeyShare", "${tv.name} didn't take the key now: $it") }
    }

    /** Saves [key] (set at [at]) here when [accepts] says so; open screens then use it (posters, rows, genres). */
    @Synchronized
    private fun take(app: Context, key: String?, at: Long): Boolean {
        val clean = key?.trim()?.ifBlank { null }
        if (!accepts(version(app), KeyVersion(fingerprint(clean), at))) return false
        Tmdb.saveKey(app, clean, at)
        CompanionHub.settingsChanged.tryEmit(Unit)
        Log.i("TmdbKeyShare", if (clean != null) "took the TMDB key from another TV" else "removed the TMDB key, as another TV did")
        return true
    }

    private fun versionOf(o: JSONObject) = KeyVersion(o.optString("fp"), o.optLong("at"))
}

// ---- Which key wins (plain logic, unit-tested) ----------------------------------------------------

/**
 * A TV's TMDB key as TVs compare it: [fingerprint] ("" when it has none) and [at], when someone
 * set or removed it (0 for a key from before keys were shared, or never set).
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

/**
 * Whether [a] is newer than [b]: set later; set at the same time (both from before keys were
 * shared, at 0), a key beats none, so a TV with no key never wipes one.
 */
internal fun newer(a: KeyVersion, b: KeyVersion): Boolean = a.at > b.at || (a.at == b.at && a.has && !b.has)

/** Whether a TV with [mine] takes [incoming]: a different key that isn't older (a tie goes to the sender). */
internal fun accepts(mine: KeyVersion, incoming: KeyVersion): Boolean =
    incoming.fingerprint != mine.fingerprint && !newer(mine, incoming)

/** Whether a linked TV with [theirs] should be sent [mine]: it differs and isn't newer. */
internal fun needs(theirs: KeyVersion, mine: KeyVersion): Boolean = accepts(theirs, mine)

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
