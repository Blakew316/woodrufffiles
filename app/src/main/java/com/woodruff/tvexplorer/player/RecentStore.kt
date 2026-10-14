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
            RecentItem(o.optString("key"), o.optString("title"), o.optLong("pos"), o.optLong("dur"), o.optLong("at"))
        }
    }

    /** In-progress items worth resuming: started, and not within the last few percent. */
    fun continueWatching(): List<RecentItem> = all().filter { it.positionMs > 10_000 && it.progress < FINISHED }

    fun resumePosition(key: String): Long =
        all().firstOrNull { it.key == key }?.takeIf { it.progress < FINISHED }?.positionMs ?: 0L

    fun save(key: String, title: String, positionMs: Long, durationMs: Long) {
        val items = listOf(RecentItem(key, title, positionMs, durationMs, System.currentTimeMillis())) +
            all().filter { it.key != key }
        write(items.take(MAX))
    }

    fun remove(key: String) = write(all().filter { it.key != key })

    private fun write(items: List<RecentItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("key", it.key).put("title", it.title).put("pos", it.positionMs).put("dur", it.durationMs).put("at", it.updatedAt))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "items"
        private const val MAX = 30
        private const val FINISHED = 0.95f
    }
}
