package com.woodruff.tvexplorer.player

import android.content.Context
import com.woodruff.tvexplorer.cloud.FamilyMerge
import com.woodruff.tvexplorer.cloud.FamilySync
import org.json.JSONArray
import org.json.JSONObject

/** One entry in the Continue-watching row. */
data class RecentItem(
    val key: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    /** The episode after one just finished, not started yet: offered as "Next · S2 E5". */
    val upNext: Boolean = false,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Remembers where playback stopped, so videos resume and Home can offer "Continue watching".
 * Kept in SharedPreferences as a small JSON list (newest first, capped at [MAX]). Drive videos are
 * shared with the family's other TVs ([FamilySync]), and so is taking one out of the list.
 */
class RecentStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("recent", Context.MODE_PRIVATE)

    fun all(): List<RecentItem> {
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            RecentItem(o.optString("key"), o.optString("title"), o.optLong("pos"), o.optLong("dur"), o.optLong("at"), o.optBoolean("nx"))
        }
    }

    /**
     * What to offer: items worth resuming (started, and not within the last few percent) and up-next
     * episodes. One per show, its newest, so a binge is one tile rather than one per episode.
     */
    fun continueWatching(): List<RecentItem> =
        all().filter { it.upNext || (it.positionMs > 10_000 && it.progress < FINISHED) }.distinctBy { showOf(it) }

    fun resumePosition(key: String): Long =
        all().firstOrNull { it.key == key }?.takeIf { it.progress < FINISHED }?.positionMs ?: 0L

    fun save(key: String, title: String, positionMs: Long, durationMs: Long) {
        synchronized(LOCK) {
            val before = all()
            // Stopped in the first seconds of an up-next episode: it's still up next.
            val stillNext = positionMs <= 10_000 && before.firstOrNull { it.key == key }?.upNext == true
            val items = listOf(RecentItem(key, title, positionMs, durationMs, System.currentTimeMillis(), stillNext)) +
                before.filter { it.key != key }
            // Playing it again outdates its removal.
            write(items.take(MAX), removals() - key)
        }
        if (FamilySync.isShared(key)) FamilySync.changed(app)
    }

    /** [key] comes after an episode that was just finished: offer it on Home and its show's page. */
    fun saveUpNext(key: String, title: String) {
        synchronized(LOCK) {
            val before = all()
            val now = System.currentTimeMillis()
            // Already partway through it: keep that place, just bring it to the front.
            val started = before.firstOrNull { it.key == key }?.takeIf { it.positionMs > 10_000 && it.progress < FINISHED }
            val item = started?.copy(updatedAt = now) ?: RecentItem(key, title, 0, 0, now, upNext = true)
            write((listOf(item) + before.filter { it.key != key }).take(MAX), removals() - key)
        }
        if (FamilySync.isShared(key)) FamilySync.changed(app)
    }

    fun remove(key: String) {
        val shared = FamilySync.isShared(key)
        synchronized(LOCK) {
            // Remembered for the other TVs, so it leaves their Continue watching too. Even when it
            // isn't listed here: an episode watched in one go was started on another TV, maybe.
            val gone = removals().let { if (shared) FamilyMerge.newest(it + (key to System.currentTimeMillis()), MAX_GONE) else it }
            write(all().filter { it.key != key }, gone)
        }
        if (shared) FamilySync.changed(app)
    }

    /**
     * Takes [item]'s show off Continue watching: every episode of it, since the row shows one per
     * show and an older, abandoned episode would otherwise take its place. A film is just itself.
     */
    fun removeShow(item: RecentItem) {
        val show = showOf(item)
        (all().filter { showOf(it) == show }.map { it.key } + item.key).distinct().forEach { remove(it) }
    }

    /** Taken out of Continue watching (finished, or removed by hand), Drive videos only: key to when. */
    internal fun removals(): Map<String, Long> {
        val o = prefs.getString(GONE, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        val out = HashMap<String, Long>()
        for (k in o.keys()) out[k] = o.optLong(k)
        return out
    }

    /**
     * For [FamilySync]: [merge] turns this list and its removals into the merged ones, in one step
     * no save can come between. Not sent on again (it came from the other TVs). True when it changed.
     */
    internal fun syncWith(merge: (List<RecentItem>, Map<String, Long>) -> Pair<List<RecentItem>, Map<String, Long>>): Boolean = synchronized(LOCK) {
        val items = all()
        val gone = removals()
        val (mergedItems, mergedGone) = merge(items, gone)
        val changed = mergedItems != items || mergedGone != gone
        if (changed) write(mergedItems.take(MAX), FamilyMerge.newest(mergedGone, MAX_GONE))
        changed
    }

    private fun write(items: List<RecentItem>, gone: Map<String, Long>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("key", it.key).put("title", it.title).put("pos", it.positionMs).put("dur", it.durationMs).put("at", it.updatedAt)
                .apply { if (it.upNext) put("nx", true) })
        }
        val removed = JSONObject()
        gone.forEach { (k, at) -> removed.put(k, at) }
        prefs.edit().putString(KEY, arr.toString()).putString(GONE, removed.toString()).apply()
    }

    companion object {
        /** The show an episode belongs to ("show:the office (2005)"); a film or a clip is its own. */
        fun showOf(item: RecentItem): String =
            if (ShowPrefs.isEpisode(item.title) && ShowPrefs.namesShow(item.title)) "show:" + ShowPrefs.showKey(item.title) else item.key

        private const val KEY = "items"
        private const val GONE = "gone"
        /** Entries kept, the other TVs' included. */
        internal const val MAX = 30
        /** Removals remembered for the other TVs: weeks of finished episodes. */
        internal const val MAX_GONE = 200
        private const val FINISHED = 0.95f
        // RecentStore is made wherever it's needed, so every instance shares one lock.
        private val LOCK = Any()
    }
}
