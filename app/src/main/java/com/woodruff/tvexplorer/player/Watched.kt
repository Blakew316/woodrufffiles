package com.woodruff.tvexplorer.player

import android.content.Context
import com.woodruff.tvexplorer.cloud.FamilyMerge
import com.woodruff.tvexplorer.cloud.FamilySync
import org.json.JSONObject

/**
 * Titles watched to the end (media keys), for the ✓ on posters and episodes. Finishing something
 * drops it from Continue watching; this remembers that it was seen. Kept in memory after the
 * first read, since tiles ask on every draw. Each ✓ keeps when it was added, and a ✓ taken off is
 * remembered with when, so the family's other TVs follow both ([FamilySync]).
 */
object Watched {
    private const val PREFS = "watched"
    // The ✓s themselves, stored as before times were kept (an older version still reads them).
    private const val KEY = "keys"
    /** When each ✓ was added. One from before has none and counts as the oldest. */
    private const val TIMES = "times"
    /** ✓s taken off, with when. Drive videos only: those are the ones the other TVs know. */
    private const val OFF = "off"
    /** Newest few thousand are plenty; the set only grows by what's finished. */
    internal const val MAX = 4000
    internal const val MAX_OFF = 2000

    private class State(val on: Map<String, Long>, val off: Map<String, Long>)
    @Volatile private var state: State? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(c: Context): State = state ?: synchronized(this) {
        state ?: prefs(c).let { p ->
            val times = readTimes(p.getString(TIMES, null))
            State(p.getStringSet(KEY, emptySet()).orEmpty().associateWith { times[it] ?: 0L }, readTimes(p.getString(OFF, null)))
        }.also { state = it }
    }

    fun isWatched(c: Context, key: String): Boolean = key in load(c).on

    fun mark(c: Context, key: String) = markAll(c, listOf(key))

    /** Marks every key in one write (a whole season). */
    fun markAll(c: Context, keys: Collection<String>) {
        val shared = synchronized(this) {
            val now = load(c)
            val added = keys.filterTo(HashSet<String>()) { it !in now.on }
            if (added.isEmpty()) return
            val at = System.currentTimeMillis()
            write(c, State(FamilyMerge.newest(now.on + added.associateWith { at }, MAX), now.off - added))
            added.any { FamilySync.isShared(it) }
        }
        if (shared) FamilySync.changed(c)
    }

    /** Clears every key in one write. */
    fun unmarkAll(c: Context, keys: Collection<String>) {
        val shared = synchronized(this) {
            val now = load(c)
            val removed = keys.filterTo(HashSet<String>()) { it in now.on }
            if (removed.isEmpty()) return
            val at = System.currentTimeMillis()
            // Remembered, so the other TVs take theirs off too.
            val off = now.off + removed.filter { FamilySync.isShared(it) }.associateWith { at }
            write(c, State(now.on - removed, FamilyMerge.newest(off, MAX_OFF)))
            removed.any { FamilySync.isShared(it) }
        }
        if (shared) FamilySync.changed(c)
    }

    fun unmark(c: Context, key: String) = unmarkAll(c, listOf(key))

    /** For [FamilySync]: the ✓s with when each was added, and the ones taken off. */
    internal fun stamps(c: Context): Pair<Map<String, Long>, Map<String, Long>> = load(c).let { it.on to it.off }

    /**
     * For [FamilySync]: [merge] turns the ✓s and the ones taken off into the merged ones, in one
     * locked step. Not sent on again (it came from the other TVs). True when anything changed.
     */
    internal fun syncWith(
        c: Context,
        merge: (Map<String, Long>, Map<String, Long>) -> Pair<Map<String, Long>, Map<String, Long>>,
    ): Boolean = synchronized(this) {
        val now = load(c)
        val (on, off) = merge(now.on, now.off)
        val changed = on != now.on || off != now.off
        if (changed) write(c, State(FamilyMerge.newest(on, MAX), FamilyMerge.newest(off, MAX_OFF)))
        changed
    }

    private fun write(c: Context, s: State) {
        state = s
        prefs(c).edit()
            .putStringSet(KEY, HashSet(s.on.keys))
            .putString(TIMES, JSONObject().apply { s.on.forEach { (k, at) -> if (at > 0) put(k, at) } }.toString())
            .putString(OFF, JSONObject().apply { s.off.forEach { (k, at) -> put(k, at) } }.toString())
            .apply()
    }

    private fun readTimes(json: String?): Map<String, Long> {
        val o = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        val out = HashMap<String, Long>()
        for (k in o.keys()) out[k] = o.optLong(k)
        return out
    }
}
