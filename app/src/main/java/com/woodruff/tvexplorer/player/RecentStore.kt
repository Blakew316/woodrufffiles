package com.woodruff.tvexplorer.player

import android.content.Context
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
 * Kept in SharedPreferences as a small JSON list (newest first, capped at [MAX]).
 */
class RecentStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("recent", Context.MODE_PRIVATE)

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
        val before = all()
        // Stopped in the first seconds of an up-next episode: it's still up next.
        val stillNext = positionMs <= 10_000 && before.firstOrNull { it.key == key }?.upNext == true
        val items = listOf(RecentItem(key, title, positionMs, durationMs, System.currentTimeMillis(), stillNext)) +
            before.filter { it.key != key }
        write(items.take(MAX))
    }

    /** [key] comes after an episode that was just finished: offer it on Home and its show's page. */
    fun saveUpNext(key: String, title: String) {
        val before = all()
        val now = System.currentTimeMillis()
        // Already partway through it: keep that place, just bring it to the front.
        val started = before.firstOrNull { it.key == key }?.takeIf { it.positionMs > 10_000 && it.progress < FINISHED }
        val item = started?.copy(updatedAt = now) ?: RecentItem(key, title, 0, 0, now, upNext = true)
        write((listOf(item) + before.filter { it.key != key }).take(MAX))
    }

    fun remove(key: String) = write(all().filter { it.key != key })

    private fun write(items: List<RecentItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("key", it.key).put("title", it.title).put("pos", it.positionMs).put("dur", it.durationMs).put("at", it.updatedAt)
                .apply { if (it.upNext) put("nx", true) })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        /** The show an episode belongs to ("show:the office (2005)"); a film or a clip is its own. */
        fun showOf(item: RecentItem): String =
            if (ShowPrefs.isEpisode(item.title) && ShowPrefs.namesShow(item.title)) "show:" + ShowPrefs.showKey(item.title) else item.key

        private const val KEY = "items"
        private const val MAX = 30
        private const val FINISHED = 0.95f
    }
}
